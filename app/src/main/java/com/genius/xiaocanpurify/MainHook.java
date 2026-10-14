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
     * 「我」页活动行（UserMenuModule）数据诊断：dump 字段/方法表 +
     * 跟踪 challengeList（入口列表数据）的赋值与内容，定位数据源后
     * 在数据层按开关剔除隐藏入口。
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
        java.lang.reflect.Field listField = null;
        for (java.lang.reflect.Field f : module.getDeclaredFields()) {
            f.setAccessible(true);
            fields.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
            if (f.getName().toLowerCase().contains("challengelist")
                    || f.getName().toLowerCase().contains("menulist")
                    || f.getName().toLowerCase().contains("entrylist")) {
                listField = f;
            }
        }
        log("UserMenuDiag fields: " + fields);
        StringBuilder methods = new StringBuilder();
        java.util.List<java.lang.reflect.Method> hooked = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : module.getDeclaredMethods()) {
            methods.append(m.getName()).append('(').append(m.getParameterCount()).append("); ");
            hooked.add(m);
        }
        log("UserMenuDiag methods: " + methods);
        final java.lang.reflect.Field fList = listField;
        // 数据层按开关剔除隐藏入口：render/renderChallenge 执行前过滤
        // challengeData 列表，Kuikly 只渲染显示态入口（渲染与数据一致）
        final java.lang.reflect.Method renderM;
        final java.lang.reflect.Method renderChallengeM;
        java.lang.reflect.Method rm = null, rcm = null;
        for (java.lang.reflect.Method m : hooked) {
            if ("render".equals(m.getName()) && m.getParameterCount() == 1) rm = m;
            if ("renderChallenge".equals(m.getName()) && m.getParameterCount() == 1) rcm = m;
        }
        renderM = rm;
        renderChallengeM = rcm;
        final java.lang.reflect.Field fData = findFieldRecursive(module, "challengeData");
        if (renderM != null && fData != null) {
            try {
                xposed.hook(renderM).intercept(chain -> {
                    try {
                        filterChallengeData(fData, chain.getThisObject());
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });
                log("UserMenuDiag: render hooked for data filtering");
            } catch (Throwable t) {
                log("UserMenuDiag: render hook failed: " + t);
            }
        }
        if (renderChallengeM != null) {
            try {
                xposed.hook(renderChallengeM).intercept(chain -> {
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof java.util.List) {
                            filterEntryList((java.util.List) arg);
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });
                log("UserMenuDiag: renderChallenge hooked for data filtering");
            } catch (Throwable t) {
                log("UserMenuDiag: renderChallenge hook failed: " + t);
            }
        }
        for (final java.lang.reflect.Method m : hooked) {
            try {
                xposed.hook(m).intercept(chain -> {
                    try {
                        Object result = chain.proceed();
                        if (fList != null) {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - lastMenuListDumpMs > 5000L) {
                                lastMenuListDumpMs = now;
                                Object self = chain.getThisObject();
                                Object v = fList.get(self);
                                if (v != null) {
                                    String s = String.valueOf(v);
                                    log("UserMenuDiag " + m.getName() + " -> "
                                            + fList.getName() + "="
                                            + s.substring(0, Math.min(s.length(), 600)));
                                }
                            }
                        }
                        return result;
                    } catch (Throwable ignored) {
                        return null;
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static volatile long lastMenuListDumpMs;

    /** 在类及其父类中按名查找字段。 */
    private static java.lang.reflect.Field findFieldRecursive(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 按开关过滤 challengeData（实例字段）：元素文本命中被隐藏入口关键词
     * 的条目从列表剔除。列表元素可能是任意 Bean，用 toString 匹配。
     */
    private static void filterChallengeData(java.lang.reflect.Field f, Object self) {
        try {
            Object v = f.get(self);
            if (v instanceof java.util.List) {
                filterEntryList((java.util.List) v);
            }
        } catch (Throwable ignored) {}
    }

    /** 隐藏开关开启且元素文本命中该入口名时剔除。 */
    private static boolean entryShouldHide(String s) {
        if (s == null) return false;
        try {
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_CHALLENGE)
                    && s.contains("挑战赛")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_LOTTERY)
                    && s.contains("免费抽奖")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_SECRET)
                    && s.contains("对接暗号")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_RAIN)
                    && s.contains("红包雨")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_ELIMINATE)
                    && s.contains("有奖消消乐")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_CHECKIN)
                    && (s.contains("签到") || s.contains("签 到"))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 就地过滤入口列表：剔除命中隐藏开关的条目。 */
    @SuppressWarnings("unchecked")
    private static void filterEntryList(java.util.List list) {
        if (list == null || list.isEmpty()) return;
        java.util.Iterator it = list.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            Object e = it.next();
            if (entryShouldHide(String.valueOf(e))) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            log("UserMenuDiag: challengeData filtered, left=" + list.size());
        }
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
