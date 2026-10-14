package com.lighty.pill.dt2l;

import android.content.Context;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PillDT2L";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    // 状态机枚举
    private static final int STATE_IDLE = 0;
    private static final int STATE_FIRST_DOWN = 1;
    private static final int STATE_WAIT_SECOND_DOWN = 2;
    private static final int STATE_SECOND_DOWN = 3;

    // 单次点击最大持续时间（毫秒）：必须显著小于圈定即搜的长按阈值（400~500ms）
    private static final long MAX_TAP_DURATION_MS = 250L;

    private int mState = STATE_IDLE;
    private long mFirstDownTime = 0L;
    private long mFirstUpTime = 0L;
    private long mSecondDownTime = 0L;

    private float mFirstDownX = 0f;
    private float mFirstDownY = 0f;
    private float mSecondDownX = 0f;
    private float mSecondDownY = 0f;

    // 防止同一事件重复分发
    private int mLastAction = -1;
    private long mLastDownTime = -1L;
    private long mLastEventTime = -1L;

    // 缓存的系统阈值与资源 ID（避免高频触摸时重复计算与分配内存）
    private boolean mConfigInitialized = false;
    private float mTouchSlopSq = 0f;
    private float mDoubleTapSlopSq = 0f;
    private long mDoubleTapTimeout = 300L;
    private float mHorizontalPaddingPx = 0f;
    private float mVerticalPaddingPx = 0f;

    private int mHomeHandleResId = 0;
    private boolean mHomeHandleIdLookupDone = false;
    private final int[] mTempLocation = new int[2];

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 优先查找 Android 15 QPR / 16 / 17 的路径，回退查找旧版 AOSP 路径
        String[] candidateClasses = new String[]{
                "com.android.systemui.navigationbar.views.NavigationBarFrame",
                "com.android.systemui.navigationbar.NavigationBarFrame",
                "com.android.systemui.navigationbar.views.NavigationBarView",
                "com.android.systemui.navigationbar.NavigationBarView"
        };

        boolean hooked = false;
        for (String className : candidateClasses) {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (targetClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                            targetClass,
                            "dispatchTouchEvent",
                            MotionEvent.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (!(param.thisObject instanceof ViewGroup)) {
                                        return;
                                    }
                                    MotionEvent ev = (MotionEvent) param.args[0];
                                    if (ev != null) {
                                        observeTouchEvent((ViewGroup) param.thisObject, ev);
                                    }
                                }
                            }
                    );
                    XposedBridge.log(TAG + ": Hooked dispatchTouchEvent on " + className);
                    hooked = true;
                    break;
                } catch (Throwable ignored) {
                    // 若当前类未直接重写 dispatchTouchEvent，继续尝试下一个候选类
                }
            }
        }

        if (!hooked) {
            XposedBridge.log(TAG + ": Failed to find suitable NavigationBar touch entry.");
        }
    }

    private void initConfigIfNeeded(Context context) {
        if (mConfigInitialized || context == null) {
            return;
        }
        ViewConfiguration vc = ViewConfiguration.get(context);
        float touchSlop = vc.getScaledTouchSlop();
        float doubleTapSlop = vc.getScaledDoubleTapSlop();
        mTouchSlopSq = touchSlop * touchSlop;
        mDoubleTapSlopSq = doubleTapSlop * doubleTapSlop;
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

        Resources res = context.getResources();
        if (res != null) {
            // 为小白条增加适度点击热区容差（横向 16dp，纵向 10dp）
            mHorizontalPaddingPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16f, res.getDisplayMetrics());
            mVerticalPaddingPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 10f, res.getDisplayMetrics());
        }
        mConfigInitialized = true;
    }

    private void observeTouchEvent(ViewGroup navBarRoot, MotionEvent ev) {
        int action = ev.getActionMasked();
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        // 去重保护
        if (action == mLastAction && downTime == mLastDownTime && eventTime == mLastEventTime) {
            return;
        }
        mLastAction = action;
        mLastDownTime = downTime;
        mLastEventTime = eventTime;

        initConfigIfNeeded(navBarRoot.getContext());

        float rawX = ev.getRawX();
        float rawY = ev.getRawY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                boolean inPill = isTouchOnPill(navBarRoot, rawX, rawY);
                if (!inPill) {
                    resetState();
                    return;
                }

                if (mState == STATE_WAIT_SECOND_DOWN) {
                    long interval = eventTime - mFirstUpTime;
                    float distSq = distanceSq(rawX, rawY, mFirstDownX, mFirstDownY);
                    if (interval >= 0L && interval <= mDoubleTapTimeout && distSq <= mDoubleTapSlopSq) {
                        mState = STATE_SECOND_DOWN;
                        mSecondDownTime = eventTime;
                        mSecondDownX = rawX;
                        mSecondDownY = rawY;
                        return;
                    }
                }

                // 作为第一次按下记录
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
                        // 发生滑动或长按，立即退出状态机，完全交给手势导航或圈定即搜
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
                        triggerScreenLock(navBarRoot);
                    } else {
                        resetState();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 系统接管手势或多指触控时立即清空状态
                resetState();
                break;
            }

            default:
                break;
        }
    }

    private boolean isTouchOnPill(ViewGroup navBarRoot, float rawX, float rawY) {
        if (!mHomeHandleIdLookupDone) {
            Resources res = navBarRoot.getResources();
            if (res != null) {
                mHomeHandleResId = res.getIdentifier("home_handle", "id", SYSTEMUI_PACKAGE);
            }
            mHomeHandleIdLookupDone = true;
        }

        if (mHomeHandleResId != 0) {
            View handleView = navBarRoot.findViewById(mHomeHandleResId);
            if (handleView != null) {
                // 如果小白条不可见（如处于三键导航模式），则不响应
                if (!handleView.isShown() || handleView.getWidth() <= 0 || handleView.getHeight() <= 0) {
                    return false;
                }
                handleView.getLocationOnScreen(mTempLocation);
                float left = mTempLocation[0] - mHorizontalPaddingPx;
                float top = mTempLocation[1] - mVerticalPaddingPx;
                float right = mTempLocation[0] + handleView.getWidth() + mHorizontalPaddingPx;
                float bottom = mTempLocation[1] + handleView.getHeight() + mVerticalPaddingPx;
                return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
            }
        }

        // 兜底逻辑：如果找不到 home_handle 控件，则取导航栏居中 36% 区域
        if (navBarRoot.getWidth() <= 0 || navBarRoot.getHeight() <= 0) {
            return false;
        }
        navBarRoot.getLocationOnScreen(mTempLocation);
        float rootWidth = navBarRoot.getWidth();
        float left = mTempLocation[0] + rootWidth * 0.32f;
        float right = mTempLocation[0] + rootWidth * 0.68f;
        float top = mTempLocation[1] - mVerticalPaddingPx;
        float bottom = mTempLocation[1] + navBarRoot.getHeight() + mVerticalPaddingPx;
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private void triggerScreenLock(View view) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        } catch (Throwable ignored) {
        }

        Context context = view.getContext();
        if (context == null) {
            return;
        }

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        try {
            XposedHelpers.callMethod(pm, "goToSleep", now);
        } catch (Throwable t1) {
            try {
                // 兼容三参数重载 goToSleep(long time, int reason, int flags)
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