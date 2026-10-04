package com.lsp.sidebar.scene;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * 目标进程：com.miui.securitycenter 的 remote 子进程（com.miui.securitycenter.remote）。
 * 该进程承载 GameBoosterService / VideoToolBoxService，以及两个场景窗口控制器
 * （游戏 I 单例、视频 I3.w 单例）和两个“进程内广播”（gb.action.update_*_list）。
 * 只有在这个进程里做场景切换，才能直接驱动窗口显隐。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.miui.securitycenter";
    private static final String REMOTE_PROCESS = "com.miui.securitycenter.remote";
    private static final String TAG = "LspSidebarSwitch";

    private static volatile boolean sInit;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        if (sInit) {
            return;
        }
        sInit = true;

        // GameBoosterService / VideoToolBoxService 都运行在 remote 子进程。
        // 在任一服务 onCreate 时完成模块初始化（拿 Context、注册广播、挂悬浮按钮）。
        for (String cls : new String[]{
                "com.miui.gamebooster.service.GameBoosterService",
                "com.miui.gamebooster.service.VideoToolBoxService"}) {
            try {
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                initModule((Context) param.thisObject);
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "hook " + cls + " failed", t);
            }
        }
    }

    private void initModule(final Context appContext) {
        try {
            if (!REMOTE_PROCESS.equals(appContext.getApplicationInfo().processName)) {
                Log.d(TAG, "skip init, process=" + appContext.getApplicationInfo().processName);
                return;
            }
            if (Module.INSTANCE != null) {
                return;
            }
            final Context ctx = appContext.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Module.init(ctx);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "init failed", t);
        }
    }
}
