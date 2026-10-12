package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 底部导航「订单」Tab 重定向。
 *
 * 原生「订单」Tab 只是 MainActivity 里 ViewPager 的一个内嵌页（信息较少），
 * 而「我的 → 全部订单」打开的是独立的 {@code MyOrderActivity}（信息更全）。
 * 这里在点击订单 Tab 时改为启动 MyOrderActivity，复用「全部订单」页。
 *
 * 为保证打开的是「全部订单」这一页（而非默认子 Tab），探针会记录用户从
 * 「我的 → 全部订单」自然跳转时的 Intent extras，作为后续重定向的模板；
 * 首次重定向（尚未记录过模板）时直接以 NEW_TASK 启动，通常默认即「全部订单」。
 */
public final class OrderTabRedirect {
    private static final String TARGET_PKG = "com.realtech.xiaocan";
    private static final String TARGET_ACTIVITY = "com.realtech.my_order.ui.MyOrderActivity";

    /** 订单 Tab 在原始 Tab 列表 [HOME, TASK, ORDER, VIP, USER] 中的索引。 */
    static final int ORDER_TAB_INDEX = 2;

    /** 最近一次「我的 → 全部订单」自然跳转的 Intent extras 模板。 */
    private static volatile Bundle lastExtras;

    private OrderTabRedirect() {}

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookExtrasProbe(xposed);
        MainHook.log("OrderTabRedirect installed");
    }

    /** 记录 MyOrderActivity 启动的 Intent extras，作为重定向模板。 */
    private static void hookExtrasProbe(XposedInterface xposed) {
        try {
            Class<?> instr = Class.forName("android.app.Instrumentation", false,
                    OrderTabRedirect.class.getClassLoader());
            for (Method m : instr.getDeclaredMethods()) {
                if (!"execStartActivity".equals(m.getName())) continue;
                xposed.hook(m).intercept(chain -> {
                    try {
                        Intent intent = null;
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof Intent) {
                                intent = (Intent) arg;
                                break;
                            }
                        }
                        if (intent != null) {
                            ComponentName cn = intent.getComponent();
                            String cls = cn != null ? cn.getClassName() : "";
                            if (TARGET_ACTIVITY.equals(cls)) {
                                Bundle extras = intent.getExtras();
                                lastExtras = extras != null ? new Bundle(extras) : null;
                                MainHook.log("OrderTabRedirect probed MyOrderActivity extras: "
                                        + (extras == null ? "null" : extras.toString()));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                MainHook.log("OrderTabRedirect: hooked Instrumentation.execStartActivity");
                return;
            }
        } catch (Throwable t) {
            MainHook.log("OrderTabRedirect probe install failed: " + t);
        }
    }

    /**
     * 在订单 Tab 被选中时拦截，启动 MyOrderActivity 并返回 true（表示已处理）。
     * 非订单 Tab 或功能未开启时返回 false，交由调用方走原生逻辑。
     */
    public static boolean tryRedirect(Activity mainActivity, Object navView, int navPos, int tabIndex) {
        if (!PurifyPrefs.enabled(PurifyPrefs.ORDER_TAB_REDIRECT)) return false;
        if (tabIndex != ORDER_TAB_INDEX) return false;
        if (mainActivity == null) return false;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (lastExtras != null) intent.putExtras(lastExtras);
            mainActivity.startActivity(intent);
            MainHook.log("OrderTabRedirect: launched MyOrderActivity (extras="
                    + (lastExtras == null ? "none" : "templated") + ")");
            // 让订单 Tab 维持高亮，实际页面在独立 Activity
            if (navView != null) {
                try {
                    Method updateTabState = navView.getClass().getMethod("updateTabState", int.class);
                    updateTabState.invoke(navView, navPos);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            MainHook.log("OrderTabRedirect launch failed: " + t);
            return false;
        }
    }
}
