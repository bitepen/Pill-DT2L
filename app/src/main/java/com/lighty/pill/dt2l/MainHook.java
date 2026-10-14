package com.lighty.pill.dt2l;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RectF;
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

    // 防止多个通道重复处理同一个触摸事件
    private int mLastAction = -1;
    private long mLastDownTime = -1L;
    private long mLastEventTime = -1L;

    private volatile Context mAppContext;
    private WeakReference<ViewGroup> mNavBarRef = new WeakReference<>(null);

    private boolean mConfigInitialized = false;
    private float mTouchSlopSq = 0f;
    private float mDoubleTapSlopSq = 0f;
    private long mDoubleTapTimeout = 300L;
    private float mPillVisualHalfWidthPx = 0f;
    private float mVerticalPaddingPx = 0f;

    private int mHomeHandleResId = 0;
    private boolean mHomeHandleIdLookupDone = false;
    private final int[] mTempLocation = new int[2];
    private final RectF mCachedPillBounds = new RectF();

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 获取全局 Application Context 兜底
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook Application.onCreate: " + t.getMessage());
        }

        // 2. 捕获 NavigationBar 视图实例，用于定位小白条和获取 Context
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
                                        ViewGroup vg = (ViewGroup) param.thisObject;
                                        recordNavBarInstance(vg);
                                        MotionEvent ev = (MotionEvent) param.args[0];
                                        if (ev != null) {
                                            observeMotionEvent(ev);
                                        }
                                    }
                                }
                            }
                    );
                    XposedBridge.log(TAG + ": Hooked dispatchTouchEvent on " + className);
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 通过类名反射 Hook 隐藏类 android.view.InputEventReceiver，捕获完整的 DOWN -> MOVE -> UP 触摸流
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
                        for (Object arg : param.args) {
                            if (arg instanceof MotionEvent) {
                                observeMotionEvent((MotionEvent) arg);
                                break;
                            } else if (arg instanceof InputEvent) {
                                break;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + ": Hooked InputEventReceiver.dispatchInputEvent");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook InputEventReceiver: " + t.getMessage());
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
            // 读取系统小白条真实绘制宽度（通常为 108dp），不再额外加横向 Padding，严格对齐白条视觉边缘
            float pillWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 108f, dm);
            int widthDimenId = res.getIdentifier("navigation_home_handle_width", "dimen", SYSTEMUI_PACKAGE);
            if (widthDimenId != 0) {
                try {
                    float sysPillWidth = res.getDimension(widthDimenId);
                    if (sysPillWidth > 0f) {
                        pillWidthPx = sysPillWidth;
                    }
                } catch (Throwable ignored) {
                }
            }
            mPillVisualHalfWidthPx = pillWidthPx * 0.5f;

            // 纵向容差保持 20dp 不变
            mVerticalPaddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, dm);
        }
        mConfigInitialized = true;
    }

    private void observeMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        // 去重：防止同一事件在 View 树和 InputEventReceiver 中被重复统计
        if (action == mLastAction && downTime == mLastDownTime && eventTime == mLastEventTime) {
            return;
        }
        mLastAction = action;
        mLastDownTime = downTime;
        mLastEventTime = eventTime;

        ViewGroup navBar = mNavBarRef.get();
        Context ctx = mAppContext;
        if (ctx == null && navBar != null) {
            ctx = navBar.getContext();
            if (ctx != null) {
                mAppContext = ctx.getApplicationContext();
            }
        }
        initConfigIfNeeded(ctx);

        float rawX = ev.getRawX();
        float rawY = ev.getRawY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (!isTouchOnPill(navBar, ctx, rawX, rawY)) {
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

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_POINTER_DOWN: {
                resetState();
                break;
            }

            default:
                break;
        }
    }

    private boolean isTouchOnPill(ViewGroup navBarRoot, Context ctx, float rawX, float rawY) {
        // 竖屏下严格限制横向范围在屏幕居中的 38% ~ 62% 之间，防止点击小白条左右两侧空白处误触发
        if (ctx != null && ctx.getResources() != null) {
            Resources res = ctx.getResources();
            DisplayMetrics dm = res.getDisplayMetrics();
            if (res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                float minX = dm.widthPixels * 0.38f;
                float maxX = dm.widthPixels * 0.62f;
                if (rawX < minX || rawX > maxX) {
                    return false;
                }
            }
        }

        // 1. 尝试从 NavigationBar 的 home_handle 获取中心位置，并严格按小白条视觉宽度裁剪
        if (navBarRoot != null) {
            if (!mHomeHandleIdLookupDone) {
                Resources res = navBarRoot.getResources();
                if (res != null) {
                    mHomeHandleResId = res.getIdentifier("home_handle", "id", SYSTEMUI_PACKAGE);
                }
                mHomeHandleIdLookupDone = true;
            }

            if (mHomeHandleResId != 0) {
                View handleView = navBarRoot.findViewById(mHomeHandleResId);
                if (handleView != null && handleView.isShown()
                        && handleView.getWidth() > 0 && handleView.getHeight() > 0) {
                    handleView.getLocationOnScreen(mTempLocation);
                    float centerX = mTempLocation[0] + handleView.getWidth() * 0.5f;
                    float halfW = mPillVisualHalfWidthPx > 0f
                            ? mPillVisualHalfWidthPx
                            : handleView.getWidth() * 0.35f;

                    float left = centerX - halfW;
                    float right = centerX + halfW;
                    float top = mTempLocation[1] - mVerticalPaddingPx;
                    float bottom = mTempLocation[1] + handleView.getHeight() + mVerticalPaddingPx;
                    mCachedPillBounds.set(left, top, right, bottom);
                    return mCachedPillBounds.contains(rawX, rawY);
                }
            }
        }

        // 2. 尝试匹配已缓存的小白条位置
        if (!mCachedPillBounds.isEmpty()) {
            return mCachedPillBounds.contains(rawX, rawY);
        }

        // 3. 屏幕底部中心区域兜底（宽度严格居中 38% ~ 62%，底部高度 42dp 内）
        if (ctx != null && ctx.getResources() != null) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            float screenW = dm.widthPixels;
            float screenH = dm.heightPixels;
            float bottomZoneHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 42f, dm);

            float left = screenW * 0.38f;
            float right = screenW * 0.62f;
            float top = screenH - bottomZoneHeight;
            float bottom = screenH;
            return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
        }

        return false;
    }

    private void triggerScreenLock(View view, Context ctx) {
        if (view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } catch (Throwable ignored) {
            }
        }

        if (ctx == null && view != null) {
            ctx = view.getContext();
        }
        if (ctx == null) {
            XposedBridge.log(TAG + ": Cannot lock screen because Context is null.");
            return;
        }

        // 第一步：通知 WindowManager 立即上锁 Keyguard（与系统无障碍锁屏同源，保留指纹解锁）
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