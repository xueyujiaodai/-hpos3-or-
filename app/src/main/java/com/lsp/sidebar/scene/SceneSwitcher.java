package com.lsp.sidebar.scene;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;

/**
 * 场景切换核心。
 *
 * 切换语义（与系统行为对齐，已验证于 手机管家 12.1.3-251205.0.1 / HyperOS 3）：
 *
 *  - 视频场景的“应用名单” = remoteprovider 的 pref_video_box_app_list（删除名单 pref_video_box_app_del_list）
 *  - 游戏场景的“应用名单” = ContentProvider content://com.miui.securitycenter.gamebooster/gamebooster（flag_white=0 行）
 *  - 视频工具箱窗口控制器 = I3.w（m() 进入 / n() 退出）
 *  - 游戏工具箱窗口控制器 = com.miui.gamebooster.service.I（e0() 进入 / g0() 退出）
 *  - 名单变更后需发进程内广播 gb.action.update_video_list / gb.action.update_game_list 让其重载
 *
 * 关键顺序：切换时必须【先取消当前场景，再进入目标场景】——
 * 例如视频应用 → 游戏场景：先退出视频工具箱并把它从视频名单移除，
 * 再把它写入游戏名单并拉起游戏工具箱。
 */
public final class SceneSwitcher {

    private static final String TAG = "LspSidebarSwitch.Scene";

    // ---- remoteprovider（B2.e 的底层通道，TYPE=5 为 StringArrayList）----
    private static final Uri REMOTE_PREF_URI =
            Uri.parse("content://com.miui.securitycenter.remoteprovider");
    private static final String KEY_VIDEO_APP_LIST = "pref_video_box_app_list";
    private static final String KEY_VIDEO_APP_DEL_LIST = "pref_video_box_app_del_list";
    private static final String KEY_VIDEO_BOOSTER_STATUS = "pref_video_booster_status";

    // ---- 游戏列表 Provider ----
    private static final Uri GAME_PROVIDER =
            Uri.parse("content://com.miui.securitycenter.gamebooster/gamebooster");
    private static final Uri GAME_PROVIDER_DELETE =
            Uri.parse("content://com.miui.securitycenter.gamebooster/gamebooster/1");

    // ---- Settings.Secure 键 ----
    private static final String KEY_VIDEOBOX_SWITCH = "pref_videobox_switch_status";
    private static final String KEY_GB_BOOSTING = "gb_boosting";
    private static final String KEY_VTB_BOOSTING = "vtb_boosting";
    private static final String KEY_OPEN_GAME_BOOSTER = "pref_open_game_booster";

    // ---- 进程内广播 ----
    private static final String ACTION_UPDATE_VIDEO_LIST = "gb.action.update_video_list";
    private static final String ACTION_UPDATE_GAME_LIST = "gb.action.update_game_list";

    // ---- 混淆类 ----
    private static final String CLS_VIDEO_BOX_MGR = "I3.w";
    private static final String CLS_GAME_BOX_MGR = "com.miui.gamebooster.service.I";

    // ============================================================
    //  对外入口
    // ============================================================

    /** 广播 / 悬浮按钮统一入口。extra "pkg" 可指定目标应用；缺省取当前前台。 */
    public static void switchScene(Context ctx, Intent intent) {
        String pkg = intent != null ? intent.getStringExtra("pkg") : null;
        if (pkg == null || pkg.isEmpty()) {
            pkg = ForegroundHelper.currentPkg(ctx);
        }
        if (pkg == null) {
            Log.w(TAG, "no target package");
            return;
        }
        int uid = ForegroundHelper.currentUid(ctx);
        if (uid <= 0) {
            ApplicationInfo ai = appInfo(ctx, pkg);
            if (ai != null) uid = ai.uid;
        }

        boolean inVideo = inVideoList(ctx, pkg);
        boolean inGame = inGameList(ctx, pkg);

        Log.i(TAG, "switchScene pkg=" + pkg + " uid=" + uid
                + " inVideo=" + inVideo + " inGame=" + inGame);

        if (inVideo) {
            // 视频场景 → 游戏场景（用户的核心诉求）
            switchToGameScene(ctx, pkg, uid);
        } else if (inGame) {
            // 游戏场景 → 视频场景
            switchToVideoScene(ctx, pkg, uid);
        } else {
            // 都不在：默认推入游戏场景（可改为推入视频场景）
            switchToGameScene(ctx, pkg, uid);
        }
    }

