package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class MainActivity extends Activity {
    /** 被 hook 的目标 App 包名（小蚕）。 */
    private static final String TARGET_PKG = "com.realtech.xiaocan";

    private static final Object[][] GROUPS = {
            {"底部导航栏", new String[][]{
                    {PurifyPrefs.TAB_VIP, "隐藏「会员」Tab", "移除底部会员（SVIP）标签，仅保留首页/订单/我"},
                    {PurifyPrefs.TAB_BENEFIT, "隐藏「赚钱」Tab", "移除底部任务与福利赚钱标签"},
                    {PurifyPrefs.TAB_PLACEMENT, "隐藏「抖音补贴」Tab", "阻止第二个 Tab 被动态替换为抖音补贴等营销入口"},
                    {PurifyPrefs.TAB_ORDER, "隐藏「订单」Tab", "移除底部订单标签，仅保留首页/我（开启后无法通过 Tab 进入订单页）"},
            }},
            {"首页", new String[][]{
                    {PurifyPrefs.HOME_TOP_BANNER, "顶部红包 Banner 与活动背景", "¥18 外卖红包等顶部大图与红/橙活动背景"},
                    {PurifyPrefs.HOME_SEARCH_BANNER, "搜索右侧「抖音补贴」图", "隐藏搜索框右侧的抖音补贴角标"},
                    {PurifyPrefs.HOME_MENU_ROW, "红包快捷入口行", "美团红包/闪购红包/京东红包/奶茶专区等一排图标"},
                    {PurifyPrefs.HOME_STORE_CARD, "0元探店 / 到店霸王餐卡片", "头部双卡片推广位（0元探店、到店霸王餐）"},
                    {PurifyPrefs.HOME_ORDER_BANNER, "「开心收下」订单横条", "首页头部订单奖励横幅"},
                    {PurifyPrefs.HOME_FAB, "浮动红包 / 新手引导", "红包雨浮动球、新手引导浮标、订单 Banner 浮标"},
                    {PurifyPrefs.HOME_BOTTOM_PLACEMENT, "底部悬浮红包", "「¥18 领红包」等底部悬浮推广位"},
                    {PurifyPrefs.HOME_SECOND_FLOOR, "下拉二楼", "关闭首页下拉进入的第二层活动页，保留普通下拉刷新"},
            }},
            {"「我」页面", new String[][]{
                    {PurifyPrefs.USER_MENU_CHALLENGE, "活动行「挑战赛」", "挑战赛/免费抽奖活动行中的挑战赛入口"},
                    {PurifyPrefs.USER_MENU_LOTTERY, "活动行「免费抽奖」", "活动行中的免费抽奖入口"},
                    {PurifyPrefs.USER_MENU_SECRET, "活动行「对接暗号」", "活动行中的对接暗号入口"},
                    {PurifyPrefs.USER_MENU_RAIN, "活动行「红包雨」", "活动行中的红包雨入口"},
                    {PurifyPrefs.USER_MENU_ELIMINATE, "活动行「有奖消消乐」", "活动行中的有奖消消乐入口"},
                    {PurifyPrefs.USER_MENU_QUALIFY, "免单活动卡（入口/横幅/轮播）", "隐藏活动行中的免单资格入口、下方「您有1笔订单待免单」红色横幅卡、以及免单/挑战赛进度轮播卡（开启后三样一起隐藏；全部活动入口隐藏时活动行整行自动收起）"},
                    {PurifyPrefs.USER_MENU_CHECKIN, "活动行「签到」", "接口请求异常时出现的签到入口"},
                    {PurifyPrefs.USER_MENU_TASK_CASH, "活动行「任务领现金」", "活动行中的任务领现金入口"},
                    {PurifyPrefs.USER_ASSET_YUANBAO, "资产行「元宝」", "元宝余额入口（元宝/卡券/红包一行）"},
                    {PurifyPrefs.USER_ASSET_COUPON, "资产行「卡券」", "卡券入口（元宝/卡券/红包一行）"},
                    {PurifyPrefs.USER_ASSET_REDPACKET, "资产行「红包」", "红包入口（元宝/卡券/红包一行）"},
                    {PurifyPrefs.USER_ORDER, "「订单」板块", "待上传/审核中/已驳回/已取消/全部订单图标行与上传凭证提醒条"},
                    {PurifyPrefs.USER_MERCHANT, "霸王餐入驻 / 0元探店入驻横条", "商家招募推广横条"},
                    {PurifyPrefs.USER_VIP_CAROUSEL, "小蚕会员 SVIP 轮播卡", "「订单全额返利」会员卡与轮播指示器"},
                    {PurifyPrefs.USER_COMMERCE, "电商/生活/出行特惠网格", "电商补贴、生活特惠、出行特惠、电影票、打车券等导购网格"},
                    {PurifyPrefs.USER_NOTICE, "公告 / 活动横幅", "水果券领取等通知横幅"},
                    {PurifyPrefs.USER_SERVICE, "客服 / 常用功能布局", "投诉建议、常见问题、商家入驻等功能布局"},
                    {PurifyPrefs.USER_NEW_USER_TIP, "新用户提示", "新用户引导提示条"},
                    {PurifyPrefs.USER_BONUS, "底部奖励提示卡", "页面底部奖励/活动提示卡"},
                    {PurifyPrefs.USER_EARN, "「赚钱」按钮与蚕豆角标", "提现卡片区「赚钱」按钮与「待领蚕豆」徽标"},
                    {PurifyPrefs.USER_OTHER_MATCH, "其他运营匹配模块", "运营位/匹配推广模块"},
                    {PurifyPrefs.USER_BQT, "BQT 营销模块", "BQT 容器营销模块"},
            }},
            {"搜索页", new String[][]{
                    {PurifyPrefs.HIDE_SEARCH_INTEL, "「小蚕情报」板块", "搜索页历史搜索下方的商户情报榜单（标题与 5 条商户推荐整块隐藏）"},
            }},
            {"详情页 / 订单页", new String[][]{
                    {PurifyPrefs.DETAIL_SHARE, "「分享赚豆」浮动入口", "详情页右下角分享赚豆浮标"},
                    {PurifyPrefs.ORDER_CHALLENGE, "霸王餐挑战", "订单页挑战横条与挑战弹窗"},
                    {PurifyPrefs.ORDER_FREE, "免费单入口", "免费单邀请条与标签提示"},
                    {PurifyPrefs.ORDER_BONUS_BUTTONS, "「领元宝/红包/奖励」按钮", "订单操作按钮中的元宝/奖励/红包类入口"},
            }},
            {"全局", new String[][]{
                    {PurifyPrefs.FLOATING_WINDOW, "浮动红包小窗", "主页/我的页悬浮红包小图标"},
                    {PurifyPrefs.LOTTERY_POPUP, "抽奖中奖弹窗与自动外跳", "「免费开红包」等抽奖页「恭喜您获得大额出行福利券」弹窗，并拦截其触发的微信小程序自动跳转"},
                    {PurifyPrefs.LOTTERY_TASK_AUTO, "自动完成免费抽奖浏览任务", "「看商城优惠/霸王餐/福利页」任务点击后自动停留10秒并返回；「领取美团/饿了么红包」任务拦截小程序外跳（发奖依赖服务端校验，若未到账请反馈日志）"},
            }},
            {"模块界面", new String[][]{
                    {PurifyPrefs.HIDE_DESKTOP_ICON, "隐藏桌面图标", "关闭后从桌面移除本模块图标；仍可在 LSPosed 模块列表中打开设置页重新开启"},
            }},
            {"定位", new String[][]{
                    {PurifyPrefs.LOCATION_CACHE, "无 GPS 时使用缓存定位", "成功定位一次后缓存位置；之后不开定位服务也默认用该位置加载商家活动数据（开启定位后自动刷新）"},
            }},
    };

    private SharedPreferences localPrefs;
    private volatile SharedPreferences remotePrefs;
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private boolean refreshing;
    /** 服务未连接时写入失败的设置，等服务绑定后补写，避免用户改动静默丢失。 */
    private final Map<String, Boolean> pendingRemoteWrites = new LinkedHashMap<>();
    private TextView syncBadge;
    /** 清理缓存卡片的模式摘要（关闭 / 退出时清缓存）。 */
    private TextView cacheSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        localPrefs = getSharedPreferences(PurifyPrefs.PREFS_NAME, MODE_PRIVATE);
        registerServiceListener();
        // 对齐桌面图标状态：仅当本地已有明确记录时执行。
        // 此时 LSPosed 服务尚未绑定（异步），getHide 可能读到默认值，
        // 若直接对齐会把已隐藏的图标误恢复，等服务绑定后 syncLauncherIconState 再最终对齐。
        if (localPrefs.contains(PurifyPrefs.HIDE_DESKTOP_ICON)) {
            try {
                setLauncherIconEnabled(!localPrefs.getBoolean(PurifyPrefs.HIDE_DESKTOP_ICON, false));
            } catch (Throwable ignored) {
            }
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(ThemeHelper.pageBg(this));
        scrollView.setFitsSystemWindows(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        layout.setPadding(pad, dp2px(24), pad, pad);

        // Header Title
        TextView title = new TextView(this);
        title.setText("小蚕净化");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textTitle(this));
        layout.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("小蚕霸王餐 Xposed / LSPosed 净化模块");
        subTitle.setTextSize(13);
        subTitle.setTextColor(ThemeHelper.textWeak(this));
        subTitle.setPadding(0, dp2px(4), 0, dp2px(16));
        layout.addView(subTitle);

        // Status Card
        LinearLayout statusCard = createCard();
        TextView statusBadge = new TextView(this);
        statusBadge.setText("● 模块已就绪");
        statusBadge.setTextSize(15);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setTextColor(ThemeHelper.accentGreen(MainActivity.this));
        statusCard.addView(statusBadge);

        TextView statusDesc = new TextView(this);
        statusDesc.setText("目标应用: com.realtech.xiaocan (小蚕霸王餐)\n在 LSPosed 勾选小蚕霸王餐后重启小蚕即可生效。");
        statusDesc.setTextSize(13);
        statusDesc.setTextColor(ThemeHelper.textDesc(this));
        statusDesc.setPadding(0, dp2px(6), 0, 0);
        statusCard.addView(statusDesc);

        syncBadge = new TextView(this);
        syncBadge.setTextSize(12);
        syncBadge.setPadding(0, dp2px(6), 0, 0);
        statusCard.addView(syncBadge);
        updateSyncStatus();
        layout.addView(statusCard);

        // 日志入口卡片：查看日志 / 导出 / 自动清理
        LinearLayout logEntry = new LinearLayout(this);
        logEntry.setOrientation(LinearLayout.HORIZONTAL);
        logEntry.setGravity(Gravity.CENTER_VERTICAL);
        logEntry.setBackgroundColor(ThemeHelper.cardBg(this));
        int lp = dp2px(14);
        logEntry.setPadding(lp, dp2px(12), lp, dp2px(12));
        LinearLayout.LayoutParams logEntryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logEntryLp.setMargins(0, 0, 0, dp2px(12));
        logEntry.setLayoutParams(logEntryLp);
        logEntry.setOnClickListener(v -> startActivity(
                new android.content.Intent(MainActivity.this, LogActivity.class)));

        TextView logTitle = new TextView(this);
        logTitle.setText("查看日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(ThemeHelper.textHeading(this));
        logEntry.addView(logTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView logArrow = new TextView(this);
        logArrow.setText("›");
        logArrow.setTextSize(18);
        logArrow.setTextColor(ThemeHelper.textSecondary(this));
        logEntry.addView(logArrow);
        layout.addView(logEntry);

        // 清理缓存卡片：定时清理（退出时清缓存），参考宿主「清理缓存 → 定时清理」
        LinearLayout cacheEntry = new LinearLayout(this);
        cacheEntry.setOrientation(LinearLayout.HORIZONTAL);
        cacheEntry.setGravity(Gravity.CENTER_VERTICAL);
        cacheEntry.setBackgroundColor(ThemeHelper.cardBg(this));
        cacheEntry.setPadding(lp, dp2px(12), lp, dp2px(12));
        cacheEntry.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cacheEntryLp =
                (LinearLayout.LayoutParams) cacheEntry.getLayoutParams();
        cacheEntryLp.setMargins(0, 0, 0, dp2px(12));
        cacheEntry.setOnClickListener(v -> showCacheCleanupDialog());

        TextView cacheTitle = new TextView(this);
        cacheTitle.setText("清理缓存");
        cacheTitle.setTextSize(14);
        cacheTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cacheTitle.setTextColor(ThemeHelper.textHeading(this));
        cacheEntry.addView(cacheTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cacheSummary = new TextView(this);
        cacheSummary.setText(cacheModeLabel(getCacheCleanupMode()));
        cacheSummary.setTextSize(13);
        cacheSummary.setTextColor(ThemeHelper.textSecondary(this));
        cacheSummary.setPadding(0, 0, dp2px(8), 0);
        cacheEntry.addView(cacheSummary);

        TextView cacheArrow = new TextView(this);
        cacheArrow.setText("›");
        cacheArrow.setTextSize(18);
        cacheArrow.setTextColor(ThemeHelper.textSecondary(this));
        cacheEntry.addView(cacheArrow);
        layout.addView(cacheEntry);

        // Theme card
        layout.addView(createThemeCard());

        // Settings toggles
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("功能开关");
        settingsTitle.setTextSize(16);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsTitle.setTextColor(ThemeHelper.textBody(this));
        settingsTitle.setPadding(0, dp2px(16), 0, dp2px(8));
        layout.addView(settingsTitle);

        TextView settingsHint = new TextView(this);
        settingsHint.setText("开关默认开启 = 隐藏对应入口（保持净化）。关闭开关即恢复展示该功能入口；修改后需强制停止并重启小蚕霸王餐生效。广告与弹窗拦截始终开启，不受开关影响。若状态卡提示「设置未同步」，说明改动没有写进宿主，请先启动一次小蚕再回到本页重新设置。");
        settingsHint.setTextSize(12);
        settingsHint.setTextColor(ThemeHelper.textHint(this));
        settingsHint.setPadding(dp2px(14), 0, dp2px(14), dp2px(8));
        layout.addView(settingsHint);

        buildToggles(layout);

        // Built-in features (always on)
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("内置净化（默认开启）");
        sectionTitle.setTextSize(16);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(ThemeHelper.textBody(this));
        sectionTitle.setPadding(0, dp2px(16), 0, dp2px(8));
        layout.addView(sectionTitle);

        // Feature 1
        layout.addView(createFeatureCard("去除所有广告",
                "• 瞬间跳过冷启动开屏广告\n" +
                "• 彻底阻止切后台返回的冷热启动开屏广告\n" +
                "• 拦截穿山甲、优量汇、快手、Sigmob、Baidu、WindMill 等全屏/激励广告\n" +
                "• 屏蔽首页与到店页信息流/卡片广告\n" +
                "• 禁用后台静默隐蔽任务引擎 (SilentTaskEngine)"));

        // Feature 2
        layout.addView(createFeatureCard("去除各类弹窗",
                "• 拦截首页营销弹窗、首单全返活动弹窗\n" +
                "• 拦截抖音商城红包弹窗、蛋糕/周年庆弹窗\n" +
                "• 屏蔽应用强制检查升级弹窗\n" +
                "• 屏蔽元宝升级提示弹窗、开学季及分享弹窗\n" +
                "• 拦截「愉悦推SDK」应用列表授权弹窗、「小蚕卫士」检测弹窗\n" +
                "• 净化全局弹窗队列与 Compose 浮动弹窗"));

        // Feature 3
        layout.addView(createFeatureCard("网络层广告过滤",
                "• 拦截 placement 广告配置与数据请求 (/g/pa)\n" +
                "• 阻断 AdProLink、Sigmob、CSJ、GDT 等广告网络流量\n" +
                "• 拦截埋点与追踪数据上报 (Burying / SensorsData)"));

        // Feature 4
        layout.addView(createFeatureCard("报名外跳保护",
                "• 拦截报名/下单成功后自动外跳美团/饿了么/淘宝等\n" +
                "• 拦截微信小程序自动拉起，保留手动操作"));

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    private void registerServiceListener() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    SharedPreferences prefs = null;
                    try {
                        prefs = service.getRemotePreferences(PurifyPrefs.PREFS_NAME);
                    } catch (Throwable ignored) {
                    }
                    remotePrefs = prefs;
                    LogStore.append(getApplicationContext(), "I", "Sync", "LSPosed 服务已连接，设置同步恢复");
                    flushPendingRemoteWrites();
                    // 自动对账：服务未连接期间的开关改动只写了本地镜像，
                    // 若页面关闭前服务一直没绑上，内存队列会随进程丢失，
                    // 远程偏好停留在旧值——宿主读到的还是旧行为（用户实测
                    // 「明明关闭了客服布局但宿主仍隐藏」）。按本地镜像全量
                    // 比对推送一次，把历史漂移的键全部纠正。
                    syncRemoteFromLocal();
                    // 服务绑定后按最终配置对齐桌面图标（覆盖 onCreate 时的临时状态）
                    syncLauncherIconState();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                refreshSwitches();
                                refreshLocationInfo();
                                updateSyncStatus();
                            }
                        }
                    });
                }

                @Override
                public void onServiceDied(XposedService service) {
                    remotePrefs = null;
                    LogStore.append(getApplicationContext(), "W", "Sync", "LSPosed 服务断开");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) updateSyncStatus();
                        }
                    });
                }
            });
        } catch (Throwable ignored) {
            // Service unavailable (module not enabled yet) - local mirror only.
        }
    }

    private void buildToggles(LinearLayout parent) {
        for (Object[] group : GROUPS) {
            String groupName = (String) group[0];
            String[][] items = (String[][]) group[1];

            TextView groupTitle = new TextView(this);
            groupTitle.setText(groupName);
            groupTitle.setTextSize(13);
            groupTitle.setTypeface(Typeface.DEFAULT_BOLD);
            groupTitle.setTextColor(ThemeHelper.accentOrangeRed(this));
            groupTitle.setPadding(dp2px(4), dp2px(8), 0, dp2px(6));
            parent.addView(groupTitle);

            LinearLayout card = createCard();
            for (int i = 0; i < items.length; i++) {
                String key = items[i][0];
                String itemTitle = items[i][1];
                String itemDesc = items[i][2];
                card.addView(createSwitchRow(key, itemTitle, itemDesc));
                if (i < items.length - 1) {
                    View divider = new View(this);
                    divider.setBackgroundColor(ThemeHelper.cardStroke(this));
                    card.addView(divider, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp2px(1)));
                }
            }
            parent.addView(card);
            if ("定位".equals(groupName)) {
                // 定位分组下追加缓存位置信息卡（POI 地址 + 经纬度 + 缓存时间）
                parent.addView(createLocationInfoCard());
            }
        }
    }

    private TextView locationInfoText;

    /** 主题卡片：跟随系统 / 浅色 / 深色 三选一（参考 HyperIsland themeMode）。 */
    private LinearLayout createThemeCard() {
        LinearLayout card = createCard();
        TextView title = new TextView(this);
        title.setText("主题");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textHeading(this));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("应用后立即切换模块界面深浅色，并同时切换小蚕的深浅色显示");
        desc.setTextSize(11);
        desc.setTextColor(ThemeHelper.textHint(this));
        desc.setPadding(0, dp2px(4), 0, dp2px(8));
        card.addView(desc);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[][] modes = {
                {ThemeHelper.MODE_SYSTEM, "跟随系统"},
                {ThemeHelper.MODE_LIGHT, "浅色"},
                {ThemeHelper.MODE_DARK, "深色"},
        };
        String current = getThemeMode();
        for (String[] m : modes) {
            TextView b = new TextView(this);
            b.setText(m[1]);
            b.setTextSize(13);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(dp2px(14), dp2px(8), dp2px(14), dp2px(8));
            boolean selected = m[0].equals(current);
            b.setTextColor(selected ? 0xFFFFFFFF : ThemeHelper.textBody(this));
            b.setBackground(roundRect(selected
                    ? ThemeHelper.accentGreen(this)
                    : ThemeHelper.cardStroke(this)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(dp2px(8));
            row.addView(b, lp);
            b.setOnClickListener(v -> setThemeMode(m[0]));
        }
        card.addView(row);
        return card;
    }

    private android.graphics.drawable.Drawable roundRect(int color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp2px(18));
        return d;
    }

    private String getThemeMode() {
        try {
            return localPrefs.getString("theme_mode", ThemeHelper.MODE_SYSTEM);
        } catch (Throwable t) {
            return ThemeHelper.MODE_SYSTEM;
        }
    }

    private void setThemeMode(String mode) {
        try {
            localPrefs.edit().putString("theme_mode", mode).apply();
        } catch (Throwable ignored) {}
        // 通知宿主切换深浅色显示（UiModeManager.setApplicationNightMode）
        try {
            android.content.Intent i = new android.content.Intent(
                    "com.genius.xiaocanpurify.ACTION_THEME_CHANGE");
            i.setPackage("com.realtech.xiaocan");
            i.putExtra("mode", mode);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
        recreate();
    }

    // ---------------------------------------------------- 定时清理缓存

    private static final String CACHE_MODE_OFF = "off";
    private static final String CACHE_MODE_EXIT = "exit";

    private String getCacheCleanupMode() {
        try {
            return localPrefs.getString(PurifyPrefs.CACHE_CLEANUP_MODE, CACHE_MODE_OFF);
        } catch (Throwable t) {
            return CACHE_MODE_OFF;
        }
    }

    private static String cacheModeLabel(String mode) {
        return CACHE_MODE_EXIT.equals(mode) ? "退出时清缓存" : "关闭";
    }

    private void setCacheCleanupMode(String mode) {
        try {
            localPrefs.edit().putString(PurifyPrefs.CACHE_CLEANUP_MODE, mode).apply();
        } catch (Throwable ignored) {}
        boolean synced = false;
        SharedPreferences rp = remotePrefs;
        if (rp != null) {
            try {
                synced = rp.edit().putString(PurifyPrefs.CACHE_CLEANUP_MODE, mode).commit();
            } catch (Throwable ignored) {}
        }
        LogStore.append(this, "I", "Sync", "定时清理缓存 -> " + cacheModeLabel(mode)
                + (synced ? "（已同步宿主）" : "（未同步：LSPosed 服务未连接）"));
        if (cacheSummary != null) {
            cacheSummary.setText(cacheModeLabel(mode));
        }
        updateSyncStatus();
    }

    /** 「定时清理」选择弹窗（自绘，跟随深浅色；选中项右侧红色 ✓）。 */
    private void showCacheCleanupDialog() {
        String current = getCacheCleanupMode();
        String[][] options = {
                {CACHE_MODE_OFF, "关闭"},
                {CACHE_MODE_EXIT, "退出时清缓存"},
        };

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setOnClickListener(v -> ((ViewGroup) overlay.getParent()).removeView(overlay));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(ThemeHelper.cardBg(this));
        bg.setCornerRadius(dp2px(14));
        card.setBackground(bg);
        int p = dp2px(18);
        card.setPadding(p, p, p, p);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp2px(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);

        TextView title = new TextView(this);
        title.setText("定时清理");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textHeading(this));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("退出时清缓存：小蚕退出（进程结束）后自动清理其缓存目录，减少垃圾堆积。");
        desc.setTextSize(12);
        desc.setTextColor(ThemeHelper.textSecondary(this));
        desc.setPadding(0, dp2px(6), 0, dp2px(4));
        card.addView(desc);

        for (String[] opt : options) {
            final String mode = opt[0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp2px(12), 0, dp2px(12));
            row.setOnClickListener(v -> {
                setCacheCleanupMode(mode);
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            });

            TextView label = new TextView(this);
            label.setText(opt[1]);
            label.setTextSize(15);
            label.setTextColor(ThemeHelper.textBody(this));
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (opt[0].equals(current)) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(15);
                check.setTypeface(Typeface.DEFAULT_BOLD);
                check.setTextColor(ThemeHelper.accentRed(this));
                row.addView(check);
            }
            card.addView(row);
        }

        ((ViewGroup) getWindow().getDecorView()).addView(overlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout createLocationInfoCard() {
        LinearLayout card = createCard();
        TextView title = new TextView(this);
        title.setText("缓存位置信息");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textHeading(this));
        card.addView(title);

        locationInfoText = new TextView(this);
        locationInfoText.setTextSize(11);
        locationInfoText.setTextColor(ThemeHelper.textSecondary(this));
        locationInfoText.setPadding(0, dp2px(4), 0, 0);
        card.addView(locationInfoText);
        refreshLocationInfo();
        // 请求宿主重发一次缓存广播：模块进程 stopped/被杀期间宿主发出的
        // 缓存广播会被 MIUI 拦截（wake path 受限）导致 inbox 陈旧；设置页
        // 打开（进程活着）时发请求，宿主收到后重发，2 秒后刷新显示
        try {
            android.content.Intent i = new android.content.Intent(
                    "com.genius.xiaocanpurify.ACTION_LOC_CACHE_RESEND");
            i.setPackage("com.realtech.xiaocan");
            sendBroadcast(i);
        } catch (Throwable ignored) {}
        if (locationInfoText != null) {
            locationInfoText.postDelayed(this::refreshLocationInfo, 2000);
        }
        return card;
    }

    /**
 * 缓存位置由目标 App 通过显式广播推送到本模块（CacheReceiver 存入 purify_cache_inbox），
     * 设置页直接读模块自有存储——无需 root、无跨 uid 文件读取、绕过包可见性限制。
     */
    private void refreshLocationInfo() {
        TextView view = locationInfoText;
        if (view == null) return;
        try {
            SharedPreferences inbox = getSharedPreferences("purify_cache_inbox", MODE_PRIVATE);
            double lat = Double.longBitsToDouble(inbox.getLong("lat", 0L));
            double lng = Double.longBitsToDouble(inbox.getLong("lng", 0L));
            long time = inbox.getLong("time", 0L);
            String address = inbox.getString("address", "");
            String text;
            if (lat == 0.0 && lng == 0.0) {
                text = "暂无缓存位置：请开启 GPS 进入小蚕，定位成功一次后自动记录。";
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("纬度：").append(String.format(java.util.Locale.US, "%.6f", lat));
                sb.append("\n经度：").append(String.format(java.util.Locale.US, "%.6f", lng));
                if (time > 0) {
                    sb.append("\n缓存时间：").append(
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                                    .format(new java.util.Date(time)));
                }
                sb.append("\nPOI 地址：");
                if (address == null || address.isEmpty()) {
                    sb.append("（快照中无地址，开启 GPS 重新定位一次即可刷新）");
                } else {
                    sb.append(address);
                }
                text = sb.toString();
            }
            view.setText(text);
        } catch (Throwable t) {
            view.setText("缓存位置信息读取失败：" + t.getClass().getSimpleName());
        }
    }

    private static String readAll(java.io.File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    /** 从 SharedPreferences XML 中解析 <long name="key" value="..."/> 的数值（默认 0）。 */
    private static long parseLongAttr(String xml, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"" + key + "\"\\s+value=\"(-?\\d+)\"")
                .matcher(xml);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    /** 提取 <string name="key">...</string> 的文本内容。 */
    private static String matchAttr(String xml, String nameToken, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(nameToken) + ">(.*?)</" + tag + ">")
                .matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** 从 snapshot 字符串（k\u0002v 以 \u0001 连接）解析 getAddress 字段。 */
    private static String parseSnapshotAddress(String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return "";
        for (String kv : snapshot.split("\u0001")) {
            int i = kv.indexOf('\u0002');
            if (i > 0 && "getAddress".equals(kv.substring(0, i))) {
                return kv.substring(i + 1);
            }
        }
        return "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLocationInfo();
        // 自动清理惰性触发：打开设置页时检查是否到达清理间隔
        LogStore.maybeAutoCleanup(this);
    }

    private View createSwitchRow(final String key, String itemTitle, String itemDesc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp2px(10), 0, dp2px(10));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(itemTitle);
        t.setTextSize(14);
        t.setTextColor(ThemeHelper.textHeading(this));
        textColumn.addView(t);

        TextView d = new TextView(this);
        d.setText(itemDesc);
        d.setTextSize(11);
        d.setTextColor(ThemeHelper.textHint(this));
        d.setPadding(0, dp2px(2), dp2px(8), 0);
        textColumn.addView(d);

        row.addView(textColumn);

        Switch sw = new Switch(this);
        sw.setChecked(getHide(key));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!refreshing) setHide(key, isChecked);
            }
        });
        switches.put(key, sw);
        row.addView(sw);
        return row;
    }

    private void refreshSwitches() {
        refreshing = true;
        try {
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                e.getValue().setChecked(getHide(e.getKey()));
            }
        } finally {
            refreshing = false;
        }
    }

    private boolean getHide(String key) {
        // 隐藏桌面图标默认关闭（false），其余净化开关默认隐藏（true）
        boolean def = PurifyPrefs.HIDE_DESKTOP_ICON.equals(key) ? false : true;
        SharedPreferences rp = remotePrefs;
        if (rp != null) {
            try {
                return rp.getBoolean(key, def);
            } catch (Throwable ignored) {
            }
        }
        // 服务未绑定（如刚从 LSPosed 打开本页）：回落到本地镜像，避免读到默认值
        if (localPrefs != null && localPrefs.contains(key)) {
            try {
                return localPrefs.getBoolean(key, def);
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private void setHide(String key, boolean hide) {
        localPrefs.edit().putBoolean(key, hide).commit();

        // 隐藏桌面图标：立即切换 launcher alias 的启用状态（非宿主 hook，即时生效）
        if (PurifyPrefs.HIDE_DESKTOP_ICON.equals(key)) {
            setLauncherIconEnabled(!hide);
        }

        boolean synced = false;
        SharedPreferences rp = remotePrefs;
        if (rp != null) {
            try {
                // commit(): 立刻落盘，避免用户切回小蚕时写入还没生效
                synced = rp.edit().putBoolean(key, hide).commit();
            } catch (Throwable ignored) {
            }
        }
        if (synced) {
            pendingRemoteWrites.remove(key);
        } else {
            // 服务未连接/写入失败：排队，等服务绑定后补写
            pendingRemoteWrites.put(key, hide);
        }
        android.util.Log.i("XiaoCanPurify", "Setting " + key + " -> " + (hide ? "HIDE" : "SHOW")
                + " remoteSynced=" + synced);
        LogStore.append(this, "I", "Sync", "设置 " + key + " -> "
                + (hide ? "HIDE" : "SHOW") + (synced ? "（已同步宿主）" : "（未同步：LSPosed 服务未连接）"));
        updateSyncStatus();
    }

    private void syncLauncherIconState() {
        setLauncherIconEnabled(!getHide(PurifyPrefs.HIDE_DESKTOP_ICON));
    }

    private void setLauncherIconEnabled(boolean enabled) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.genius.xiaocanpurify.MainActivityAlias");
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
            android.util.Log.i("XiaoCanPurify", "Launcher icon " + (enabled ? "shown" : "hidden"));
        } catch (Throwable t) {
            android.util.Log.e("XiaoCanPurify", "setLauncherIconEnabled failed: " + t);
        }
    }

    private void flushPendingRemoteWrites() {
        SharedPreferences rp = remotePrefs;
        if (rp == null || pendingRemoteWrites.isEmpty()) return;
        try {
            SharedPreferences.Editor editor = rp.edit();
            for (Map.Entry<String, Boolean> entry : pendingRemoteWrites.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            if (editor.commit()) {
                pendingRemoteWrites.clear();
                android.util.Log.i("XiaoCanPurify", "Flushed pending settings to remote prefs");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 开关默认值（与 getHide 一致：桌面图标默认显示，其余默认隐藏）。 */
    private boolean defaultHide(String key) {
        return !PurifyPrefs.HIDE_DESKTOP_ICON.equals(key);
    }

    /**
     * 远程偏好对账：以本地镜像为准，比对全部已知键，把不一致的键推送到宿主。
     * 修复「服务未连接时改开关 → 页面关闭进程被杀 → 队列丢失 → 远程停留旧值」
     * 导致的宿主行为与模块设置不一致（用户实测客服布局关不掉）。
     */
    private void syncRemoteFromLocal() {
        SharedPreferences rp = remotePrefs;
        if (rp == null) return;
        try {
            SharedPreferences.Editor editor = rp.edit();
            boolean changed = false;
            for (Object[] group : GROUPS) {
                for (String[] item : (String[][]) group[1]) {
                    String key = item[0];
                    boolean local = localPrefs.getBoolean(key, defaultHide(key));
                    if (rp.getBoolean(key, !local) != local) {
                        editor.putBoolean(key, local);
                        changed = true;
                        LogStore.append(this, "I", "Sync", "对账修正 " + key + " -> "
                                + (local ? "HIDE" : "SHOW"));
                    }
                }
            }
            String cm = localPrefs.getString(PurifyPrefs.CACHE_CLEANUP_MODE, "off");
            if (!cm.equals(rp.getString(PurifyPrefs.CACHE_CLEANUP_MODE, "off"))) {
                editor.putString(PurifyPrefs.CACHE_CLEANUP_MODE, cm);
                changed = true;
            }
            if (changed && editor.commit()) {
                LogStore.append(this, "I", "Sync", "远程偏好对账完成，已推送本地最新设置");
                updateSyncStatus();
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateSyncStatus() {
        final TextView badge = syncBadge;
        if (badge == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if (remotePrefs == null) {
                    badge.setText("⚠ 设置未同步：LSPosed 服务未连接。此状态下的改动只存本地，小蚕仍按默认「隐藏」执行，请重启小蚕后重新打开本页再改。");
                    badge.setTextColor(ThemeHelper.accentRed(MainActivity.this));
                } else if (!pendingRemoteWrites.isEmpty()) {
                    badge.setText("⚠ 仍有 " + pendingRemoteWrites.size() + " 项设置待同步到宿主。");
                    badge.setTextColor(ThemeHelper.accentOrange(MainActivity.this));
                } else {
                    badge.setText("● 设置已同步到宿主（改动后强制停止并重启小蚕生效）");
                    badge.setTextColor(ThemeHelper.accentGreen(MainActivity.this));
                }
            }
        });
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(ThemeHelper.cardBg(this));
        int p = dp2px(14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp2px(12));
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout createFeatureCard(String title, String desc) {
        LinearLayout card = createCard();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ThemeHelper.textHeading(this));
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(ThemeHelper.textSecondary(this));
        d.setLineSpacing(dp2px(2), 1.15f);
        d.setPadding(0, dp2px(6), 0, 0);
        card.addView(d);
        return card;
    }

    private int dp2px(float dp) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
