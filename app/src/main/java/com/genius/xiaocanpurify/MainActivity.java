package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
    private static final Object[][] GROUPS = {
            {"底部导航栏", new String[][]{
                    {PurifyPrefs.TAB_VIP, "隐藏「会员」Tab", "移除底部会员（SVIP）标签，仅保留首页/订单/我"},
                    {PurifyPrefs.TAB_BENEFIT, "隐藏「福利/赚钱」Tab", "移除底部任务与福利赚钱标签"},
                    {PurifyPrefs.TAB_PLACEMENT, "隐藏「抖音补贴」营销位", "阻止第二个 Tab 被动态替换为抖音补贴等营销入口"},
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
                    {PurifyPrefs.USER_MENU_ROW, "挑战赛/免费抽奖活动行", "挑战赛、免费抽奖、对接暗号、红包雨、有奖消消乐及免单资格卡"},
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
            {"详情页 / 订单页", new String[][]{
                    {PurifyPrefs.DETAIL_SHARE, "「分享赚豆」浮动入口", "详情页右下角分享赚豆浮标"},
                    {PurifyPrefs.ORDER_CHALLENGE, "霸王餐挑战", "订单页挑战横条与挑战弹窗"},
                    {PurifyPrefs.ORDER_FREE, "免费单入口", "免费单邀请条与标签提示"},
                    {PurifyPrefs.ORDER_BONUS_BUTTONS, "「领元宝/红包/奖励」按钮", "订单操作按钮中的元宝/奖励/红包类入口"},
            }},
            {"全局", new String[][]{
                    {PurifyPrefs.FLOATING_WINDOW, "浮动红包小窗", "主页/我的页悬浮红包小图标"},
            }},
    };

    private SharedPreferences localPrefs;
    private volatile SharedPreferences remotePrefs;
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        localPrefs = getSharedPreferences(PurifyPrefs.PREFS_NAME, MODE_PRIVATE);
        registerServiceListener();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F6F7F9"));
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
        title.setTextColor(Color.parseColor("#1A1A1A"));
        layout.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("小蚕霸王餐 Xposed / LSPosed 净化模块");
        subTitle.setTextSize(13);
        subTitle.setTextColor(Color.parseColor("#888888"));
        subTitle.setPadding(0, dp2px(4), 0, dp2px(16));
        layout.addView(subTitle);

        // Status Card
        LinearLayout statusCard = createCard();
        TextView statusBadge = new TextView(this);
        statusBadge.setText("● 模块已就绪");
        statusBadge.setTextSize(15);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setTextColor(Color.parseColor("#07C160"));
        statusCard.addView(statusBadge);

        TextView statusDesc = new TextView(this);
        statusDesc.setText("目标应用: com.realtech.xiaocan (小蚕霸王餐)\n在 LSPosed 勾选小蚕霸王餐后重启小蚕即可生效。");
        statusDesc.setTextSize(13);
        statusDesc.setTextColor(Color.parseColor("#555555"));
        statusDesc.setPadding(0, dp2px(6), 0, 0);
        statusCard.addView(statusDesc);
        layout.addView(statusCard);

        // Settings toggles
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("功能开关");
        settingsTitle.setTextSize(16);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsTitle.setTextColor(Color.parseColor("#333333"));
        settingsTitle.setPadding(0, dp2px(16), 0, dp2px(8));
        layout.addView(settingsTitle);

        TextView settingsHint = new TextView(this);
        settingsHint.setText("开关默认开启 = 隐藏对应入口（保持净化）。关闭开关即恢复展示该功能入口；修改后需强制停止并重启小蚕霸王餐生效。广告与弹窗拦截始终开启，不受开关影响。");
        settingsHint.setTextSize(12);
        settingsHint.setTextColor(Color.parseColor("#999999"));
        settingsHint.setPadding(dp2px(14), 0, dp2px(14), dp2px(8));
        layout.addView(settingsHint);

        buildToggles(layout);

        // Built-in features (always on)
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("内置净化（默认开启）");
        sectionTitle.setTextSize(16);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(Color.parseColor("#333333"));
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
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) refreshSwitches();
                        }
                    });
                }

                @Override
                public void onServiceDied(XposedService service) {
                    remotePrefs = null;
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
            groupTitle.setTextColor(Color.parseColor("#FF6600"));
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
                    divider.setBackgroundColor(Color.parseColor("#F0F0F0"));
                    card.addView(divider, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp2px(1)));
                }
            }
            parent.addView(card);
        }
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
        t.setTextColor(Color.parseColor("#222222"));
        textColumn.addView(t);

        TextView d = new TextView(this);
        d.setText(itemDesc);
        d.setTextSize(11);
        d.setTextColor(Color.parseColor("#999999"));
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
        boolean def = localPrefs.getBoolean(key, true);
        SharedPreferences rp = remotePrefs;
        if (rp != null) {
            try {
                return rp.getBoolean(key, def);
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private void setHide(String key, boolean hide) {
        localPrefs.edit().putBoolean(key, hide).apply();
        SharedPreferences rp = remotePrefs;
        if (rp != null) {
            try {
                rp.edit().putBoolean(key, hide).apply();
            } catch (Throwable ignored) {
            }
        }
        android.util.Log.i("XiaoCanPurify", "Setting " + key + " -> " + (hide ? "HIDE" : "SHOW"));
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
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
        t.setTextColor(Color.parseColor("#222222"));
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(Color.parseColor("#666666"));
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