    // ============================================================
    //  视频场景 → 游戏场景（先取消视频，再切游戏）
    // ============================================================

    public static boolean switchToGameScene(Context ctx, String pkg, int uid) {
        Log.i(TAG, "== switchToGameScene: " + pkg + " ==");
        try {
            // ---------- 第一步：先取消视频场景 ----------
            // 1.1 若视频工具箱正在前台展示，先退出（清 boosting 标志、隐藏窗口）
            if (isVideoBoxActive(ctx)) {
                exitVideoBox(ctx);
            }
            // 1.2 从视频场景应用名单移除，并加入“删除名单”（防止默认名单把它加回来）
            ArrayList<String> videoList = readList(ctx, KEY_VIDEO_APP_LIST);
            boolean removed = videoList.remove(pkg);
            if (removed) {
                writeList(ctx, KEY_VIDEO_APP_LIST, videoList);
            }
            ArrayList<String> delList = readList(ctx, KEY_VIDEO_APP_DEL_LIST);
            if (!delList.contains(pkg)) {
                delList.add(pkg);
                writeList(ctx, KEY_VIDEO_APP_DEL_LIST, delList);
            }
            // 1.3 让视频服务重载名单
            LocalBc.send(ctx, ACTION_UPDATE_VIDEO_LIST);
            Log.i(TAG, "video scene cancelled, removed=" + removed);

            // ---------- 第二步：再切换到游戏场景 ----------
            // 2.1 保证游戏工具箱总开关开启（用户明确要“切到游戏场景”，开关关闭时窗口不会驻留）
            Prefs prefs = Module.prefs();
            if (prefs != null && prefs.autoEnableGameBooster()) {
                setSecureInt(ctx, KEY_OPEN_GAME_BOOSTER, 1);
            }
            // 2.2 写入游戏名单（provider 插入一行，flag_white=0），并清除“手动删除”标记
            if (!inGameList(ctx, pkg)) {
                insertGameRow(ctx, pkg, uid);
                removeManualDeleteFlag(ctx, pkg);
            }
            // 2.3 让游戏服务重载名单
            LocalBc.send(ctx, ACTION_UPDATE_GAME_LIST);
            // 2.4 若目标应用就在前台，立即拉起游戏工具箱
            String fg = ForegroundHelper.currentPkg(ctx);
            if (pkg.equals(fg)) {
                enterGameMode(ctx, pkg, uid);
            }
            Log.i(TAG, "game scene entered");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "switchToGameScene error", t);
            return false;
        }
    }

    // ============================================================
    //  游戏场景 → 视频场景（先取消游戏，再切视频）
    // ============================================================

    public static boolean switchToVideoScene(Context ctx, String pkg, int uid) {
        Log.i(TAG, "== switchToVideoScene: " + pkg + " ==");
        try {
            // ---------- 第一步：先取消游戏场景 ----------
            if (inGameMode(ctx)) {
                exitGameMode(ctx);
            }
            if (inGameList(ctx, pkg)) {
                deleteGameRow(ctx, pkg, uid);
                addManualDeleteFlag(ctx, pkg);
            }
            LocalBc.send(ctx, ACTION_UPDATE_GAME_LIST);
            Log.i(TAG, "game scene cancelled");

            // ---------- 第二步：再切换到视频场景 ----------
            // 保证视频工具箱总开关开启
            setSecureInt(ctx, KEY_VIDEOBOX_SWITCH, 1);
            ArrayList<String> videoList = readList(ctx, KEY_VIDEO_APP_LIST);
            if (!videoList.contains(pkg)) {
                videoList.add(pkg);
                writeList(ctx, KEY_VIDEO_APP_LIST, videoList);
            }
            ArrayList<String> delList = readList(ctx, KEY_VIDEO_APP_DEL_LIST);
            if (delList.remove(pkg)) {
                writeList(ctx, KEY_VIDEO_APP_DEL_LIST, delList);
            }
            LocalBc.send(ctx, ACTION_UPDATE_VIDEO_LIST);
            String fg = ForegroundHelper.currentPkg(ctx);
            if (pkg.equals(fg)) {
                enterVideoBox(ctx, pkg, uid);
            }
            Log.i(TAG, "video scene entered");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "switchToVideoScene error", t);
            return false;
        }
    }

    // ============================================================
    //  视频名单（remoteprovider，复刻 B2.e 的 TYPE=5 存取）
    // ============================================================

    @SuppressWarnings("unchecked")
    private static ArrayList<String> readList(Context ctx, String key) {
        try {
            Bundle b = new Bundle();
            b.putInt("TYPE", 5);
            b.putString("key", key);
            b.putStringArrayList("default", new ArrayList<>());
            Bundle r = ctx.getContentResolver().call(REMOTE_PREF_URI, "callPreference", key, b);
            if (r != null) {
                ArrayList<String> list = r.getStringArrayList(key);
                if (list != null) {
                    return new ArrayList<>(list);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "readList " + key + " failed", t);
        }
        return new ArrayList<>();
    }

    private static void writeList(Context ctx, String key, ArrayList<String> list) {
        try {
            Bundle b = new Bundle();
            b.putInt("TYPE", 5);
            b.putString("key", key);
            b.putStringArrayList("value", new ArrayList<>(list));
            ctx.getContentResolver().call(REMOTE_PREF_URI, "callPreference", key, b);
            try {
                ctx.getContentResolver().notifyChange(
                        Uri.withAppendedPath(REMOTE_PREF_URI, key), null, false);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeList " + key + " failed", t);
        }
    }

    // ============================================================
    //  游戏名单（ContentProvider）
    // ============================================================

    private static boolean inGameList(Context ctx, String pkg) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(GAME_PROVIDER, null,
                    "package_name=? AND flag_white=?", new String[]{pkg, "0"}, null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            Log.w(TAG, "inGameList failed", t);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 等价于系统 N.g(context, appLabel, pkg, uid, 0)。 */
    private static void insertGameRow(Context ctx, String pkg, int uid) {
        ApplicationInfo ai = appInfo(ctx, pkg);
        String label = "Unknown";
        if (ai != null) {
            try {
                CharSequence l = ai.loadLabel(ctx.getPackageManager());
                if (l != null) label = l.toString();
            } catch (Throwable ignored) {
            }
        }
        int nUid = ForegroundHelper.normalizeUid(uid);

        ContentValues v = new ContentValues();
        v.put("app_name", label);
        v.put("package_name", pkg);
        v.put("package_uid", nUid);
        v.put("flag_white", 0);
        v.put("settings_gs", -1);
        v.put("settings_ts", -1);
        v.put("settings_edge", -1);
        v.put("settings_sensitivity", -1);
        v.put("settings_op_stability", -1);
        v.put("settings_hdr", -1);
        v.put("settings_4d", 0);
        v.put("settings_follow", -1);
        v.put("settings_finger", -1);
        v.put("settings_hot_area", -1);
        v.put("settings_shake", -1);
        v.put("settings_vibrator", -1);
        v.put("game_gravity", 17);          // F3.a.a = GRAVITY_CENTER
        v.put("game_ratio", 0.0f);          // F3.a.f = RATIO_FULLSCREEN
        ctx.getContentResolver().insert(GAME_PROVIDER, v);
        Log.i(TAG, "inserted game row: " + pkg + " uid=" + nUid);
    }

    /** 等价于系统 N.c(context, pkg, uid, false, 0)。 */
    private static void deleteGameRow(Context ctx, String pkg, int uid) {
        int nUid = ForegroundHelper.normalizeUid(uid);
        try {
            ctx.getContentResolver().delete(GAME_PROVIDER_DELETE,
                    "package_name=? AND package_uid=? AND flag_white=?",
                    new String[]{pkg, String.valueOf(nUid), "0"});
        } catch (Throwable t) {
            Log.w(TAG, "deleteGameRow failed", t);
        }
    }

    /** pref_manually_delete_game_list：游戏“手动删除”名单，防止云同步/默认逻辑再加回来。 */
    private static void addManualDeleteFlag(Context ctx, String pkg) {
        ArrayList<String> m = readList(ctx, "pref_manually_delete_game_list");
        if (!m.contains(pkg)) {
            m.add(pkg);
            writeList(ctx, "pref_manually_delete_game_list", m);
        }
    }

    private static void removeManualDeleteFlag(Context ctx, String pkg) {
        ArrayList<String> m = readList(ctx, "pref_manually_delete_game_list");
        if (m.remove(pkg)) {
            writeList(ctx, "pref_manually_delete_game_list", m);
        }
    }

    // ============================================================
    //  场景状态
    // ============================================================

    private static boolean inVideoList(Context ctx, String pkg) {
        return readList(ctx, KEY_VIDEO_APP_LIST).contains(pkg);
    }

    /** 视频工具箱是否正在展示（boosting 标志或 booster 状态）。 */
    private static boolean isVideoBoxActive(Context ctx) {
        try {
            if (getSecureInt(ctx, KEY_GB_BOOSTING, 0) == 1 || getSecureInt(ctx, KEY_VTB_BOOSTING, 0) == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // pref_video_booster_status 为 true 也说明处于视频增强中
        return readBoolean(ctx, KEY_VIDEO_BOOSTER_STATUS);
    }

    /** 是否处于游戏模式：I.B().C() 返回 true 表示“不在游戏模式中”。 */
    private static boolean inGameMode(Context ctx) {
        try {
            Object mgr = LocalBc.XposedHelpersRef.callStatic(CLS_GAME_BOX_MGR, "B", ctx, null);
            Object c = LocalBc.XposedHelpersRef.call(mgr, "C");
            return Boolean.FALSE.equals(c);
        } catch (Throwable t) {
            Log.w(TAG, "inGameMode failed", t);
            return false;
        }
    }

    // ============================================================
    //  窗口控制器（反射调用，类名基于本 APK 版本已验证）
    // ============================================================

    /** 退出视频工具箱：I3.w.e(ctx, handler).n() */
    private static void exitVideoBox(Context ctx) {
        try {
            Object mgr = LocalBc.XposedHelpersRef.callStatic(CLS_VIDEO_BOX_MGR, "e", ctx, null);
            LocalBc.XposedHelpersRef.call(mgr, "n");
            Log.i(TAG, "video box exited");
        } catch (Throwable t) {
            Log.w(TAG, "exitVideoBox failed", t);
        }
    }

    /** 进入视频工具箱：I3.w.e(ctx).k(pkg,uid); m() */
    private static void enterVideoBox(Context ctx, String pkg, int uid) {
        try {
            Object mgr = LocalBc.XposedHelpersRef.callStatic(CLS_VIDEO_BOX_MGR, "e", ctx, null);
            LocalBc.XposedHelpersRef.call(mgr, "k", pkg, uid);
            LocalBc.XposedHelpersRef.call(mgr, "m");
            Log.i(TAG, "video box entered");
        } catch (Throwable t) {
            Log.w(TAG, "enterVideoBox failed", t);
        }
    }

    /** 进入游戏模式：I.B().R(pkg); a0(uid); （未在游戏中则 e0() 完整启动，否则 S(true) 刷新） */
    private static void enterGameMode(Context ctx, String pkg, int uid) {
        try {
            Object mgr = LocalBc.XposedHelpersRef.callStatic(CLS_GAME_BOX_MGR, "B", ctx, null);
            LocalBc.XposedHelpersRef.call(mgr, "R", pkg);
            LocalBc.XposedHelpersRef.call(mgr, "a0", uid);
            Object c = LocalBc.XposedHelpersRef.call(mgr, "C");
            if (Boolean.TRUE.equals(c)) {
                LocalBc.XposedHelpersRef.call(mgr, "e0");
            } else {
                LocalBc.XposedHelpersRef.call(mgr, "S", true);
            }
            Log.i(TAG, "game mode entered");
        } catch (Throwable t) {
            Log.w(TAG, "enterGameMode failed", t);
        }
    }

    /** 退出游戏模式：I.B().g0() */
    private static void exitGameMode(Context ctx) {
        try {
            Object mgr = LocalBc.XposedHelpersRef.callStatic(CLS_GAME_BOX_MGR, "B", ctx, null);
            LocalBc.XposedHelpersRef.call(mgr, "g0");
            Log.i(TAG, "game mode exited");
        } catch (Throwable t) {
            Log.w(TAG, "exitGameMode failed", t);
        }
    }

    // ============================================================
    //  小工具
    // ============================================================

    private static ApplicationInfo appInfo(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setSecureInt(Context ctx, String key, int value) {
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            Log.w(TAG, "setSecureInt " + key + " failed", t);
        }
    }

    private static int getSecureInt(Context ctx, String key, int def) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 读布尔（TYPE=1） */
    private static boolean readBoolean(Context ctx, String key) {
        try {
            Bundle b = new Bundle();
            b.putInt("TYPE", 1);
            b.putString("key", key);
            b.putBoolean("default", false);
            Bundle r = ctx.getContentResolver().call(REMOTE_PREF_URI, "callPreference", key, b);
            return r != null && r.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }
}
