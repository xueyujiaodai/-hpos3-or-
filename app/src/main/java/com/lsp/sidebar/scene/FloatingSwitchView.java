package com.lsp.sidebar.scene;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮切换按钮。
 *
 * 一个小圆钮挂在屏幕右侧（可拖拽），点击即对当前前台应用执行场景切换：
 *   - 应用在视频场景 → 切成游戏场景（先取消视频、再进游戏）
 *   - 应用在游戏场景 → 切成视频场景
 *   - 都不在 → 推入游戏场景
 *
 * 由 remote 子进程（系统应用）添加，TYPE_APPLICATION_OVERLAY 即可悬浮。
 */
public class FloatingSwitchView {

    private static final String TAG = "LspSidebarSwitch.Float";

    private final Context mContext;
    private final Prefs mPrefs;
    private final WindowManager mWm;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mView;
    private WindowManager.LayoutParams mLp;
    private boolean mShowing;

    private float mDownRawX, mDownRawY;
    private float mStartX, mStartY;
    private boolean mMoved;
    private long mDownTime;

    @SuppressLint("ClickableViewAccessibility")
    public FloatingSwitchView(Context ctx, Prefs prefs) {
        mContext = ctx;
        mPrefs = prefs;
        mWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (mShowing) return;
        try {
            mView = new TextView(mContext);
            mView.setText("⇄");
            mView.setTextSize(16f);
            mView.setTextColor(Color.WHITE);
            mView.setTypeface(Typeface.DEFAULT_BOLD);
            mView.setGravity(Gravity.CENTER);
            mView.setBackgroundColor(Color.parseColor("#CC3A6EA5"));
            mView.setContentDescription("切换侧边栏场景");

            int size = dp(44);
            mLp = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            mLp.gravity = Gravity.TOP | Gravity.START;

            float x = mPrefs.getButtonX();
            float y = mPrefs.getButtonY();
            if (x < 0 || y < 0) {
                x = dp(8);
                y = dp(220);
            }
            mLp.x = (int) x;
            mLp.y = (int) y;

            mView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return onTouchEvent(event);
                }
            });

            mWm.addView(mView, mLp);
            mShowing = true;
            Log.i(TAG, "floating button shown");
        } catch (Throwable t) {
            Log.w(TAG, "show failed", t);
        }
    }

    public void hide() {
        try {
            if (mShowing && mView != null) {
                mWm.removeView(mView);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hide failed", t);
        }
        mShowing = false;
    }

    private boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartX = mLp.x;
                mStartY = mLp.y;
                mMoved = false;
                mDownTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                    mMoved = true;
                }
                mLp.x = (int) (mStartX + dx);
                mLp.y = (int) (mStartY + dy);
                try {
                    mWm.updateViewLayout(mView, mLp);
                } catch (Throwable t) {
                    Log.w(TAG, "update layout failed", t);
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean isTap = !mMoved && (System.currentTimeMillis() - mDownTime) < 600L;
                if (mMoved) {
                    mPrefs.setButtonX(mLp.x);
                    mPrefs.setButtonY(mLp.y);
                }
                if (isTap) {
                    onClick();
                }
                return true;
        }
        return false;
    }

    private void onClick() {
        Log.i(TAG, "button clicked, switching scene");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                SceneSwitcher.switchScene(mContext, null);
            }
        });
    }

    private int dp(float v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }
}
