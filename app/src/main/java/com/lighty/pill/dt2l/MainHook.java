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
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PillDT2L";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_SECOND_DOWN = 1;
    private static final int STATE_FIRST_DOWN = 2;
    private static final int STATE_SECOND_DOWN = 3;

    // PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON = 4
    private static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;

    // 单次点击最大按压时长（毫秒）：280ms 兼顾手感与防误触，且远小于圈定即搜 400ms+ 的长按阈值
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

    // 触摸容差与小白条预计算边界缓存（全程使用基本数据类型，零 GC）
    private boolean mConfigInitialized = false;
    private float mTouchSlopSq = 1200f;
    private float mDoubleTapSlopSq = 10000f;
    private long mDoubleTapTimeout = 300L;
    private float mBottomZoneHeightPx = 130f;

    private int mCachedOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int mCachedScreenWidthDp = 0;
    private float mPillLeft = 0f;
    private float mPillRight = 0f;
    private float mPillTop = 0f;
    private float mPillBottom = 0f;

    // 锁屏服务与反射方法缓存
    private Object mWindowManagerService;
    private Method mLockNowMethod;
    private boolean mLockNowLookupDone = false;

    private PowerManager mPowerManager;
    private Method mGoToSleep3Method;
    private Method mGoToSleep1Method;
    private boolean mGoToSleepLookupDone = false;

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

        // 2. Hook SystemUI 全面屏手势核心类 EdgeBackGestureHandler（兼容 Pixel 与 Android 15 类原生 ROM）
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
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof MotionEvent) {
                            observeMotionEvent((MotionEvent) param.args[0]);
                        }
                    }
                };
                XposedBridge.hookAllMethods(edgeHandlerClass, "onMotionEvent", edgeHook);
                XposedBridge.hookAllMethods(edgeHandlerClass, "onInputEvent", edgeHook);
            }
        } catch (Throwable ignored) {
        }

        // 3. 捕获首个匹配的 NavigationBar 视图实例（用于震动反馈与 Context 获取）
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
                    break;
                } catch (Throwable ignored) {
                }
            }
        }

        // 4. Hook InputEventReceiver.dispatchInputEvent 作为通用底层事件入口
        try {
            Class<?> receiverClass = XposedHelpers.findClassIfExists(
                    "android.view.InputEventReceiver", lpparam.classLoader);
            if (receiverClass != null) {
                XposedBridge.hookAllMethods(receiverClass, "dispatchInputEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) {
                            return;
                        }
                        for (int i = 0, len = param.args.length; i < len; i++) {
                            Object arg = param.args[i];
                            if (arg instanceof MotionEvent) {
                                observeMotionEvent((MotionEvent) arg);
                                break;
                            } else if (arg instanceof InputEvent) {
                                break;
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
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

    private Context getContext() {
        Context ctx = mAppContext;
        if (ctx != null) {
            return ctx;
        }
        ViewGroup navBar = mNavBarRef.get();
        if (navBar != null) {
            ctx = navBar.getContext();
        }
        if (ctx == null) {
            try {
                ctx = AndroidAppHelper.currentApplication();
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            mAppContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            initConfigIfNeeded(mAppContext);
        }
        return mAppContext;
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
            mBottomZoneHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 42f, dm);
        }
        mConfigInitialized = true;
    }

    private void observeMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        // 【极速短路】平时滑动屏幕时 mState 为 STATE_IDLE（0）或 STATE_WAIT_SECOND_DOWN（1），
        // 任何非 ACTION_DOWN 事件在第一行直接退出，不读取坐标或时间戳，实现 120Hz 滑动零开销
        if (action != MotionEvent.ACTION_DOWN && mState <= STATE_WAIT_SECOND_DOWN) {
            return;
        }

        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        // 去重：防止同一事件在多个通道中被重复统计
        if (action == mLastAction && downTime == mLastDownTime && eventTime == mLastEventTime) {
            return;
        }
        mLastAction = action;
        mLastDownTime = downTime;
        mLastEventTime = eventTime;

        float rawX = ev.getRawX();
        float rawY = ev.getRawY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                Context ctx = getContext();
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
                        triggerScreenLock(getContext());
                    } else {
                        resetState();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_POINTER_DOWN: {
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

        Resources res = ctx.getResources();
        if (res == null) {
            return false;
        }

        Configuration config = res.getConfiguration();
        // 仅在首次运行或屏幕旋转/尺寸改变时更新一次小白条边界，日常点击零对象分配、零系统服务调用
        if (mPillBottom <= 0f
                || config.orientation != mCachedOrientation
                || config.screenWidthDp != mCachedScreenWidthDp) {
            updatePillBounds(ctx, res, config);
        }

        // Y 轴优先短路：98% 的日常屏幕点击都在底部导航栏上方，第一次比较即可立刻返回 false
        return rawY >= mPillTop && rawY <= mPillBottom && rawX >= mPillLeft && rawX <= mPillRight;
    }

    private void updatePillBounds(Context ctx, Resources res, Configuration config) {
        initConfigIfNeeded(ctx);

        float screenW = 0f;
        float screenH = 0f;

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
            DisplayMetrics dm = res.getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }

        boolean isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT || screenH >= screenW;
        float leftRatio = isPortrait ? 0.38f : 0.40f;
        float rightRatio = isPortrait ? 0.62f : 0.60f;

        mPillLeft = screenW * leftRatio;
        mPillRight = screenW * rightRatio;
        mPillTop = screenH - mBottomZoneHeightPx;
        mPillBottom = screenH;

        mCachedOrientation = config.orientation;
        mCachedScreenWidthDp = config.screenWidthDp;
    }

    private void triggerScreenLock(Context ctx) {
        ViewGroup navBar = mNavBarRef.get();
        if (navBar != null) {
            try {
                navBar.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } catch (Throwable ignored) {
            }
        }

        if (ctx == null) {
            return;
        }

        // 第一步：调用缓存的 WindowManager.lockNow(null) 立即锁定 Keyguard（保留指纹与生物识别）
        try {
            if (!mLockNowLookupDone) {
                Class<?> wmgClass = XposedHelpers.findClassIfExists(
                        "android.view.WindowManagerGlobal", ctx.getClassLoader());
                if (wmgClass != null) {
                    mWindowManagerService = XposedHelpers.callStaticMethod(wmgClass, "getWindowManagerService");
                    if (mWindowManagerService != null) {
                        mLockNowMethod = XposedHelpers.findMethodExactIfExists(
                                mWindowManagerService.getClass(), "lockNow", Bundle.class);
                    }
                }
                mLockNowLookupDone = true;
            }
            if (mWindowManagerService != null && mLockNowMethod != null) {
                mLockNowMethod.invoke(mWindowManagerService, (Object) null);
            }
        } catch (Throwable ignored) {
        }

        // 第二步：调用缓存的 PowerManager.goToSleep 立即熄屏
        try {
            if (mPowerManager == null) {
                mPowerManager = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            }
            if (mPowerManager == null) {
                return;
            }
            if (!mGoToSleepLookupDone) {
                mGoToSleep3Method = XposedHelpers.findMethodExactIfExists(
                        PowerManager.class, "goToSleep", long.class, int.class, int.class);
                if (mGoToSleep3Method == null) {
                    mGoToSleep1Method = XposedHelpers.findMethodExactIfExists(
                            PowerManager.class, "goToSleep", long.class);
                }
                mGoToSleepLookupDone = true;
            }

            long now = SystemClock.uptimeMillis();
            if (mGoToSleep3Method != null) {
                mGoToSleep3Method.invoke(mPowerManager, now, GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
            } else if (mGoToSleep1Method != null) {
                mGoToSleep1Method.invoke(mPowerManager, now);
            }
        } catch (Throwable ignored) {
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