package com.lsp.sidebar.scene;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 广播触发入口。
 *
 * 任意方式发一条广播即可触发切换（缺省切换当前前台应用）：
 *   adb shell am broadcast -a com.lsp.sidebar.scene.SWITCH
 *   adb shell am broadcast -a com.lsp.sidebar.scene.SWITCH --es pkg com.tencent.tmgp.sgame
 * 也可由其它 App / Tasker / 快捷方式调用（接收者为本模块在 remote 进程动态注册）。
 */
public class SwitchReceiver extends BroadcastReceiver {

    public static final String ACTION_SWITCH = "com.lsp.sidebar.scene.SWITCH";
    private static final String TAG = "LspSidebarSwitch.Rcv";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Log.i(TAG, "switch requested: " + (intent != null ? intent.getAction() : "null"));
            Module.onSwitchRequested(context, intent);
        } catch (Throwable t) {
            Log.w(TAG, "switch failed", t);
        }
    }
}
