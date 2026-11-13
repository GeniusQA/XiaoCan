package com.genius.xiaocanpurify;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Hook-side reader of module settings.
 *
 * Values are written by the module UI (MainActivity) into libxposed
 * RemotePreferences and read here inside the target process. When remote
 * preferences are unavailable (older LSPosed / test environment) the
 * defaults preserve the historical behaviour: every toggle defaults to
 * "hide" so purification stays fully active.
 */
public final class PurifyPrefs {
    public static final String PREFS_NAME = "purify_settings";

    // Bottom navigation bar
    public static final String TAB_VIP = "hide_tab_vip";
    public static final String TAB_BENEFIT = "hide_tab_benefit";
    public static final String TAB_PLACEMENT = "hide_tab_placement";

    // Home page
    public static final String HOME_TOP_BANNER = "hide_home_top_banner";
    public static final String HOME_MENU_ROW = "hide_home_menu_row";
    public static final String HOME_STORE_CARD = "hide_home_store_card";
    public static final String HOME_SEARCH_BANNER = "hide_home_search_banner";
    public static final String HOME_FAB = "hide_home_fab";
    public static final String HOME_BOTTOM_PLACEMENT = "hide_home_bottom_placement";
    public static final String HOME_ORDER_BANNER = "hide_home_order_banner";
    public static final String HOME_SECOND_FLOOR = "hide_home_second_floor";

    // User page
    // 「挑战赛 / 免费抽奖」活动行内的子项开关（各自独立）
    public static final String USER_MENU_CHALLENGE = "hide_user_menu_challenge";
    public static final String USER_MENU_LOTTERY = "hide_user_menu_lottery";
    public static final String USER_MENU_SECRET = "hide_user_menu_secret";
    public static final String USER_MENU_RAIN = "hide_user_menu_rain";
    public static final String USER_MENU_ELIMINATE = "hide_user_menu_eliminate";
    public static final String USER_MENU_QUALIFY = "hide_user_menu_qualify";
    // 活动行「签到」入口（接口异常时服务端动态插入）
    public static final String USER_MENU_CHECKIN = "hide_user_menu_checkin";
    // 活动行「任务领现金」入口（服务端动态下发，此前无对应开关）
    public static final String USER_MENU_TASK_CASH = "hide_user_menu_task_cash";
    // 「我」页面资产行（元宝 / 卡券 / 红包）子项开关
    public static final String USER_ASSET_YUANBAO = "hide_user_asset_yuanbao";
    public static final String USER_ASSET_COUPON = "hide_user_asset_coupon";
    public static final String USER_ASSET_REDPACKET = "hide_user_asset_redpacket";
    // 「我」页面订单板块（待上传/审核中/已驳回/已取消/全部订单 + 上传凭证提醒条）
    public static final String USER_ORDER = "hide_user_order";
    public static final String USER_MERCHANT = "hide_user_merchant";
    public static final String USER_VIP_CAROUSEL = "hide_user_vip_carousel";
    public static final String USER_COMMERCE = "hide_user_commerce";
    public static final String USER_NOTICE = "hide_user_notice";
    public static final String USER_SERVICE = "hide_user_service";
    public static final String USER_NEW_USER_TIP = "hide_user_new_user_tip";
    public static final String USER_BONUS = "hide_user_bonus";
    public static final String USER_EARN = "hide_user_earn";
    public static final String USER_OTHER_MATCH = "hide_user_other_match";
    public static final String USER_BQT = "hide_user_bqt";

    // Global
    public static final String FLOATING_WINDOW = "hide_floating_window";
    // 抽奖活动（免费开红包）中奖弹窗 + 微信小程序自动外跳拦截
    public static final String LOTTERY_POPUP = "hide_lottery_coupon_popup";

    // Module UI
    public static final String HIDE_DESKTOP_ICON = "hide_desktop_icon";

    // Location
    public static final String LOCATION_CACHE = "location_cache";

    // 多地图源（反查/搜索）：高德 > 百度
    public static final String MAP_SRC_AMAP = "map_src_amap";
    public static final String MAP_SRC_BAIDU = "map_src_baidu";
    // 第三方源 Web 服务 Key（默认空；为空则该源不参与）
    public static final String AMAP_KEY = "amap_key";
    public static final String BAIDU_KEY = "baidu_key";

