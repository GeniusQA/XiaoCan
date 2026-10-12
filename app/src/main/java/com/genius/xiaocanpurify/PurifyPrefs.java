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
    // 自动完成「看商城优惠得抽奖机会」浏览任务（点击后自动停留10s并返回）
    public static final String LOTTERY_TASK_AUTO = "auto_lottery_browse_task";

    // Module UI
    public static final String HIDE_DESKTOP_ICON = "hide_desktop_icon";

    // Location
    public static final String LOCATION_CACHE = "location_cache";

    // Detail page
    public static final String DETAIL_SHARE = "hide_detail_share";

    // Order page
    public static final String ORDER_CHALLENGE = "hide_order_challenge";
    public static final String ORDER_FREE = "hide_order_free";
    public static final String ORDER_BONUS_BUTTONS = "hide_order_bonus_buttons";

    private static volatile SharedPreferences remote;
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
