package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 宿主深浅色适配：小蚕宿主没有 values-night 深色资源、未适配深色主题，
 * 仅调 UiModeManager.setApplicationNightMode 只会切换 uiMode 配置，
 * 界面不会有任何变化。本类在此基础上：
 *  1. 独立注册主题切换广播（原先注册在 LocationCacheHook 内，
 *     用户关闭「定位缓存」开关时整个主题功能失效；且 targetSdk 34+
 *     必须指定 RECEIVER_EXPORTED 才能收到跨应用广播）；
 *  2. 为宿主所有 Activity 开启 Android 10+ Force Dark（强制深色）
 *     算法，浅色界面自动反转为深色。Force Dark 仅在 uiMode=NIGHT_YES
 *     时生效，浅色模式下开启无副作用，无需在切回浅色时撤销。
 */
public final class HostThemeHook {
    /** 与 LocationCacheHook 共用宿主私有 prefs（保持 host_theme_mode 兼容）。 */
    private static final String PREFS_NAME = "purify_loc_cache";
    private static final String KEY_MODE = "host_theme_mode";

    private static volatile boolean receiverRegistered;
    private static volatile boolean savedModeApplied;
    /** 最近恢复到前台的 Activity：切深色时立即对其生效，无需等重建。 */
    private static volatile WeakReference<Activity> lastResumed;

    private HostThemeHook() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        registerThemeReceiver(null);

        // 宿主重启后应用持久化的主题模式
        applySavedModeOnce();

        hookActivityLifecycle(xposed);
    }

    /** 每个 Activity 创建/恢复时开启 Force Dark（幂等、开销极小）。 */
    private static void hookActivityLifecycle(XposedInterface xposed) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", android.os.Bundle.class);
            xposed.hook(onCreate).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Activity a = (Activity) chain.getThisObject();
                    if (a != null) {
                        registerThemeReceiver(a.getApplication());
                        applySavedModeOnce();
                        enableForceDark(a);
                        // 兜底：install 时 Application 未就绪的话，这里补注册日志拉取接收器
                        HostLogBridge.install();
                    }
                } catch (Throwable ignored) {}
                return r;
            });
            MainHook.log("HostTheme: Activity.onCreate hook installed");
        } catch (Throwable t) {
            MainHook.log("HostTheme: hook Activity.onCreate failed: " + t);
        }
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            xposed.hook(onResume).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Activity a = (Activity) chain.getThisObject();
                    if (a != null) {
                        lastResumed = new WeakReference<>(a);
                        enableForceDark(a);
                    }
                } catch (Throwable ignored) {}
                return r;
            });
            MainHook.log("HostTheme: Activity.onResume hook installed");
        } catch (Throwable t) {
            MainHook.log("HostTheme: hook Activity.onResume failed: " + t);
        }
    }

    /** 宿主重启后应用持久化的主题模式（Application 未就绪时延迟到首个 Activity）。 */
    private static void applySavedModeOnce() {
        if (savedModeApplied) return;
        synchronized (HostThemeHook.class) {
            if (savedModeApplied) return;
            try {
                SharedPreferences p = getPrefs();
                if (p != null) {
                    String saved = p.getString(KEY_MODE, null);
                    if (saved != null) {
                        applyHostNightMode(saved);
                    }
                    savedModeApplied = true;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void registerThemeReceiver(Context app) {
        if (app == null || receiverRegistered) return;
        synchronized (HostThemeHook.class) {
            if (receiverRegistered) return;
            try {
                android.content.BroadcastReceiver themeReceiver =
                        new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context c,
                                                  android.content.Intent i) {
                                try {
                                    applyHostNightMode(i.getStringExtra("mode"));
                                } catch (Throwable ignored) {}
                            }
                        };
                android.content.IntentFilter tf = new android.content.IntentFilter(
                        "com.genius.xiaocanpurify.ACTION_THEME_CHANGE");
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // targetSdk 34+ 必须显式指定 exported 标志；模块与宿主不同 uid，需 exported
                    app.registerReceiver(themeReceiver, tf, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(themeReceiver, tf);
                }
                receiverRegistered = true;
                MainHook.log("HostTheme: theme receiver registered");
            } catch (Throwable t) {
                MainHook.log("HostTheme: register theme receiver failed: " + t);
            }
        }
    }

    /**
     * 宿主深浅色应用：mode=light/dark/system。
     * setApplicationNightMode 切 uiMode 配置（重建 Activity），
     * Force Dark 由 Activity 生命周期 hook 补齐，两者配合界面才真正变深。
     */
    private static void applyHostNightMode(String mode) {
        if (mode == null) return;
        try {
            Context ctx = currentApplication();
            if (ctx == null) return;
            Object um = ctx.getSystemService(android.app.UiModeManager.class);
            if (um == null) return;
            int uiMode;
            if ("light".equals(mode)) {
                uiMode = android.app.UiModeManager.MODE_NIGHT_NO;
            } else if ("dark".equals(mode)) {
                uiMode = android.app.UiModeManager.MODE_NIGHT_YES;
            } else {
                uiMode = android.app.UiModeManager.MODE_NIGHT_AUTO;
            }
            java.lang.reflect.Method m = um.getClass().getMethod(
                    "setApplicationNightMode", int.class);
            m.invoke(um, uiMode);
            getPrefs().edit().putString(KEY_MODE, mode).apply();
            // 宿主声明了 configChanges="uiMode" 时 Activity 不会重建，
            // 立即对当前前台 Activity 开启 Force Dark 兜底
            if ("dark".equals(mode)) {
                WeakReference<Activity> ref = lastResumed;
                Activity a = ref != null ? ref.get() : null;
                if (a != null) enableForceDark(a);
            }
            MainHook.log("HostTheme: host night mode -> " + mode);
        } catch (Throwable t) {
            MainHook.log("HostTheme: applyHostNightMode failed: " + t);
        }
    }

    private static void enableForceDark(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            View decor = a.getWindow().getDecorView();
            if (!decor.isForceDarkAllowed()) {
                decor.setForceDarkAllowed(true);
                MainHook.log("HostTheme: force dark enabled on " + a.getClass().getName());
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- Prefs helpers

    private static SharedPreferences getPrefs() {
        try {
            Context ctx = currentApplication();
            if (ctx != null) {
                return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