    // 底部「订单」Tab 点击重定向到 MyOrderActivity（与「我的-全部订单」同一页）
    public static final String ORDER_TAB_REDIRECT = "order_tab_redirect";

    // 隐藏底部「订单」Tab
    public static final String TAB_ORDER = "hide_tab_order";

    // 搜索页
    public static final String HIDE_SEARCH_INTEL = "hide_search_intel";
    // 实验：响应层清空「小蚕情报」列表（默认关，确认接口不共用后再开）
    public static final String INTEL_RESPONSE_FILTER = "intel_response_filter";
    // 搜索结果：仅展示「实付满返」商家（rebate_condition==99），过滤比例返等其它返利形式
    public static final String SEARCH_ONLY_FULL_REBATE = "search_only_full_rebate";

    // 首页 Banner 响应层过滤总开关（默认开启）
    public static final String PLACEMENT_FILTER = "placement_filter";
    // 隐藏首页顶部导航里的活动外跳 tab（如「闪购叠红包」，按 tab_type!=1 判定，默认开启）
    public static final String PLACEMENT_HIDE_ACTIVITY_TAB = "placement_hide_activity_tab";
    // 首页 Banner 响应层过滤：用户可追加的资源位 slug（逗号分隔）
    public static final String PLACEMENT_BLOCK_EXTRA = "placement_block_extra";
    // 按结构特征自动屏蔽新增的插入式广告位（默认关，避免误删正常内容）
    public static final String PLACEMENT_AUTO_BLOCK = "placement_auto_block";
    // Hook 侧回写的「最近发现的资源位」slug 清单，供设置页展示/一键复制
    public static final String PLACEMENT_DISCOVERED = "placement_discovered";

    /**
     * 内置黑名单 slug → 对应广告位说明（响应层拦截的单一事实来源）。
     * NetworkAdInterceptor.DEFAULT_BLOCKED_SLUGS 直接取 keySet，保证黑名单与设置页展示一致。
     * 说明按实测/代码注释能坐实的写；坐实不了的标注「形态未确认」，不臆测。
     */
    public static final java.util.LinkedHashMap<String, String> DEFAULT_SLUG_DESC =
            new java.util.LinkedHashMap<>();
    static {
        DEFAULT_SLUG_DESC.put("MESSAGE_PLACE", "首页列表内插入的 Banner 集合");
        DEFAULT_SLUG_DESC.put("MESSAGE_PLACE_NEW", "首页列表插入 Banner 集合（新版）");
        DEFAULT_SLUG_DESC.put("XC_JG", "形态未确认（响应层按 slug 精确拦截）");
        DEFAULT_SLUG_DESC.put("HOME_BOTTOM", "首页底部横幅");
        DEFAULT_SLUG_DESC.put("AGG_HOME_BOTTOM", "首页底部聚合横幅");
        DEFAULT_SLUG_DESC.put("DOUYIN_BANNER", "抖音引流 Banner");
        DEFAULT_SLUG_DESC.put("SHARER_HOME_POPUP", "分享弹窗");
        DEFAULT_SLUG_DESC.put("OPS_POPUP", "运营弹窗");
        DEFAULT_SLUG_DESC.put("POPUP_NEW", "运营弹窗（新版）");
        DEFAULT_SLUG_DESC.put("BARGAIN_HOME_SEEDS", "福利群种子卡（「XX区霸王餐福利群」）");
        DEFAULT_SLUG_DESC.put("HOME_KJBANNER_TO_MINI", "首页 Banner 跳小程序卡");
        DEFAULT_SLUG_DESC.put("MAIN_TO_TANDIAN_MINI", "探店小程序跳转卡");
        DEFAULT_SLUG_DESC.put("BESTSELLERS_TAG", "热销榜标签卡");
        DEFAULT_SLUG_DESC.put("CERAMIC_NEW", "形态未确认（响应层按 slug 精确拦截）");
        DEFAULT_SLUG_DESC.put("UP_FRIST_PAGE_GIF", "首页置顶 GIF 动图卡");
        // 原候选名单实测确认后移入（2026-11-13）
        DEFAULT_SLUG_DESC.put("HOME_BANNER_TD_NEWUSER_THREEORDER",
                "首页新用户「探店三单」跳转 Banner 卡（web_url 跳 H5 探店落地页）");
        DEFAULT_SLUG_DESC.put("MAIN_TO_DAODIAN_MINI", "到店版小程序跳转卡（探店卡的兄弟）");
        DEFAULT_SLUG_DESC.put("FLASH_SALE_CAROUSEL_PRODUCT", "闪购轮播商品卡插入");
    }

