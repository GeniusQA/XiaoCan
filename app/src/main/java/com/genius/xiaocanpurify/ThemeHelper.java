package com.genius.xiaocanpurify;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 模块设置页深浅色主题（参考 HyperIsland 的 themeMode 三态模式）：
 * system=跟随系统 / light=浅色 / dark=深色。
 * 设置页所有颜色统一从本类取值，切换主题后 recreate() 生效。
 */
public final class ThemeHelper {
    private ThemeHelper() {}

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    /** 当前主题模式（跟随系统/浅色/深色）。 */
    public static String themeMode(Context ctx) {
        try {
            return ctx.getSharedPreferences(PurifyPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("theme_mode", MODE_SYSTEM);
        } catch (Throwable t) {
            return MODE_SYSTEM;
        }
    }

    /** 是否深色：dark=true；light=false；system=跟随系统日夜模式。 */
    public static boolean isDark(Context ctx) {
        String mode = themeMode(ctx);
        if (MODE_LIGHT.equals(mode)) return false;
        if (MODE_DARK.equals(mode)) return true;
        try {
            int mask = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mask == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 页面背景。 */
    public static int pageBg(Context ctx) {
        return color(ctx, 0xFFF6F7F9, 0xFF121212);
    }

    /** 卡片背景。 */
    public static int cardBg(Context ctx) {
        return color(ctx, 0xFFFFFFFF, 0xFF1E1E1E);
    }

    /** 一级标题文字。 */
    public static int textTitle(Context ctx) {
        return color(ctx, 0xFF1A1A1A, 0xFFF5F5F5);
    }

    /** 分组标题文字。 */
    public static int textHeading(Context ctx) {
        return color(ctx, 0xFF222222, 0xFFEEEEEE);
    }

    /** 正文文字。 */
    public static int textBody(Context ctx) {
        return color(ctx, 0xFF333333, 0xFFDDDDDD);
    }

    /** 描述文字。 */
    public static int textDesc(Context ctx) {
        return color(ctx, 0xFF555555, 0xFFAAAAAA);
    }

    /** 次要文字。 */
    public static int textSecondary(Context ctx) {
        return color(ctx, 0xFF666666, 0xFF999999);
    }

    /** 时间/弱化文字。 */
    public static int textWeak(Context ctx) {
        return color(ctx, 0xFF888888, 0xFF777777);
    }

    /** 占位/提示文字。 */
    public static int textHint(Context ctx) {
        return color(ctx, 0xFF999999, 0xFF666666);
    }

    /** 卡片描边。 */
    public static int cardStroke(Context ctx) {
        return color(ctx, 0xFFF0F0F0, 0xFF2C2C2C);
    }

    /** 强调色（红）。 */
    public static int accentRed(Context ctx) {
        return color(ctx, 0xFFE64545, 0xFFE64545);
    }

    /** 强调色（橙）。 */
    public static int accentOrange(Context ctx) {
        return color(ctx, 0xFFE6A23C, 0xFFE6A23C);
    }

    /** 强调色（绿）。 */
    public static int accentGreen(Context ctx) {
        return color(ctx, 0xFF07C160, 0xFF07C160);
    }

    /** 强调色（橙红）。 */
    public static int accentOrangeRed(Context ctx) {
        return color(ctx, 0xFFFF6600, 0xFFFF6600);
    }

    private static int color(Context ctx, int light, int dark) {
        return isDark(ctx) ? dark : light;
    }
}
