package com.genius.xiaocanpurify;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

public final class HomePagePurifier {
    private HomePagePurifier() {}

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookStatusBarAndWindow(xposed, classLoader);
        hookSearchHint(xposed, classLoader);
        if (PurifyPrefs.hide(PurifyPrefs.HOME_SEARCH_BANNER)) {
            hookSearchRightBanner(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_TOP_BANNER)) {
            hookTopBanner(xposed, classLoader);
            hookHideHeadAdapter(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_ORDER_BANNER)) {
            hookOrderBanner(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_FAB)) {
            hookFloatingRedPacket(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_BOTTOM_PLACEMENT)) {
            hookBottomPlacement(xposed, classLoader);
            hookHomeBottomInit(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_SECOND_FLOOR)) {
            hookRefreshLayout(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_MENU_ROW)) {
            hookMenusRow(xposed, classLoader);
        }
        hookHomeFragmentGuard(xposed, classLoader);
    }

    /**
     * Persistent layout guard re-hiding restored views according to the toggles.
     */
    private static void hookHomeFragmentGuard(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> fragClass = Class.forName("com.realtech.promotion.pages.home.HomeFragment", false, cl);
            for (Method m : fragClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("lazyInitView".equals(mName) || "lazyLoadData".equals(mName) || "onResume".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Object fragment = chain.getThisObject();
                        if (fragment != null) {
                            forceHideFloatingViews(fragment);
                        }
                        return result;
                    });
                    MainHook.log("Hooked HomeFragment." + mName);
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFragment lifecycle: " + t);
        }
    }

    private static void hookRefreshLayout(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> vm = Class.forName("com.realtech.promotion.pages.home.viewmodel.HomeViewModel", false, cl);
            Method eligible = vm.getDeclaredMethod("canUseHomeSecondFloor");
            // The host then installs its normal MaterialHeader and keeps its refresh listener.
            xposed.hook(eligible).intercept(chain -> false);
            MainHook.log("Home second floor disabled; ordinary refresh preserved");
        } catch (Throwable t) {
            MainHook.log("Failed to hook home second-floor eligibility: " + t);
        }
    }

    /**
     * Fix status bar color: force clean white status bar with dark icons.
     */
    private static void hookStatusBarAndWindow(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> mainActivityClass = Class.forName("com.realtech.xiaocan.MainActivity", false, cl);
            for (Method m : mainActivityClass.getDeclaredMethods()) {
                if ("onCreate".equals(m.getName()) || "onResume".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (activity != null && activity.getWindow() != null) {
                            activity.getWindow().getDecorView().setBackgroundColor(Color.WHITE);
                            activity.getWindow().setStatusBarColor(Color.WHITE);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                View decor = activity.getWindow().getDecorView();
                                int flags = decor.getSystemUiVisibility();
                                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                                decor.setSystemUiVisibility(flags);
                            }
                        }
                        return result;
                    });
                }
            }
            MainHook.log("Hooked MainActivity window status bar -> pure white + dark icons");
        } catch (Throwable t) {
            MainHook.log("Failed to hook MainActivity status bar: " + t);
        }

        try {
            Class<?> fragClass = Class.forName("com.realtech.promotion.pages.home.HomeFragment", false, cl);
            for (Method m : fragClass.getDeclaredMethods()) {
                if ("applyStatusBarThemeColor".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object binding = chain.getArg(0);
                        if (binding != null) {
                            try {
                                Field sbField = binding.getClass().getField("statusBar");
                                View statusBar = (View) sbField.get(binding);
                                if (statusBar != null) {
                                    statusBar.setBackgroundColor(Color.WHITE);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeFragment.applyStatusBarThemeColor -> white");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook applyStatusBarThemeColor: " + t);
        }

        try {
            Class<?> barLayoutClass = Class.forName("com.realtech.promotion.widget.HomeAppBarLayoutView", false, cl);
            for (Method m : barLayoutClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("setSpringFestivalState".equals(mName) || "set5stState".equals(mName)) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeAppBarLayoutView." + mName + " -> blocked festive bg");
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Eradicate marketing/ad hint words from the search box and reset placeholder to standard.
     */
    private static void hookSearchHint(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> autoScrollClass = Class.forName("com.realtech.promotion.widget.AutoScrollTextView", false, cl);
            for (Method m : autoScrollClass.getDeclaredMethods()) {
                if ("setTextList".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object autoScroll = chain.getThisObject();
                        if (autoScroll != null) {
                            try {
                                Method stopScroll = autoScroll.getClass().getMethod("stopScroll");
                                stopScroll.invoke(autoScroll);
                                Method getCurrentTextView = autoScroll.getClass().getMethod("getCurrentTextView");
                                TextView tv = (TextView) getCurrentTextView.invoke(autoScroll);
                                if (tv != null) {
                                    tv.setText("搜索商家或套餐");
                                    tv.setTextColor(0xFF999999);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
                    MainHook.log("Hooked AutoScrollTextView.setTextList");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook AutoScrollTextView: " + t);
        }

        try {
            Class<?> vmClass = Class.forName("com.realtech.promotion.pages.home.viewmodel.HomeViewModel", false, cl);
            for (Method m : vmClass.getDeclaredMethods()) {
                if ("refreshSearchWord".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeViewModel.refreshSearchWord");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeViewModel.refreshSearchWord: " + t);
        }
    }

    /**
     * Fix search button color to orange and hide search right banner.
     */
    private static void hookSearchRightBanner(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> barLayoutClass = Class.forName("com.realtech.promotion.widget.HomeAppBarLayoutView", false, cl);
            for (Method m : barLayoutClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("renderSearchRightBanner".equals(mName) || "updateSearchRightImgList".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        Object barLayout = chain.getThisObject();
                        if (barLayout != null) {
                            try {
                                Field bindingField = barLayout.getClass().getDeclaredField("binding");
                                bindingField.setAccessible(true);
                                Object binding = bindingField.get(barLayout);
                                Field searchViewField = binding.getClass().getField("searchView");
                                Object searchView = searchViewField.get(binding);
                                Field bannerField = searchView.getClass().getField("bannerSearchRight");
                                View banner = (View) bannerField.get(searchView);
                                if (banner != null) {
                                    banner.setVisibility(View.GONE);
                                }
                                Field ivField = searchView.getClass().getField("ivSearchRight");
                                View iv = (View) ivField.get(searchView);
                                if (iv != null) {
                                    iv.setVisibility(View.GONE);
                                }
                                // Force search button to orange
                                if (searchView != null) {
                                    Method setBg = searchView.getClass().getMethod("setButtonBackgroundColor", int.class);
                                    setBg.invoke(searchView, 0xFFFF6600);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeAppBarLayoutView." + mName);
                }
            }

            for (Method m : barLayoutClass.getDeclaredMethods()) {
                if (!"updateTitleView".equals(m.getName()) && !"updateTitleBar".equals(m.getName())) continue;
                xposed.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Field f = barLayoutClass.getDeclaredField("binding");
                        f.setAccessible(true);
                        Object search = field(f.get(chain.getThisObject()), "searchView");
                        View button = asView(field(search, "tvSearch"));
                        if (button != null) {
                            Object helper = button.getClass().getMethod("getHelper").invoke(button);
                            helper.getClass().getMethod("setBackgroundColorNormal", int.class).invoke(helper, Color.TRANSPARENT);
                            helper.getClass().getMethod("setStrokeColorNormal", int.class).invoke(helper, Color.TRANSPARENT);
                            button.setBackgroundColor(Color.TRANSPARENT);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
            }

        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeSearchView: " + t);
        }
    }

    /**
     * Eradicate top banner ("请学生免费喝10000杯奶茶") and top red/orange background.
     */
    private static void hookTopBanner(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> bannerExtClass = Class.forName("com.realtech.promotion.pages.home.ext.HomeFragmentBannerExtKt", false, cl);
            for (Method m : bannerExtClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("applyTrafficExpBBanner".equals(mName)
                        || "applyTrafficExpBNoBannerTopArea".equals(mName)
                        || "applyTrafficExpBImmersiveBannerLayout".equals(mName)
                        || "initBanner".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        for (Object arg : args) {
                            if (arg != null) {
                                String cn = arg.getClass().getName();
                                if (cn.contains("ViewHeadHomeBinding")) {
                                    collapseTopBanner(arg);
                                } else if (cn.contains("FragmentHomeBinding")) {
                                    try {
                                        Field sbField = arg.getClass().getField("statusBar");
                                        View sb = (View) sbField.get(arg);
                                        if (sb != null) sb.setBackgroundColor(Color.WHITE);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeFragmentBannerExtKt." + mName);
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFragmentBannerExtKt: " + t);
        }

        try {
            Class<?> headViewExtClass = Class.forName("com.realtech.promotion.pages.home.ext.HeadViewExtKt", false, cl);
            for (Method m : headViewExtClass.getDeclaredMethods()) {
                if ("updateHeadBannerView".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        for (Object arg : args) {
                            if (arg != null) {
                                String cn = arg.getClass().getName();
                                if (cn.contains("ViewHeadHomeBinding")) {
                                    collapseTopBanner(arg);
                                } else if (cn.contains("FragmentHomeBinding")) {
                                    try {
                                        Field sbField = arg.getClass().getField("statusBar");
                                        View sb = (View) sbField.get(arg);
                                        if (sb != null) sb.setBackgroundColor(Color.WHITE);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        return null;
                    });
                    MainHook.log("Hooked HeadViewExtKt.updateHeadBannerView");
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HeadViewExtKt: " + t);
        }


    }

    private static void collapseTopBanner(Object headBinding) {
        try {
            Field flBannerField = headBinding.getClass().getField("flBanner");
            View flBanner = (View) flBannerField.get(headBinding);
            if (flBanner != null) {
                flBanner.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = flBanner.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    lp.height = 0;
                    flBanner.setLayoutParams(lp);
                }
            }

            Field bgViewField = headBinding.getClass().getField("trafficExpTopBackground");
            View bgView = (View) bgViewField.get(headBinding);
            if (bgView != null) {
                bgView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = bgView.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    bgView.setLayoutParams(lp);
                }
            }

            Field bannerField = headBinding.getClass().getField("banner");
            View banner = (View) bannerField.get(headBinding);
            if (banner != null) banner.setVisibility(View.GONE);

            Field trafficExpBannerField = headBinding.getClass().getField("trafficExpBanner");
            View trafficExpBanner = (View) trafficExpBannerField.get(headBinding);
            if (trafficExpBanner != null) trafficExpBanner.setVisibility(View.GONE);

            if (PurifyPrefs.hide(PurifyPrefs.HOME_STORE_CARD)) {
                Field conHelicopterField = headBinding.getClass().getField("conHelicopterchallenge");
                View conHelicopter = (View) conHelicopterField.get(headBinding);
                if (conHelicopter != null) conHelicopter.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Block HomeBottomKt.initHomeBottomView from setting bottom placement visible
     */
    private static void hookHomeBottomInit(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> bottomKtClass = Class.forName("com.realtech.promotion.pages.home.ext.HomeBottomKt", false, cl);
            for (Method m : bottomKtClass.getDeclaredMethods()) {
                if ("initHomeBottomView".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeBottomKt.initHomeBottomView -> blocked");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeBottomKt: " + t);
        }
    }

    /**
     * Collapse the home header menus row (美团红包/闪购红包 etc.)
     */
    private static void hookMenusRow(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> menusBindingClass = Class.forName("com.realtech.promotion.databinding.LayoutHomeMenus2Binding", false, cl);
            for (Method m : menusBindingClass.getDeclaredMethods()) {
                if ("bind".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result != null) {
                            try {
                                Method getRoot = result.getClass().getMethod("getRoot");
                                View root = (View) getRoot.invoke(result);
                                if (root != null) {
                                    root.setVisibility(View.GONE);
                                    ViewGroup.LayoutParams lp = root.getLayoutParams();
                                    if (lp != null) {
                                        lp.height = 0;
                                        root.setLayoutParams(lp);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
                    MainHook.log("Hooked LayoutHomeMenus2Binding.bind");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook LayoutHomeMenus2Binding: " + t);
        }
    }

    /**
     * Hide RecyclerView header content (banner/menus) but keep header functional.
     * Only collapse ad/promo elements, keep search + filter in header.
     */
    private static void hookHideHeadAdapter(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> type = Class.forName("com.realtech.promotion.adapters.HomeHeadAdapter", false, cl);
            for (Method m : type.getDeclaredMethods()) {
                if (!"bindAll".equals(m.getName())) continue;
                xposed.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!chain.getArgs().isEmpty()) collapseHeader(asView(chain.getArg(0)));
                    return result;
                });
            }
        } catch (Throwable t) { MainHook.log("Header hook: " + t); }
    }

    private static void collapseHeader(View root) {
        if (root == null) return;
        collapseView(root);
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp != null && lp.height != 0) { lp.height = 0; root.setLayoutParams(lp); }
        if (root.getMinimumHeight() != 0) root.setMinimumHeight(0);
    }

    private static View asView(Object value) {
        if (value instanceof View) return (View) value;
        if (value == null) return null;
        try { return (View) value.getClass().getMethod("getRoot").invoke(value); }
        catch (Throwable ignored) { return null; }
    }

    private static Object field(Object obj, String name) {
        if (obj == null) return null;
        try { return obj.getClass().getField(name).get(obj); }
        catch (Throwable ignored) { return null; }
    }

    /**
     * Filter ad items (picbanner/pic/group) from HomePromotionAdapter data list
     * so they never appear in the RecyclerView at all.
     */
    private static void hookFeedBanners(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> type = Class.forName("com.realtech.promotion.adapters.HomePromotionAdapter", false, cl);
            for (Method m : type.getSuperclass().getDeclaredMethods()) {
                String name = m.getName();
                boolean bulk = "submitList".equals(name) || "setItems".equals(name) || "addAll".equals(name);
                if (!bulk && !"add".equals(name) && !"set".equals(name)) continue;
                xposed.hook(m).intercept(chain -> {
                    if (!type.isInstance(chain.getThisObject())) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (bulk) {
                        for (int i = 0; i < args.length; i++) {
                            if (!(args[i] instanceof java.util.Collection)) continue;
                            java.util.ArrayList<Object> clean = new java.util.ArrayList<>();
                            for (Object item : (java.util.Collection<?>) args[i])
                                if (!isAdFeedItem(item)) clean.add(item);
                            args[i] = clean;
                        }
                    } else if (args.length > 0 && isAdFeedItem(args[args.length - 1])) {
                        if ("set".equals(name)) {
                            type.getSuperclass().getMethod("removeAt", int.class)
                                .invoke(chain.getThisObject(), args[0]);
                        }
                        return null;
                    }
                    return chain.proceed(args);
                });
                MainHook.log("Home-only feed filter: " + m.getName());
            }
        } catch (Throwable t) { MainHook.log("Feed hook: " + t); }
    }

    private static boolean isAdFeedItem(Object item) {
        if (item == null) return false;
        try {
            // Check the item's "type" field (HomeType enum)
            Method getType = item.getClass().getMethod("getType");
            Object type = getType.invoke(item);
            if (type != null) {
                String typeName = type instanceof Enum ? ((Enum<?>) type).name() : type.toString();
                return "pic".equals(typeName) || "picbanner".equals(typeName) || "group".equals(typeName);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void collapseViewHolder(Object holder) {
        if (holder == null) return;
        try {
            Method getBinding = holder.getClass().getMethod("getBinding");
            Object binding = getBinding.invoke(holder);
            if (binding != null) {
                Method getRoot = binding.getClass().getMethod("getRoot");
                View root = (View) getRoot.invoke(binding);
                collapseView(root);
                root.requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Eradicate "开心收下" order banner bar from home header.
     */
    private static void hookOrderBanner(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> extClass = Class.forName("com.realtech.promotion.pages.home.ext.HomeTrafficExpCOrderExtKt", false, cl);

            for (Method m : extClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("buildTrafficExpCOrderBannerItems".equals(mName)) {
                    xposed.hook(m).intercept(chain -> Collections.emptyList());
                    MainHook.log("Hooked HomeTrafficExpCOrderExtKt.buildTrafficExpCOrderBannerItems");
                } else if ("updateTrafficExpCOrderBanner".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        for (Object arg : args) {
                            if (arg != null && arg.getClass().getName().contains("ViewHeadHomeBinding")) {
                                try {
                                    Field layoutBannerField = arg.getClass().getField("layoutTrafficExpCOrderBanner");
                                    Object layoutBanner = layoutBannerField.get(arg);
                                    if (layoutBanner != null) {
                                        Method getRoot = layoutBanner.getClass().getMethod("getRoot");
                                        View root = (View) getRoot.invoke(layoutBanner);
                                        collapseView(root);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeTrafficExpCOrderExtKt.updateTrafficExpCOrderBanner");
                } else if ("bindTrafficExpCOrderBanner".equals(mName)) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeTrafficExpCOrderExtKt.bindTrafficExpCOrderBanner");
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeTrafficExpCOrderExtKt: " + t);
        }
    }

    /**
     * Eradicate floating red packet ("¥18 领红包") at the bottom of the home screen.
     * This is app:id/layout_bottom_placement & app:id/ivBottomImg in LayoutHomeBottomBinding!
     */
    private static void hookBottomPlacement(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> bottomPlacementBindingClass = Class.forName("com.realtech.promotion.databinding.LayoutHomeBottomPlacementBinding", false, cl);
            for (Method m : bottomPlacementBindingClass.getDeclaredMethods()) {
                if ("bind".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        MainHook.log("LayoutHomeBottomPlacementBinding.bind INVOKED");
                        Object result = chain.proceed();
                        if (result != null) {
                            try {
                                Method getRoot = result.getClass().getMethod("getRoot");
                                View root = (View) getRoot.invoke(result);
                                if (root != null) {
                                    root.setVisibility(View.GONE);
                                    MainHook.log("LayoutHomeBottomPlacementBinding root GONE, view=" + root.getClass().getSimpleName());
                                }
                            } catch (Throwable t) {
                                MainHook.log("LayoutHomeBottomPlacementBinding root hide error: " + t);
                            }
                        }
                        return result;
                    });
                    MainHook.log("Hooked LayoutHomeBottomPlacementBinding.bind");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook LayoutHomeBottomPlacementBinding: " + t);
        }

        try {
            Class<?> bottomBindingClass = Class.forName("com.realtech.promotion.databinding.LayoutHomeBottomBinding", false, cl);
            for (Method m : bottomBindingClass.getDeclaredMethods()) {
                if ("bind".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result != null) {
                            try {
                                Field placementField = result.getClass().getField("layoutBottomPlacement");
                                Object placement = placementField.get(result);
                                if (placement != null) {
                                    Method getRoot = placement.getClass().getMethod("getRoot");
                                    View root = (View) getRoot.invoke(placement);
                                    collapseView(root);
                                }
                                Field tipsField = result.getClass().getField("bottomTips");
                                View tips = (View) tipsField.get(result);
                                if (tips != null) {
                                    tips.setVisibility(View.GONE);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
                    MainHook.log("Hooked LayoutHomeBottomBinding.bind");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook LayoutHomeBottomBinding: " + t);
        }
    }

    /**
     * Eradicate floating red packet rain and all floating bubbles on home.
     */
    private static void hookFloatingRedPacket(XposedInterface xposed, ClassLoader cl) {
        // 1. Hook LayoutHomeFabBinding.bind -> collapse layout_fab immediately
        try {
            Class<?> fabBindingClass = Class.forName("com.realtech.promotion.databinding.LayoutHomeFabBinding", false, cl);
            for (Method m : fabBindingClass.getDeclaredMethods()) {
                if ("bind".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result != null) {
                            try {
                                Method getRoot = result.getClass().getMethod("getRoot");
                                View root = (View) getRoot.invoke(result);
                                collapseView(root);
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
                    MainHook.log("Hooked LayoutHomeFabBinding.bind");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook LayoutHomeFabBinding.bind: " + t);
        }

        // 2. Hook HomeRedPackRainFabView
        try {
            Class<?> rainClass = Class.forName("com.realtech.promotion.widget.home.HomeRedPackRainFabView", false, cl);
            for (Method m : rainClass.getDeclaredMethods()) {
                if ("checkShowRain".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> false);
                    MainHook.log("Hooked HomeRedPackRainFabView.checkShowRain -> false");
                } else if ("updateViews".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        View view = (View) chain.getThisObject();
                        collapseView(view);
                        return null;
                    });
                    MainHook.log("Hooked HomeRedPackRainFabView.updateViews");
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeRedPackRainFabView: " + t);
        }

        // 3. Hook modern HomeFabKt & HomeFabOrderKt
        try {
            Class<?> fabKtClass = Class.forName("com.realtech.promotion.pages.home.ext.fab.HomeFabKt", false, cl);
            for (Method m : fabKtClass.getDeclaredMethods()) {
                if ("initHomeFab".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object binding = chain.getArg(0);
                        if (binding != null) {
                            try {
                                Method getRoot = binding.getClass().getMethod("getRoot");
                                View root = (View) getRoot.invoke(binding);
                                collapseView(root);
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeFabKt.initHomeFab");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFabKt: " + t);
        }

        try {
            Class<?> fabOrderKtClass = Class.forName("com.realtech.promotion.pages.home.ext.fab.HomeFabOrderKt", false, cl);
            for (Method m : fabOrderKtClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("initFabOrder".equals(mName) || "updateOrderFabView".equals(mName)) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeFabOrderKt." + mName);
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFabOrderKt: " + t);
        }

        // 4. Hook legacy HomeFabExtKt
        try {
            Class<?> fabExtClass = Class.forName("com.realtech.promotion.pages.home.ext.HomeFabExtKt", false, cl);
            for (Method m : fabExtClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("initFabView".equals(mName)
                        || "updateOrderFabView".equals(mName)
                        || "initNewUserFabView".equals(mName)
                        || "updateNewUserFabView".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) != null) {
                            hideHomeLayoutFab(args.get(0));
                        }
                        return null;
                    });
                    MainHook.log("Hooked HomeFabExtKt." + mName);
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFabExtKt: " + t);
        }

        // 5. Hook HomeFabOrderBannerAdapter
        try {
            Class<?> adapterClass = Class.forName("com.realtech.promotion.adapters.HomeFabOrderBannerAdapter", false, cl);
            for (Method m : adapterClass.getDeclaredMethods()) {
                if ("onBindView".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> null);
                    MainHook.log("Hooked HomeFabOrderBannerAdapter.onBindView");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook HomeFabOrderBannerAdapter: " + t);
        }

        // 6. HomeFragment lifecycle guard is installed by hookHomeFragmentGuard().

    }

    private static final java.util.WeakHashMap<View, Boolean> watchedHomes = new java.util.WeakHashMap<>();

    private static void forceHideFloatingViews(Object fragment) {
        try {
            Object binding = fragment.getClass().getMethod("getBinding").invoke(fragment);
            View root = asView(binding);
            if (root == null) return;
            enforceHome(binding);
            if (watchedHomes.put(root, Boolean.TRUE) == null) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(() -> enforceHome(binding));
                MainHook.log("Home persistent layout guard attached");
            }
        } catch (Throwable t) { MainHook.log("Home guard: " + t); }
    }

    /** 工具栏/fl_banner 结构 dump 上次时间（60 秒节流）。 */
    private static volatile long lastToolbarDumpMs;

    /** 诊断：递归 dump 视图树（资源 id 名/类名/窗口Y坐标/可见性，限深）。 */
    private static void dumpViewTree(View v, StringBuilder sb, int depth, int maxDepth) {
        if (v == null) return;
        String idName = "no-id";
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                idName = v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        sb.append(v.getClass().getSimpleName()).append('@').append(idName)
                .append('[').append(loc[1]).append('+').append(v.getHeight())
                .append(v.getVisibility() == View.VISIBLE ? "" : " G").append(']');
        if (depth >= maxDepth || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        sb.append('{');
        int n = Math.min(g.getChildCount(), 10);
        for (int i = 0; i < n; i++) {
            dumpViewTree(g.getChildAt(i), sb, depth + 1, maxDepth);
            if (i < n - 1) sb.append(',');
        }
        sb.append('}');
    }

    private static void enforceHome(Object binding) {
        if (PurifyPrefs.hide(PurifyPrefs.HOME_FAB)) {
            collapseView(asView(field(binding, "layoutFab")));
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_SECOND_FLOOR)) {
            for (String name : new String[]{"flSceondFloor", "lySceondFloorGuide", "layoutSecondHeader"})
                collapseView(asView(field(binding, name)));
        }
        if (PurifyPrefs.hide(PurifyPrefs.HOME_BOTTOM_PLACEMENT)) {
            collapseView(asView(field(field(binding, "layoutBottom"), "layoutBottomPlacement")));
        }
        View rv = asView(field(binding, "rv"));
        View toolbar = asView(field(binding, "toolBar"));
        boolean headerCollapsed = PurifyPrefs.hide(PurifyPrefs.HOME_TOP_BANNER);
        // 诊断：dump 工具栏子树（id/类名/窗口Y坐标）——定位地址栏视图
        //（无条件 dump：此前放在 height>0 分支内，启动早期未布局时不输出；
        //  节流 10 秒，布局完成后会再 dump 一次带真实坐标的）
        if (toolbar != null) {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastToolbarDumpMs > 10000L) {
                    lastToolbarDumpMs = now;
                    StringBuilder sb = new StringBuilder();
                    dumpViewTree(toolbar, sb, 0, 5);
                    MainHook.log("ToolbarDiag: " + sb);
                }
            } catch (Throwable ignored) {}
        }
        if (headerCollapsed && toolbar != null) {
            // 不再上移列表：实测列表 padding 触发宿主「折叠标题栏」逻辑
            //（ll_collapse_title_bar），把左上角地址栏（layout_address）
            // GONE，搜索框顶到 logo 行（用户截图：开模块后地址栏被搜索框
            // 遮挡）。保留 fl_banner 容器清理（清背景+折叠图片子视图）与
            // Banner 数据层过滤——容器内容为空后高度自然塌陷，列表由宿主
            // 自行上移，布局结构恢复宿主原样（地址栏显示），净化效果不变。
            // 地址栏恢复：宿主可能残留折叠态（layout_address GONE），恢复
            // 显示并把缓存地址回填进 tv_address（宿主地址文本来自定位回调，
            // 回放缓存的地址在部分路径下未同步到该控件）。
            try {
                View addrBar = findViewByName(toolbar, "layout_address");
                if (addrBar != null && addrBar.getVisibility() != View.VISIBLE) {
                    addrBar.setVisibility(View.VISIBLE);
                    MainHook.log("HomeAddress: restored layout_address");
                }
                View addrText = findViewByName(toolbar, "tv_address");
                if (addrText instanceof android.widget.TextView) {
                    String want = LocationCacheHook.cachedAddressText();
                    String cur = String.valueOf(((android.widget.TextView) addrText).getText());
                    if (want != null && !want.isEmpty() && !want.equals(cur)) {
                        ((android.widget.TextView) addrText).setText(want);
                        MainHook.log("HomeAddress: tv_address filled");
                    }
                }
            } catch (Throwable ignored) {}
        }
        View root = asView(binding);
        if (root instanceof ViewGroup) {
            // 诊断：dump 页面根结构（4 层，含 id 名）——fl_banner 内没有
            // 地址栏、toolBar 字段为 null，地址栏在页面其它容器，在此定位
            try {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastToolbarDumpMs > 60000L) {
                    lastToolbarDumpMs = now;
                    StringBuilder sb = new StringBuilder();
                    dumpViewTree(root, sb, 0, 4);
                    MainHook.log("RootDiag: " + sb);
                }
            } catch (Throwable ignored) {}
            cleanHomeTree((ViewGroup) root);
            collapseMerchantCouponTabs((ViewGroup) root);
        }
    }

    /**
     * 隐藏首页顶部导航栏里的「美团水果神券」营销 Tab（常开，不受开关影响）。
     * 该 Tab 是服务端活动期间动态插入的（附近商家/爆品好价 旁边），
     * 没有固定的 view id，按文案定位后折叠其所在的可点击 Tab 项；
     * 宿主重新渲染后由 GlobalLayout 守卫再次折叠。
     */
    private static void collapseMerchantCouponTabs(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView
                    && ((android.widget.TextView) child).getText() != null
                    && isCouponTabText(((android.widget.TextView) child).getText().toString())) {
                View tab = enclosingClickable(child);
                if (tab != child && isTabItemOnly(tab)) {
                    tab.setVisibility(View.GONE);
                } else {
                    child.setVisibility(View.GONE);
                }
            }
            if (child instanceof ViewGroup) collapseMerchantCouponTabs((ViewGroup) child);
        }
    }

    /** 顶部导航水果券营销 Tab 的文案变体：老版「美团水果券」、新版「美团水果神券」。 */
    private static boolean isCouponTabText(String text) {
        return text.contains("美团水果") || text.contains("水果神券");
    }

    /** 校验该可点击节点只包含「美团水果神券」这一个 Tab 的内容（防止爬升过头折叠整行）。 */
    private static boolean isTabItemOnly(View tab) {
        if (!(tab instanceof ViewGroup)) return true;
        StringBuilder sb = new StringBuilder();
        collectTabText((ViewGroup) tab, sb);
        String text = sb.toString();
        return !text.contains("附近商家") && !text.contains("爆品好价") && !text.contains("只看可抢");
    }

    private static void collectTabText(ViewGroup group, StringBuilder sb) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView
                    && ((android.widget.TextView) child).getText() != null) {
                sb.append(((android.widget.TextView) child).getText()).append('\n');
            }
            if (child instanceof ViewGroup) collectTabText((ViewGroup) child, sb);
        }
    }

    /** 向上查找最近的可点击祖先（Tab 项整体可点击切换页面）。 */
    private static View enclosingClickable(View v) {
        View cur = v;
        while (cur.getParent() instanceof View) {
            View parent = (View) cur.getParent();
            if (parent.isClickable()) return parent;
            cur = parent;
        }
        return cur;
    }

    private static View topmostContent(View rv) {
        if (!(rv instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) rv;
        View best = null;
        int bestTop = Integer.MAX_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 2) continue;
            int[] location = new int[2];
            child.getLocationInWindow(location);
            if (location[1] < bestTop) {
                bestTop = location[1];
                best = child;
            }
        }
        return best;
    }

    private static View findViewByName(View root, String name) {
        if (name.equals(getViewIdName(root))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findViewByName(group.getChildAt(i), name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void cleanHomeTree(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String id = getViewIdName(child);
            if ("fl_banner".equals(id) && PurifyPrefs.hide(PurifyPrefs.HOME_TOP_BANNER)) {
                // 诊断：dump fl_banner 容器内部结构（4 层，含 id 名）——
                // 确认地址栏视图是否在其中及其 id 名
                try {
                    if (child instanceof ViewGroup) {
                        StringBuilder fb = new StringBuilder();
                        dumpViewTree(child, fb, 0, 4);
                        MainHook.log("FlBannerDiag: " + fb);
                    }
                } catch (Throwable ignored) {}
                // 3.21.5 把左上角地址栏入口放进了 fl_banner 容器——GONE 容器
                // 会连带隐藏地址栏（用户逐个开关实测确认）。改为：保留容器，
                // 清空活动背景，折叠容器内的图片类子视图（活动大图）；
                // Banner 数据由 hookFeedBanners 在数据层过滤，容器内容为空后
                // 高度自然塌陷，地址栏与净化两全。
                child.setBackground(null);
                if (child instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) child;
                    for (int j = 0; j < g.getChildCount(); j++) {
                        View s = g.getChildAt(j);
                        if (s instanceof android.widget.ImageView) {
                            collapseView(s);
                        }
                    }
                }
            }
            boolean hide = ("layout_traffic_exp_c_order_banner".equals(id) && PurifyPrefs.hide(PurifyPrefs.HOME_ORDER_BANNER))
                    || ("layout_bottom_placement".equals(id) && PurifyPrefs.hide(PurifyPrefs.HOME_BOTTOM_PLACEMENT))
                    || ("layout_fab".equals(id) && PurifyPrefs.hide(PurifyPrefs.HOME_FAB))
                    || (("fl_sceond_floor".equals(id) || "ly_sceond_floor_guide".equals(id)
                        || "layout_second_header".equals(id)) && PurifyPrefs.hide(PurifyPrefs.HOME_SECOND_FLOOR))
                    || ("con_helicopterchallenge".equals(id) && PurifyPrefs.hide(PurifyPrefs.HOME_STORE_CARD));
            if (hide) collapseView(child);
            if (child instanceof ViewGroup) cleanHomeTree((ViewGroup) child);
        }
    }

    private static void collapseChildrenByIdName(android.view.ViewGroup vg, String idName) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            String name = getViewIdName(child);
            if (idName.equals(name)) {
                child.setVisibility(View.GONE);
                child.getLayoutParams().height = 0;
            }
            if (child instanceof android.view.ViewGroup) {
                collapseChildrenByIdName((android.view.ViewGroup) child, idName);
            }
        }
    }

    private static String getViewIdName(View v) {
        if (v == null || v.getId() == View.NO_ID) return "";
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable e) {
            return "";
        }
    }

    private static void hideHomeLayoutFab(Object fragment) {
        try {
            Method getBinding = fragment.getClass().getMethod("getBinding");
            Object binding = getBinding.invoke(fragment);
            if (binding != null) {
                hideBindingLayoutFab(binding);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideBindingLayoutFab(Object binding) {
        try {
            Field layoutFabField = binding.getClass().getField("layoutFab");
            Object layoutFab = layoutFabField.get(binding);
            if (layoutFab != null) {
                Method getRoot = layoutFab.getClass().getMethod("getRoot");
                View root = (View) getRoot.invoke(layoutFab);
                collapseView(root);
            }
        } catch (Throwable ignored) {}
    }

    private static void collapseView(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
    }
}
