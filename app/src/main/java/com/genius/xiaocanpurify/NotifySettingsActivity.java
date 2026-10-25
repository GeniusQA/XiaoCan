package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知设置页（模块侧）：渠道选择 + 渠道凭据配置 + 事件订阅 + 测试发送。
 * 配置以 JSON 字符串存本地与远程偏好（PurifyPrefs.NOTIFY_CONFIG），
 * 宿主经远程偏好读取后由 Notifier 在事件发生时推送。
 */
public class NotifySettingsActivity extends Activity {
    private static final String[][] CHANNELS = {
            {"off", "关闭"},
            {"wecom", "企业微信机器人"},
            {"dingtalk", "钉钉机器人"},
            {"feishu", "飞书机器人"},
            {"serverchan", "Server酱"},
            {"bark", "Bark (iOS)"},
            {"smtp", "邮件 (SMTP)"},
            {"telegram", "Telegram Bot"},
            {"gotify", "Gotify"},
            {"discord", "Discord Webhook"},
            {"qq", "QQ (OneBot)"},
            {"wxpusher", "WxPusher"},
            {"custom", "自定义 Webhook"},
    };

    private JSONObject cfg;
    private LinearLayout content;
    private FrameLayout overlayHost;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = loadConfig();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ThemeHelper.pageBg(this));
        overlayHost = root;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        content.setPadding(pad, dp2px(12), pad, dp2px(24));
        content.setFitsSystemWindows(true);
        scroll.addView(content);

        buildTopBar();
        buildChannelSection();
        buildEventSection();
        buildTestButton();
        setContentView(root);
    }

    // ---------------------------------------------------------------- UI

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(ThemeHelper.textTitle(this));
        back.setPadding(0, dp2px(6), dp2px(16), dp2px(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("通知设置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textTitle(this));
        bar.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(bar);
    }

    private void buildChannelSection() {
        sectionTitle("通知渠道");
        LinearLayout card = createCard();
        String current = cfg.optString("channel", "off");
        for (String[] ch : CHANNELS) {
            final String key = ch[0];
            boolean selected = key.equals(current);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp2px(11), 0, dp2px(11));
            TextView label = new TextView(this);
            label.setText(ch[1]);
            label.setTextSize(14);
            label.setTextColor(ThemeHelper.textBody(this));
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (selected) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(15);
                check.setTypeface(Typeface.DEFAULT_BOLD);
                check.setTextColor(ThemeHelper.accentRed(this));
                row.addView(check);
            }
            row.setOnClickListener(v -> {
                try {
                    cfg.put("channel", key);
                    cfg.put("enabled", "off".equals(key) ? "0" : "1");
                } catch (Throwable ignored) {}
                saveConfig();
                rebuild();
            });
            card.addView(row);
        }
        content.addView(card);

        // 渠道字段（随渠道变化）
        String channel = cfg.optString("channel", "off");
        if ("off".equals(channel)) return;
        sectionTitle("渠道配置");
        LinearLayout fields = createCard();
        for (Map.Entry<String, String> e : fieldDefs(channel).entrySet()) {
            addFieldRow(fields, e.getKey(), e.getValue());
        }
        content.addView(fields);
    }

    /** 每个渠道需要填写的字段：key -> 显示名。 */
    private Map<String, String> fieldDefs(String channel) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (channel) {
            case "wecom":
            case "dingtalk":
            case "feishu":
            case "custom":
                m.put("webhook", "Webhook 地址");
                break;
            case "serverchan":
                m.put("serverchan_key", "SendKey");
                break;
            case "bark":
                m.put("bark_key", "Bark Key");
                break;
            case "smtp":
                m.put("smtp_host", "SMTP 服务器");
                m.put("smtp_port", "SMTP 端口（465/587）");
                m.put("smtp_user", "SMTP 用户名");
                m.put("smtp_pass", "SMTP 密码/授权码");
                m.put("smtp_from", "发件人邮箱");
                m.put("smtp_from_name", "发件人姓名");
                m.put("smtp_to", "收件人邮箱");
                break;
            case "telegram":
                m.put("tg_token", "Bot Token");
                m.put("tg_chat", "Chat ID");
                break;
            case "gotify":
                m.put("gotify_url", "Gotify 服务地址");
                m.put("gotify_token", "应用 Token");
                break;
            case "discord":
                m.put("discord_webhook", "Discord Webhook 地址");
                break;
            case "qq":
                m.put("qq_url", "OneBot 服务地址");
                m.put("qq_token", "Access Token");
                m.put("qq_uid", "目标 QQ 号");
                break;
            case "wxpusher":
                m.put("wxpusher_token", "AppToken");
                m.put("wxpusher_uid", "接收者 UID");
                break;
            default:
                break;
        }
        return m;
    }

    private void addFieldRow(LinearLayout card, String key, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp2px(10), 0, dp2px(10));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14);
        title.setTextColor(ThemeHelper.textHeading(this));
        row.addView(title);

        String value = cfg.optString(key, "");
        boolean secret = key.contains("pass") || key.contains("key");
        TextView desc = new TextView(this);
        desc.setText(value.isEmpty() ? "未配置，点击填写"
                : (secret ? mask(value) : value));
        desc.setTextSize(12);
        desc.setTextColor(value.isEmpty() ? ThemeHelper.textHint(this)
                : ThemeHelper.textSecondary(this));
        desc.setPadding(0, dp2px(2), 0, 0);
        row.addView(desc);

        row.setOnClickListener(v -> showInput(key, label, value, secret));
        card.addView(row);

        View d = new View(this);
        d.setBackgroundColor(ThemeHelper.cardStroke(this));
        card.addView(d, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(1)));
    }

    private void buildEventSection() {
        sectionTitle("通知事件");
        LinearLayout card = createCard();
        String events = cfg.optString("events", "daily");
        addEventRow(card, Notifier.EV_DAILY, "一键日常结果", "每天任务执行完成后推送汇总", events);
        addEventRow(card, Notifier.EV_SPIKE, "元宝秒杀提醒", "秒杀商品有货 / 兑换成功时推送", events);
        addEventRow(card, Notifier.EV_ERROR, "异常告警", "凭证缺失、任务失败等异常推送", events);
        content.addView(card);
    }

    private void addEventRow(LinearLayout card, String ev, String title, String desc,
                             String events) {
        boolean on = events.contains(ev);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp2px(10), 0, dp2px(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(ThemeHelper.textHeading(this));
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(ThemeHelper.textHint(this));
        col.addView(d);
        row.addView(col);

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((v, checked) -> {
            String cur = cfg.optString("events", "daily");
            try {
                cfg.put("events", rebuildEvents(cur, ev, checked));
            } catch (Throwable ignored) {}
            saveConfig();
        });
        row.addView(sw);
        card.addView(row);
    }

    /** 当前渠道的必填项是否都已填写（发件人姓名为可选项）。 */
    private boolean isChannelConfigured() {
        String channel = cfg.optString("channel", "off");
        if ("off".equals(channel)) return false;
        Map<String, String> fields = fieldDefs(channel);
        if (fields.isEmpty()) return false;
        for (String key : fields.keySet()) {
            if ("smtp_from_name".equals(key)) continue;
            String v = cfg.optString(key, "");
            if (v == null || v.trim().isEmpty()) return false;
        }
        return true;
    }

    private String rebuildEvents(String cur, String ev, boolean add) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : cur.split(",")) {
            if (!s.trim().isEmpty()) set.add(s.trim());
        }
        if (add) set.add(ev);
        else set.remove(ev);
        return String.join(",", set);
    }

    private void buildTestButton() {
        final boolean ready = isChannelConfigured();
        TextView btn = new TextView(this);
        btn.setText(ready ? "发送测试通知" : "请先完成必填配置");
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(ready ? 0xFFFFFFFF : 0x66FFFFFF);
        btn.setBackground(roundRect(ready
                ? ThemeHelper.accentGreen(this) : 0xFF3A3A3A));
        btn.setPadding(0, dp2px(12), 0, dp2px(12));
        btn.setEnabled(ready);
        btn.setOnClickListener(v -> {
            if (!isChannelConfigured()) {
                Toast.makeText(this, "渠道必填项未填写完整", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "正在发送…", Toast.LENGTH_SHORT).show();
            Notifier.sendAsync(this, cfg, "小蚕助理 · 测试通知",
                    "这是一条测试通知，收到即表示渠道配置正确。\n时间："
                            + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.US).format(new java.util.Date()));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp2px(18);
        content.addView(btn, lp);
    }

    // ---------------------------------------------------------------- 交互

    private void showInput(String key, String label, String current, boolean secret) {
        // 用系统 AlertDialog 而非自绘遮罩：键盘弹起时系统会自动把对话框
        // 挪到可视区上方，自绘居中卡片会被键盘顶出屏幕导致「保存」点不到
        //（用户实测收件人邮箱存不上）
        EditText input = new EditText(this);
        input.setText(current);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setHint("请输入" + label);
        int p = dp2px(12);
        input.setPadding(p, dp2px(8), p, dp2px(8));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp2px(16), dp2px(4), dp2px(16), 0);
        wrap.addView(input);

        new android.app.AlertDialog.Builder(this)
                .setTitle(label)
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        cfg.put(key, input.getText() == null
                                ? "" : input.getText().toString().trim());
                    } catch (Throwable ignored) {}
                    saveConfig();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 存储

    private JSONObject loadConfig() {
        try {
            SharedPreferences p = getSharedPreferences("purify_settings", MODE_PRIVATE);
            String raw = p.getString(PurifyPrefs.NOTIFY_CONFIG, null);
            if (raw != null && !raw.isEmpty()) return new JSONObject(raw);
        } catch (Throwable ignored) {}
        JSONObject o = new JSONObject();
        try {
            o.put("channel", "off");
            o.put("enabled", "0");
            o.put("events", "daily");
        } catch (Throwable ignored) {}
        return o;
    }

    private void saveConfig() {
        String raw = cfg.toString();
        try {
            getSharedPreferences("purify_settings", MODE_PRIVATE).edit()
                    .putString(PurifyPrefs.NOTIFY_CONFIG, raw).apply();
        } catch (Throwable ignored) {}
        // 同步到远程偏好（宿主读取）
        try {
            io.github.libxposed.service.XposedService svc = MainActivity.getService();
            if (svc != null) {
                SharedPreferences rp = svc.getRemotePreferences("purify_settings");
                if (rp != null) rp.edit().putString(PurifyPrefs.NOTIFY_CONFIG, raw).commit();
            }
        } catch (Throwable ignored) {}
    }

    private void rebuild() {
        content.removeAllViews();
        buildTopBar();
        buildChannelSection();
        buildEventSection();
        buildTestButton();
    }

    // ---------------------------------------------------------------- 工具

    private void sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ThemeHelper.textSecondary(this));
        t.setPadding(dp2px(2), dp2px(14), 0, dp2px(6));
        content.addView(t);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect2(ThemeHelper.cardBg(this), 12));
        int p = dp2px(14);
        card.setPadding(p, dp2px(4), p, dp2px(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(4);
        card.setLayoutParams(lp);
        return card;
    }

    private String mask(String s) {
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private GradientDrawable roundRect(int color) {
        return roundRect2(color, 8);
    }

    private GradientDrawable roundRect2(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp2px(radiusDp));
        return d;
    }

    private int dp2px(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
