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
            hookAutoLoopCarouselFix(xposed, classLoader);
        } catch (Throwable t) {
            log("AutoLoopCarouselFix install error: " + t);
        }

        try {
            hookChallengeIndexDiag(xposed, classLoader);
        } catch (Throwable t) {
            log("ChallengeIndexDiag install error: " + t);
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
     * 禁用 Kuikly AutoLoopCarousel 自动翻页。
     *
     * 根因（运行时诊断确认）：「我」页活动入口轮播由
     * {@code UserChallengeViewExtKt.initChallengeView} 用
     * {@code AutoLoopCarousel(items, autoPlay, interval, ...)} 构建，
     * 翻页由协程按 autoPlay/interval 循环调用
     * {@code AutoLoopCarousel$animateToNext} 驱动。被隐藏入口 GONE 后
     * 第 2 页无内容——Kuikly 绘制层翻到第 2 页即渲染空白（原生视图树
     * 仍停在第 1 页，故 hit-test 有效、点击可跳转，表现为「入口短暂
     * 显示后变空白但可点击」）。
     *
     * 修复：拦截 animateToNext 使其不执行——轮播恒停在第 1 页（含全部
     * 可见入口），空页永不出现。手动浏览场景此轮播为纯展示，无影响。
     */
    private static void hookAutoLoopCarouselFix(XposedInterface xposed, ClassLoader cl) {
        final Class<?> kt;
        try {
            kt = Class.forName(
                    "com.realtech.xiaocankuikly.widget.AutoLoopCarouselKt", false, cl);
        } catch (Throwable t) {
            log("AutoLoopCarouselFix: class not found: " + t);
            return;
        }
        int hooked = 0;
        for (java.lang.reflect.Method m : kt.getDeclaredMethods()) {
            String name = m.getName();
            // Kotlin 编译器的桥接方法 access$AutoLoopCarousel$animateToNext
            // 才是 lambda/协程实际调用的入口，必须一并拦截
            boolean isAdvance = "AutoLoopCarousel$animateToNext".equals(name)
                    || "access$AutoLoopCarousel$animateToNext".equals(name);
            if (!isAdvance) continue;
            try {
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    // 禁用自动翻页：空页（免单页被隐藏后）翻页即空白
                    return null;
                });
                hooked++;
            } catch (Throwable t) {
                log("AutoLoopCarouselFix: hook " + name + " failed: " + t);
            }
        }
        log("AutoLoopCarouselFix: animateToNext hooked x" + hooked
                + " (auto-advance disabled)");

        // 两页轮播（1/2 免单信息页、2/2 入口页）：免单页被隐藏后翻到该页
        // 即空白。本轮先只 dump AutoLoopCarousel(9) 的参数（items 列表
        // 内容、autoPlay/interval 等）——确认 items 是"页列表"还是"入口
        // 列表"后再精准过滤（入口列表中含「免单资格」入口，误删风险高）
        for (java.lang.reflect.Method m : kt.getDeclaredMethods()) {
            if (!"AutoLoopCarousel".equals(m.getName()) || m.getParameterCount() != 9) {
                continue;
            }
            try {
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    try {
                        Object[] args = chain.getArgs().toArray();
                        StringBuilder d = new StringBuilder();
                        for (int i = 0; i < args.length; i++) {
                            Object a = args[i];
                            if (a instanceof java.util.List) {
                                java.util.List list = (java.util.List) a;
                                StringBuilder sb = new StringBuilder();
                                int n = 0;
                                for (Object o : list) {
                                    if (n++ >= 6) { sb.append("..."); break; }
                                    String s = String.valueOf(o);
                                    sb.append('[').append(s.length() > 60
                                            ? s.substring(0, 60) : s).append(']');
                                }
                                d.append(" args[").append(i).append("]List(size=")
                                        .append(list.size()).append(")=").append(sb);
                            } else if (a != null) {
                                String s = String.valueOf(a);
                                d.append(" args[").append(i).append("]")
                                        .append(a.getClass().getSimpleName()).append('=')
                                        .append(s.length() > 30
                                                ? s.substring(0, 30) : s);
                            }
                        }
                        log("AutoLoopCarousel call:" + d);
                    } catch (Throwable t) {
                        log("AutoLoopCarousel dump error: " + t);
                    }
                    return chain.proceed();
                });
                log("AutoLoopCarouselFix: AutoLoopCarousel(9) hooked for dump");
            } catch (Throwable t) {
                log("AutoLoopCarouselFix: AutoLoopCarousel(9) hook failed: " + t);
            }
        }
    }

    /** renderChallengeIndex 上次参数快照（值变化才打日志）。 */
    private static volatile String lastChallengeIndexArgs;

    /**
     * 「我」页挑战/免单两页轮播的诊断：UserChallengeViewExtKt
     * .renderChallengeIndex 是"渲染指定挑战页"的入口（每 3 秒翻页时被
     * 调用）——dump 其参数类型与值，定位页索引驱动，确认是否可固定
     * 停留在含入口的页（入口区空白 ⟺ 轮播切到免单页）。
     */
    private static void hookChallengeIndexDiag(XposedInterface xposed, ClassLoader cl) {
        final Class<?> kt;
        try {
            kt = Class.forName(
                    "com.realtech.user.pages.ext.UserChallengeViewExtKt", false, cl);
        } catch (Throwable t) {
            log("ChallengeIndexDiag: class not found: " + t);
            return;
        }
        for (java.lang.reflect.Method m : kt.getDeclaredMethods()) {
            final String name = m.getName();
            if (!"renderChallengeIndex".equals(name)
                    && !"access$renderChallengeIndex".equals(name)) {
                continue;
            }
            try {
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    try {
                        StringBuilder sb = new StringBuilder();
                        for (Object a : chain.getArgs()) {
                            String s = a == null ? "null"
                                    : a.getClass().getSimpleName() + "="
                                            + String.valueOf(a);
                            if (s.length() > 60) s = s.substring(0, 60);
                            sb.append('[').append(s).append(']');
                        }
                        String snap = name + sb;
                        if (!snap.equals(lastChallengeIndexArgs)) {
                            lastChallengeIndexArgs = snap;
                            log("ChallengeIndex: " + snap);
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });
                log("ChallengeIndexDiag: " + name + " hooked");
            } catch (Throwable t) {
                log("ChallengeIndexDiag: hook " + name + " failed: " + t);
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
