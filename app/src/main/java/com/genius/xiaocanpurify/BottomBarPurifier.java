package com.genius.xiaocanpurify;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

public final class BottomBarPurifier {
    private BottomBarPurifier() {}

    /**
     * 宿主原始 Tab 列表（[HOME, TASK, ORDER, VIP, USER]），
     * 由 hookTabActionList 捕获，仅用于建立「导航位置 -> 页面索引」映射。
     */
    private static volatile List<?> originalTabs;

    /**
     * 全部 hook 都装上，开关值在**调用时**实时读取。
     * 这样设置改动不再依赖进程启动时的一次性读取，
     * 避免因 RemotePreferences 未同步而长期停留在默认「隐藏」状态。
     *
     * 架构说明：ViewPager 始终保留宿主全部页面（含被隐藏的福利/会员页），
     * 只移除底部导航项。这样「我的-邀新赚钱」等跳转福利页的入口仍然可用；
     * 导航点击通过 hookOnItemSelectedListener 做位置映射。
     */
    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookTabActionList(xposed, classLoader);
        hookMainActivitySetupPages(xposed, classLoader);
        hookApplyBottomTabTheme(xposed, classLoader);
        hookOnItemSelectedListener(xposed, classLoader);
        hookSecondTabPlacement(xposed, classLoader);
        MainHook.log("BottomBar toggles (live): vip=" + PurifyPrefs.hide(PurifyPrefs.TAB_VIP)
                + " benefit=" + PurifyPrefs.hide(PurifyPrefs.TAB_BENEFIT)
                + " placement=" + PurifyPrefs.hide(PurifyPrefs.TAB_PLACEMENT));
    }

    private static boolean hideVip() {
        return PurifyPrefs.hide(PurifyPrefs.TAB_VIP);
    }

    private static boolean hideBenefit() {
        return PurifyPrefs.hide(PurifyPrefs.TAB_BENEFIT);
    }

    private static boolean hidePlacement() {
        return PurifyPrefs.hide(PurifyPrefs.TAB_PLACEMENT);
    }

    /**
     * 捕获宿主原始 Tab 列表（不过滤，页面保持完整）。
     * 隐藏哪个 Tab 由导航项移除 + 导航位置映射完成。
     */
    private static void hookTabActionList(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> mainActivityClass = Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            for (Method m : mainActivityClass.getDeclaredMethods()) {
                if ("getTabActionList".equals(m.getName()) || "tabActionList_delegate$lambda$0".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object original = chain.proceed();
                        if (original instanceof List) {
                            originalTabs = (List<?>) original;
                        }
                        return original;
                    });
                    MainHook.log("Hooked " + m.getName() + " to capture original tab list");
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook tabActionList: " + t);
        }
    }

    private static boolean isHiddenAction(Object action) {
        if (action == null) return false;
        String name = action.getClass().getName();
        if (hideVip() && name.endsWith("$VIP")) return true;
        // 「福利/赚钱」Tab 对应 TASK
        if (hideBenefit() && name.endsWith("$TASK")) return true;
        return false;
    }

    /**
     * Prevent dynamic replacement of Tab 1 (Order) with marketing placements such as "抖音补贴".
     */
    private static void hookSecondTabPlacement(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> mainActivityClass = Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            for (Method m : mainActivityClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("getSecondTabPlacementContent".equals(mName) || "getSecondTabPlacementMatchValue".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        if (!hidePlacement()) {
                            return chain.proceed();
                        }
                        MainHook.log("Intercepted " + mName + ", returning null to keep pure Order tab");
                        return null;
                    });
                } else if ("isSecondTabPlacementAction".equals(mName)) {
                    xposed.hook(m).intercept(chain -> hidePlacement() ? false : chain.proceed());
                }
            }
            MainHook.log("Hooked second tab placement overrides");
        } catch (Throwable t) {
            MainHook.log("Failed to hook second tab placement: " + t);
        }
    }

    /**
     * Hook MainActivity.setUpPages to remove VIP and Benefit tabs AFTER ViewBinding has completed.
     */
    private static void hookMainActivitySetupPages(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> mainActivityClass = Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            for (Method m : mainActivityClass.getDeclaredMethods()) {
                if ("setUpPages".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        purgeExtraTabs(activity);
                        return result;
                    });
                    MainHook.log("Hooked MainActivity.setUpPages");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook MainActivity.setUpPages: " + t);
        }
    }

    private static void hookApplyBottomTabTheme(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> mainActivityClass = Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            for (Method m : mainActivityClass.getDeclaredMethods()) {
                if ("applyBottomTabTheme".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        purgeExtraTabs(activity);
                        return result;
                    });
                    MainHook.log("Hooked MainActivity.applyBottomTabTheme");
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void purgeExtraTabs(Activity activity) {
        try {
            Method getBinding = activity.getClass().getMethod("getBinding");
            Object binding = getBinding.invoke(activity);
            if (binding == null) return;

            boolean hideVip = hideVip();
            boolean hideBenefit = hideBenefit();

            // Hide VIP decorations / badges
            if (hideVip) {
                View v = getBindingView(binding, "lyActiveVip");
                if (v != null) v.setVisibility(View.GONE);
                View expire = getBindingView(binding, "tvVipExpireTag");
                if (expire != null) expire.setVisibility(View.GONE);
            }

            // Hide floating red packet icon on main activity
            if (PurifyPrefs.hide(PurifyPrefs.FLOATING_WINDOW)) {
                View v = getBindingView(binding, "ivFloatingWindow");
                if (v != null) v.setVisibility(View.GONE);
            }

            if (!hideVip && !hideBenefit) {
                // 两个开关都关闭：保留宿主原生导航项，不做任何移除
                MainHook.log("BottomBar toggles off: keep native nav items");
                return;
            }

            View navViewRaw = getBindingView(binding, "navView");
            if (!(navViewRaw instanceof ViewGroup)) return;
            ViewGroup navView = (ViewGroup) navViewRaw;

            // 先让 navView 完成自身初始化，再移除导航项，
            // 避免 init() 重建子项时把已移除的项带回来
            try {
                Method initMethod = navView.getClass().getMethod("init");
                initMethod.invoke(navView);
            } catch (Throwable ignored) {}

            View nbiVip = getBindingView(binding, "nbiVip");
            View nbiBenefit = getBindingView(binding, "nbiBenefit");

            boolean removed = false;
            if (hideVip && nbiVip != null && nbiVip.getParent() == navView) {
                nbiVip.setVisibility(View.GONE);
                navView.removeView(nbiVip);
                removed = true;
            }
            if (hideBenefit && nbiBenefit != null && nbiBenefit.getParent() == navView) {
                nbiBenefit.setVisibility(View.GONE);
                navView.removeView(nbiBenefit);
                removed = true;
            }
            if (hideVip && hideBenefit) {
                View nbiTd = getBindingView(binding, "nbiTd");
                if (nbiTd != null) {
                    try {
                        Method setItemKey = nbiTd.getClass().getMethod("setItemKey", String.class);
                        setItemKey.invoke(nbiTd, "order");
                    } catch (Throwable ignored) {}
                }
            }
            if (removed) {
                MainHook.log("navView purged (pages kept intact)");
            }
        } catch (Throwable t) {
            MainHook.log("Failed to purge extra tabs: " + t);
        }
    }

    private static View getBindingView(Object binding, String fieldName) {
        try {
            Field field = binding.getClass().getField(fieldName);
            Object value = field.get(binding);
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Hook MainActivity$setUpPages$2.onItemSelected（导航项点击回调，arg2 = 导航子项位置）。
     * 页面列表保持宿主原始 5 项，导航项被移除后「子项位置 != 页面索引」，
     * 这里把导航位置映射回原始页面索引后再切换页面。
     */
    private static void hookOnItemSelectedListener(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> listenerClass = Class.forName("com.realtech.xiaocan.MainActivity$setUpPages$2", false, cl);
            for (Method m : listenerClass.getDeclaredMethods()) {
                if ("onItemSelected".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        if (!hideVip() && !hideBenefit()) {
                            // 无隐藏：导航位置 == 页面索引，走宿主原生逻辑
                            return chain.proceed();
                        }
                        int navPos = (int) chain.getArg(2);

                        Field this$0Field = listenerClass.getDeclaredField("this$0");
                        this$0Field.setAccessible(true);
                        Activity mainActivity = (Activity) this$0Field.get(chain.getThisObject());
                        if (mainActivity == null) {
                            return chain.proceed();
                        }
                        Object[] ctx = resolveTabViews(mainActivity);
                        if (ctx == null) {
                            return chain.proceed();
                        }
                        Object viewPager2 = ctx[0];
                        Object navView = ctx[1];

                        int tabIndex = pageForNavPos(navPos);
                        if (tabIndex < 0) {
                            return chain.proceed();
                        }
                        // 关键：ViewPager 适配器页数可能少于 tab 列表（如 VIP 页因账号身份缺失），
                        // 按适配器真实页数收敛，避免 IndexOutOfBoundsException
                        int page = toPagerIndex(viewPager2, tabIndex);

                        if (isUserPage(tabIndex)) {
                            switchToUserTab(mainActivity, page, viewPager2);
                        } else {
                            try {
                                Method setCurrentItem = viewPager2.getClass()
                                        .getMethod("setCurrentItem", int.class, boolean.class);
                                setCurrentItem.invoke(viewPager2, page, false);
                            } catch (Throwable t) {
                                MainHook.log("Failed to switch page: " + t);
                                return chain.proceed();
                            }
                        }
                        try {
                            Method updateTabState = navView.getClass().getMethod("updateTabState", int.class);
                            updateTabState.invoke(navView, navPos);
                        } catch (Throwable ignored) {}
                        MainHook.log("Nav position " + navPos + " -> tab " + tabIndex + " -> page " + page);
                        // 吞掉原生逻辑：其按「导航位置 == 页面索引」处理，会切到错误页面
                        return false;
                    });
                    MainHook.log("Hooked MainActivity$setUpPages$2.onItemSelected");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook MainActivity$setUpPages$2.onItemSelected: " + t);
        }
    }

    /** 导航子项位置 -> 原始页面索引；无法计算时返回 -1。 */
    private static int pageForNavPos(int navPos) {
        List<?> tabs = originalTabs;
        if (tabs == null || navPos < 0) {
            return -1;
        }
        int kept = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Object action = tabs.get(i);
            if (action == null || isHiddenAction(action)) {
                continue;
            }
            if (kept == navPos) {
                return i;
            }
            kept++;
        }
        // 超出导航子项数量：可能传入的是页面索引（同步回调），原样返回
        return navPos < tabs.size() ? navPos : -1;
    }

    /**
     * tab 列表索引 -> ViewPager 适配器真实页索引。
     * 适配器页数可能少于 tab 列表（如 VIP 页因账号身份不存在），
     * USER 恒为最后一个 tab，越界时夹到最后一页。
     */
    private static int toPagerIndex(Object viewPager2, int tabIndex) {
        int count = pagerItemCount(viewPager2);
        if (count > 0 && tabIndex >= count) {
            MainHook.log("toPagerIndex: tab " + tabIndex + " clamped to " + (count - 1)
                    + " (pager count=" + count + ")");
            return count - 1;
        }
        return tabIndex;
    }

    /** 读取 ViewPager2 适配器页数；取不到返回 -1（此时不做收敛）。 */
    private static int pagerItemCount(Object viewPager2) {
        try {
            Method getAdapter = viewPager2.getClass().getMethod("getAdapter");
            Object adapter = getAdapter.invoke(viewPager2);
            if (adapter == null) {
                MainHook.log("pagerItemCount: adapter is null");
                return -1;
            }
            // 宿主适配器类可能不可见，沿父类链找 getItemCount 并放开访问
            Method getItemCount = null;
            for (Class<?> c = adapter.getClass(); c != null && getItemCount == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if ("getItemCount".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        getItemCount = m;
                        break;
                    }
                }
            }
            if (getItemCount == null) {
                MainHook.log("pagerItemCount: getItemCount not found on " + adapter.getClass().getName());
                return -1;
            }
            getItemCount.setAccessible(true);
            Object n = getItemCount.invoke(adapter);
            return n instanceof Integer ? (Integer) n : -1;
        } catch (Throwable t) {
            MainHook.log("pagerItemCount failed: " + t);
            return -1;
        }
    }

    private static boolean isUserPage(int pageIndex) {
        List<?> tabs = originalTabs;
        if (tabs == null || pageIndex < 0 || pageIndex >= tabs.size()) {
            return false;
        }
        return tabs.get(pageIndex).getClass().getName().endsWith("$USER");
    }

    /** 返回 [viewPager2, navView]，取不到时返回 null。 */
    private static Object[] resolveTabViews(Activity mainActivity) {
        try {
            Method getBinding = mainActivity.getClass().getMethod("getBinding");
            Object binding = getBinding.invoke(mainActivity);
            if (binding == null) {
                return null;
            }
            Object viewPager2 = binding.getClass().getField("viewPager2").get(binding);
            Object navView = binding.getClass().getField("navView").get(binding);
            if (viewPager2 == null || navView == null) {
                return null;
            }
            return new Object[]{viewPager2, navView};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void switchToUserTab(Activity mainActivity, int pageIndex, Object viewPager2) {
        try {
            // Track source
            try {
                Class<?> trackClass = Class.forName("com.realtech.track.Track", false, mainActivity.getClassLoader());
                Object trackInstance = trackClass.getField("INSTANCE").get(null);
                Method setFromSource = trackClass.getMethod("setFromSource", String.class);
                setFromSource.invoke(trackInstance, "我的");
            } catch (Throwable ignored) {}

            // fetchKeFuUnread
            try {
                Method getMViewModel = mainActivity.getClass().getMethod("getMViewModel");
                Object vm = getMViewModel.invoke(mainActivity);
                if (vm != null) {
                    Method fetchKeFu = vm.getClass().getMethod("fetchKeFuUnread");
                    fetchKeFu.invoke(vm);
                }
            } catch (Throwable ignored) {}

            // viewPager2.setCurrentItem(pageIndex, false)
            // 直接用外层已解析的 viewPager2，避免二次 resolveTabViews 在切换瞬间拿不到 binding 导致切不动
            if (viewPager2 != null) {
                try {
                    Method setCurrentItem = viewPager2.getClass().getMethod("setCurrentItem", int.class, boolean.class);
                    setCurrentItem.invoke(viewPager2, pageIndex, false);
                } catch (InvocationTargetException ite) {
                    Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                    MainHook.log("setCurrentItem(" + pageIndex + ") threw: "
                            + android.util.Log.getStackTraceString(cause));
                    // 兜底：走宿主自己的 changeTabIndex 切换路径
                    try {
                        Method changeTab = mainActivity.getClass().getMethod("changeTabIndex", int.class);
                        changeTab.invoke(mainActivity, pageIndex);
                        MainHook.log("changeTabIndex(" + pageIndex + ") fallback ok");
                    } catch (Throwable t2) {
                        MainHook.log("changeTabIndex(" + pageIndex + ") fallback failed: "
                                + android.util.Log.getStackTraceString(t2));
                    }
                }
            } else {
                MainHook.log("switchToUserTab: viewPager2 is null, cannot switch");
            }
        } catch (Throwable t) {
            MainHook.log("Failed to switch to user tab: " + android.util.Log.getStackTraceString(t));
        }
    }
}
