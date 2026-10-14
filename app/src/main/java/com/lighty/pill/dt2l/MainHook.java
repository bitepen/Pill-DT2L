package com.lighty.pill.dt2l;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputEvent;
import android.view.InputEventReceiver;
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

    // 单次点击最大按压时长（毫秒）：放宽到 280ms 保证手感，同时避开 400ms+ 的圈定即搜长按
    private static final long MAX_TAP_DURATION_MS = 280L;

    private int mState = STATE_IDLE;
    private long mFirstDownTime = 0L;
    private long mFirstUpTime = 0L;
    private long mSecondDownTime = 0L;

    private float mFirstDownX = 0f;
    private float mFirstDownY = 0f;
    private float mSecondDownX = 0f;
    private float mSecondDownY = 0f;

    // 防止多个 InputChannel 重复派发同一个事件
    private int mLastAction = -1;
    private long mLastDownTime = -1L;
    private long mLastEventTime = -1L;

    private volatile Context mAppContext;
    private WeakReference<ViewGroup> mNavBarRef = new WeakReference<>(null);

    private boolean mConfigInitialized = false;
    private float mTouchSlopSq = 0f;
    private float mDoubleTapSlopSq = 0f;
    private long mDoubleTapTimeout = 300L;
    private float mHorizontalPaddingPx = 0f;
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

        // 1. 获取全局 Context 兜底
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

        // 2. 捕获 NavigationBar 实例以实时定位 home_handle 小白条真实位置
        String[] navBarClasses = new String[]{
                "com.android.systemui.navigationbar.views.NavigationBarView",
                "com.android.systemui.navigationbar.NavigationBarView",
                "com.android.systemui.navigationbar.views.NavigationBarFrame",
                "com.android.systemui.navigationbar.NavigationBarFrame"
        };

        for (String className : navBarClasses) {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (targetClass != null) {
                try {
                    XposedBridge.hookAllConstructors(targetClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) param.thisObject;
                                mNavBarRef = new WeakReference<>(vg);
                                if (mAppContext == null && vg.getContext() != null) {
                                    mAppContext = vg.getContext().getApplicationContext();
                                    initConfigIfNeeded(mAppContext);
                                }
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": Hooked constructor of " + className);
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 核心：Hook InputEventReceiver.dispatchInputEvent，确保拿到完整的 DOWN -> MOVE -> UP 序列
        try {
            XposedHelpers.findAndHookMethod(
                    InputEventReceiver.class,
                    "dispatchInputEvent",
                    int.class,
                    InputEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            InputEvent event = (InputEvent) param.args[1];
                            if (event instanceof MotionEvent) {
                                observeMotionEvent((MotionEvent) event);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Successfully hooked InputEventReceiver.dispatchInputEvent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook InputEventReceiver: " + t.getMessage());
        }
    }

    private void initConfigIfNeeded(Context context) {
        if (mConfigInitialized || context == null) {
            return;
        }
        ViewConfiguration vc = ViewConfiguration.get(context);
        // 适当放宽一点单次点击的手指抖动容差（1.5 倍 TouchSlop），防止双击时轻微移位被误判为滑动
        float touchSlop = vc.getScaledTouchSlop() * 1.5f;
        float doubleTapSlop = vc.getScaledDoubleTapSlop() * 1.2f;
        mTouchSlopSq = touchSlop * touchSlop;
        mDoubleTapSlopSq = doubleTapSlop * doubleTapSlop;
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

        Resources res = context.getResources();
        if (res != null) {
            // 小白条点击热区容差：横向扩充 28dp，纵向扩充 18dp（覆盖底部边缘极窄触控区）
            mHorizontalPaddingPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 28f, res.getDisplayMetrics());
            mVerticalPaddingPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 18f, res.getDisplayMetrics());
        }
        mConfigInitialized = true;
    }

    private void observeMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        // 去重：防止同一触摸事件被多个 InputMonitor 重复观察
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
                    if (interval >= 20L && interval <= mDoubleTapTimeout && distSq <= mDoubleTapSlopSq) {
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
                        XposedBridge.log(TAG + ": Double tap detected on pill, locking screen.");
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
        // 1. 尝试从 NavigationBarView 的 home_handle 获取精准坐标
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
                    float left = mTempLocation[0] - mHorizontalPaddingPx;
                    float top = mTempLocation[1] - mVerticalPaddingPx;
                    float right = mTempLocation[0] + handleView.getWidth() + mHorizontalPaddingPx;
                    float bottom = mTempLocation[1] + handleView.getHeight() + mVerticalPaddingPx;
                    mCachedPillBounds.set(left, top, right, bottom);
                    return mCachedPillBounds.contains(rawX, rawY);
                }
            }
        }

        // 2. 如果之前缓存过有效的小白条位置，优先使用缓存
        if (!mCachedPillBounds.isEmpty()) {
            return mCachedPillBounds.contains(rawX, rawY);
        }

        // 3. 终极兜底：根据当前屏幕尺寸计算底部居中小白条热区（宽度居中 30% ~ 70%，底部高度 36dp 内）
        if (ctx != null && ctx.getResources() != null) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            float screenW = dm.widthPixels;
            float screenH = dm.heightPixels;
            float bottomZoneHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, dm);

            float left = screenW * 0.30f;
            float right = screenW * 0.70f;
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

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        try {
            XposedHelpers.callMethod(pm, "goToSleep", now);
        } catch (Throwable t1) {
            try {
                XposedHelpers.callMethod(pm, "goToSleep", now, 4, 0);
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