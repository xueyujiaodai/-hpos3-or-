package com.lsp.sidebar.scene;

import android.content.Context;
import android.util.Log;

/**
 * 获取当前前台应用包名/uid。
 *
 * 首选复用手机管家自己的通道：com.miui.gamebooster.service.E.e()，
 * 它内部通过 miui.process.ProcessManager.getForegroundInfo() 取前台信息（最准确）。
 * 失败时回退 ActivityManager#getRunningTasks。
 */
public final class ForegroundHelper {

    private static final String TAG = "LspSidebarSwitch.Foreground";
    private static final String CLS_SERVICE_E = "com.miui.gamebooster.service.E";
    private static final String CLS_L0 = "com.miui.common.utils.L0";

    public static String currentPkg(Context ctx) {
        try {
            Object fg = LocalBc.XposedHelpersRef.callStatic(CLS_SERVICE_E, "e");
            if (fg != null) {
                Object pkg = LocalBc.XposedHelpersRef.call(fg, "getForegroundPackageName");
                if (pkg instanceof String) {
                    return (String) pkg;
                }
                // 旧版字段名
                try {
                    java.lang.reflect.Field f = fg.getClass().getField("mForegroundPackageName");
                    Object v = f.get(fg);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.d(TAG, "E.e() failed: " + t.getMessage());
        }
        // 回退
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRunningTasks fallback failed", t);
        }
        return null;
    }

    /** 前台 uid（原始值；写入游戏列表时会再归一化）。 */
    public static int currentUid(Context ctx) {
        try {
            Object fg = LocalBc.XposedHelpersRef.callStatic(CLS_SERVICE_E, "e");
            if (fg != null) {
                Object uid = LocalBc.XposedHelpersRef.call(fg, "getForegroundUid");
                if (uid instanceof Integer) {
                    return (Integer) uid;
                }
                try {
                    java.lang.reflect.Field f = fg.getClass().getField("mForegroundUid");
                    Object v = f.get(fg);
                    if (v instanceof Integer) return (Integer) v;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.d(TAG, "E.e() uid failed: " + t.getMessage());
        }
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(currentPkg(ctx), 0);
            return ai.uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 双开/分身归一化（对应 L0.o(uid)），拿不到时原样返回。 */
    public static int normalizeUid(int uid) {
        try {
            Object r = LocalBc.XposedHelpersRef.callStatic(CLS_L0, "o", uid);
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable t) {
            Log.d(TAG, "L0.o failed, keep uid=" + uid);
        }
        return uid;
    }
}
