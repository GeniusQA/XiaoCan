package io.github.jessire.xiaocanpurify;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedService;

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
    public static final String USER_MENU_ROW = "hide_user_menu_row";
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

    // Detail page
    public static final String DETAIL_SHARE = "hide_detail_share";

    // Order page
    public static final String ORDER_CHALLENGE = "hide_order_challenge";
    public static final String ORDER_FREE = "hide_order_free";
    public static final String ORDER_BONUS_BUTTONS = "hide_order_bonus_buttons";

    private static volatile XposedService.RemotePreferences remote;
    private static final Map<String, Boolean> LOCAL = new ConcurrentHashMap<>();

    private PurifyPrefs() {}

    public static void init(XposedModule module) {
        if (module == null) return;
        try {
            remote = module.getRemotePreferences(PREFS_NAME);
            MainHook.log("Remote preferences loaded: " + PREFS_NAME);
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
        XposedService.RemotePreferences prefs = remote;
        if (prefs != null) {
            try {
                return prefs.getBoolean(key, local == null || local);
            } catch (Throwable ignored) {}
        }
        return local == null || local;
    }
}
