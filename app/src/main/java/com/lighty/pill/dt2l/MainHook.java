package com.lighty.pill.dt2l;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PillDT2L";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final int STATE_IDLE = 0;
    private static final int STATE_FIRST_DOWN = 1;
    private static final int STATE_WAIT_SECOND_DOWN = 2;
    private static final int STATE_SECOND_DOWN = 3;

    // PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON = 4
    private static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;

    // 单次点击最大按压时长（毫秒）：280ms 兼顾手感与防误触，且避开 400ms+ 的圈定即搜长按
    private static final long MAX_TAP_DURATION_MS = 280L;

    private int mState = STATE_IDLE;
    private long mFirstDownTime = 0L;
    private long mFirstUpTime = 0L;
    private long mSecondDownTime = 0L;

    private float mFirstDownX = 0f;
    private float mFirstDownY = 0f;
    private float mSecondDownX = 0f;
    private float mSecondDownY = 0f;

    // 防止多个触摸通道重复统计同一个事件
    private int mLastAction = -1;
    private long mLastDownTime = -1L;
    private long mLastEventTime = -1L;

    private volatile Context mAppContext;
    private WeakReference<ViewGroup> mNavBarRef = new WeakReference<>(null);

    private boolean mConfigInitialized = false;
    // 设置合理的默认初始容差，防止极端情况下 Context 尚未初始化时容差为 0
    private float mTouchSlopSq = 1200f;
    private float mDoubleTapSlopSq = 10000f;
    private long mDoubleTapTimeout = 300L;
    private float mBottomZoneHeightPx = 130f;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 获取全局 Application Context
        try {
            Class<?> appClass = XposedHelpers.findClassIfExists("android.app.Application", lpparam.classLoader);
            if (appClass != null) {
                XposedHelpers.findAndHookMethod(appClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mAppContext == null && param.thisObject instanceof Context) {
                            mAppContext = ((Context) param.thisObject).getApplicationContext();
                            initConfigIfNeeded(mAppContext);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        // 2. Hook SystemUI 内部全面屏手势核心类 EdgeBackGestureHandler（完美兼容 Android 15 类原生 ROM）
        try {
            Class<?> edgeHandlerClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                    lpparam.classLoader
            );
            if (edgeHandlerClass != null) {
                XC_MethodHook edgeHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mAppContext == null && param.thisObject != null) {
                            try {
                                Object ctxObj = XposedHelpers.getObjectField(param.thisObject, "mContext");
                                if (ctxObj instanceof Context) {
                                    mAppContext = ((Context) ctxObj).getApplicationContext();
                                    initConfigIfNeeded(mAppContext);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        if (param.args != null && param.args.length > 0) {
                            Object arg = param.args[0];
                            if (arg instanceof MotionEvent) {
                                observeMotionEvent((MotionEvent) arg);
                            }
                        }
                    }
                };
                XposedBridge.hookAllMethods(edgeHandlerClass, "onMotionEvent", edgeHook);
                XposedBridge.hookAllMethods(edgeHandlerClass, "onInputEvent", edgeHook);
                XposedBridge.log(TAG + ": Hooked EdgeBackGestureHandler");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook EdgeBackGestureHandler: " + t.getMessage());
        }

        // 3. 捕获 NavigationBar 视图实例与触摸分发
        String[] navBarClasses = new String[]{
                "com.android.systemui.navigationbar.views.NavigationBarFrame",
                "com.android.systemui.navigationbar.NavigationBarFrame",
                "com.android.systemui.navigationbar.views.NavigationBarView",
                "com.android.systemui.navigationbar.NavigationBarView"
        };

        for (String className : navBarClasses) {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (targetClass != null) {
                try {
                    XposedBridge.hookAllConstructors(targetClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof ViewGroup) {
                                recordNavBarInstance((ViewGroup) param.thisObject);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }

                try {
                    XposedHelpers.findAndHookMethod(
                            targetClass,
                            "dispatchTouchEvent",
                            MotionEvent.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (param.thisObject instanceof ViewGroup) {
                                        recordNavBarInstance((ViewGroup) param.thisObject);
                                        MotionEvent ev = (MotionEvent) param.args[0];
                                        if (ev != null) {
                                            observeMotionEvent(ev);
                                        }
                                    }
                                }
                            }
                    );
                } catch (Throwable ignored) {
                }
            }
        }

        // 4. Hook InputEventReceiver 及其子类（覆盖 dispatchInputEvent 与 onInputEvent）
        String[] receiverClasses = new String[]{
                "android.view.InputEventReceiver",
                "android.view.BatchedInputEventReceiver",
                "android.view.ViewRootImpl$WindowInputEventReceiver"
        };

        XC_MethodHook receiverHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof MotionEvent) {
                        observeMotionEvent((MotionEvent) arg);
                        break;
                    } else if (arg instanceof InputEvent) {
                        break;
                    }
                }
            }
        };

        for (String rClassName : receiverClasses) {
            try {
                Class<?> rClass = XposedHelpers.findClassIfExists(rClassName, lpparam.classLoader);
                if (rClass != null) {
                    XposedBridge.hookAllMethods(rClass, "dispatchInputEvent", receiverHook);
                    XposedBridge.hookAllMethods(rClass, "onInputEvent", receiverHook);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void recordNavBarInstance(ViewGroup vg) {
        if (vg == null) {
            return;
        }
        mNavBarRef = new WeakReference<>(vg);
        if (mAppContext == null && vg.getContext() != null) {
            mAppContext = vg.getContext().getApplicationContext();
            initConfigIfNeeded(mAppContext);
        }
    }

    private Context getContext(ViewGroup navBar) {
        Context ctx = mAppContext;
        if (ctx == null && navBar != null) {
            ctx = navBar.getContext();
        }
        if (ctx == null) {
            try {
                ctx = AndroidAppHelper.currentApplication();
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null && mAppContext == null) {
            mAppContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        return ctx;
    }

    private void initConfigIfNeeded(Context context) {
        if (mConfigInitialized || context == null) {
            return;
        }
        ViewConfiguration vc = ViewConfiguration.get(context);
        float touchSlop = vc.getScaledTouchSlop() * 1.5f;
        float doubleTapSlop = vc.getScaledDoubleTapSlop() * 1.2f;
        mTouchSlopSq = touchSlop * touchSlop;
        mDoubleTapSlopSq = doubleTapSlop * doubleTapSlop;
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

        Resources res = context.getResources();
        if (res != null) {
            DisplayMetrics dm = res.getDisplayMetrics();
            // 底部小白条纵向有效触发高度：42dp
            mBottomZoneHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 42f, dm);
        }
        mConfigInitialized = true;
    }

    private void observeMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        // 去重：防止同一事件在多个通道中被重复处理
        if (action == mLastAction && downTime == mLastDownTime && eventTime == mLastEventTime) {
            return;
        }
        mLastAction = action;
        mLastDownTime = downTime;
        mLastEventTime = eventTime;

        ViewGroup navBar = mNavBarRef.get();
        Context ctx = getContext(navBar);
        initConfigIfNeeded(ctx);

        float rawX = ev.getRawX();
        float rawY = ev.getRawY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (!isTouchOnPill(ctx, rawX, rawY)) {
                    resetState();
                    return;
                }

                if (mState == STATE_WAIT_SECOND_DOWN) {
                    long interval = eventTime - mFirstUpTime;
                    float distSq = distanceSq(rawX, rawY, mFirstDownX, mFirstDownY);
                    if (interval >= 15L && interval <= mDoubleTapTimeout && distSq <= mDoubleTapSlopSq) {
                        mState = STATE_SECOND_DOWN;
                        mSecondDownTime = eventTime;
                        mSecondDownX = rawX;
                        mSecondDownY = rawY;
                        return;
                    }
                }

                mState = STATE_FIRST_DOWN;
                mFirstDownTime = eventTime;
                mFirstDownX = rawX;
                mFirstDownY = rawY;
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mState == STATE_FIRST_DOWN) {
                    if ((eventTime - mFirstDownTime) > MAX_TAP_DURATION_MS
                            || distanceSq(rawX, rawY, mFirstDownX, mFirstDownY) > mTouchSlopSq) {
                        resetState();
                    }
                } else if (mState == STATE_SECOND_DOWN) {
                    if ((eventTime - mSecondDownTime) > MAX_TAP_DURATION_MS
                            || distanceSq(rawX, rawY, mSecondDownX, mSecondDownY) > mTouchSlopSq) {
                        resetState();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP: {
                if (mState == STATE_FIRST_DOWN) {
                    long duration = eventTime - mFirstDownTime;
                    if (duration <= MAX_TAP_DURATION_MS
                            && distanceSq(rawX, rawY, mFirstDownX, mFirstDownY) <= mTouchSlopSq) {
                        mState = STATE_WAIT_SECOND_DOWN;
                        mFirstUpTime = eventTime;
                    } else {
                        resetState();
                    }
                } else if (mState == STATE_SECOND_DOWN) {
                    long duration = eventTime - mSecondDownTime;
                    if (duration <= MAX_TAP_DURATION_MS
                            && distanceSq(rawX, rawY, mSecondDownX, mSecondDownY) <= mTouchSlopSq) {
                        resetState();
                        XposedBridge.log(TAG + ": Double tap detected on pill, locking screen immediately.");
                        triggerScreenLock(navBar, ctx);
                    } else {
                        resetState();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 多指触控或超出边界时重置；不再因旁路通道的 ACTION_CANCEL 误清空正在进行的点击
                resetState();
                break;
            }

            default:
                break;
        }
    }

    private boolean isTouchOnPill(Context ctx, float rawX, float rawY) {
        if (ctx == null) {
            return false;
        }

        float screenW = 0f;
        float screenH = 0f;

        // 优先通过 WindowManager 获取包含导航栏在内的真实物理屏幕分辨率（修复 Android 15 下 heightPixels 偏小的问题）
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                screenW = bounds.width();
                screenH = bounds.height();
            }
        } catch (Throwable ignored) {
        }

        if (screenW <= 0f || screenH <= 0f) {
            Resources res = ctx.getResources();
            if (res == null) {
                return false;
            }
            DisplayMetrics dm = res.getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }

        boolean isPortrait = screenH >= screenW;
        try {
            isPortrait = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        } catch (Throwable ignored) {
        }

        // 横向严格限制在屏幕正中央的小白条视觉宽度内（竖屏 38% ~ 62%，横屏 40% ~ 60%）
        float leftRatio = isPortrait ? 0.38f : 0.40f;
        float rightRatio = isPortrait ? 0.62f : 0.60f;

        float left = screenW * leftRatio;
        float right = screenW * rightRatio;
        float top = screenH - mBottomZoneHeightPx;
        float bottom = screenH;

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private void triggerScreenLock(View view, Context ctx) {
        if (view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } catch (Throwable ignored) {
            }
        }

        if (ctx == null) {
            ctx = getContext(null);
        }
        if (ctx == null) {
            XposedBridge.log(TAG + ": Cannot lock screen because Context is null.");
            return;
        }

        // 第一步：通知 WindowManager 立即上锁 Keyguard（保留指纹与生物识别解锁）
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> wmgClass = XposedHelpers.findClassIfExists("android.view.WindowManagerGlobal", cl);
            if (wmgClass != null) {
                Object wms = XposedHelpers.callStaticMethod(wmgClass, "getWindowManagerService");
                if (wms != null) {
                    XposedHelpers.callMethod(wms, "lockNow", new Class<?>[]{Bundle.class}, (Object) null);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to call WindowManager.lockNow: " + t.getMessage());
        }

        // 第二步：以按下电源键的原因 (GO_TO_SLEEP_REASON_POWER_BUTTON = 4) 立即熄屏休眠
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        try {
            XposedHelpers.callMethod(
                    pm,
                    "goToSleep",
                    new Class<?>[]{long.class, int.class, int.class},
                    now,
                    GO_TO_SLEEP_REASON_POWER_BUTTON,
                    0
            );
        } catch (Throwable t1) {
            try {
                XposedHelpers.callMethod(pm, "goToSleep", now);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": Failed to call goToSleep: " + t2.getMessage());
            }
        }
    }

    private void resetState() {
        mState = STATE_IDLE;
        mFirstDownTime = 0L;
        mFirstUpTime = 0L;
        mSecondDownTime = 0L;
    }

    private static float distanceSq(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}