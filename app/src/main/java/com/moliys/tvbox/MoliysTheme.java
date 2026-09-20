package com.moliys.tvbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.fongmi.android.tv.App;

/**
 * 沫离壳主题：站点开关为唯一开关，原生影视主页 / 直播 / 设置跟随。
 * 未保存过时跟随系统；保存后按保存值固定。
 */
public final class MoliysTheme {

    private static final String PREFS = "moliys_shell";
    private static final String KEY = "theme";
    private static final int SHELL_LIGHT = 0xFFF1F5FA;
    private static final int SHELL_DARK = 0xFF0F1115;

    private MoliysTheme() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String saved() {
        try {
            String value = prefs().getString(KEY, "");
            return "light".equals(value) || "dark".equals(value) ? value : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasSaved() {
        return !saved().isEmpty();
    }

    public static boolean isLight() {
        String value = saved();
        if ("light".equals(value)) return true;
        if ("dark".equals(value)) return false;
        return !systemNight();
    }

    private static boolean systemNight() {
        try {
            int uiMode = App.get().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return true;
        }
    }

    public static void set(boolean light) {
        try {
            prefs().edit().putString(KEY, light ? "light" : "dark").apply();
        } catch (Exception e) {
        }
        applyNightMode(light);
    }

    public static void apply() {
        if (hasSaved()) applyNightMode(isLight());
    }

    private static void applyNightMode(boolean light) {
        try {
            AppCompatDelegate.setDefaultNightMode(light ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
        } catch (Exception e) {
        }
    }

    public static int shellBackground() {
        return isLight() ? SHELL_LIGHT : SHELL_DARK;
    }

    public static String toJson() {
        return "{\"light\":" + isLight() + ",\"saved\":" + hasSaved() + ",\"mode\":\"" + (isLight() ? "light" : "dark") + "\"}";
    }
}