    // Detail page
    public static final String DETAIL_SHARE = "hide_detail_share";

    // 定时清理缓存：off=关闭 / exit=退出时清缓存（退出杀掉宿主进程即清）
    public static final String CACHE_CLEANUP_MODE = "cache_cleanup_mode";

    // Order page
    public static final String ORDER_CHALLENGE = "hide_order_challenge";
    public static final String ORDER_FREE = "hide_order_free";
    public static final String ORDER_BONUS_BUTTONS = "hide_order_bonus_buttons";

    private static volatile SharedPreferences remote;
    /** 模块 UI 进程的本地镜像：设置页（非 Hook 进程）remote 为空时回落到它。 */
    private static volatile SharedPreferences localMirror;
    private static final Map<String, Boolean> LOCAL = new ConcurrentHashMap<>();

    private PurifyPrefs() {}

    public static void init(XposedModule module) {
        if (module == null) return;
        try {
            remote = module.getRemotePreferences(PREFS_NAME);
            MainHook.log("Remote preferences loaded: " + PREFS_NAME
                    + " (tab_vip=" + hide(TAB_VIP)
                    + ", tab_benefit=" + hide(TAB_BENEFIT)
                    + ", tab_placement=" + hide(TAB_PLACEMENT) + ")");
        } catch (Throwable t) {
            remote = null;
            MainHook.log("Remote preferences unavailable, using defaults: " + t);
        }
    }

    /**
     * @return true when the entry should stay hidden (default).
     *         Users opt out by turning the toggle off in the module UI.
     */
    public static boolean hide(String key) {
        Boolean local = LOCAL.get(key);
        SharedPreferences prefs = remote;
        if (prefs != null) {
            try {
                return prefs.getBoolean(key, local == null || local);
            } catch (Throwable ignored) {}
        }
        return local == null || local;
    }

    /**
     * Feature switch (default ON), independent of the "hide" semantics above.
     */
    public static boolean enabled(String key) {
        SharedPreferences prefs = remote;
        if (prefs != null) {
            try {
                return prefs.getBoolean(key, true);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    /** 设置页（模块 UI 进程）注入本地镜像，使非 Hook 进程也能读到自身写入的值。 */
    public static void setLocal(SharedPreferences lp) {
        localMirror = lp;
    }

    /** 字符串配置（如定时清理缓存模式），Hook 侧按事件时刻读取最新值。 */
    public static String getString(String key, String def) {
        SharedPreferences prefs = remote;
        if (prefs != null) {
            try {
                String v = prefs.getString(key, null);
                if (v != null) return v;
            } catch (Throwable ignored) {}
        }
        SharedPreferences lp = localMirror;
        if (lp != null) {
            try {
                return lp.getString(key, def);
            } catch (Throwable ignored) {}
        }
        return def;
    }

    /**
     * 开关：默认开启（"1"），关闭存 "0"；用于地图源启停与全局功能开关。
     * 与 hide()（默认隐藏）语义不同。
     */
    public static boolean isOn(String key) {
        return !"0".equals(getString(key, "1"));
    }

    /**
     * Hook 侧向远程偏好写入镜像数据（如定位缓存快照），供模块设置页展示。
     * 写入失败静默忽略——镜像数据仅供 UI 展示，不影响净化功能。
     */
    public static void putRemoteString(String key, String value) {
        SharedPreferences prefs = remote;
        if (prefs == null || value == null) return;
        try {
            prefs.edit().putString(key, value).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Opt-in switch (default OFF): entry stays visible unless the user
     * explicitly enables hiding in the module UI (e.g. detail enter-shop button).
     */
    public static boolean hideOptIn(String key) {
        Boolean local = LOCAL.get(key);
        SharedPreferences prefs = remote;
        if (prefs != null) {
            try {
                return prefs.getBoolean(key, local != null && local);
            } catch (Throwable ignored) {}
        }
        return local != null && local;
    }
}
