package com.genius.xiaocanpurify;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资源位 slug 全量列表页：内置黑名单/保护名单/候选名单（逐 slug 附说明）+
 * 最近发现的全部 slug。设置页卡片空间有限、长列表截断展示不下，
 * 统一跳转本页查看；保护名单与最近发现的 slug 可点按切换是否加入额外屏蔽
 * （保护名单点按追加 = 覆盖保护判定，如大牌 INSERT_POSITION 配置）。
 */
public class SlugListActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp2px(14);
        list.setPadding(p, p, p, p);
        scroll.addView(list);
        setContentView(scroll);
        buildSections();
    }

    private void buildSections() {
        addSectionHeader("内置黑名单（响应层直接拦截，"
                + PurifyPrefs.DEFAULT_SLUG_DESC.size() + " 个，只读）");
        for (Map.Entry<String, String> e : PurifyPrefs.DEFAULT_SLUG_DESC.entrySet()) {
            addDetailRow(e.getKey() + " — " + e.getValue());
        }

        addSectionHeader("保护名单（黑名单/特征识别均不删；点按切换是否追加到额外屏蔽以覆盖保护，"
                + PurifyPrefs.PROTECTED_SLUG_DESC.size() + " 个）");
        for (Map.Entry<String, String> e : PurifyPrefs.PROTECTED_SLUG_DESC.entrySet()) {
            addToggleRow(e.getKey(), e.getValue());
        }

        addSectionHeader("候选名单");
        addDetailRow("无（原 3 条已于 2026-11-13 移入内置黑名单）");

        String discovered = localPrefs().getString(PurifyPrefs.PLACEMENT_DISCOVERED, "");
        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        if (discovered != null) {
            for (String s : discovered.split("[,，\\s]+")) {
                if (!s.trim().isEmpty()) slugs.add(s.trim());
            }
        }
        addSectionHeader("最近发现的资源位（" + slugs.size()
                + " 个；点按切换是否加入额外屏蔽）");
        if (slugs.isEmpty()) {
            addDetailRow("（暂无，打开一次小蚕首页后自动生成）");
        } else {
            for (String slug : slugs) addToggleRow(slug, "");
        }
    }

    // ---------------------------------------------------------------- 行构建

    private void addSectionHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(ThemeHelper.textHeading(this));
        t.setPadding(0, dp2px(14), 0, dp2px(6));
        list.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDetailRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(ThemeHelper.textBody(this));
        t.setPadding(0, dp2px(3), 0, dp2px(3));
        list.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 可点按切换行：在额外屏蔽里 → 灰色 + 删除线 + 「（已追加）」；否则正文色。 */
    private void addToggleRow(final String slug, final String desc) {
        final TextView t = new TextView(this);
        t.setTextSize(12);
        t.setPadding(0, dp2px(3), 0, dp2px(3));
        styleToggleRow(t, slug, desc);
        t.setOnClickListener(v -> {
            boolean added = toggleExtraSlug(slug);
            styleToggleRow(t, slug, desc);
            Toast.makeText(this, (added ? "已追加到额外屏蔽：" : "已从额外屏蔽移除：") + slug,
                    Toast.LENGTH_SHORT).show();
        });
        list.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void styleToggleRow(TextView t, String slug, String desc) {
        String base = slug + (desc == null || desc.isEmpty() ? "" : " — " + desc);
        if (isInExtra(slug)) {
            t.setText(base + "　（已追加）");
            t.setPaintFlags(t.getPaintFlags()
                    | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            t.setTextColor(ThemeHelper.textHint(this));
        } else {
            t.setText(base);
            t.setPaintFlags(t.getPaintFlags()
                    & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            t.setTextColor(ThemeHelper.textBody(this));
        }
    }

    // ---------------------------------------------------------------- 额外屏蔽读写

    private android.content.SharedPreferences localPrefs() {
        return getSharedPreferences(PurifyPrefs.PREFS_NAME, MODE_PRIVATE);
    }

    private Set<String> extraSet() {
        Set<String> set = new LinkedHashSet<>();
        for (String s : localPrefs().getString(PurifyPrefs.PLACEMENT_BLOCK_EXTRA, "")
                .split("[,，\\s]+")) {
            if (!s.trim().isEmpty()) set.add(s.trim().toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private boolean isInExtra(String slug) {
        return extraSet().contains(slug.toUpperCase(Locale.ROOT));
    }

    /** 切换 slug 是否在额外屏蔽：在则移除、不在则追加。返回 true 表示切换后为「已追加」。 */
    private boolean toggleExtraSlug(String slug) {
        Set<String> set = extraSet();
        String up = slug.toUpperCase(Locale.ROOT);
        boolean removed = set.remove(up);
        if (!removed) set.add(up);
        localPrefs().edit().putString(PurifyPrefs.PLACEMENT_BLOCK_EXTRA,
                TextUtils.join(",", set)).apply();
        return !removed;
    }

    private int dp2px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
