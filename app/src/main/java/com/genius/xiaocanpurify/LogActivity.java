package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 查看日志页（参考 HyperLyrics LogPage 的功能编排，程序化 UI）：
 *  - 应用日志列表：等级徽章 + 来源 + 时间 + 内容，点击展开/收起；
 *  - 右上角菜单：日志等级 / 导出日志 / 清空日志 / 自动清理 / 清理记录；
 *  - 打开时发 ACTION_LOG_PULL 拉取 Hook 侧日志，ACTION_LOG_PUSH 回传后自动刷新；
 *  - 导出走 ACTION_CREATE_DOCUMENT（SAF），用户选择保存位置后写入。
 * 所有颜色取自 ThemeHelper，深浅色跟随模块主题。
 */
public class LogActivity extends Activity {
    private static final int REQ_EXPORT = 1001;
    private static final String[] LEVEL_OPTIONS = {"ALL", "I", "W", "E"};
    private static final String[] LEVEL_LABELS = {"全部", "仅信息 I", "仅警告 W", "仅错误 E"};

    private LinearLayout listContainer;
    private TextView countView;
    private String levelFilter = "ALL";
    private String currentTab = LogStore.SOURCE_APP;
    private final java.util.Map<String, TextView> tabViews = new java.util.LinkedHashMap<>();
    private BroadcastReceiver pushReceiver;
    private FrameLayout overlayHost;
    private PopupWindow currentPopup;
    /** 页面可见期间的 Hook 日志轮询间隔。 */
    private static final long POLL_INTERVAL_MS = 3000;
    private final android.os.Handler pollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            requestHookLogs();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ThemeHelper.pageBg(this));
        overlayHost = root;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFitsSystemWindows(true);
        int pad = dp2px(20);
        content.setPadding(pad, dp2px(12), pad, dp2px(12));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：返回 + 标题 + 菜单
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(ThemeHelper.textTitle(this));
        back.setPadding(0, dp2px(6), dp2px(16), dp2px(6));
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("查看日志");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textTitle(this));
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView menuBtn = new TextView(this);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(22);
        menuBtn.setTypeface(Typeface.DEFAULT_BOLD);
        menuBtn.setTextColor(ThemeHelper.textTitle(this));
        menuBtn.setPadding(dp2px(12), dp2px(6), 0, dp2px(6));
        menuBtn.setOnClickListener(this::showMenu);
        topBar.addView(menuBtn);
        content.addView(topBar);

        // Tab 行：「应用日志」 + 刷新
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(0, dp2px(8), 0, dp2px(8));
        tabViews.clear();
        tabRow.addView(buildTab("应用日志", LogStore.SOURCE_APP));
        tabRow.addView(buildTab("模块日志", LogStore.SOURCE_MODULE));

        TextView refresh = new TextView(this);
        refresh.setText("↻ 刷新");
        refresh.setTextSize(13);
        refresh.setTextColor(ThemeHelper.accentGreen(this));
        refresh.setPadding(dp2px(16), 0, 0, 0);
        refresh.setOnClickListener(v -> { requestHookLogs(); reload(); });
        tabRow.addView(refresh);
        content.addView(tabRow);

        // 日志列表
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ThemeHelper.cardBg(this));
        int r = dp2px(10);
        scroll.setBackground(roundRect(ThemeHelper.cardBg(this)));
        scroll.addView(buildListHolder());
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部统计
        countView = new TextView(this);
        countView.setTextSize(12);
        countView.setTextColor(ThemeHelper.textSecondary(this));
        countView.setPadding(0, dp2px(8), 0, 0);
        content.addView(countView);

        setContentView(root);

        registerPushReceiver();
        refreshTabStyles();
        requestHookLogs();
        reload();
    }

    private LinearLayout buildListHolder() {
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        return listContainer;
    }

    /** Tab 药丸按钮：选中态高亮，点击切换日志来源并刷新。 */
    private View buildTab(String label, String source) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(14);
        tab.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));
        tab.setOnClickListener(v -> {
            if (currentTab.equals(source)) return;
            currentTab = source;
            if (LogStore.SOURCE_MODULE.equals(source)) requestHookLogs();
            refreshTabStyles();
            reload();
        });
        tabViews.put(source, tab);
        return tab;
    }

    private void refreshTabStyles() {
        for (java.util.Map.Entry<String, TextView> e : tabViews.entrySet()) {
            TextView t = e.getValue();
            boolean selected = e.getKey().equals(currentTab);
            t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setTextColor(selected ? ThemeHelper.textHeading(this)
                    : ThemeHelper.textSecondary(this));
            t.setBackground(selected ? roundRect(ThemeHelper.cardStroke(this)) : null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到页面立即拉取一次；页面可见期间轮询拉取 Hook 侧日志，
        // 宿主推送回来后接收器会自动刷新列表（LogStore 按行去重，重复拉取无害）
        requestHookLogs();
        reload();
        pollHandler.removeCallbacks(pollTask);
        pollHandler.postDelayed(pollTask, POLL_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollTask);
    }

    @Override
    protected void onDestroy() {
        if (pushReceiver != null) {
            try {
                unregisterReceiver(pushReceiver);
            } catch (Throwable ignored) {}
            pushReceiver = null;
        }
        pollHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 数据

    private void registerPushReceiver() {
        try {
            pushReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        String[] lines = i.getStringArrayExtra("lines");
                        if (lines != null && lines.length > 0) {
                            LogStore.appendHookLogs(LogActivity.this, lines);
                        }
                        reload();
                    } catch (Throwable ignored) {}
                }
            };
            IntentFilter f = new IntentFilter("com.genius.xiaocanpurify.ACTION_LOG_PUSH");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pushReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(pushReceiver, f);
            }
        } catch (Throwable ignored) {}
    }

    /** 请求 Hook 侧日志：推送异步，稍后自动刷新一次兜底。 */
    private void requestHookLogs() {
        try {
            Intent i = new Intent("com.genius.xiaocanpurify.ACTION_LOG_PULL");
            i.setPackage("com.realtech.xiaocan");
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private void reload() {
        listContainer.removeAllViews();
        boolean isModule = LogStore.SOURCE_MODULE.equals(currentTab);
        List<String[]> logs = LogStore.readLogs(this, levelFilter, currentTab);
        if (logs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(isModule
                    ? "暂无模块日志\n打开小蚕后 Hook 日志会自动收集到这里"
                    : "暂无日志");
            empty.setTextSize(14);
            empty.setTextColor(ThemeHelper.textHint(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(48), 0, dp2px(48));
            listContainer.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (String[] e : logs) {
                listContainer.addView(buildLogItem(e));
            }
        }
        countView.setText((isModule ? "模块日志" : "应用日志") + " 共 " + logs.size()
                + " 条 · 等级: " + ("ALL".equals(levelFilter) ? "全部" : levelFilter));
    }

    /** 单条日志：徽章 + tag + 时间，内容默认 2 行，点击展开/收起。 */
    private View buildLogItem(String[] e) {
        String time = e[0], level = e[1], tag = e[2], msg = e[3];
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        int p = dp2px(12);
        item.setPadding(p, dp2px(8), p, dp2px(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(level);
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        int bc = "E".equals(level) ? ThemeHelper.accentRed(this)
                : "W".equals(level) ? ThemeHelper.accentOrange(this)
                : ThemeHelper.accentGreen(this);
        badge.setTextColor(0xFFFFFFFF);
        badge.setBackground(roundRect(bc));
        badge.setPadding(dp2px(5), dp2px(1), dp2px(5), dp2px(1));
        head.addView(badge);

        TextView tagView = new TextView(this);
        tagView.setText(tag);
        tagView.setTextSize(11);
        tagView.setTypeface(Typeface.DEFAULT_BOLD);
        tagView.setTextColor(ThemeHelper.accentOrangeRed(this));
        tagView.setPadding(dp2px(8), 0, 0, 0);
        head.addView(tagView);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextSize(11);
        timeView.setTextColor(ThemeHelper.textWeak(this));
        timeView.setPadding(dp2px(8), 0, 0, 0);
        head.addView(timeView);

        item.addView(head);

        TextView msgView = new TextView(this);
        msgView.setText(msg);
        msgView.setTextSize(12);
        msgView.setTextColor(ThemeHelper.textBody(this));
        msgView.setPadding(0, dp2px(4), 0, 0);
        msgView.setMaxLines(2);
        msgView.setOnClickListener(v ->
                msgView.setMaxLines(msgView.getMaxLines() == 2 ? 200 : 2));
        item.addView(msgView);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(ThemeHelper.cardStroke(this));
        listContainer.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(1)));
        return item;
    }

    // ---------------------------------------------------------------- 菜单

    private void showMenu(View anchor) {
        LinearLayout menuView = popupContainer();

        popupItem(menuView, "日志等级", levelSummaryText(), false, v -> showLevelMenu(anchor));
        popupItem(menuView, "导出日志", null, false, v -> {
            dismissPopup();
            exportLogs();
        });
        popupItem(menuView, "清空日志", null, false, v -> {
            dismissPopup();
            confirmClear();
        });
        popupItem(menuView, "自动清理", cleanupSummaryText(), false, v -> showCleanupMenu(anchor));
        popupItem(menuView, "清理记录", null, false, v -> {
            dismissPopup();
            showCleanupHistory();
        });
        showPopup(anchor, menuView);
    }

    private void showLevelMenu(View anchor) {
        LinearLayout menuView = popupContainer();
        for (int i = 0; i < LEVEL_OPTIONS.length; i++) {
            String opt = LEVEL_OPTIONS[i];
            String label = LEVEL_LABELS[i];
            boolean selected = opt.equals(levelFilter);
            popupItem(menuView, label, null, selected, v -> {
                levelFilter = opt;
                reload();
                // 重建弹窗，让右侧红色勾选立即反映最新选择
                showLevelMenu(anchor);
            });
        }
        showPopup(anchor, menuView);
    }

    private void showCleanupMenu(View anchor) {
        LinearLayout menuView = popupContainer();
        String[] labels = {"关闭", "每 1 小时", "每 24 小时", "每 7 天"};
        for (int i = 0; i < LogStore.INTERVAL_CHOICES.length; i++) {
            final int hours = LogStore.INTERVAL_CHOICES[i];
            final String label = labels[i];
            boolean selected = hours == LogStore.getCleanupIntervalHours(this);
            popupItem(menuView, label, null, selected, v -> {
                LogStore.setCleanupIntervalHours(this, hours);
                LogStore.maybeAutoCleanup(this);
                Toast.makeText(this, "自动清理：" + label, Toast.LENGTH_SHORT).show();
                showCleanupMenu(anchor);
            });
        }
        showPopup(anchor, menuView);
    }

    private void showPopup(View anchor, LinearLayout menuView) {
        try {
            dismissPopup();
            PopupWindow pw = new PopupWindow(menuView, dp2px(190),
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            pw.setOutsideTouchable(true);
            pw.setOnDismissListener(() -> {
                if (currentPopup == pw) currentPopup = null;
            });
            currentPopup = pw;
            pw.showAsDropDown(anchor, dp2px(-8), dp2px(4));
        } catch (Throwable ignored) {}
    }

    private void dismissPopup() {
        PopupWindow pw = currentPopup;
        currentPopup = null;
        if (pw != null) {
            try {
                pw.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    private LinearLayout popupContainer() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(ThemeHelper.cardBg(this));
        bg.setCornerRadius(dp2px(12));
        bg.setStroke(dp2px(1), ThemeHelper.cardStroke(this));
        menu.setBackground(bg);
        menu.setElevation(dp2px(8));
        int p = dp2px(6);
        menu.setPadding(p, p, p, p);
        return menu;
    }

    /** 菜单项：标题 + 可选摘要行；selected=true 时标题右侧显示红色 ✓。 */
    private void popupItem(LinearLayout parent, String title, String summary, boolean selected,
                           View.OnClickListener onClick) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp2px(14), dp2px(10), dp2px(14), dp2px(10));
        item.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(v);
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(ThemeHelper.textHeading(this));
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextSize(15);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setTextColor(ThemeHelper.accentRed(this));
            head.addView(check);
        }
        item.addView(head);

        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextSize(12);
            s.setTextColor(ThemeHelper.textSecondary(this));
            item.addView(s);
        }
        parent.addView(item);
    }

    // ---------------------------------------------------------------- 导出

    private void exportLogs() {
        try {
            String kind = LogStore.SOURCE_MODULE.equals(currentTab) ? "module" : "app";
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "xiaocanpurify_" + kind + "_logs_"
                    + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date())
                    + ".txt");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                Toast.makeText(this, "导出失败：无法写入所选位置", Toast.LENGTH_SHORT).show();
                return;
            }
            String text = LogStore.exportText(this, levelFilter, currentTab);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.close();
            int lines = text.split("\n").length;
            Toast.makeText(this, "已导出日志（" + lines + " 行）", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 清空 / 记录

    private void confirmClear() {
        showDialog("清空日志",
                "将删除全部应用日志与已收集的 Hook 日志，且不可恢复。确定清空吗？",
                "清空", ThemeHelper.accentRed(this), () -> {
                    boolean ok = LogStore.clearAll(this, LogStore.TRIGGER_MANUAL);
                    Toast.makeText(this, ok ? "日志已清空" : "清空失败", Toast.LENGTH_SHORT).show();
                    reload();
                });
    }

    private void showCleanupHistory() {
        List<String[]> records = LogStore.readCleanupHistory(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp2px(14);
        body.setPadding(p, p, p, p);
        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无清理记录");
            empty.setTextSize(13);
            empty.setTextColor(ThemeHelper.textHint(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(16), 0, dp2px(16));
            body.addView(empty);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (String[] r : records) {
                long ts = Long.parseLong(r[0]);
                boolean ok = "1".equals(r[2]);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp2px(8), 0, dp2px(8));

                TextView line1 = new TextView(this);
                line1.setText(fmt.format(new Date(ts))
                        + (LogStore.TRIGGER_SCHEDULED.equals(r[1]) ? " · 自动清理" : " · 手动清空")
                        + (ok ? " · 成功" : " · 失败"));
                line1.setTextSize(13);
                line1.setTextColor(ok ? ThemeHelper.textHeading(this) : ThemeHelper.accentRed(this));
                row.addView(line1);

                TextView line2 = new TextView(this);
                line2.setText(r[3]);
                line2.setTextSize(11);
                line2.setTextColor(ThemeHelper.textWeak(this));
                row.addView(line2);

                body.addView(row);
                View divider = new View(this);
                divider.setBackgroundColor(ThemeHelper.cardStroke(this));
                body.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)));
            }
        }
        showDialogView("清理记录", body);
    }

    // ---------------------------------------------------------------- 通用弹窗（自绘，深浅色一致）

    private void showDialog(String title, String message, String positive,
                            int positiveColor, Runnable onPositive) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp2px(14);
        body.setPadding(p, p, p, p);
        TextView msg = new TextView(this);
        msg.setText(message);
        msg.setTextSize(14);
        msg.setTextColor(ThemeHelper.textBody(this));
        body.addView(msg);
        showDialogViewWithButtons(title, body, positive, positiveColor, onPositive);
    }

    private void showDialogView(String title, LinearLayout body) {
        showDialogViewWithButtons(title, body, null, 0, null);
    }

    private void showDialogViewWithButtons(String title, LinearLayout body, String positive,
                                           int positiveColor, Runnable onPositive) {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setOnClickListener(v -> overlayHost.removeView(overlay));

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
                dp2px(280), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ThemeHelper.textHeading(this));
        card.addView(t);
        card.addView(body);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.RIGHT);
        btnRow.setPadding(0, dp2px(14), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(ThemeHelper.textSecondary(this));
        cancel.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));
        cancel.setOnClickListener(v -> overlayHost.removeView(overlay));
        btnRow.addView(cancel);

        if (positive != null) {
            TextView ok = new TextView(this);
            ok.setText(positive);
            ok.setTextSize(14);
            ok.setTypeface(Typeface.DEFAULT_BOLD);
            ok.setTextColor(positiveColor);
            ok.setPadding(dp2px(16), dp2px(8), dp2px(4), dp2px(8));
            ok.setOnClickListener(v -> {
                overlayHost.removeView(overlay);
                if (onPositive != null) onPositive.run();
            });
            btnRow.addView(ok);
        }
        card.addView(btnRow);
        overlayHost.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------- 摘要

    private String levelSummaryText() {
        return "ALL".equals(levelFilter) ? "全部" : levelFilter;
    }

    private String cleanupSummaryText() {
        int h = LogStore.getCleanupIntervalHours(this);
        if (h <= 0) return "关闭";
        if (h == 1) return "每 1 小时";
        if (h == 24) return "每 24 小时";
        return "每 " + h + " 小时";
    }

    // ---------------------------------------------------------------- 工具

    private android.graphics.drawable.Drawable roundRect(int color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp2px(8));
        return d;
    }

    private int dp2px(float dp) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
