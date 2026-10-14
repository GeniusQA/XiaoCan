package com.genius.xiaocanpurify;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 「我」页面按模块隐藏开关控制展示（上游基线）。
 *
 * 架构对齐上游：隐藏/展示在「状态层 + 模块层 + binding 层」控制——
 * 开关开启（隐藏）时拦截状态或静默模块；开关关闭（展示）时放行框架
 * 渲染。入口的逐项隐藏优先走状态层列表过滤（框架只渲染剩余项，
 * 状态与 UI 一致，避免渲染后逐项操作视图导致的渲染脱同步）。
 */
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

    /** 活动行入口「文案 -> 隐藏开关」。 */
    private static final String[][] MENU_ITEMS = {
            {PurifyPrefs.USER_MENU_CHALLENGE, "挑战赛"},
            {PurifyPrefs.USER_MENU_LOTTERY, "免费抽奖"},
            {PurifyPrefs.USER_MENU_SECRET, "对接暗号"},
            {PurifyPrefs.USER_MENU_RAIN, "红包雨"},
            {PurifyPrefs.USER_MENU_ELIMINATE, "有奖消消乐"},
            {PurifyPrefs.USER_MENU_QUALIFY, "免单资格"},
            {PurifyPrefs.USER_MENU_CHECKIN, "签到"},
    };

    /** 资产行子项「文案 -> 隐藏开关」。 */
    private static final String[][] ASSET_ITEMS = {
            {PurifyPrefs.USER_ASSET_YUANBAO, "元宝"},
            {PurifyPrefs.USER_ASSET_COUPON, "卡券"},
            {PurifyPrefs.USER_ASSET_REDPACKET, "红包"},
    };

    /** 活动行全部入口都勾选隐藏时返回 true（整行收起）。 */
    private static boolean allMenuItemsHidden() {
        for (String[] item : MENU_ITEMS) {
            if (!PurifyPrefs.hide(item[0])) {
                return false;
            }
        }
        return true;
    }

    /** 资产行全部子项都勾选隐藏时返回 true（整行收起）。 */
    private static boolean allAssetItemsHidden() {
        for (String[] item : ASSET_ITEMS) {
            if (!PurifyPrefs.hide(item[0])) {
                return false;
            }
        }
        return true;
    }

    /** 入口元素文本命中被隐藏开关的入口名时返回 true。 */
    private static boolean menuEntryShouldHide(String s) {
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
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_QUALIFY)
                    && s.contains("免单资格")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_CHECKIN)
                    && (s.contains("签到") || s.contains("签 到"))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 资产元素文本命中被隐藏开关时返回 true。 */
    private static boolean assetEntryShouldHide(String s) {
        if (s == null) return false;
        try {
            if (PurifyPrefs.hide(PurifyPrefs.USER_ASSET_YUANBAO)
                    && s.contains("元宝")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_ASSET_COUPON)
                    && s.contains("卡券")) return true;
            if (PurifyPrefs.hide(PurifyPrefs.USER_ASSET_REDPACKET)
                    && s.contains("红包")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 状态层列表过滤：活动行/资产行状态内的列表字段按隐藏开关剔除元素，
     * 框架只渲染剩余项。日志输出过滤前后摘要，便于核对判据命中情况。
     */
    private static void filterStateLists(Object state, String sName) {
        boolean menu = sName.contains("Menu") || sName.contains("Challenge");
        boolean asset = sName.contains("YuanBao");
        if (!menu && !asset) return;
        try {
            for (Class<?> c = state.getClass(); c != null && c != Object.class;
                    c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(state);
                    if (!(v instanceof List)) continue;
                    List list = (List) v;
                    if (list.isEmpty()) continue;
                    StringBuilder before = new StringBuilder();
                    java.util.Iterator probe = list.iterator();
                    int n = 0;
                    while (probe.hasNext() && n++ < 8) {
                        String s = String.valueOf(probe.next());
                        before.append('[').append(s.length() > 40
                                ? s.substring(0, 40) : s).append(']');
                    }
                    int removed = 0;
                    try {
                        java.util.Iterator it = list.iterator();
                        while (it.hasNext()) {
                            Object o = it.next();
                            String s = String.valueOf(o);
                            boolean drop = menu ? menuEntryShouldHide(s)
                                    : assetEntryShouldHide(s);
                            if (drop) {
                                it.remove();
                                removed++;
                            }
                        }
                    } catch (Throwable t) {
                        // Kotlin 只读列表不可 remove：重建过滤后的新列表并回写字段
                        java.util.ArrayList<Object> kept = new java.util.ArrayList<>();
                        for (Object o : list) {
                            String s = String.valueOf(o);
                            boolean drop = menu ? menuEntryShouldHide(s)
                                    : assetEntryShouldHide(s);
                            if (drop) {
                                removed++;
                            } else {
                                kept.add(o);
                            }
                        }
                        if (removed > 0) {
                            try {
                                f.set(state, kept);
                            } catch (Throwable t2) {
                                MainHook.log("StateFilter: cannot set list on "
                                        + f.getName() + ": " + t2);
                            }
                        }
                    }
                    if (removed > 0) {
                        MainHook.log("StateFilter: " + sName + "." + f.getName()
                                + " removed=" + removed + " left=" + list.size()
                                + " before=" + before);
                    } else {
                        MainHook.log("StateFilter probe: " + sName + "." + f.getName()
                                + " size=" + list.size() + " items=" + before);
                    }
                }
            }
        } catch (Throwable t) {
            MainHook.log("StateFilter error: " + t);
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
                            boolean hide = false;
                            if (sName.contains("Menu") || sName.contains("Challenge")) {
                                // 活动行：全部入口开关都开启（隐藏）时才整行拦截；
                                // 否则放行渲染，由状态层列表过滤剔除被隐藏的入口
                                hide = allMenuItemsHidden();
                            } else if (sName.contains("YuanBao")) {
                                hide = allAssetItemsHidden();
                            } else if (sName.contains("Service")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_SERVICE);
                            } else if (sName.contains("BWC")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_MERCHANT);
                            } else if (sName.contains("Notice")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_NOTICE);
                            } else if (sName.contains("NewUserTip")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_NEW_USER_TIP);
                            } else if (sName.contains("Commerce")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_COMMERCE);
                            } else if (sName.contains("BaiduAd")) {
                                hide = true;
                            } else if (sName.contains("MatchPlacement")) {
                                hide = PurifyPrefs.hide(PurifyPrefs.USER_OTHER_MATCH);
                            }
                            if (hide) {
                                MainHook.log("Filtered UserCenterViewState: " + sName);
                                return null;
                            }
                            filterStateLists(state, sName);
                        }
                        Object result = chain.proceed();
                        // 「订单」板块：无稳定 binding 字段，按文案锚点定位整卡
                        // 隐藏；宿主重渲染会复位，逐次渲染后重放（幂等，已
                        // GONE 的不重复操作）
                        if (PurifyPrefs.hide(PurifyPrefs.USER_ORDER)) {
                            try {
                                Object fragment = chain.getThisObject();
                                if (fragment != null) {
                                    Field bf = fragment.getClass().getField("binding");
                                    Object b = bf.get(fragment);
                                    if (b != null) hideUserOrderSection(b);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
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
            if (PurifyPrefs.hide(PurifyPrefs.USER_BQT)) {
                hideBindingField(binding, "bqtContainer");
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_COMMERCE)) {
                hideBindingField(binding, "commerceGridContainer");
            }
            // 「免单资格卡」开关：免单资格横幅（下方红色横幅卡）
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_QUALIFY)) {
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
            // 活动行：全部入口开关都开启（隐藏）时才整行收起
            if (allMenuItemsHidden()) {
                hideBindingLayout(binding, "layoutMenu");
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
        // 注意：UserMenuModule（活动行）不再静默——入口展示由 renderState
        // 状态层过滤 + 开关控制，静默会连显示态入口一起屏蔽
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserServiceModule",
                PurifyPrefs.USER_SERVICE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserBWCModule",
                PurifyPrefs.USER_MERCHANT);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserNoticeModule",
                PurifyPrefs.USER_NOTICE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserCommerceGridModule",
                PurifyPrefs.USER_COMMERCE);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserOtherMatchModule",
                PurifyPrefs.USER_OTHER_MATCH);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.UserBqtModule",
                PurifyPrefs.USER_BQT);
        hookModuleRender(xposed, cl, "com.realtech.user.pages.userv2.NewUserTipModule",
                PurifyPrefs.USER_NEW_USER_TIP);
    }

    private static void hookModuleRender(XposedInterface xposed, ClassLoader cl,
                                         String className, String prefKey) {
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

    /* ==================== 「订单」板块隐藏（文案锚点定位） ==================== */

    private static View getBindingRoot(Object binding) {
        try {
            Method getRoot = binding.getClass().getMethod("getRoot");
            return (View) getRoot.invoke(binding);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 隐藏「订单」板块（待上传/审核中/已驳回/已取消/全部订单图标行 +
     * 上传凭证提醒条）。该板块没有稳定的 binding 字段名，用文案锚点定位：
     * 优先爬升到页面根容器的直接子节点整卡 GONE（校验只含订单内容）；
     * 卡片与资产行等其它板块同层时，兜底按「不含其它板块关键词的最高祖先」
     * 定位订单卡片边界整卡隐藏，避免只藏子项后留下空白卡片壳。
     */
    private static void hideUserOrderSection(Object binding) {
        try {
            View root = getBindingRoot(binding);
            if (!(root instanceof ViewGroup)) return;
            ViewGroup rootGroup = (ViewGroup) root;

            java.util.Set<View> sections = new java.util.LinkedHashSet<>();
            for (String label : new String[]{"待上传", "全部订单"}) {
                View labelView = findLabelView(root, label);
                if (labelView != null) collectOrderSection(rootGroup, labelView, sections);
            }
            // 上传凭证提醒条（文案动态，用包含匹配）
            View notice = findTextContaining(root, "上传凭证");
            if (notice != null) collectOrderSection(rootGroup, notice, sections);

            if (sections.isEmpty()) {
                // 兜底：订单卡片与其它板块同处一个根级子节点，无法整块折叠根节点，
                // 此时爬升到不含其它板块关键词的最高祖先（订单卡片边界）整卡 GONE
                java.util.Set<View> cards = new java.util.LinkedHashSet<>();
                for (String label : new String[]{"待上传", "审核中", "已驳回", "已取消", "全部订单"}) {
                    View labelView = findLabelView(root, label);
                    if (labelView != null) collectOrderCard(rootGroup, labelView, cards);
                }
                View noticeBar = findTextContaining(root, "上传凭证");
                if (noticeBar != null) collectOrderCard(rootGroup, noticeBar, cards);
                for (View card : cards) {
                    card.setVisibility(View.GONE);
                }
                if (!cards.isEmpty()) {
                    MainHook.log("Hidden user order card fallback (" + cards.size() + " view(s))");
                }
                return;
            }
            for (View section : sections) {
                section.setVisibility(View.GONE);
            }
            MainHook.log("Hidden user order section (" + sections.size() + " view(s))");
        } catch (Throwable t) {
            MainHook.log("hideUserOrderSection error: " + t);
        }
    }

    /** 从锚点向上爬到 root 的直接子节点，校验子树确为订单板块后加入候选集合。 */
    private static void collectOrderSection(ViewGroup rootGroup, View anchor, java.util.Set<View> out) {
        View cur = anchor;
        while (cur.getParent() instanceof View && cur.getParent() != rootGroup) {
            cur = (View) cur.getParent();
        }
        if (cur.getParent() != rootGroup || cur == rootGroup) return;
        String text = subtreeText(cur);
        boolean looksLikeOrder = text.contains("全部订单") || text.contains("待上传") || text.contains("上传凭证");
        // 排除列表必须包含资产行关键词：订单行与资产行（元宝/卡券/红包）常在同一张卡片，
        // 否则隐藏订单板块会把资产行一起整块 GONE，资产子项开关全部关闭也无法恢复显示
        if (looksLikeOrder && !subtreeHasOtherSection(text) && cur.getVisibility() != View.GONE) {
            out.add(cur);
        }
    }

    /**
     * 兜底用：从锚点向上爬到「子树不含其它板块关键词」的最高祖先（即订单卡片边界）。
     * 一旦祖先子树出现资产行等其它板块文案立即停止，保证不误伤同卡的资产行。
     */
    private static void collectOrderCard(ViewGroup rootGroup, View anchor, java.util.Set<View> out) {
        View best = anchor;
        View cur = anchor;
        while (cur.getParent() instanceof View && cur.getParent() != rootGroup) {
            View parent = (View) cur.getParent();
            if (subtreeHasOtherSection(subtreeText(parent))) break;
            best = parent;
            cur = parent;
        }
        if (best != anchor && best.getVisibility() != View.GONE) {
            out.add(best);
        }
    }

    /** 订单板块排除文案：出现即说明子树混有资产行等其它板块，不能整块折叠。 */
    private static boolean subtreeHasOtherSection(String text) {
        String[] keys = {"可提现蚕豆", "累计团员", "信用极好", "免费抽奖", "元宝", "卡券", "红包"};
        for (String key : keys) {
            if (text.contains(key)) return true;
        }
        return false;
    }

    /** 查找文本包含关键词的第一个 TextView（区别于 findLabelView 的精确匹配）。 */
    private static View findTextContaining(View parent, String keyword) {
        if (parent instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) parent).getText();
            String text = normalize(cs == null ? "" : cs.toString());
            return text.contains(keyword) ? parent : null;
        }
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTextContaining(group.getChildAt(i), keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 查找文本精确等于关键词（或「关键词(计数)」）的 TextView。 */
    private static View findLabelView(View parent, String keyword) {
        if (parent instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) parent).getText();
            String text = normalize(cs == null ? "" : cs.toString());
            if (matchesAssetText(text, keyword)) return parent;
            return null;
        }
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findLabelView(group.getChildAt(i), keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean matchesAssetText(String text, String keyword) {
        if (text.equals(keyword)) return true;
        if (text.length() > keyword.length() && text.startsWith(keyword)) {
            char next = text.charAt(keyword.length());
            // 允许「元宝(1.3w)」「卡券（108）」这类带计数的写法
            return next == '(' || next == '（';
        }
        return false;
    }

    private static String subtreeText(View view) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            return text == null ? "" : normalize(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            StringBuilder sb = new StringBuilder();
            // 容器自身可能是富文本控件（Kuikly RichFontTextView 等）
            String own = reflectiveText(view);
            if (!own.isEmpty()) sb.append(own);
            for (int i = 0; i < group.getChildCount(); i++) {
                sb.append(subtreeText(group.getChildAt(i)));
            }
            return sb.toString();
        }
        return reflectiveText(view);
    }

    /** getText/getRichText 反射 Method 缓存（按类）：全树扫描高频调用需缓存。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Method>
            TEXT_METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Set<String> TEXT_METHOD_MISS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static Method textMethodOf(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        if (TEXT_METHOD_MISS.contains(key)) return null;
        try {
            Method m = cls.getMethod(name);
            TEXT_METHOD_CACHE.put(key, m);
            return m;
        } catch (Throwable t) {
            TEXT_METHOD_MISS.add(key);
            return null;
        }
    }

    /** 反射读取非 TextView 控件的文本：getText/getRichText/contentDescription。 */
    private static String reflectiveText(View view) {
        if (view == null) return "";
        for (String name : new String[]{"getText", "getRichText"}) {
            try {
                Method m = textMethodOf(view.getClass(), name);
                if (m == null) continue;
                Object t = m.invoke(view);
                if (t instanceof CharSequence) {
                    CharSequence cs = (CharSequence) t;
                    if (cs.length() > 0 && cs.length() <= 200) {
                        return normalize(cs.toString());
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            CharSequence cd = view.getContentDescription();
            if (cd != null && cd.length() > 0 && cd.length() <= 200) {
                return normalize(cd.toString());
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "");
    }
}
