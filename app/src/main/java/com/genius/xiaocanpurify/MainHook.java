package com.genius.xiaocanpurify;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MainHook extends XposedModule {
    public static final String TARGET_PACKAGE = "com.realtech.xiaocan";
    public static final String LOG_TAG = "XiaoCanPurify";
    private static final AtomicBoolean HOOKED = new AtomicBoolean(false);
    private static volatile MainHook instance;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        instance = this;
        log("XiaoCanPurify API 102 module loaded.");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        instance = this;
        log("Target package ready: " + param.getPackageName());

        ClassLoader initialLoader = param.getClassLoader();
        if (canLoadTargetClasses(initialLoader)) {
            installAll(this, initialLoader);
            return;
        }

        // App uses ShellApplication / Aliyun Jiagu unpacker, hook Application & Activity lifecycle
        hookLifecycle(this);
    }

    private static boolean canLoadTargetClasses(ClassLoader cl) {
        if (cl == null) return false;
        try {
            Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void hookLifecycle(XposedInterface xposed) {
        try {
            Method attachBaseContext = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
            xposed.hook(attachBaseContext).intercept(chain -> {
                Object result = chain.proceed();
                Context context = (Context) chain.getArg(0);
                if (context != null) {
                    ClassLoader cl = context.getClassLoader();
                    if (canLoadTargetClasses(cl)) {
                        installAll(xposed, cl);
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            log("Hook Application.attachBaseContext failed: " + t);
        }

        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            xposed.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Application app = (Application) chain.getThisObject();
                if (app != null) {
                    ClassLoader cl = app.getClassLoader();
                    if (canLoadTargetClasses(cl)) {
                        installAll(xposed, cl);
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            log("Hook Application.onCreate failed: " + t);
        }

        try {
            Method actOnCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            xposed.hook(actOnCreate).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                if (activity != null) {
                    ClassLoader cl = activity.getClassLoader();
                    if (canLoadTargetClasses(cl)) {
                        installAll(xposed, cl);
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log("Hook Activity.onCreate failed: " + t);
        }
    }

    public static synchronized void installAll(XposedInterface xposed, ClassLoader classLoader) {
        if (!HOOKED.compareAndSet(false, true)) {
            return;
        }
        log("Target classes available, installing purifier hooks with ClassLoader: " + classLoader);

        if (xposed instanceof XposedModule) {
            PurifyPrefs.init((XposedModule) xposed);
        }

        try {
            AdBlocker.install(xposed, classLoader);
            log("AdBlocker installed successfully.");
        } catch (Throwable t) {
            log("AdBlocker install error: " + t);
        }

        try {
            hookUserMenuDiag(xposed, classLoader);
        } catch (Throwable t) {
            log("UserMenuDiag install error: " + t);
        }

        try {
            hookCarouselDiag(classLoader);
        } catch (Throwable t) {
            log("CarouselDiag install error: " + t);
        }

        try {
            PopupBlocker.install(xposed, classLoader);
            log("PopupBlocker installed successfully.");
        } catch (Throwable t) {
            log("PopupBlocker install error: " + t);
        }

        try {
            BottomBarPurifier.install(xposed, classLoader);
            log("BottomBarPurifier installed successfully.");
        } catch (Throwable t) {
            log("BottomBarPurifier install error: " + t);
        }

        try {
            HomePagePurifier.install(xposed, classLoader);
            log("HomePagePurifier installed successfully.");
        } catch (Throwable t) {
            log("HomePagePurifier install error: " + t);
        }

        try {
            OrderPagePurifier.install(xposed, classLoader);
            log("OrderPagePurifier installed successfully.");
        } catch (Throwable t) {
            log("OrderPagePurifier install error: " + t);
        }

        try {
            DetailPagePurifier.install(xposed, classLoader);
            log("DetailPagePurifier installed successfully.");
        } catch (Throwable t) {
            log("DetailPagePurifier install error: " + t);
        }

        try {
            UserPagePurifier.install(xposed, classLoader);
            log("UserPagePurifier installed successfully.");
        } catch (Throwable t) {
            log("UserPagePurifier install error: " + t);
        }

        try {
            NetworkAdInterceptor.install(xposed, classLoader);
            log("NetworkAdInterceptor installed successfully.");
        } catch (Throwable t) {
            log("NetworkAdInterceptor install error: " + t);
        }


        try {
            FlutterPageGuard.install(xposed, classLoader);
            log("FlutterPageGuard installed successfully.");
        } catch (Throwable t) {
            log("FlutterPageGuard install error: " + t);
        }

        try {
            LocationCacheHook.install(xposed, classLoader);
            log("LocationCacheHook installed successfully.");
        } catch (Throwable t) {
            log("LocationCacheHook install error: " + t);
        }

        try {
            LotteryTaskAutomator.install(xposed, classLoader);
            log("LotteryTaskAutomator installed successfully.");
        } catch (Throwable t) {
            log("LotteryTaskAutomator install error: " + t);
        }

        try {
            OrderTabRedirect.install(xposed, classLoader);
            log("OrderTabRedirect installed successfully.");
        } catch (Throwable t) {
            log("OrderTabRedirect install error: " + t);
        }

        log("All XiaoCanPurify hooks successfully initialized!");
    }

    /**
     * 轮播组件运行时诊断：宿主 classes.dex 为加固壳（静态反编译不可行），
     * 在宿主进程内反射 dump Kuikly 轮播相关类的类存在性与方法签名，
     * 确定「翻到空页」问题的 hook 点。
     */
    private static void hookCarouselDiag(ClassLoader cl) {
        String[] candidates = {
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$1",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$2",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$3$1",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$4",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$4$1",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$5$1",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$5$3",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$6",
                "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt$AutoLoopCarousel$animateToNext$1",
                "com.realtech.user.pages.ext.UserChallengeViewExtKt",
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    sb.append(m.getName()).append('(').append(m.getParameterCount())
                            .append(")->").append(m.getReturnType().getSimpleName())
                            .append("; ");
                }
                StringBuilder fs = new StringBuilder();
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    fs.append(f.getType().getSimpleName()).append(' ')
                            .append(f.getName()).append("; ");
                }
                log("CarouselDiag " + name.substring(name.lastIndexOf('.') + 1)
                        + " methods: " + sb);
                if (fs.length() > 0) {
                    log("CarouselDiag " + name.substring(name.lastIndexOf('.') + 1)
                            + " fields: " + fs);
                }
            } catch (Throwable t) {
                log("CarouselDiag " + name + " -> " + t);
            }
        }
    }

    /**
     * 「我」页活动行（UserMenuModule）字段/方法表诊断 dump。
     * 注：不做数据层过滤（challengeData 剔除）——入口隐藏统一由
     * UserPagePurifier 的 UI 层负责：单入口嵌入蚕豆卡、隐藏态 GONE、
     * 多入口正常排版，Kuikly 渲染层按过滤后的视图状态绘制。
     */
    private static void hookUserMenuDiag(XposedInterface xposed, ClassLoader cl) {
        final Class<?> module;
        try {
            module = Class.forName("com.realtech.user.pages.userv2.UserMenuModule", false, cl);
        } catch (Throwable t) {
            log("UserMenuDiag: class not found: " + t);
            return;
        }
        StringBuilder fields = new StringBuilder();
        for (java.lang.reflect.Field f : module.getDeclaredFields()) {
            f.setAccessible(true);
            fields.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
        }
        log("UserMenuDiag fields: " + fields);
        StringBuilder methods = new StringBuilder();
        for (java.lang.reflect.Method m : module.getDeclaredMethods()) {
            methods.append(m.getName()).append('(').append(m.getParameterCount()).append("); ");
        }
        log("UserMenuDiag methods: " + methods);
    }

    public static void log(String message) {
        MainHook hook = instance;
        if (hook != null) {
            try {
                hook.log(Log.INFO, LOG_TAG, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.i(LOG_TAG, message);
    }
}
