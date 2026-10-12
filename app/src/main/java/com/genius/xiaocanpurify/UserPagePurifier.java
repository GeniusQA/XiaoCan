package com.genius.xiaocanpurify;

import android.view.View;
import android.view.ViewGroup;

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
                                    // 活动行/资产行：仅当全部子项开关都勾选隐藏时才整行拦截；
                                    // 否则必须放行渲染，由 filterMenuItems/filterAssetItems 逐项过滤，
                                    // 保证关掉单个开关后对应入口能恢复显示
                                    || (sName.contains("Challenge") && allMenuItemsHidden())
                                    || (sName.contains("YuanBao") && allAssetItemsHidden());
                            if (hide) {
                                MainHook.log("Filtered UserCenterViewState: " + sName);
                                return null;
                            }
                        }
                        Object result = chain.proceed();
                        // 渲染后重放子项过滤（活动行 + 资产行，计数等异步更新不影响已隐藏状态）
                        try {
                            Object fragment = chain.getThisObject();
                            if (fragment != null) {
                                Field bf = fragment.getClass().getField("binding");
                                Object b = bf.get(fragment);
                                if (b != null) {
                                    if (allMenuItemsHidden()) {
                                        hideBindingLayout(b, "layoutMenu");
                                    } else {
                                        filterMenuItems(b);
                                        collapseMenuIfEmpty(b);
                                    }
                                    filterAssetItems(b);
                                    if (PurifyPrefs.hide(PurifyPrefs.USER_ORDER)) {
                                        hideUserOrderSection(b);
                                    }
                                    if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_QUALIFY)) {
                                        hideFreeOrderQualifyCard(b);
                                    }
                                    // 重渲染后同样做单入口居中（对所有卡片生效）
                                    try {
                                        View root2 = getBindingRoot(b);
                                        if (root2 != null) centerSingleVisibleEntries(root2);
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
                collapseMenuIfEmpty(binding);
            }
            filterAssetItems(binding);
            if (PurifyPrefs.hide(PurifyPrefs.USER_ORDER)) {
                hideUserOrderSection(binding);
            }
            if (PurifyPrefs.hide(PurifyPrefs.USER_MENU_QUALIFY)) {
                hideFreeOrderQualifyCard(binding);
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

            // 净化后卡片内只剩一个可见入口时水平居中展示（对所有卡片生效）
            try {
                View root = getBindingRoot(binding);
                if (root != null) centerSingleVisibleEntries(root);
            } catch (Throwable ignored) {}

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
            {PurifyPrefs.USER_MENU_CHECKIN, "签到"},
    };

    /**
     * 资产行（元宝 / 卡券 / 红包）子项开关：文案 -> 开关 key。
     * 该行不在 layoutMenu 内，按整页根视图精确定位标签文本后隐藏所在子项。
     */
    private static final String[][] ASSET_ITEMS = {
            {PurifyPrefs.USER_ASSET_YUANBAO, "元宝"},
            {PurifyPrefs.USER_ASSET_COUPON, "卡券"},
            {PurifyPrefs.USER_ASSET_REDPACKET, "红包"},
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

    /** 仅当资产行（元宝/卡券/红包）的全部子项都被勾选隐藏时，才允许整行拦截。 */
    private static boolean allAssetItemsHidden() {
        for (String[] item : ASSET_ITEMS) {
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
            View target = findItemView(root, normalize(item[1]));
            if (target == null) continue;
            if (PurifyPrefs.hide(item[0])) {
                target.setVisibility(View.GONE);
                collapseLayoutParams(target);
                MainHook.log("Hidden user menu item: " + item[1]);
            } else if (target.getVisibility() == View.GONE) {
                // 开关已关闭但视图仍是历史隐藏态（宿主复用视图不会自动恢复）
                target.setVisibility(View.VISIBLE);
                restoreLayoutParams(target);
                MainHook.log("Restored user menu item: " + item[1]);
            }
        }
        // 开关从「全隐藏」退回部分隐藏时，恢复之前被 collapseMenuIfEmpty 收起的整行；
        // hasVisibleChild 校验避免覆盖宿主自身的隐藏意图
        if (root instanceof ViewGroup && root.getVisibility() == View.GONE
                && hasVisibleChild((ViewGroup) root)) {
            root.setVisibility(View.VISIBLE);
            MainHook.log("Restored layoutMenu after toggles off");
        }
        if (root instanceof ViewGroup) {
            centerSingleMenuEntry((ViewGroup) root);
        }
    }

    /** 被居中处理过的子项 -> 其直接父容器；入口数量恢复后按记录还原等分布局。 */
    private static final java.util.WeakHashMap<View, ViewGroup> CENTERED_CHILDREN = new java.util.WeakHashMap<>();
    /** 被居中子项挂的布局监听，还原时移除。 */
    private static final java.util.WeakHashMap<View, View.OnLayoutChangeListener> CENTERED_LISTENERS =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Integer> SAVED_WIDTHS = new java.util.WeakHashMap<>();

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
                    // RecyclerView 等容器：GONE 兄弟仍占位，可见项的布局位置（getLeft）
                    // 不为 0——必须以实际位置为基准平移。挂布局监听保持每次布局后都居中
                    final View fEntry = entry;
                    final ViewGroup fParent = parent;
                    View.OnLayoutChangeListener l = (v, l2, t2, r2, b2, ol2, ot2, or2, ob2) -> {
                        try {
                            if (!CENTERED_CHILDREN.containsKey(v)) return;
                            ViewGroup p = v.getParent() instanceof ViewGroup
                                    ? (ViewGroup) v.getParent() : null;
                            if (p == null) return;
                            int pw = p.getWidth();
                            int iw = v.getWidth();
                            if (pw > 0 && iw > 0) {
                                float tx = (pw - iw) / 2f - v.getLeft();
                                if (Math.abs(v.getTranslationX() - tx) > 0.5f) {
                                    v.setTranslationX(tx);
                                }
                            }
                        } catch (Throwable ignored) {}
                    };
                    fEntry.addOnLayoutChangeListener(l);
                    CENTERED_LISTENERS.put(fEntry, l);
                    CENTERED_CHILDREN.put(fEntry, fParent);
                    fParent.post(() -> {
                        try {
                            if (CENTERED_CHILDREN.containsKey(fEntry)) {
                                l.onLayoutChange(fEntry, 0, 0, 0, 0, 0, 0, 0, 0);
                            }
                        } catch (Throwable ignored) {}
                    });
                    MainHook.log("Centered single menu entry (recycler translate)");
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
     * 活动行过滤后若已没有任何可见子项（如服务端当期未下发未被隐藏的入口，
     * 或剩余入口的活动已结束），把整行收起，避免留下空白板块。
     * 只做「空则收起」，不反向强制显示，避免覆盖宿主自身的隐藏意图。
     */
    private static void collapseMenuIfEmpty(Object binding) {
        View root = getLayoutRoot(binding, "layoutMenu");
        // 仅在能精确定位 layoutMenu 时才收起；禁止兜底到整页根视图，
        // 否则页面早期渲染子视图未挂载时会把整个页面误隐藏
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        if (group.getVisibility() == View.GONE) return;
        if (!hasVisibleChild(group)) {
            group.setVisibility(View.GONE);
            MainHook.log("layoutMenu empty after filter: collapsed");
        }
    }

    /**
     * 隐藏「免单资格卡」——活动行下方的红色横幅卡
     * （「您有1笔订单待免单 今日邀1人得抽免单资格」）。
     * 该卡不在 layoutMenu 内，filterMenuItems 找不到它；且卡内含其它入口文案，
     * 通用最小单项下探只会藏到内部 TextView。改用卡片独有双关键词
     * （「订单待免单」+「今日邀」）定位同时包含两者的最小整卡。
     */
    private static void hideFreeOrderQualifyCard(Object binding) {
        View root = getBindingRoot(binding);
        if (!(root instanceof ViewGroup)) return;
        View card = findSmallestContainingAll((ViewGroup) root,
                new String[]{normalize("订单待免单"), normalize("今日邀")});
        if (card != null && card.getVisibility() != View.GONE) {
            card.setVisibility(View.GONE);
            MainHook.log("Hidden free-order qualify card");
        }
    }

    /** 查找同时包含全部关键词的最小子树（找不到更小命中时返回当前命中项）。 */
    private static View findSmallestContainingAll(ViewGroup parent, String[] keywords) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String text = subtreeText(child);
            boolean all = true;
            for (String keyword : keywords) {
                if (!text.contains(keyword)) {
                    all = false;
                    break;
                }
            }
            if (all && child instanceof ViewGroup) {
                View deeper = findSmallestContainingAll((ViewGroup) child, keywords);
                return deeper != null ? deeper : child;
            }
        }
        return null;
    }

    /**
     * 卡片内只剩一个可见入口时水平居中展示（对所有卡片生效）。
     * 递归整棵视图树：对水平方向的 LinearLayout，若可见子项恰好为 1 个
     * 且未被 weight 拉伸，则将其 layout_gravity 设为水平居中（保留原垂直对齐）。
     * 只改 gravity，不改尺寸与可见性，宿主重渲染后由 renderState 路径重放。
     */
    private static void centerSingleVisibleEntries(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group instanceof android.widget.LinearLayout
                    && ((android.widget.LinearLayout) group).getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
                View only = null;
                int visibleCount = 0;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE) {
                        visibleCount++;
                        only = child;
                        if (visibleCount > 1) break;
                    }
                }
                if (visibleCount == 1 && only != null) {
                    ViewGroup.LayoutParams lp = only.getLayoutParams();
                    if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                        android.widget.LinearLayout.LayoutParams llp = (android.widget.LinearLayout.LayoutParams) lp;
                        if (llp.weight == 0f
                                && (llp.gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                                        != android.view.Gravity.CENTER_HORIZONTAL) {
                            int vertical = llp.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
                            llp.gravity = android.view.Gravity.CENTER_HORIZONTAL | vertical;
                            only.setLayoutParams(llp);
                            only.requestLayout();
                        }
                    }
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                centerSingleVisibleEntries(group.getChildAt(i));
            }
        }
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

    private static View getBindingRoot(Object binding) {
        try {
            Method getRoot = binding.getClass().getMethod("getRoot");
            return (View) getRoot.invoke(binding);
        } catch (Throwable t) {
            return null;
        }
    }

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

    /**
     * 隐藏「我」页面订单板块：待上传/审核中/已驳回/已取消/全部订单 图标行 + 上传凭证提醒条。
     * 该板块没有稳定的 binding 字段名，用文案锚点定位：
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
                MainHook.log("Hidden user order card fallback (" + cards.size() + " view(s))");
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
        return subtreeHasOtherSection(text, null);
    }

    /**
     * 板块排除文案检查。keyword 非空时，与当前关键词互为子串的排除词豁免：
     * 「红包雨」子树必含「红包」、「元宝」子树必含「元宝」，
     * 否则会把入口自身误判成其它板块，导致永不隐藏或只藏文字留下图标。
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
            String text = normalize(((android.widget.TextView) parent).getText().toString());
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
            String text = normalize(((android.widget.TextView) parent).getText().toString());
            if (matchesAssetText(text, keyword)) return parent;
            return null;
        }
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
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

    /** 行内等分布局：隐藏子项后清掉权重，避免留下空白占位；原权重缓存供恢复用。 */
    private static final java.util.WeakHashMap<View, Float> SAVED_WEIGHTS = new java.util.WeakHashMap<>();

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
