package com.lsp.sidebar.scene;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块配置（存于安全中心进程私有目录，模块内部自用）。 */
public class Prefs {

    private static final String FILE = "lsp_sidebar_switch";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enableFloatingButton() {
        return sp.getBoolean("enable_floating", true);
    }

    public void setEnableFloatingButton(boolean v) {
        sp.edit().putBoolean("enable_floating", v).apply();
    }

    /** 切换时是否顺带打开“游戏工具箱”总开关（pref_open_game_booster），默认开 */
    public boolean autoEnableGameBooster() {
        return sp.getBoolean("auto_enable_game_booster", true);
    }

    public void setAutoEnableGameBooster(boolean v) {
        sp.edit().putBoolean("auto_enable_game_booster", v).apply();
    }

    public float getButtonX() {
        return sp.getFloat("btn_x", -1f);
    }

    public void setButtonX(float x) {
        sp.edit().putFloat("btn_x", x).apply();
    }

    public float getButtonY() {
        return sp.getFloat("btn_y", -1f);
    }

    public void setButtonY(float y) {
        sp.edit().putFloat("btn_y", y).apply();
    }
}
