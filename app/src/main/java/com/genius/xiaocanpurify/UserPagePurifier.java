package com.genius.xiaocanpurify;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

public final class UserPagePurifier {
    private UserPagePurifier() {}

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookUserV2Fragment(xposed, classLoader);
        hookUserModules(xposed, classLoader);
        if (PurifyPrefs.hide(PurifyPrefs.USER_EARN)) {
            hookUserInfoModule(xposed, classLoader);
        }
        if (PurifyPrefs.hide(PurifyPrefs.USER_VIP_CAROUSEL)) {
            hookVipCarousel(xposed, classLoader);
        }
    }

    private static void hookVipCarousel(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> module = Class.forName("com.realtech.user.pages.userv2.UserVipModule", false, cl);
            for (Method method : module.getDeclaredMethods()) {
                if (!"render".equals(method.getName())) continue;
                xposed.hook(method).intercept(chain -> {
                    Object binding = module.getField("binding").get(chain.getThisObject());
                    hideVipCarousel(binding);
                    return null;
                });
            }
            Class<?> binding = Class.forName("com.realtech.user.databinding.UserLayoutUserV2SilkBinding", false, cl);
            Method bind = binding.getDeclaredMethod("bind", View.class);
            xposed.hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                hideVipCarousel(result);
                return result;
            });
            MainHook.log("VIP carousel hidden; withdraw card preserved");
        } catch (Throwable t) {
            MainHook.log("VIP carousel hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void hideVipCarousel(Object binding) {
        if (binding == null) return;
        hideBindingField(binding, "bannerVipCard");
        hideBindingField(binding, "bannerIndicator");
        try {
            Object banner = binding.getClass().getField("bannerVipCard").get(binding);
            banner.getClass().getMethod("stop").invoke(banner);
            View card = (View) binding.getClass().getField("cardSilkEarn").get(binding);
            android.view.ViewGroup.LayoutParams params = card.getLayoutParams();
            if (params != null) {
                // Only change horizontal constraints; keep the balance and withdraw button intact.
                for (String name : new String[]{"endToStart", "rightToLeft"})
                    params.getClass().getField(name).setInt(params, -1);
                params.getClass().getField("endToEnd").setInt(params, 0);
                params.getClass().getField("rightToRight").setInt(params, 0);
                params.getClass().getField("matchConstraintPercentWidth").setFloat(params, 1f);
                params.getClass().getField("matchConstraintDefaultWidth").setInt(params, 0);
                params.width = 0;
                card.setLayoutParams(params);
            }
        } catch (Throwable t) {
            MainHook.log("VIP carousel layout unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void hookUserV2Fragment(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> userFragClass = Class.forName("com.realtech.user.pages.UserV2Fragment", false, cl);

            for (Method m : userFragClass.getDeclaredMethods()) {
                if ("lazyInitView".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Object fragment = chain.getThisObject();
                        purgeUserViews(fragment);
                        return result;
                    });
                    MainHook.log("Hooked UserV2Fragment.lazyInitView");
                    break;
                }
            }

            for (Method m : userFragClass.getDeclaredMethods()) {
                if ("renderState".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object state = chain.getArg(0);
                        if (state != null) {
                            String sName = state.getClass().getSimpleName();
                            boolean hide = (sName.contains("Menu") && allMenuItemsHidden())
                                    || (sName.contains("Service") && PurifyPrefs.hide(PurifyPrefs.USER_SERVICE))
                                    || (sName.contains("BWC") && PurifyPrefs.hide(PurifyPrefs.USER_MERCHANT))
                                    || (sName.contains("Notice") && PurifyPrefs.hide(PurifyPrefs.USER_NOTICE))
                                    || (sName.contains("NewUserTip") && PurifyPrefs.hide(PurifyPrefs.USER_NEW_USER_TIP))
                                    || (sName.contains("Commerce") && PurifyPrefs.hide(PurifyPrefs.USER_COMMERCE))
                                    || sName.contains("BaiduAd")
                                    || (sName.contains("MatchPlacement") && PurifyPrefs.hide(PurifyPrefs.USER_OTHER_MATCH))
                                    || sName.contains("Challenge")
                                    || sName.contains("YuanBao");
                            if (hide) {
                                MainHook.log("Filtered UserCenterViewState: " + sName);
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                    MainHook.log("Hooked UserV2Fragment.renderState");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook UserV2Fragment: " + t);
        }
    }

    private static void purgeUserViews(Object fragment) {
        try {
            Field bindingField = fragment.getClass().getField("binding");
            Object binding = bindingField.get(fragment);
            if (binding == null) return;

            hideBindingField(binding, "adContainer");
            hideBindingField(binding, "bqtContainer");
            if (PurifyPrefs.hide(PurifyPrefs.USER_COMMERCE)) {
                hideBindingField(binding, "commerceGridContainer");
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_NOTICE)) {
                hideBindingField(binding, "bannerNotice");
            }
            if (PurifyPrefs.hide(PurifyPrefs.FLOATING_WINDOW)) {
                hideBindingField(binding, "ivFloatingWindow");
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_BONUS)) {
                hideBindingField(binding, "frameBottomBonus");
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_NEW_USER_TIP)) {
                hideBindingLayout(binding, "layoutNewUserTip");
            }
            if (allMenuItemsHidden()) {
                hideBindingLayout(binding, "layoutMenu");
            } else {
                filterMenuItems(binding);
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_MERCHANT)) {
                hideBindingLayout(binding, "layoutUserMerchant");
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_SERVICE)) {
                hideBindingLayout(binding, "layoutServer");
            }

            // Hide "赚钱" button and "待领蚕豆" badge next to 提现
            if (PurifyPrefs.hide(PurifyPrefs.USER_EARN)) {
                try {
                    Field silkField = binding.getClass().getField("layoutSilkEarn");
                    Object silkBinding = silkField.get(binding);
                    if (silkBinding != null) {
                        hideBindingField(silkBinding, "tvMakeProfit");
                        hideBindingField(silkBinding, "tvMakeProfitClaimBadge");
                    }
                } catch (Throwable ignored) {}
            }

            MainHook.log("Successfully purged unwanted user page views");
        } catch (Throwable t) {
            MainHook.log("Error in purgeUserViews: " + t);
        }
    }

    /**
     * 「我」页面活动行内的子项开关：文案 -> 开关 key。
     * 活动行内容由服务端下发、UserMenuModule 动态渲染，因此按文案在
     * layoutMenu 内定位对应子项，逐项隐藏。
     */
    private static final String[][] MENU_ITEMS = {
            {PurifyPrefs.USER_MENU_CHALLENGE, "挑战赛"},
            {PurifyPrefs.USER_MENU_LOTTERY, "免费抽奖"},
            {PurifyPrefs.USER_MENU_SECRET, "对接暗号"},
            {PurifyPrefs.USER_MENU_RAIN, "红包雨"},
            {PurifyPrefs.USER_MENU_ELIMINATE, "有奖消消乐"},
            {PurifyPrefs.USER_MENU_QUALIFY, "免单资格"},
    };

    /** 仅当活动行的全部子项都被勾选隐藏时，才整行隐藏（避免留下空行）。 */
    private static boolean allMenuItemsHidden() {
        for (String[] item : MENU_ITEMS) {
            if (!PurifyPrefs.hide(item[0])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 遍历 layoutMenu，把需要隐藏的子项单独置为 GONE（其余子项保留展示）。
     */
    private static void filterMenuItems(Object binding) {
        View root = getLayoutRoot(binding, "layoutMenu");
        if (root == null) {
            // binding 本身就是活动行时，直接用它的根视图
            root = getBindingRoot(binding);
        }
        if (root == null) return;
        for (String[] item : MENU_ITEMS) {
            if (!PurifyPrefs.hide(item[0])) continue;
            View target = findItemView(root, normalize(item[1]));
            if (target != null) {
                target.setVisibility(View.GONE);
                collapseLayoutParams(target);
                MainHook.log("Hidden user menu item: " + item[1]);
            }
        }
    }

    private static View getLayoutRoot(Object binding, String fieldName) {
        try {
            Field field = binding.getClass().getField(fieldName);
            Object layoutBinding = field.get(binding);
            if (layoutBinding == null) return null;
            Method getRoot = layoutBinding.getClass().getMethod("getRoot");
            return (View) getRoot.invoke(layoutBinding);
        } catch (Throwable t) {
            return null;
        }
    }

    private static View getBindingRoot(Object binding) {
        try {
            Method getRoot = binding.getClass().getMethod("getRoot");
            return (View) getRoot.invoke(binding);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在布局中查找"承载该文案的最小可点击子项"：先看直接子节点，命中即返回，
     * 否则递归下探，保证隐藏的是整项而不是其中一行文字。
     */
    private static View findItemView(View parent, String keyword) {
        if (!(parent instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (subtreeText(child).contains(keyword)) {
                return child;
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findItemView(group.getChildAt(i), keyword);
            if (found != null) return found;
        }
        return null;
    }

    private static String subtreeText(View view) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            return text == null ? "" : normalize(text.toString());
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < group.getChildCount(); i++) {
                sb.append(subtreeText(group.getChildAt(i)));
            }
            return sb.toString();
        }
        return "";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "");
    }

    /** 行内等分布局：隐藏子项后清掉权重，避免留下空白占位。 */
    private static void collapseLayoutParams(View view) {
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) lp).weight = 0f;
                view.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    private static void hookUserInfoModule(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> moduleClass = Class.forName("com.realtech.user.pages.userv2.UserInfoModule", false, cl);
            for (Method m : moduleClass.getDeclaredMethods()) {
                if ("render".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        Object module = chain.getThisObject();
                        if (module != null) {
                            try {
                                Field silkField = module.getClass().getField("silkBinding");
                                Object silkBinding = silkField.get(module);
                                if (silkBinding != null) {
                                    hideBindingField(silkBinding, "tvMakeProfit");
                                    hideBindingField(silkBinding, "tvMakeProfitClaimBadge");
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
                    MainHook.log("Hooked UserInfoModule.render (hidden tvMakeProfit & badge)");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook UserInfoModule: " + t);
        }
    }

    private static void hideBindingField(Object binding, String fieldName) {
        try {
            Field field = binding.getClass().getField(fieldName);
            View view = (View) field.get(binding);
            if (view != null) {
                view.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideBindingLayout(Object binding, String fieldName) {
        try {
            Field field = binding.getClass().getField(fieldName);
            Object layoutBinding = field.get(binding);
            if (layoutBinding != null) {
                Method getRoot = layoutBinding.getClass().getMethod("getRoot");
                View root = (View) getRoot.invoke(layoutBinding);
                if (root != null) {
                    root.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void hookUserModules(XposedInterface xposed, ClassLoader cl) {
        hookMenuModuleItems(xposed, cl);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserServiceModule", PurifyPrefs.USER_SERVICE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserBWCModule", PurifyPrefs.USER_MERCHANT);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserNoticeModule", PurifyPrefs.USER_NOTICE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserCommerceGridModule", PurifyPrefs.USER_COMMERCE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserOtherMatchModule", PurifyPrefs.USER_OTHER_MATCH);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserBqtModule", PurifyPrefs.USER_BQT);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.NewUserTipModule", PurifyPrefs.USER_NEW_USER_TIP);
    }

    /**
     * 活动行子项是按开关逐项过滤的：即使未开启「整行隐藏」，
     * 也要在 UserMenuModule 渲染完成后隐藏被勾选的子项。
     */
    private static void hookMenuModuleItems(XposedInterface xposed, ClassLoader cl) {
        boolean anyItemHidden = false;
        for (String[] item : MENU_ITEMS) {
            if (PurifyPrefs.hide(item[0])) {
                anyItemHidden = true;
                break;
            }
        }
        if (!anyItemHidden) return;
        try {
            Class<?> moduleClass = Class.forName("com.realtech.user.pages.userv2.UserMenuModule", false, cl);
            for (Method m : moduleClass.getDeclaredMethods()) {
                String mName = m.getName();
                if (!"render".equals(mName) && !"load".equals(mName) && !"initView".equals(mName)) continue;
                xposed.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    Object module = chain.getThisObject();
                    if (module != null) {
                        try {
                            Field bf = module.getClass().getField("binding");
                            Object binding = bf.get(module);
                            if (binding != null) filterMenuItems(binding);
                        } catch (Throwable ignored) {}
                    }
                    return result;
                });
            }
            MainHook.log("Hooked UserMenuModule for per-item filters");
        } catch (Throwable t) {
            MainHook.log("Failed to hook UserMenuModule items: " + t);
        }
    }

    private static void hookModuleRender(XposedInterface xposed, ClassLoader cl, String className, String prefKey) {
        if (!PurifyPrefs.hide(prefKey)) return;
        try {
            Class<?> moduleClass = Class.forName(className, false, cl);
            for (Method m : moduleClass.getDeclaredMethods()) {
                String mName = m.getName();
                if ("render".equals(mName) || "load".equals(mName) || "initView".equals(mName)) {
                    xposed.hook(m).intercept(chain -> {
                        Object module = chain.getThisObject();
                        if (module != null) {
                            try {
                                Field bf = module.getClass().getField("binding");
                                Object binding = bf.get(module);
                                if (binding != null) {
                                    Method getRoot = binding.getClass().getMethod("getRoot");
                                    View root = (View) getRoot.invoke(binding);
                                    if (root != null) {
                                        root.setVisibility(View.GONE);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
                    MainHook.log("Hooked " + className + "." + mName + " -> silenced");
                }
            }
        } catch (Throwable ignored) {}
    }
}
