package com.genius.xiaocanpurify;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 定时清理缓存：参考宿主设置页「清理缓存 → 定时清理 → 退出时清缓存」。
 *
 * 退出判定与清理时机：
 *  - 通过 ActivityLifecycleCallbacks 记存活 Activity 数；
 *    全部销毁（含用户从最近任务划掉、返回键退出）即视为退出，立即清缓存；
 *  - 进程被系统直接杀死时无法执行代码，因此每次 Activity 创建时在宿主
 *    私有 prefs 打上 dirty 标记，干净退出时清除；下次启动若发现 dirty
 *    仍为 true（上次异常死亡），启动时补清一次；
 *  - 缓存目录：cacheDir / codeCacheDir / externalCacheDir 的内容
 *    （只删内容不删目录本身），后台线程执行避免卡顿。
 * 开关走 PurifyPrefs 远程偏好（cache_cleanup_mode：off/exit），
 * 事件时刻读取最新值，模块设置页改完重启小蚕即生效。
 */
public final class CacheCleanerHook {
    private static final String HOST_PREFS = "purify_cache";
    private static final String KEY_DIRTY = "cache_dirty_session";

    private static final AtomicBoolean registered = new AtomicBoolean(false);
    private static final AtomicInteger LIVE_ACTIVITIES = new AtomicInteger(0);

    private CacheCleanerHook() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        // Application 未就绪时延迟到首个 Activity 创建再注册
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", android.os.Bundle.class);
            xposed.hook(onCreate).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Activity a = (Activity) chain.getThisObject();
                    if (a != null) register(a.getApplication());
                } catch (Throwable ignored) {}
                return r;
            });
        } catch (Throwable t) {
            MainHook.log("CacheCleaner: hook Activity.onCreate failed: " + t);
        }
        try {
            register((Application) currentApplication());
        } catch (Throwable ignored) {
            // currentApplication 返回的不是 Application 实例时静默跳过，等首个 Activity 兜底
        }
    }

    private static void register(Application app) {
        if (app == null || !registered.compareAndSet(false, true)) return;
        try {
            // 上次会话异常死亡（未干净退出）：启动时补清缓存
            if (enabled() && app.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DIRTY, false)) {
                MainHook.log("CacheCleaner: previous session ended abnormally, clearing cache");
                asyncClear(app);
            }
        } catch (Throwable ignored) {}

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle icicle) {
                LIVE_ACTIVITIES.incrementAndGet();
                if (enabled()) {
                    try {
                        a.getApplication()
                                .getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_DIRTY, true).apply();
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onActivityDestroyed(Activity a) {
                int live = LIVE_ACTIVITIES.decrementAndGet();
                // 全部销毁 = 退出（旋转屏幕等配置变更时新 Activity 先创建，不会归零）
                if (live <= 0 && enabled()) {
                    Application app1 = a.getApplication();
                    if (app1 != null) {
                        asyncClear(app1);
                    }
                }
            }

            // 其余回调无操作
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle outState) {}
        });
        MainHook.log("CacheCleaner: lifecycle callbacks registered (mode=" + mode() + ")");
    }

    private static String mode() {
        return PurifyPrefs.getString(PurifyPrefs.CACHE_CLEANUP_MODE, "off");
    }

    private static boolean enabled() {
        return "exit".equals(mode());
    }

    private static void asyncClear(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            long freed = clearDir(app.getCacheDir());
            freed += clearDir(app.getCodeCacheDir());
            try {
                File ext = app.getExternalCacheDir();
                if (ext != null) freed += clearDir(ext);
            } catch (Throwable ignored) {}
            try {
                app.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_DIRTY, false).apply();
            } catch (Throwable ignored) {}
            MainHook.log("CacheCleaner: cache cleared, freed "
                    + android.text.format.Formatter.formatFileSize(app, freed));
        }, "XiaoCanPurify-CacheCleaner").start();
    }

    /** 递归删除目录内容（不删目录本身），返回释放的字节数。 */
    private static long clearDir(File dir) {
        long freed = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File f : children) {
            if (f.isDirectory()) {
                freed += clearDir(f);
                f.delete();
            } else {
                long len = f.length();
                if (f.delete()) freed += len;
            }
        }
        return freed;
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
