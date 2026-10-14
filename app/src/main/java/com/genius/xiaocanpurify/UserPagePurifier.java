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

    /** StateFilter 保底日志节流（状态类名 -> 上次时间）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long>
            STATE_LOG_THROTTLE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 活动行入口「文案 -> 隐藏开关」。 */
    private static final String[][] MENU_ITEMS = {
            {PurifyPrefs.USER_MENU_CHALLENGE, "挑战赛"},
            {PurifyPrefs.USER_MENU_LOTTERY, "免费抽奖"},
            {PurifyPrefs.USER_MENU_SECRET, "对接暗号"},
            {PurifyPrefs.USER_MENU_RAIN, "红包雨"},
            {PurifyPrefs.USER_MENU_ELIMINATE, "有奖消消乐"},
            {PurifyPrefs.USER_MENU_QUALIFY, "免单资格"},
            {PurifyPrefs.USER_MENU_CHECKIN, "签到"},
            {PurifyPrefs.USER_MENU_TASK_CASH, "任务领现金"},
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
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_TASK_CASH)
                    && s.contains("任务领现金")) return true;
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
        // 保底日志（5 秒节流）：确认状态层过滤路径确实在运行
        try {
            String key = sName;
            Long last = STATE_LOG_THROTTLE.get(key);
            long now = android.os.SystemClock.uptimeMillis();
            if (last == null || now - last > 5000L) {
                STATE_LOG_THROTTLE.put(key, now);
                MainHook.log("StateFilter: enter " + sName
                        + " fields=" + state.getClass().getDeclaredFields().length);
            }
        } catch (Throwable ignored) {}
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
                        // 渲染后重放（幂等，已隐藏的不重复操作）：
                        // 1) 资产行（元宝/卡券/红包）——原生布局，逐项按开关过滤
                        // 2) 「订单」板块——无稳定 binding 字段，按文案锚点整卡隐藏
                        // 3) 「免单资格卡」横幅——宿主轮播重建会复现，逐次压回
                        try {
                            Object fragment = chain.getThisObject();
                            if (fragment != null) {
                                Field bf = fragment.getClass().getField("binding");
                                Object b = bf.get(fragment);
                                if (b != null) {
                                    filterAssetItems(b);
                                    if (PurifyPrefs.hide(PurifyPrefs.USER_ORDER)) {
                                        hideUserOrderSection(b);
                                    }
                                    if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_QUALIFY)) {
                                        hideBindingField(b, "bannerNotice");
                                        hideFreeOrderQualifyCard(b);
                                    }
                                    // 单入口嵌入蚕豆卡：活动行只剩 1 个可见入口时
                                    // 嵌入到「可提现蚕豆/余额」与「提现」按钮中间
                                    // （并收起活动行，不留空行）；多入口时自动拆除
                                    try {
                                        View layoutMenu = getLayoutRoot(b, "layoutMenu");
                                        if (layoutMenu instanceof ViewGroup) {
                                            embedSingleMenuEntryIntoBeanCard((ViewGroup) layoutMenu);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
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
            // 资产行（原生布局）：逐项按开关过滤（渲染后重放同路径）
            filterAssetItems(binding);
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

    /** 订单板块排除文案（不豁免）：出现即说明子树混有资产行等其它板块。 */
    private static boolean subtreeHasOtherSection(String text) {
        return subtreeHasOtherSection(text, null);
    }

    /**
     * 板块排除文案检查。keyword 非空时，与当前关键词互为子串的排除词豁免：
     * 「红包雨」子树必含「红包」、「元宝」子树必含「元宝」，
     * 否则会把入口自身误判成其它板块——资产行「元宝」会因此只藏掉文字
     * 留下图标（爬升被自身关键词中断）。
     */
    private static boolean subtreeHasOtherSection(String text, String keyword) {
        String[] keys = {"可提现蚕豆", "累计团员", "信用极好", "免费抽奖", "元宝", "卡券", "红包"};
        for (String key : keys) {
            if (keyword != null && !isExclusionKeyword(keyword, key)) continue;
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

    /**
     * 隐藏「免单资格卡」——活动行下方的红色横幅卡
     * （「您有1笔订单待免单 今日邀1人得抽免单资格」）。
     * 该卡不在 layoutMenu 内，通用最小单项下探只会藏到内部 TextView；
     * 且宿主轮播重建会复现，由 renderState 渲染后路径逐次幂等重放。
     */
    private static void hideFreeOrderQualifyCard(Object binding) {
        try {
            View root = getBindingRoot(binding);
            if (!(root instanceof ViewGroup)) return;
            View card = findSmallestContainingAll((ViewGroup) root,
                    new String[]{normalize("订单待免单"), normalize("今日邀")});
            if (card != null && card.getVisibility() != View.GONE) {
                card.setVisibility(View.GONE);
                MainHook.log("Hidden free-order qualify card");
            }
        } catch (Throwable t) {
            MainHook.log("hideFreeOrderQualifyCard error: " + t);
        }
    }

    /**
     * 查找同时包含全部关键词的最小子树。
     * 关键：先校验当前节点自身包含全部关键词再下探——两个关键词可能
     * 分属不同子 View（如横幅的两行文案在不同 TextView 里），
     * 只在「子节点同时包含全部」时才下探会因关键词分层而漏找。
     */
    private static View findSmallestContainingAll(ViewGroup parent, String[] keywords) {
        String self = subtreeText(parent);
        for (String k : keywords) {
            if (!self.contains(k)) return null;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                View deeper = findSmallestContainingAll((ViewGroup) child, keywords);
                if (deeper != null) return deeper;
            }
        }
        return parent;
    }

    /* ==================== 资产行（元宝/卡券/红包）UI 层逐项过滤 ==================== */
    // 资产行是原生 databinding 布局（标签「元宝(1.7w)」等），不经状态层——
    // 逐项隐藏必须在渲染后按开关对视图操作（幂等，已隐藏的不重复操作）

    /** 行内等分布局：隐藏子项后清掉权重，避免留下空白占位；原权重缓存供恢复用。 */
    private static final java.util.WeakHashMap<View, Float> SAVED_WEIGHTS =
            new java.util.WeakHashMap<>();

    /**
     * 隐藏资产行（元宝 / 卡券 / 红包）中被勾选的子项。
     * 标签文本是精确文案（可带括号计数，如「元宝(1.3w)」），
     * 命中后向上找最近的可点击父容器整体隐藏，避免只藏文字留下图标。
     */
    private static void filterAssetItems(Object binding) {
        View root = getBindingRoot(binding);
        if (root == null) return;
        for (String[] item : ASSET_ITEMS) {
            View label = findLabelView(root, item[1]);
            if (label == null) continue;
            // 向上爬到「子树不含其它入口文案」的最大安全容器：防止整行容器
            // 可点击时把元宝/卡券/红包同行其它入口一起隐藏
            View target = enclosingExclusiveContainer(label, item[1]);
            if (target == null) continue;
            if (PurifyPrefs.hide(item[0])) {
                if (target.getVisibility() != View.GONE) {
                    target.setVisibility(View.GONE);
                    // 清掉行内等分权重，让同行剩余入口重新铺满，UI 不留空洞
                    collapseLayoutParams(target);
                    MainHook.log("Hidden user asset item: " + item[1]);
                }
            } else if (target.getVisibility() == View.GONE) {
                // 开关已关闭但视图仍是历史隐藏态，恢复显示并还原权重
                target.setVisibility(View.VISIBLE);
                restoreLayoutParams(target);
                MainHook.log("Restored user asset item: " + item[1]);
            }
        }
    }

    /** 从命中节点向上爬到「子树不含其它入口文案」的最大安全容器。 */
    private static View enclosingExclusiveContainer(View node, String keyword) {
        View best = node;
        View cur = node;
        while (cur.getParent() instanceof View) {
            View parent = (View) cur.getParent();
            if (subtreeHasOtherEntries(parent, keyword)) break;
            best = parent;
            cur = parent;
        }
        return best;
    }

    /** 子树是否出现其它入口/板块文案（与当前关键词互为子串的除外）。 */
    private static boolean subtreeHasOtherEntries(View view, String keyword) {
        String text = subtreeText(view);
        if (text.isEmpty()) return false;
        for (String[] item : MENU_ITEMS) {
            if (isExclusionKeyword(keyword, item[1]) && text.contains(item[1])) return true;
        }
        for (String[] item : ASSET_ITEMS) {
            if (isExclusionKeyword(keyword, item[1]) && text.contains(item[1])) return true;
        }
        return subtreeHasOtherSection(text, keyword)
                || text.contains("全部订单") || text.contains("待上传");
    }

    /** 排除词与当前关键词互不为子串时才生效，避免「红包」误伤「红包雨」自身。 */
    private static boolean isExclusionKeyword(String keyword, String other) {
        return !keyword.contains(other) && !other.contains(keyword);
    }

    /** 清零视图的 LinearLayout weight（缓存原值供 restoreLayoutParams 还原）。 */
    private static void collapseLayoutParams(View view) {
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                android.widget.LinearLayout.LayoutParams llp = (android.widget.LinearLayout.LayoutParams) lp;
                if (!SAVED_WEIGHTS.containsKey(view)) SAVED_WEIGHTS.put(view, llp.weight);
                llp.weight = 0f;
                view.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    /** 恢复开关关闭前缓存的原有权重，让行内等分布局还原。 */
    private static void restoreLayoutParams(View view) {
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                Float weight = SAVED_WEIGHTS.get(view);
                if (weight != null) {
                    ((android.widget.LinearLayout.LayoutParams) lp).weight = weight;
                    view.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {}
    }

    /* ==================== 单入口嵌入蚕豆卡（fork 定制移植） ==================== */

    /** 反射设置 int 型字段（ConstraintLayout.LayoutParams 锚点定位用）。 */
    private static void setIntField(Object target, String name, int value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getField(name);
        f.setInt(target, value);
    }

    /**
     * 活动行只剩一个可见入口时水平居中展示。
     * 关键：操作对象是「入口的直接父容器」——layoutMenu 本身可能是外层容器，
     * 入口实际排在嵌套的横向 LinearLayout 里，对外层设 gravity 不生效。
     */
    private static void centerSingleMenuEntry(ViewGroup row) {
        int visibleEntries = 0;
        View entry = null;
        for (String[] item : MENU_ITEMS) {
            View v = findItemView(row, normalize(item[1]));
            if (v != null && v.getVisibility() == View.VISIBLE) {
                visibleEntries++;
                entry = v;
            }
        }
        if (visibleEntries == 1 && entry != null && !CENTERED_CHILDREN.containsKey(entry)) {
            ViewGroup parent = entry.getParent() instanceof ViewGroup
                    ? (ViewGroup) entry.getParent() : null;
            MainHook.log("CenterMenu diag: entry=" + entry.getClass().getSimpleName()
                    + " parent=" + (parent == null ? "null" : parent.getClass().getSimpleName())
                    + " siblings=" + (parent == null ? -1 : parent.getChildCount()));
            if (parent != null) {
                if (parent instanceof android.widget.LinearLayout) {
                    // 线性布局父容器：gravity 居中
                    try {
                        android.view.ViewGroup.LayoutParams lp = entry.getLayoutParams();
                        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                            android.widget.LinearLayout.LayoutParams llp =
                                    (android.widget.LinearLayout.LayoutParams) lp;
                            if (!SAVED_WEIGHTS.containsKey(entry)) SAVED_WEIGHTS.put(entry, llp.weight);
                            if (!SAVED_WIDTHS.containsKey(entry)) SAVED_WIDTHS.put(entry, llp.width);
                            llp.weight = 0f;
                            llp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                            entry.setLayoutParams(lp);
                            ((android.widget.LinearLayout) parent)
                                    .setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                            CENTERED_CHILDREN.put(entry, parent);
                            MainHook.log("Centered single menu entry (parent gravity)");
                        }
                    } catch (Throwable ignored) {}
                } else {
                    // RecyclerView 等自绘容器：不再做平移居中——实测对
                    // Kuikly 自绘视图持续 setTranslationX 会与其渲染管线
                    // 冲突（纹理失效空白但 hit-test 有效）。单入口场景
                    // 由嵌入蚕豆卡方案负责排版
                    MainHook.log("CenterMenu: recycler parent, skip translate centering");
                }
            }
        } else if (visibleEntries != 1 && !CENTERED_CHILDREN.isEmpty()) {
            // 入口恢复多个：还原之前被居中的子项（RecyclerView 路径复位平移，
            // LinearLayout 路径恢复权重/宽度与默认对齐）
            for (java.util.Map.Entry<View, ViewGroup> e : CENTERED_CHILDREN.entrySet()) {
                View child = e.getKey();
                ViewGroup parent = e.getValue();
                if (child == null || parent == null) continue;
                if (parent instanceof android.widget.LinearLayout) {
                    restoreLayoutParams(child);
                    Integer w = SAVED_WIDTHS.get(child);
                    if (w != null) {
                        android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
                        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                            ((android.widget.LinearLayout.LayoutParams) lp).width = w;
                            child.setLayoutParams(lp);
                        }
                    }
                    ((android.widget.LinearLayout) parent).setGravity(android.view.Gravity.START);
                }
                child.setTranslationX(0f);
                View.OnLayoutChangeListener l = CENTERED_LISTENERS.get(child);
                if (l != null) {
                    child.removeOnLayoutChangeListener(l);
                    CENTERED_LISTENERS.remove(child);
                }
                MainHook.log("Restored menu entry layout after entries back");
            }
            CENTERED_CHILDREN.clear();
        }
    }

    /**
     * 活动行只剩一个可见入口时，把入口嵌入上方「可提现蚕豆」卡中
     * 「可提现蚕豆/余额」与「提现」按钮之间的空位，并收起活动行。
     * 入口视图可能挂在 RecyclerView 内，直接 reparent 有回收崩溃风险，
     * 因此采用位图快照 + 点击代理（快照点击转调原入口 performClick）。
     *
     * @return true 表示已按嵌入方案处理（成功调度或已嵌入），调用方无需再做居中
     */
    private static boolean embedSingleMenuEntryIntoBeanCard(ViewGroup row) {
        int visibleEntries = 0;
        View entry = null;
        for (String[] item : MENU_ITEMS) {
            View v = findItemView(row, normalize(item[1]));
            if (v != null && v.getVisibility() == View.VISIBLE) {
                visibleEntries++;
                entry = v;
            }
        }
        if (visibleEntries != 1) {
            // 明细日志：各入口的查找结果与可见性（定位入口消失/未嵌入原因）
            StringBuilder detail = new StringBuilder();
            for (String[] item : MENU_ITEMS) {
                View v = findItemView(row, normalize(item[1]));
                detail.append(' ').append(item[1]).append('=')
                        .append(v == null ? "none"
                                : String.valueOf(v.getVisibility()));
            }
            MainHook.log("EmbedEntry: visibleEntries=" + visibleEntries
                    + " skip [" + detail + " ]");
            // 两个及以上入口可见：嵌入只适用于单入口，立即完全拆除并
            // 恢复活动行（容器链由 filterMenuItems 的祖先链恢复负责）
            if (visibleEntries >= 2 && embeddedSnapshotView != null) {
                MainHook.log("EmbedEntry: multiple entries visible, teardown embed");
                restoreEmbeddedEntry(row);
                return false;
            }
            // 嵌入激活时不要因「本轮找不到入口」拆除嵌入：宿主重渲染后
            // 入口异步重建，部分轮次渲染出来前是找不到的——拆除再重嵌
            // 会造成嵌入态/空白态每几秒来回跳（实测截图）。保持嵌入态，
            // 把容器/卡片壳重新压回 GONE 等入口重渲染；仅当用户把
            // 「免费抽奖」开关改为隐藏时才真正拆除
            if (embeddedSnapshotView != null) {
                boolean reset = false;
                if (embeddedRowView != null
                        && embeddedRowView.getVisibility() != View.GONE) {
                    embeddedRowView.setVisibility(View.GONE);
                    reset = true;
                }
                if (embeddedCardCollapsed && embeddedMenuRow != null
                        && embeddedMenuRow.getVisibility() != View.GONE) {
                    embeddedMenuRow.setVisibility(View.GONE);
                    reset = true;
                }
                if (embeddedEntryView != null
                        && embeddedEntryView.getVisibility() != View.GONE) {
                    embeddedEntryView.setVisibility(View.GONE);
                }
                boolean userHide = false;
                try {
                    userHide = PurifyPrefs.hide(PurifyPrefs.USER_MENU_LOTTERY);
                } catch (Throwable ignored) {}
                if (userHide) {
                    MainHook.log("EmbedEntry: lottery switch hidden, teardown");
                    restoreEmbeddedEntry(row);
                    return false;
                }
                if (reset) {
                    MainHook.log("EmbedEntry: keep embed state while entry re-rendering");
                }
                return true;
            }
            restoreEmbeddedEntry(row);
            return false;
        }
        if (entry == null) return false;
        if (embeddedSnapshotView != null && embeddedEntryView == entry) {
            // 宿主重渲染会不断复位活动行/原入口的可见性：全部重新压回隐藏态
            boolean reset = false;
            if (embeddedRowView != null && embeddedRowView.getVisibility() != View.GONE) {
                embeddedRowView.setVisibility(View.GONE);
                reset = true;
            }
            if (embeddedCardCollapsed && embeddedMenuRow != null
                    && embeddedMenuRow.getVisibility() != View.GONE) {
                embeddedMenuRow.setVisibility(View.GONE);
                reset = true;
            }
            if (embeddedEntryView != null && embeddedEntryView.getVisibility() != View.GONE) {
                embeddedEntryView.setVisibility(View.GONE);
                reset = true;
            }
            if (reset) MainHook.log("EmbedEntry: re-collapsed row & entry after host re-render");
            return true;
        }
        // 入口实例变化（宿主重渲染）：清旧快照后重嵌
        //（不恢复容器显示，避免重嵌完成前闪现一帧空白）
        restoreEmbeddedEntry(row, false);

        final View fEntry = entry;
        final ViewGroup fRow = row;
        row.post(() -> {
            try {
                if (!doEmbedSingleEntry(fEntry, fRow)) {
                    // 结构不满足（找不到提现按钮/容器非横向线性等）：退回居中
                    centerSingleMenuEntry(fRow);
                }
            } catch (Throwable t) {
                MainHook.log("EmbedEntry error: " + t);
                try {
                    centerSingleMenuEntry(fRow);
                } catch (Throwable ignored) {}
            }
        });
        return true;
    }

    /** 执行嵌入；返回 false 表示结构不满足需回退居中。 */
    private static boolean doEmbedSingleEntry(View entry, ViewGroup row) {
        if (entry.getWidth() <= 0 || entry.getHeight() <= 0) return false;
        if (entry.getParent() == null) return false;
        // 布局后宿主可能已再次调整入口数量，重查一遍
        int count = 0;
        for (String[] item : MENU_ITEMS) {
            View v = findItemView(row, normalize(item[1]));
            if (v != null && v.getVisibility() == View.VISIBLE) count++;
        }
        if (count != 1) return false;

        // 从活动行向上找到视图树顶端，定位「提现」按钮（精确文案，
        // 避免「可提现蚕豆」中的「提现」子串误匹配）
        View top = row;
        while (top.getParent() instanceof View) top = (View) top.getParent();
        if (!(top instanceof ViewGroup)) return false;
        View withdraw = findExactTextView((ViewGroup) top, "提现");
        if (withdraw == null || !(withdraw.getParent() instanceof ViewGroup)) {
            MainHook.log("EmbedEntry: withdraw button not found, fallback");
            return false;
        }
        // 从提现按钮向上找同时承载「可提现蚕豆」左列的行容器；
        // btnWrap 记录提现按钮在该行容器内的祖先子项
        View btnWrap = withdraw;
        View rowC = null;
        while (btnWrap.getParent() instanceof View) {
            View p = (View) btnWrap.getParent();
            if (subtreeText(p).contains(normalize("可提现蚕豆"))) {
                rowC = p;
                break;
            }
            btnWrap = p;
        }
        if (!(rowC instanceof ViewGroup)) {
            MainHook.log("EmbedEntry: bean card row not found, fallback");
            return false;
        }
        ViewGroup slot = (ViewGroup) rowC;
        boolean linear = slot instanceof android.widget.LinearLayout
                && ((android.widget.LinearLayout) slot).getOrientation()
                        == android.widget.LinearLayout.HORIZONTAL;
        boolean constraint = slot.getClass().getName().contains("ConstraintLayout");
        if (!linear && !constraint) {
            MainHook.log("EmbedEntry: unsupported slot container ("
                    + slot.getClass().getSimpleName() + "), fallback");
            return false;
        }

        // 先清历史居中平移，避免历史变换影响重建视图
        View.OnLayoutChangeListener oldL = CENTERED_LISTENERS.get(entry);
        if (oldL != null) {
            entry.removeOnLayoutChangeListener(oldL);
            CENTERED_LISTENERS.remove(entry);
        }
        CENTERED_CHILDREN.remove(entry);
        entry.setTranslationX(0f);

        // 取「可提现蚕豆」左列 logo 的实际尺寸，嵌入入口的 logo 与其同尺寸。
        // 列内可能没有已测量的 ImageView（logo 由 Kuikly 自绘或尚未布局）——
        // 测量尺寸为 0 时回退读 LayoutParams；仍取不到时整卡扫描并加
        // 32dp 上限（整卡最大图可能是提现按钮背景等大图，实测偏大）
        int beanIconSize = 0;
        for (int i = 0; i < slot.getChildCount(); i++) {
            View c = slot.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE
                    && subtreeText(c).contains(normalize("可提现蚕豆"))) {
                beanIconSize = estimateIconSize(c);
                break;
            }
        }
        if (beanIconSize == 0) {
            beanIconSize = estimateIconSize(slot);
            int cap = dp(entry, 32);
            if (beanIconSize > cap) beanIconSize = cap;
        }
        MainHook.log("EmbedEntry: beanIconSize=" + beanIconSize);

        // 从原入口提取图标与文字，重建紧凑横排视图（高度约 28dp，
        // 天然放得进蚕豆卡原有空位，不改动任何容器尺寸，避免布局重叠）
        View inline = buildInlineEntryView(slot.getContext(), entry, beanIconSize);
        if (inline == null) {
            MainHook.log("EmbedEntry: cannot build inline view, fallback");
            return false;
        }
        inline.setOnClickListener(v -> {
            try {
                View target = resolveEmbeddedEntryTarget(entry);
                if (target == null) return;
                // Kuikly 自绘入口的点击语义挂在触摸层（onTouchEvent）上，
                // 直接 performClick 往往不生效（原入口被 GONE 后其 OnClickListener
                // 链路也可能已失效）。
                // 实测结论：
                // 1) 直接向入口投递（局部中心点坐标）事件被消费但不导航
                //    —— 语义挂在入口上方的行容器层；
                // 2) 向窗口根视图按绝对坐标投递会跳错页面 —— 原入口被 GONE
                //    后其子树布局冻结，getLocationOnScreen 是过期屏幕坐标，
                //    与当前滚动位置有偏差，命中了别的可点视图。
                // 因此只在冻结子树内部（入口 → 活动行根）逐层投递，每层把
                // 入口中心点映射为该层局部坐标：相对偏移在同一冻结快照内
                // 自洽，不依赖绝对坐标。超出活动行以上的祖先（RecyclerView、
                // 页面容器等）已随滚动重排，坐标失效，禁止投递。
                // 投递前临时恢复隐藏链（GONE 子树会被命中测试跳过）。
                java.util.List<View> revealed = new java.util.ArrayList<>();
                java.util.List<Integer> prevVis = new java.util.ArrayList<>();
                try {
                    View rv = target;
                    while (rv != null && !rv.isShown()) {
                        if (rv.getVisibility() != View.VISIBLE) {
                            revealed.add(rv);
                            prevVis.add(rv.getVisibility());
                            rv.setVisibility(View.VISIBLE);
                        }
                        rv = rv.getParent() instanceof View ? (View) rv.getParent() : null;
                    }
                    View rowTop = embeddedMenuRow != null ? embeddedMenuRow : embeddedRowView;
                    if (rowTop == null || rowTop.getWidth() <= 0 || rowTop.getHeight() <= 0) {
                        rowTop = target;
                    }
                    // 实测：仅靠冻结边界做坐标映射会命中落空（consumed=false）
                    // ——宿主 Kuikly 反复重渲染会使 GONE 子树的边界漂移失效。
                    // 先对活动行根做一次同步测量+布局刷新，使恢复 VISIBLE 的
                    // 子树获得全新的自洽边界，再做坐标映射与投递。
                    try {
                        int ws = View.MeasureSpec.makeMeasureSpec(
                                rowTop.getWidth(), View.MeasureSpec.EXACTLY);
                        int hs = View.MeasureSpec.makeMeasureSpec(
                                rowTop.getHeight(), View.MeasureSpec.EXACTLY);
                        rowTop.measure(ws, hs);
                        rowTop.layout(rowTop.getLeft(), rowTop.getTop(),
                                rowTop.getLeft() + rowTop.getWidth(),
                                rowTop.getTop() + rowTop.getHeight());
                    } catch (Throwable t) {
                        MainHook.log("EmbedEntry: relayout refresh failed: " + t);
                    }
                    View cur = target;
                    float px = target.getWidth() / 2f;
                    float py = target.getHeight() / 2f;
                    // 诊断：记录入口 → 活动行根 每层的边界、可点击性与监听器，
                    // 以及入口中心在行根坐标系下的映射结果
                    StringBuilder diag = new StringBuilder();
                    View clickTarget = null;
                    while (cur != null && cur.getWidth() > 0 && cur.getHeight() > 0) {
                        diag.append(cur.getClass().getSimpleName())
                                .append('[').append(cur.getLeft()).append(',')
                                .append(cur.getTop()).append(' ')
                                .append(cur.getWidth()).append('x')
                                .append(cur.getHeight())
                                .append(cur.isClickable() ? " C" : "")
                                .append(cur.hasOnClickListeners() ? " L" : "")
                                .append(cur.isShown() ? "" : " H")
                                .append(']');
                        if (clickTarget == null && cur.hasOnClickListeners()) {
                            clickTarget = cur;
                        }
                        if (cur == rowTop) break;
                        px += cur.getLeft() + cur.getTranslationX() - cur.getScrollX();
                        py += cur.getTop() + cur.getTranslationY() - cur.getScrollY();
                        cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                    }
                    MainHook.log("EmbedEntry: chain " + diag
                            + " -> rowPoint(" + (int) px + "," + (int) py + ")");
                    // 优先原生点击监听：链上最近的 OnClickListener 最具体，
                    // performClick 直接触发，无坐标依赖
                    if (clickTarget != null) {
                        MainHook.log("EmbedEntry: click -> performClick on "
                                + clickTarget.getClass().getSimpleName());
                        clickTarget.performClick();
                        return;
                    }
                    if (cur == rowTop && cur.getWidth() > 0 && cur.getHeight() > 0) {
                        // 单次从活动行根投递：dispatchTouchEvent 会先跑行根自身的
                        // 触摸监听，再沿子树逐层命中测试到入口——子树内所有层级
                        // 的语义处理器（最外层返回 true 的那个）恰好触发一次，
                        // 不会重复导航
                        boolean consumed = dispatchSyntheticTapAt(rowTop, px, py);
                        MainHook.log("EmbedEntry: click -> tap at (" + (int) px + ","
                                + (int) py + ") on " + rowTop.getClass().getSimpleName()
                                + " consumed=" + consumed);
                        return;
                    }
                    MainHook.log("EmbedEntry: click -> row top not reachable, fallback");
                } finally {
                    for (int i = 0; i < revealed.size(); i++) {
                        try {
                            revealed.get(i).setVisibility(prevVis.get(i));
                        } catch (Throwable ignored) {}
                    }
                }
                // 回退：标准 View 入口，转调点击处理（可能挂在其容器/条目上）
                View cur = target;
                for (int i = 0; i < 8 && cur != null; i++) {
                    if (cur.hasOnClickListeners()) {
                        MainHook.log("EmbedEntry: click -> "
                                + cur.getClass().getSimpleName()
                                + (cur == target ? " (entry)" : " (ancestor)"));
                        cur.performClick();
                        return;
                    }
                    cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                }
                MainHook.log("EmbedEntry: click -> no click handler on entry chain");
            } catch (Throwable t) {
                MainHook.log("EmbedEntry: click error: " + t);
            }
        });

        android.view.ViewGroup.LayoutParams slp;
        int m = dp(slot, 12);
        if (linear) {
            android.widget.LinearLayout.LayoutParams llp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.gravity = android.view.Gravity.CENTER_VERTICAL;
            llp.leftMargin = m;
            llp.rightMargin = m;
            slp = llp;
            slot.addView(inline, slot.indexOfChild(btnWrap), slp);
        } else {
            // ConstraintLayout：不依赖 view id（Kuikly 视图常无 id）。
            // 嵌入视图锚定父容器左上角（确定性原点），位置由 translation 动态控制：
            // 水平放在左列之后、提现按钮之前，垂直与左列居中对齐。
            View leftCol = null;
            for (int i = 0; i < slot.getChildCount(); i++) {
                View c = slot.getChildAt(i);
                if (c != btnWrap && c.getVisibility() == View.VISIBLE
                        && subtreeText(c).contains(normalize("可提现蚕豆"))) {
                    leftCol = c;
                    break;
                }
            }
            if (leftCol == null || btnWrap.getParent() != slot) {
                MainHook.log("EmbedEntry: constraint anchors missing, fallback");
                return false;
            }
            try {
                Class<?> clpClass = Class.forName(
                        "androidx.constraintlayout.widget.ConstraintLayout$LayoutParams",
                        true, slot.getClass().getClassLoader());
                Object clp = clpClass.getConstructor(int.class, int.class).newInstance(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                setIntField(clp, "leftToLeft", 0);   // ConstraintLayout.PARENT_ID = 0
                setIntField(clp, "topToTop", 0);
                slot.addView(inline, slot.getChildCount(),
                        (android.view.ViewGroup.LayoutParams) clp);
                embeddedSlotView = slot;
                embeddedLeftColView = leftCol;
                embeddedBtnWrapView = btnWrap;
                View.OnLayoutChangeListener sl = (v, l, t, r, b, ol, ot, or2, ob) ->
                        positionEmbeddedSnapshot();
                slot.addOnLayoutChangeListener(sl);
                embeddedSlotListener = sl;
                slot.post(() -> positionEmbeddedSnapshot());
            } catch (Throwable t) {
                MainHook.log("EmbedEntry: constraint params failed (" + t + "), fallback");
                return false;
            }
        }

        // 原入口一并隐藏：宿主不再复位行可见性（与全隐藏收起的稳定路径一致）
        entry.setVisibility(View.GONE);
        // 只收起承载入口的子容器：layoutMenu 可能同时承载资产行（元宝/卡券/红包），
        // 整个 layoutMenu GONE 会把资产行连带藏掉——向上爬到不含资产行关键词的最高层
        ViewGroup collapseTarget = null;
        View cur = entry;
        while (cur != row && cur != null) {
            View p = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            if (!(p instanceof ViewGroup)) break;
            String t = subtreeText(p);
            if (t.contains("元宝") || t.contains("卡券") || t.contains("红包")) break;
            collapseTarget = (ViewGroup) p;
            cur = p;
        }
        if (collapseTarget == null) {
            collapseTarget = entry.getParent() instanceof ViewGroup
                    ? (ViewGroup) entry.getParent() : null;
        }
        if (collapseTarget != null) {
            collapseTarget.setVisibility(View.GONE);
        }
        // 实测：收起入口子容器后，活动行卡片壳（row=MaterialCardView）仍以
        // 固定高度渲染为空白卡，加上卡片间隙形成截图中的「灰线+空白」。
        // 若卡片壳内已无可见资产行内容，把壳一并 GONE。
        embeddedCardCollapsed = false;
        if (row.getVisibility() == View.VISIBLE && !rowHasVisibleAssetContent(row)) {
            row.setVisibility(View.GONE);
            embeddedCardCollapsed = true;
            MainHook.log("EmbedEntry: empty menu card shell collapsed");
        }
        embeddedSnapshotView = inline;
        embeddedEntryView = entry;
        embeddedRowView = collapseTarget;
        embeddedMenuRow = row;
        MainHook.log("EmbedEntry: compact entry embedded into bean card, row collapsed");
        return true;
    }

    /** 活动行卡片壳内是否存在可见的资产行内容（有则不能收起壳）。 */
    private static boolean rowHasVisibleAssetContent(ViewGroup row) {
        for (String[] item : ASSET_ITEMS) {
            View lbl = findLabelView(row, item[1]);
            if (lbl == null) lbl = findTextContaining(row, item[1]);
            if (lbl != null && lbl.isShown()) return true;
        }
        return false;
    }

    /**
     * 定位当前真正承载跳转的入口视图：
     * 1) 优先原入口（仍挂在视图树上时）；
     * 2) 实例已被宿主重渲染更换时，按开关找「未被隐藏」的当前入口——
     *    不能直接取第一个找到的视图，那可能是被隐藏的其它入口（点了没反应）。
     */
    private static View resolveEmbeddedEntryTarget(View fallback) {
        if (embeddedEntryView != null && embeddedEntryView.getParent() != null) {
            return embeddedEntryView;
        }
        ViewGroup rowRef = embeddedMenuRow;
        if (rowRef != null) {
            for (String[] item : MENU_ITEMS) {
                if (PurifyPrefs.hide(item[0])) continue;
                View v = findItemView(rowRef, normalize(item[1]));
                if (v != null) return v;
            }
        }
        return fallback;
    }

    /**
     * 在视图局部坐标 (x, y) 处向 target 投递一次合成点击。
     * 完整 DOWN → MOVE → UP 序列：部分手势识别器依赖 MOVE 才判定为 tap。
     */
    private static boolean dispatchSyntheticTapAt(View target, float x, float y) {
        if (target == null) return false;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent move = android.view.MotionEvent.obtain(
                now, now + 40L, android.view.MotionEvent.ACTION_MOVE, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                now, now + 90L, android.view.MotionEvent.ACTION_UP, x, y, 0);
        boolean consumed;
        try {
            consumed = target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(move);
            target.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            move.recycle();
            up.recycle();
        }
        return consumed;
    }

    /**
     * 用原入口的图标与文字重建紧凑横排视图（不改任何容器尺寸）。
     * iconSizePx > 0 时 logo 使用该尺寸（与左侧「可提现蚕豆」logo 同尺寸），
     * 否则退回 20dp。提取不到文本时退化为按 28dp 高度等比缩小的入口位图。
     */
    private static View buildInlineEntryView(android.content.Context ctx, View entry,
                                             int iconSizePx) {
        android.widget.ImageView srcIcon = findFirstImageView(entry);
        android.widget.TextView srcText = findFirstTextView(entry);
        android.graphics.drawable.Drawable iconDrawable = null;
        if (srcIcon != null) {
            iconDrawable = srcIcon.getDrawable() != null
                    ? srcIcon.getDrawable() : srcIcon.getBackground();
        } else if (srcText != null) {
            for (android.graphics.drawable.Drawable d : srcText.getCompoundDrawables()) {
                if (d != null) {
                    iconDrawable = d;
                    break;
                }
            }
        }
        if (srcText == null && iconDrawable == null) {
            try {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        entry.getWidth(), entry.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
                entry.draw(new android.graphics.Canvas(bmp));
                int h = dp(entry, 28);
                int w = Math.max(1, Math.round(
                        entry.getWidth() * (h / (float) Math.max(1, entry.getHeight()))));
                android.widget.ImageView iv = new android.widget.ImageView(ctx);
                iv.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true));
                return iv;
            } catch (Throwable t) {
                return null;
            }
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (iconDrawable != null) {
            android.widget.ImageView iv = new android.widget.ImageView(ctx);
            iv.setImageDrawable(iconDrawable);
            int isz = iconSizePx > 0 ? iconSizePx : dp(entry, 20);
            android.widget.LinearLayout.LayoutParams ip =
                    new android.widget.LinearLayout.LayoutParams(isz, isz);
            ip.rightMargin = dp(entry, 4);
            box.addView(iv, ip);
        }
        if (srcText != null) {
            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(srcText.getText());
            tv.setTextColor(srcText.getCurrentTextColor());
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, srcText.getTextSize());
            tv.setIncludeFontPadding(false);
            box.addView(tv);
        }
        return box;
    }

    /** 估算区域内最大图标尺寸：取 ImageView 的测量尺寸，为 0 时读
     *  LayoutParams（Kuikly 布局可能尚未测量，params 仍保留目标尺寸）。 */
    private static int estimateIconSize(View parent) {
        if (parent instanceof android.widget.ImageView) {
            return iconSizeOf((android.widget.ImageView) parent);
        }
        if (parent instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) parent;
            int best = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                int s = estimateIconSize(g.getChildAt(i));
                if (s > best) best = s;
            }
            return best;
        }
        return 0;
    }

    private static int iconSizeOf(android.widget.ImageView iv) {
        if (iv.getVisibility() == View.VISIBLE
                && iv.getWidth() > 0 && iv.getHeight() > 0) {
            return Math.min(iv.getWidth(), iv.getHeight());
        }
        android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
        if (lp != null && lp.width > 0 && lp.height > 0) {
            return Math.min(lp.width, lp.height);
        }
        return 0;
    }

    /** 深度优先找第一个 ImageView。 */
    private static android.widget.ImageView findFirstImageView(View parent) {
        if (parent instanceof android.widget.ImageView) {
            return (android.widget.ImageView) parent;
        }
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ImageView found = findFirstImageView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 深度优先找第一个非空文本 TextView。 */
    private static android.widget.TextView findFirstTextView(View parent) {
        if (parent instanceof android.widget.TextView
                && ((android.widget.TextView) parent).getText() != null
                && ((android.widget.TextView) parent).getText().length() > 0) {
            return (android.widget.TextView) parent;
        }
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findFirstTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 把嵌入视图定位到左列之后、提现按钮之前的空位（与左列垂直居中对齐）。 */
    private static void positionEmbeddedSnapshot() {
        try {
            if (embeddedSnapshotView == null || embeddedSlotView == null
                    || embeddedLeftColView == null || embeddedBtnWrapView == null) return;
            ViewGroup slot = embeddedSlotView;
            if (slot.getWidth() <= 0) return;
            int iw = embeddedSnapshotView.getWidth();
            int ih = embeddedSnapshotView.getHeight();
            if (iw <= 0 || ih <= 0) return;
            // 在左列与提现按钮之间的空位内水平居中展示
            float gapL = embeddedLeftColView.getRight() + dp(embeddedSnapshotView, 8);
            float gapR = embeddedBtnWrapView.getLeft() - dp(embeddedSnapshotView, 8);
            float tx = gapL + (gapR - gapL - iw) / 2f;
            if (tx > gapR - iw) tx = gapR - iw;
            if (tx < gapL) tx = gapL;
            float ty = embeddedLeftColView.getTop()
                    + (embeddedLeftColView.getHeight() - ih) / 2f;
            if (Math.abs(embeddedSnapshotView.getTranslationX() - tx) > 0.5f
                    || Math.abs(embeddedSnapshotView.getTranslationY() - ty) > 0.5f) {
                embeddedSnapshotView.setTranslationX(tx);
                embeddedSnapshotView.setTranslationY(ty);
            }
        } catch (Throwable ignored) {}
    }

    /** 撤销嵌入：移除嵌入视图、恢复活动行与原入口显示。 */
    private static void restoreEmbeddedEntry(ViewGroup row) {
        restoreEmbeddedEntry(row, true);
    }

    /**
     * 撤销嵌入。showContainers=false 用于「拆除后立即重嵌」的场景：
     * 不把活动行容器/卡片壳恢复显示，避免重嵌完成前闪现一帧空白。
     */
    private static void restoreEmbeddedEntry(ViewGroup row, boolean showContainers) {
        if (embeddedSnapshotView == null) return;
        try {
            ViewGroup p = embeddedSnapshotView.getParent() instanceof ViewGroup
                    ? (ViewGroup) embeddedSnapshotView.getParent() : null;
            if (p != null) p.removeView(embeddedSnapshotView);
            if (embeddedSlotView != null && embeddedSlotListener != null) {
                embeddedSlotView.removeOnLayoutChangeListener(embeddedSlotListener);
            }
        } catch (Throwable ignored) {}
        embeddedSnapshotView = null;
        if (embeddedEntryView != null) {
            // 恢复原入口显示（嵌入期间被隐藏）
            embeddedEntryView.setVisibility(View.VISIBLE);
        }
        embeddedEntryView = null;
        embeddedSlotView = null;
        embeddedLeftColView = null;
        embeddedBtnWrapView = null;
        embeddedSlotListener = null;
        ViewGroup r = embeddedRowView != null ? embeddedRowView : row;
        if (showContainers && r != null && r.getVisibility() == View.GONE
                && hasVisibleChild(r)) {
            r.setVisibility(View.VISIBLE);
            MainHook.log("EmbedEntry: menu row restored");
        }
        // 恢复被连带收起的活动行卡片壳
        ViewGroup shell = embeddedMenuRow;
        if (showContainers && embeddedCardCollapsed && shell != null
                && shell.getVisibility() == View.GONE) {
            shell.setVisibility(View.VISIBLE);
            MainHook.log("EmbedEntry: menu card shell restored");
        }
        embeddedCardCollapsed = false;
        embeddedMenuRow = null;
        embeddedRowView = null;
    }

    /** 查找文本精确等于关键词的 TextView（深度优先）。 */
    private static View findExactTextView(ViewGroup parent, String keyword) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof android.widget.TextView
                    && normalize(((android.widget.TextView) child).getText().toString()).equals(keyword)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findExactTextView((ViewGroup) child, keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean hasVisibleChild(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() == View.VISIBLE) return true;
        }
        return false;
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

    /**
     * 在布局中查找"只承载该文案的最小子项"：命中节点若还包含其它入口文案，
     * 说明它是整行容器而非单项，继续向内下探，保证隐藏的是单项而不是整行。
     */
    private static View findItemView(View parent, String keyword) {
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) parent;
        // 第一轮：找子树只含目标文案（不含其它入口文案）的直接子项
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (subtreeText(child).contains(keyword) && !subtreeHasOtherEntries(child, keyword)) {
                return child;
            }
        }
        // 第二轮：命中子项内部继续下探找最小单项
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (subtreeText(child).contains(keyword)) {
                View found = findItemView(child, keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 被居中处理过的子项 -> 其直接父容器；入口数量恢复后按记录还原等分布局。 */
    private static final java.util.WeakHashMap<View, ViewGroup> CENTERED_CHILDREN = new java.util.WeakHashMap<>();

    /** 被居中子项挂的布局监听，还原时移除。 */
    private static final java.util.WeakHashMap<View, View.OnLayoutChangeListener> CENTERED_LISTENERS =
            new java.util.WeakHashMap<>();

    private static final java.util.WeakHashMap<View, Integer> SAVED_WIDTHS = new java.util.WeakHashMap<>();

    /** 嵌入的紧凑入口视图（原入口图标+文字的横排重建）。 */
    private static View embeddedSnapshotView;

    /** 被快照的原始入口（宿主重渲染后实例变化时需重新嵌入）。 */
    private static View embeddedEntryView;

    /** 被收起的活动行（还原时恢复显示）。 */
    private static ViewGroup embeddedRowView;

    /** 承载嵌入视图的槽位/锚点视图（ConstraintLayout 平移定位用）。 */
    private static ViewGroup embeddedSlotView;

    private static View embeddedLeftColView;

    private static View embeddedBtnWrapView;

    private static View.OnLayoutChangeListener embeddedSlotListener;

    /** 活动行根视图（点击时重新定位当前入口实例，宿主重渲染会更换实例）。 */
    private static ViewGroup embeddedMenuRow;

    /** 活动行卡片壳是否已被连带收起（壳内无可见资产行内容时）。 */
    private static boolean embeddedCardCollapsed;

}
