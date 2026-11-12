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
        // 不再 GONE 整个 header root、也不再把 height/minimumHeight 置 0。
        // 实测：header 高度归零会让 RecyclerView 首 item 顶到吸顶筛选栏下，
        // 第一个商家卡片的名称行被裁（关闭 HOME_TOP_BANNER 开关后即恢复完整）。
        // 改为只精细折叠 fl_banner 内的活动大图并清背景，容器本身保留，
        // 内容为空后高度自然塌陷，地址栏与首 item 布局都不受影响。
        View banner = findViewByName(root, "fl_banner");
        if (banner == null) return;
        banner.setBackground(null);
        if (banner instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) banner;
            for (int j = 0; j < g.getChildCount(); j++) {
                View child = g.getChildAt(j);
                if (child instanceof android.widget.ImageView) collapseView(child);
            }
        }
    }

    /** 搜索框 leftMargin 修正次数（防止宿主重排导致的无限重布局）。 */
    private static volatile int svAdjustCount;

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

    /** 已挂全局布局监听的地址栏父容器（防重复挂）。 */
    private static final java.util.WeakHashMap<View, Boolean> ADDR_HOOKED =
            new java.util.WeakHashMap<>();
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
        View toolbar = asView(field(binding, "toolBar"));
        // 首页顶栏单行：logo + 区名 + 搜索框并排。
        // 宿主定位判空会 GONE 地址栏并把搜索框拉成全宽（仍从左侧起），模块
        // 恢复地址栏 VISIBLE 后两者重叠。这里做两件事：
        //  1) 把「logo+地址栏」行 bringToFront 恢复宿主原生的绘制层级；
        //  2) 按地址栏右边界给 searchView 加 leftMargin，右端约束不变，
        //     搜索框自动缩短，从区名右侧开始，彻底消除重叠（不用改约束/宽度）。
        // 文案由 districtAddressText() 收敛为「XX区」，让出空间给搜索框。
        if (toolbar != null) {
            try {
                View addrBar = findViewByName(toolbar, "layout_address");
                // searchView 是 binding 的直接字段（与 toolBar 平级），不在 toolBar
                // 子树内；只在 toolbar 里 findViewByName 会拿到 null，导致下面的
                // leftMargin 修正整段被跳过（搜索框一直压在区名上）。
                View searchView = asView(field(binding, "searchView"));
                if (searchView == null) {
                    try {
                        View rootView = (View) binding.getClass()
                                .getMethod("getRoot").invoke(binding);
                        if (rootView != null) searchView = findViewByName(rootView, "searchView");
                    } catch (Throwable ignored) {}
                }
                if (searchView == null) searchView = findViewByName(toolbar, "searchView");
                if (addrBar != null) {
                    final View addrFinal = addrBar;
                    final View svFinal = searchView;
                    final Runnable ensureAddrVisible = () -> {
                        try {
                            if (addrFinal.getVisibility() != View.VISIBLE) {
                                addrFinal.setVisibility(View.VISIBLE);
                            }
                            // addrBar 的直接父容器（no-id 行容器）与 searchView
                            // 同属 ll_collapse_title_bar；把行容器提到最上层
                            android.view.ViewParent p = addrFinal.getParent();
                            if (p instanceof View) {
                                View row = (View) p;
                                android.view.ViewParent gp = row.getParent();
                                if (gp instanceof ViewGroup) {
                                    ViewGroup g = (ViewGroup) gp;
                                    if (g.indexOfChild(row) != g.getChildCount() - 1) {
                                        row.bringToFront();
                                        g.invalidate();
                                    }
                                }
                            }
                            // 缩短搜索框：左边界收敛到「logo+区名」右侧 + 3dp。
                            // 区名已收敛为「成华区」，地址栏宽度稳定，此修正可稳定收敛。
                            if (svFinal != null && addrFinal.getWidth() > 0
                                    && svFinal.getWidth() > 0) {
                                int[] la = new int[2], ls = new int[2];
                                addrFinal.getLocationOnScreen(la);
                                svFinal.getLocationOnScreen(ls);
                                int gap = (int) (3 * svFinal.getResources()
                                        .getDisplayMetrics().density + 0.5f);
                                int wantLeft = la[0] + addrFinal.getWidth() + gap;
                                int delta = wantLeft - ls[0];
                                ViewGroup.LayoutParams lp = svFinal.getLayoutParams();
                                if (Math.abs(delta) > 2
                                        && lp instanceof ViewGroup.MarginLayoutParams) {
                                    ViewGroup.MarginLayoutParams mp =
                                            (ViewGroup.MarginLayoutParams) lp;
                                    int maxLeft = svFinal.getWidth() / 2;
                                    if (svAdjustCount < 20) {
                                        int newLeft = Math.max(0, Math.min(
                                                mp.leftMargin + delta / 2, maxLeft));
                                        if (Math.abs(newLeft - mp.leftMargin) > 1) {
                                            mp.leftMargin = newLeft;
                                            svFinal.setLayoutParams(lp);
                                            svAdjustCount++;
                                            MainHook.log("HomeSearch fix#" + svAdjustCount
                                                    + " margin=" + newLeft
                                                    + " svLeft=" + ls[0] + " addrRight="
                                                    + (la[0] + addrFinal.getWidth())
                                                    + " svW=" + svFinal.getWidth());
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    };
                    ensureAddrVisible.run();
                    View parentView = addrBar.getParent() instanceof View
                            ? (View) addrBar.getParent() : null;
                    if (parentView != null && !ADDR_HOOKED.containsKey(parentView)) {
                        ADDR_HOOKED.put(parentView, Boolean.TRUE);
                        parentView.getViewTreeObserver()
                                .addOnGlobalLayoutListener(ensureAddrVisible::run);
                    }
                }
                View addrText = findViewByName(toolbar, "tv_address");
                if (addrText instanceof android.widget.TextView) {
                    final android.widget.TextView ftv = (android.widget.TextView) addrText;
                    // 模块填写收敛后的区名（如「成华区」）：短文本不挤压搜索框。
                    // 可见性/层级由 ensureAddrVisible 负责，完整官方地址仍在选择页展示。
                    String want = LocationCacheHook.districtAddressText();
                    String cur = String.valueOf(ftv.getText());
                    if (want != null && !want.isEmpty() && !want.equals(cur)) {
                        ftv.setText(want);
                        MainHook.log("HomeAddress: tv_address filled");
                    }
                }
            } catch (Throwable ignored) {}
        }
        View root = asView(binding);
        if (root instanceof ViewGroup) {
            cleanHomeTree((ViewGroup) root);
            collapseMerchantCouponTabs((ViewGroup) root);
            // 顶部红包 Banner / 活动背景：隐藏首页 feed 顶部活动卡片 (promotions_view)。
            // 原 hookTopBanner 依赖的 HomeFragmentBannerExtKt 在当前版本已不存在，
            // 无法折叠 binding 内 banner 字段；该活动卡片实际是 HomePromotionAdapter
            // 的首项（id=promotions_view），GONE 后地址栏/搜索框自然露出，且只去掉
            // rv 内这一项，不会像 GONE 整个 header 那样裁切首 item 名称。
            if (PurifyPrefs.hide(PurifyPrefs.HOME_TOP_BANNER)) {
                View pv = findViewByName(root, "promotions_view");
                if (pv != null) {
                    pv.setBackground(null);
                    collapseView(pv);
                }
            }
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
