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

    /**
     * 广告 SDK 素材缓存目录（相对应用数据根）：持续膨胀的大头。
     * 实测分布：files/videoCache 201MB（视频广告）、files/pangle_p 83MB
     * （穿山甲素材）、files/ksadsdk 17MB、files/cybermedia 12MB 等。
     * 只清素材缓存，不清插件/so 目录（app_sodler、app_tt_pangle_bykv_file、
     * app_baidu_sdk_remote、ksad_dynamic 等）——清了会被重新下载约 150MB。
     */
    private static final String[] AD_CACHE_DIRS = {
            "files/videoCache",
            "files/pangle_p",
            "files/ksadsdk",
            "files/cybermedia",
            "files/com.yt.ad.file.cache",
            "files/syad",
            "files/imgCache",
    };

    private static void asyncClear(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            // 分目录统计释放量，模块日志页可直接核对每次清理明细
            StringBuilder detail = new StringBuilder();
            long freed = 0;
            freed += clearDirDetail(app, app.getCacheDir(), "cache", detail);
            freed += clearDirDetail(app, app.getCodeCacheDir(), "code_cache", detail);
            try {
                File ext = app.getExternalCacheDir();
                if (ext != null) freed += clearDirDetail(app, ext, "external_cache", detail);
            } catch (Throwable ignored) {}
            // 广告 SDK 素材缓存（files/ 下各广告组件的下载缓存）
            File base = app.getFilesDir().getParentFile();
            if (base != null) {
                for (String rel : AD_CACHE_DIRS) {
                    File dir = new File(base, rel);
                    if (dir.exists()) {
                        freed += clearDirDetail(app, dir, rel.replace("files/", ""), detail);
                    }
                }
            }
            try {
                app.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_DIRTY, false).apply();
            } catch (Throwable ignored) {}
            MainHook.log("CacheCleaner: cache cleared, freed "
                    + android.text.format.Formatter.formatFileSize(app, freed)
                    + " (" + detail + ")");
        }, "XiaoCanAssistant-CacheCleaner").start();
    }

    /** 递归删除目录内容（不删目录本身），返回释放的字节数并追加明细。 */
    private static long clearDirDetail(Context app, File dir, String label,
                                       StringBuilder detail) {
        long freed = clearDir(dir);
        if (detail.length() > 0) detail.append(", ");
        detail.append(label).append("=")
                .append(android.text.format.Formatter.formatFileSize(app, freed));
        return freed;
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
