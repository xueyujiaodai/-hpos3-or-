package com.lsp.sidebar.scene;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 模块初始化：注册触发广播 + 挂悬浮切换按钮。
 * 只在 remote 子进程（服务进程）执行一次。
 */
public class Module {

    private static final String TAG = "LspSidebarSwitch";

    public static volatile Module INSTANCE;

    private final Context mContext;
    private final Prefs mPrefs;
    private FloatingSwitchView mFloating;

    private Module(Context ctx) {
        mContext = ctx;
        mPrefs = new Prefs(ctx);
    }

    public static void init(Context ctx) {
        try {
            if (INSTANCE != null) {
                return;
            }
            INSTANCE = new Module(ctx);
            INSTANCE.registerTrigger();
            INSTANCE.attachFloatingButton();
            Log.i(TAG, "module initialized in " + ctx.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "module init error", t);
        }
    }

    public static Context context() {
        return INSTANCE != null ? INSTANCE.mContext : null;
    }

    public static Prefs prefs() {
        return INSTANCE != null ? INSTANCE.mPrefs : null;
    }

    private void registerTrigger() {
        try {
            IntentFilter filter = new IntentFilter(SwitchReceiver.ACTION_SWITCH);
            mContext.registerReceiver(new SwitchReceiver(), filter);
            Log.i(TAG, "trigger receiver registered: " + SwitchReceiver.ACTION_SWITCH);
        } catch (Throwable t) {
            Log.w(TAG, "register receiver failed", t);
        }
    }

    private void attachFloatingButton() {
        try {
            if (!mPrefs.enableFloatingButton()) {
                return;
            }
            mFloating = new FloatingSwitchView(mContext, mPrefs);
            mFloating.show();
            Log.i(TAG, "floating button attached");
        } catch (Throwable t) {
            Log.w(TAG, "floating button failed", t);
        }
    }

    /** 外部可通过广播触发：action = SwitchReceiver.ACTION_SWITCH, extra "pkg" 可选 */
    public static void onSwitchRequested(Context context, Intent intent) {
        SceneSwitcher.switchScene(context, intent);
    }
}
