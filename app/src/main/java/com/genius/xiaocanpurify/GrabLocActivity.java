package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 抢单位置设置页（原「定位缓存」弹窗改为独立页面，参照「查看日志」的跳转模式，
 * 彻底绕开 Dialog 在部分 ROM 上的兼容性问题）：
 *  - 顶栏：返回 + 标题；
 *  - 内容：当前地址卡（城市/地图源标签 + 获取本机定位）、搜索选点、
 *    坐标微调 + 反查地址、历史位置（单行列表 + 编辑/删除）；
 *  - 底部：取消 / 确定下发 ACTION_SET_GRAB_LOC 广播给宿主。
 * 所有颜色取自 ThemeHelper，深浅色跟随模块主题。
 */
public class GrabLocActivity extends Activity {
    private static final int REQ_REALTIME_LOC = 1001;
    private boolean pendingRealtimeLoc;
    private Runnable realtimeLocAction;

    @Override
    public void finish() {
        // 诊断：页面被自动关闭（resume 后 ~200ms），覆写 finish 打印调用栈定位发起者
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            new Throwable("who-calls-finish").printStackTrace(new java.io.PrintWriter(sw));
            LogStore.append(this, "W", "Loc",
                    "GrabLocActivity.finish() called\n" + sw);
        } catch (Throwable ignored) {}
        super.finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogStore.append(this, "I", "Loc", "GrabLocActivity onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogStore.append(this, "I", "Loc",
                "GrabLocActivity onPause isFinishing=" + isFinishing());
    }

    @Override
    protected void onDestroy() {
        LogStore.append(this, "I", "Loc",
                "GrabLocActivity onDestroy isFinishing=" + isFinishing());
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 统一配色（跟随深浅色主题），避免控件默认浅色样式与深色卡片混色看不清
        final boolean dark = ThemeHelper.isDark(this);
        final int cInput = dark ? 0xFF2A2A2A : 0xFFF2F3F5;
        final int cInputStroke = dark ? 0xFF3A3A3A : 0xFFE0E2E6;
        final int cBtn = dark ? 0xFF303030 : 0xFFEDEFF2;
        final int cTextBody = ThemeHelper.textBody(this);
        final int cTextSub = ThemeHelper.textSecondary(this);
        final int cTextHint = ThemeHelper.textHint(this);
        // 主色（蓝色主按钮/强调文字）
        final int cPrimary = dark ? 0xFF4C8DFF : 0xFF3B6EF5;
        final int cOnPrimary = 0xFFFFFFFF;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ThemeHelper.pageBg(this));
        page.setFitsSystemWindows(true);
        setContentView(page);

        // 顶栏：返回 + 标题
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(ThemeHelper.textTitle(this));
        back.setPadding(dp2px(6), dp2px(6), dp2px(16), dp2px(6));
        back.setOnClickListener(v -> finish());
        topBar.addView(back);
        TextView title = new TextView(this);
        title.setText("定位缓存");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.textTitle(this));
        topBar.addView(title);
        page.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        page.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p14 = dp2px(14);
        box.setPadding(p14, dp2px(8), p14, dp2px(4));
        pageScroll.addView(box);

        // ── 当前地址块（宿主定位快照 / 上次选定） ──
        android.widget.LinearLayout curBlock = new android.widget.LinearLayout(this);
        curBlock.setOrientation(android.widget.LinearLayout.VERTICAL);
        curBlock.setBackground(roundBg(cInput, cInputStroke));
        int cbp = dp2px(12);
        curBlock.setPadding(cbp, cbp, cbp, cbp);
        android.widget.LinearLayout curHead = new android.widget.LinearLayout(this);
        curHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        curHead.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextView curTag = new android.widget.TextView(this);
        curTag.setText("当前位置");
        curTag.setTextSize(11);
        curTag.setTextColor(cTextSub);
        curHead.addView(curTag);
        // 城市标签（如「成都」）
        final android.widget.TextView cityTag = new android.widget.TextView(this);
        cityTag.setTextSize(10);
        cityTag.setTextColor(0xFFFFFFFF);
        cityTag.setBackground(roundBg(cPrimary));
        cityTag.setPadding(dp2px(6), dp2px(1), dp2px(6), dp2px(1));
        cityTag.setVisibility(android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams ctagLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ctagLp.setMarginStart(dp2px(6));
        curHead.addView(cityTag, ctagLp);
        // 地图源标签（如「高德」）：只显示当前命中的来源，橙色徽标
        final android.widget.TextView srcTag = new android.widget.TextView(this);
        srcTag.setTextSize(10);
        srcTag.setTextColor(dark ? 0xFFFFC46B : 0xFFC25E00);
        srcTag.setBackground(roundBg(dark ? 0xFF4A3520 : 0xFFFFF3E0,
                dark ? 0xFF7A5628 : 0xFFFFD8A8));
        srcTag.setPadding(dp2px(6), dp2px(1), dp2px(6), dp2px(1));
        android.widget.LinearLayout.LayoutParams stagLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        stagLp.setMarginStart(dp2px(6));
        curHead.addView(srcTag, stagLp);
        showPrimarySource(srcTag);
        // 占位撑开，把定位按钮推到右侧
        android.view.View curSpacer = new android.view.View(this);
        curHead.addView(curSpacer, new android.widget.LinearLayout.LayoutParams(0, 1, 1f));
        android.widget.Button locBtn = new android.widget.Button(this);
        locBtn.setText("获取本机定位");
        locBtn.setTextSize(11);
        locBtn.setAllCaps(false);
        locBtn.setBackground(roundBg(cPrimary));
        locBtn.setTextColor(cOnPrimary);
        locBtn.setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6));
        locBtn.setMinWidth(0);
        locBtn.setMinimumWidth(0);
        locBtn.setMinHeight(0);
        locBtn.setMinimumHeight(0);
        curHead.addView(locBtn);
        curBlock.addView(curHead);
        final android.widget.TextView curAddr = new android.widget.TextView(this);
        curAddr.setTextSize(14);
        curAddr.setTypeface(Typeface.DEFAULT_BOLD);
        curAddr.setTextColor(ThemeHelper.textHeading(this));
        curAddr.setPadding(0, dp2px(6), 0, 0);
        curBlock.addView(curAddr);
        final android.widget.TextView curCoord = new android.widget.TextView(this);
        curCoord.setTextSize(11);
        curCoord.setTextColor(cTextSub);
        curBlock.addView(curCoord);
        box.addView(curBlock, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 搜索行（统一圆角容器：放大镜 + 输入 + 蓝色搜索按钮） ──
        android.widget.LinearLayout searchRow = new android.widget.LinearLayout(this);
        searchRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(roundBg(cInput, cInputStroke));
        searchRow.setPadding(dp2px(12), dp2px(5), dp2px(5), dp2px(5));
        android.widget.TextView searchIcon = new android.widget.TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(14);
        searchIcon.setTextColor(cTextHint);
        searchIcon.setPadding(0, 0, dp2px(6), 0);
        searchRow.addView(searchIcon);
        final android.widget.EditText searchInput = new android.widget.EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("搜索地址或地标");
        searchInput.setTextSize(13);
        searchInput.setBackground(null);
        searchInput.setTextColor(cTextBody);
        searchInput.setHintTextColor(cTextHint);
        searchInput.setPadding(0, dp2px(4), dp2px(4), dp2px(4));
        searchRow.addView(searchInput, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button searchBtn = new android.widget.Button(this);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(12);
        searchBtn.setAllCaps(false);
        searchBtn.setBackground(roundBg(cPrimary));
        searchBtn.setTextColor(cOnPrimary);
        searchBtn.setPadding(dp2px(16), dp2px(6), dp2px(16), dp2px(6));
        searchBtn.setMinWidth(0);
        searchBtn.setMinimumWidth(0);
        searchBtn.setMinHeight(0);
        searchBtn.setMinimumHeight(0);
        searchRow.addView(searchBtn, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp2px(34)));
        android.widget.LinearLayout.LayoutParams srlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp2px(10);
        box.addView(searchRow, srlp);
        // ── 候选地点列表 ──
        final android.widget.ListView resultList = new android.widget.ListView(this);
        resultList.setDivider(new android.graphics.drawable.ColorDrawable(
                ThemeHelper.cardStroke(this)));
        resultList.setDividerHeight(dp2px(1));
        final java.util.List<GeoResolver.GeoPlace> resultItems = new java.util.ArrayList<>();
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return resultItems.size(); }
            @Override public Object getItem(int position) { return resultItems.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override
            public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
                GeoResolver.GeoPlace pl = resultItems.get(position);
                android.widget.LinearLayout row = new android.widget.LinearLayout(GrabLocActivity.this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp2px(16), dp2px(11), dp2px(16), dp2px(11));
                row.setBackgroundResource(android.R.drawable.list_selector_background);

                android.widget.TextView nameTv = new android.widget.TextView(GrabLocActivity.this);
                nameTv.setText(pl.name == null || pl.name.isEmpty() ? pl.addr : pl.name);
                nameTv.setTextSize(14);
                nameTv.setTextColor(cTextBody);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(nameTv, new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                if (pl.badge != null && !pl.badge.isEmpty()) {
                    android.widget.TextView badgeTv = new android.widget.TextView(GrabLocActivity.this);
                    badgeTv.setText(pl.badge);
                    badgeTv.setTextSize(10);
                    badgeTv.setTextColor(dark ? 0xFFFFC46B : 0xFFC25E00);
                    badgeTv.setBackground(roundBg(dark ? 0xFF4A3520 : 0xFFFFF3E0,
                            dark ? 0xFF7A5628 : 0xFFFFD8A8));
                    badgeTv.setPadding(dp2px(6), dp2px(1), dp2px(6), dp2px(1));
                    android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    blp.setMarginStart(dp2px(8));
                    row.addView(badgeTv, blp);
                }
                return row;
            }
        };
        resultList.setAdapter(adapter);
        android.widget.LinearLayout.LayoutParams listLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp2px(150));
        listLp.topMargin = dp2px(6);
        box.addView(resultList, listLp);

        // ── 坐标微调 + 反查地址 ──
        final double[] picked = new double[2];
        picked[0] = Double.NaN;
        picked[1] = Double.NaN;
        final android.widget.EditText lonInput = new android.widget.EditText(this);
        final android.widget.EditText latInput = new android.widget.EditText(this);
        final Runnable fillInputs = () -> {
            if (Double.isNaN(picked[0])) return;
            lonInput.setText(String.format(java.util.Locale.US, "%.6f", picked[0]));
            latInput.setText(String.format(java.util.Locale.US, "%.6f", picked[1]));
        };
        android.widget.LinearLayout coordRow = new android.widget.LinearLayout(this);
        coordRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        coordRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextView lonLabel = new android.widget.TextView(this);
        lonLabel.setText("坐标微调");
        lonLabel.setTextSize(12);
        lonLabel.setTextColor(cTextSub);
        coordRow.addView(lonLabel);
        // 经度输入框（独立）
        lonInput.setSingleLine(true);
        lonInput.setTextSize(12);
        lonInput.setTextColor(cTextBody);
        lonInput.setHintTextColor(cTextHint);
        lonInput.setBackground(roundBg(cInput, cInputStroke));
        lonInput.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
        lonInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        android.widget.LinearLayout.LayoutParams lonLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lonLp.setMarginStart(dp2px(8));
        coordRow.addView(lonInput, lonLp);
        // 纬度输入框（独立，与经度并排）
        latInput.setSingleLine(true);
        latInput.setTextSize(12);
        latInput.setTextColor(cTextBody);
        latInput.setHintTextColor(cTextHint);
        latInput.setBackground(roundBg(cInput, cInputStroke));
        latInput.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
        latInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        android.widget.LinearLayout.LayoutParams latLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        latLp.setMarginStart(dp2px(8));
        coordRow.addView(latInput, latLp);
        android.widget.Button revBtn = new android.widget.Button(this);
        revBtn.setText("⟳ 反查地址");
        revBtn.setTextSize(12);
        revBtn.setAllCaps(false);
        revBtn.setBackground(null);
        revBtn.setTextColor(cPrimary);
        revBtn.setPadding(dp2px(4), 0, 0, 0);
        revBtn.setMinWidth(0);
        revBtn.setMinimumWidth(0);
        revBtn.setMinHeight(0);
        revBtn.setMinimumHeight(0);
        android.widget.LinearLayout.LayoutParams rvlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rvlp.setMarginStart(dp2px(8));
        coordRow.addView(revBtn, rvlp);
        android.widget.LinearLayout.LayoutParams crlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp2px(8);
        box.addView(coordRow, crlp);
        // ── 历史位置 + 清空 ──
        android.widget.LinearLayout histHead = new android.widget.LinearLayout(this);
        histHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView histLabel = new android.widget.TextView(this);
        histLabel.setText("历史位置");
        histLabel.setTextSize(12);
        histLabel.setTextColor(cTextSub);
        histHead.addView(histLabel, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView clearBtn = new android.widget.TextView(this);
        clearBtn.setText("清空");
        clearBtn.setTextSize(11);
        clearBtn.setTextColor(ThemeHelper.accentRed(this));
        histHead.addView(clearBtn);
        android.widget.LinearLayout.LayoutParams hhlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hhlp.topMargin = dp2px(10);
        box.addView(histHead, hhlp);
        final android.widget.LinearLayout histList = new android.widget.LinearLayout(this);
        histList.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(histList, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        // 统一回填：地址 + 城市标签 + 地图源标签（获取定位 / 搜索 / 反查 / 历史共用）
        final java.util.function.BiConsumer<GeoResolver.GeoPlace, String> applyLoc =
                (gp, fallback) -> {
                    if (gp == null) {
                        curAddr.setText(fallback == null || fallback.isEmpty()
                                ? "（地址反查失败）" : fallback);
                        return;
                    }
                    String full = gp.addr == null || gp.addr.isEmpty()
                            ? ((gp.name == null || gp.name.isEmpty()) ? fallback : gp.name)
                            : gp.addr;
                    String shortName;
                    if (fallback != null && !fallback.isEmpty()
                            && (gp.name == null || gp.name.equals(gp.addr))) {
                        // 搜索选点：POI 名就是 short_name
                        shortName = fallback;
                    } else if (gp.name != null && !gp.name.isEmpty()
                            && !gp.name.equals(gp.addr)) {
                        shortName = gp.name;
                    } else {
                        // 纯反查结果只有完整地址，短化成「城市·POI」
                        shortName = LocationCacheHook.shortenAddress(full);
                    }
                    curAddr.setText(formatAddress(shortName, full));
                    if (gp.badge != null && !gp.badge.isEmpty()) {
                        srcTag.setText(gp.badge);
                        srcTag.setVisibility(android.view.View.VISIBLE);
                    }
                    String city = extractCity(gp.addr);
                    if (!city.isEmpty()) {
                        cityTag.setText(city);
                        cityTag.setVisibility(android.view.View.VISIBLE);
                    }
                };
        final Runnable[] renderHistory = new Runnable[1];
        renderHistory[0] = () -> {
            histList.removeAllViews();
            try {
                org.json.JSONArray items = new org.json.JSONArray(
                        historyPrefs().getString("items", "[]"));
                if (items.length() == 0) {
                    android.widget.TextView empty = new android.widget.TextView(this);
                    empty.setText("暂无历史位置");
                    empty.setTextSize(11);
                    empty.setTextColor(ThemeHelper.textHint(this));
                    histList.addView(empty);
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    final int hIdx = i;
                    final org.json.JSONObject old = items.optJSONObject(i);
                    if (old == null) continue;
                    final double selLon = old.optDouble("lon", Double.NaN);
                    final double selLat = old.optDouble("lat", Double.NaN);
                    final boolean selected = !Double.isNaN(selLon) && !Double.isNaN(selLat)
                            && !Double.isNaN(picked[0])
                            && Math.abs(picked[0] - selLon) < 1e-6
                            && Math.abs(picked[1] - selLat) < 1e-6;
                    android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp2px(8), dp2px(9), dp2px(8), dp2px(9));
                    if (selected) {
                        android.graphics.drawable.GradientDrawable sbg =
                                new android.graphics.drawable.GradientDrawable();
                        sbg.setColor(0xFFEAF2FF);
                        sbg.setCornerRadius(dp2px(8));
                        row.setBackground(sbg);
                    }
                    histList.addView(row, new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                    if (i < items.length() - 1) {
                        android.view.View divider = new android.view.View(this);
                        divider.setBackgroundColor(0xFFF0F0F0);
                        android.widget.LinearLayout.LayoutParams dlp =
                                new android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                        dp2px(1));
                        dlp.leftMargin = dp2px(8);
                        dlp.rightMargin = dp2px(8);
                        histList.addView(divider, dlp);
                    }
                    android.widget.TextView dot = new android.widget.TextView(this);
                    dot.setText("●");
                    dot.setTextSize(8);
                    dot.setTextColor(selected ? cPrimary : 0xFFBFBFBF);
                    dot.setPadding(0, 0, dp2px(8), 0);
                    row.addView(dot);
                    android.widget.TextView tv = new android.widget.TextView(this);
                    String histText = displayLabel(old);
                    tv.setText(histText.isEmpty() ? "历史位置" : histText);
                    tv.setTextSize(12);
                    tv.setTextColor(selected ? cPrimary : ThemeHelper.textBody(this));
                    if (selected) tv.setTypeface(Typeface.DEFAULT_BOLD);
                    tv.setSingleLine(true);
                    tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(tv, new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    // 编辑：只修改 short_name，full_address 保持不变
                    android.widget.TextView edit = new android.widget.TextView(this);
                    edit.setText("✎");
                    edit.setTextSize(13);
                    edit.setTextColor(ThemeHelper.textHint(this));
                    edit.setPadding(dp2px(8), 0, dp2px(6), 0);
                    row.addView(edit);
                    edit.setOnClickListener(v -> {
                        final android.widget.EditText et = new android.widget.EditText(this);
                        et.setText(shortOf(old));
                        et.setSelection(et.getText().length());
                        et.setSingleLine(true);
                        et.setHint("short_name（位置名称）");
                        et.setPadding(dp2px(16), dp2px(6), dp2px(16), dp2px(6));
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("编辑 short_name")
                                .setView(et)
                                .setPositiveButton("保存", (d, w) -> {
                                    String text = et.getText().toString().trim();
                                    if (text.isEmpty()) return;
                                    try {
                                        org.json.JSONArray arr = new org.json.JSONArray(
                                                historyPrefs().getString("items", "[]"));
                                        org.json.JSONObject it = arr.optJSONObject(hIdx);
                                        if (it != null) {
                                            String full = fullOf(it);
                                            it.put("short_name", text);
                                            it.put("full_address", full);
                                            it.put("label", formatAddress(text, full));
                                            historyPrefs().edit()
                                                    .putString("items", arr.toString()).commit();
                                        }
                                    } catch (Throwable ignored) {}
                                    if (selected) {
                                        curAddr.setText(formatAddress(text, fullOf(old)));
                                    }
                                    renderHistory[0].run();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    });
                    android.widget.TextView del = new android.widget.TextView(this);
                    del.setText("🗑");
                    del.setTextSize(13);
                    del.setTextColor(ThemeHelper.textHint(this));
                    del.setPadding(dp2px(6), 0, dp2px(2), 0);
                    row.addView(del);
                    row.setOnClickListener(v -> {
                        double hLon = old.optDouble("lon", Double.NaN);
                        double hLat = old.optDouble("lat", Double.NaN);
                        if (Double.isNaN(hLon) || Double.isNaN(hLat)) return;
                        picked[0] = hLon;
                        picked[1] = hLat;
                        fillInputs.run();
                        curCoord.setText(String.format(java.util.Locale.US,
                                "坐标：%.6f, %.6f", hLon, hLat));
                        String label = displayLabel(old);
                        if (!label.isEmpty()) {
                            curAddr.setText(label);
                            showPrimarySource(srcTag);
                        } else {
                            curAddr.setText("（反查地址中…）");
                            new Thread(() -> {
                                GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(
                                        GrabLocActivity.this, hLon, hLat);
                                runOnUiThread(() -> applyLoc.accept(gp, null));
                            }, "XiaoCanAssistant-RevGeo").start();
                        }
                        renderHistory[0].run();
                    });
                    del.setOnClickListener(v -> {
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(
                                    historyPrefs().getString("items", "[]"));
                            org.json.JSONArray next = new org.json.JSONArray();
                            for (int j = 0; j < arr.length(); j++) {
                                if (j != hIdx) next.put(arr.get(j));
                            }
                            historyPrefs().edit().putString("items", next.toString()).commit();
                        } catch (Throwable ignored) {}
                        renderHistory[0].run();
                    });
                }
            } catch (Throwable ignored) {}
        };
        // 初始地址：优先「最近一次保存的历史位置」→ 宿主缓存坐标反查
        boolean seeded = false;
        try {
            org.json.JSONArray items0 = new org.json.JSONArray(
                    historyPrefs().getString("items", "[]"));
            org.json.JSONObject last = items0.length() > 0 ? items0.optJSONObject(0) : null;
            if (last != null) {
                double hLon = last.optDouble("lon", Double.NaN);
                double hLat = last.optDouble("lat", Double.NaN);
                if (!Double.isNaN(hLon) && !Double.isNaN(hLat)) {
                    picked[0] = hLon;
                    picked[1] = hLat;
                    fillInputs.run();
                    curCoord.setText(String.format(java.util.Locale.US,
                            "坐标：%.6f, %.6f", hLon, hLat));
                    String label = displayLabel(last);
                    if (!label.isEmpty()) {
                        curAddr.setText(label);
                        showPrimarySource(srcTag);
                        String city = extractCity(label);
                        if (!city.isEmpty()) {
                            cityTag.setText(city);
                            cityTag.setVisibility(android.view.View.VISIBLE);
                        }
                    } else {
                        curAddr.setText("（反查地址中…）");
                        new Thread(() -> {
                            GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(
                                    GrabLocActivity.this, hLon, hLat);
                            runOnUiThread(() -> applyLoc.accept(gp, null));
                        }, "XiaoCanAssistant-RevGeo").start();
                    }
                    seeded = true;
                }
            }
        } catch (Throwable ignored) {}
        if (!seeded) {
            try {
                SharedPreferences inbox = getSharedPreferences("purify_cache_inbox", MODE_PRIVATE);
                final double iLat = Double.longBitsToDouble(inbox.getLong("lat", 0L));
                final double iLng = Double.longBitsToDouble(inbox.getLong("lng", 0L));
                if (iLat != 0.0 && iLng != 0.0) {
                    picked[0] = iLng;
                    picked[1] = iLat;
                    fillInputs.run();
                    curCoord.setText(String.format(java.util.Locale.US,
                            "坐标：%.6f, %.6f", iLng, iLat));
                    curAddr.setText("（反查地址中…）");
                    new Thread(() -> {
                        GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(this, iLng, iLat);
                        runOnUiThread(() -> applyLoc.accept(gp, null));
                    }, "XiaoCanAssistant-RevGeo").start();
                }
            } catch (Throwable ignored) {}
        }
        // 初始化完成后再渲染历史，确保「最近一次保存」一行呈现选中态
        renderHistory[0].run();
        clearBtn.setOnClickListener(v -> {
            historyPrefs().edit().remove("items").apply();
            LocationCacheHook.clearExplicitLocation();
            renderHistory[0].run();
            android.widget.Toast.makeText(this, "已清空历史并重置抢单位置",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        // ── 获取本机定位：校验 GPS → 申请权限 → 取实时定位回填「当前位置」 ──
        realtimeLocAction = () -> {
            try {
                final android.location.LocationManager lm = (android.location.LocationManager)
                        getSystemService(android.content.Context.LOCATION_SERVICE);
                if (lm == null) {
                    android.widget.Toast.makeText(this, "定位服务不可用",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                curAddr.setText("获取实时定位中…");
                curCoord.setText("");
                final boolean[] done = new boolean[1];
                final android.location.LocationListener[] listener =
                        new android.location.LocationListener[1];
                final android.os.Handler handler =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                final Runnable timeout = () -> {
                    if (done[0]) return;
                    done[0] = true;
                    try { lm.removeUpdates(listener[0]); } catch (Throwable ignored) {}
                    curAddr.setText("获取实时定位超时");
                    curCoord.setText("请到空旷处或打开 GPS 后重试");
                    android.widget.Toast.makeText(this,
                            "获取实时定位超时，请到空旷处重试",
                            android.widget.Toast.LENGTH_LONG).show();
                };
                listener[0] = new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(android.location.Location loc) {
                        if (done[0] || loc == null) return;
                        done[0] = true;
                        try { lm.removeUpdates(listener[0]); } catch (Throwable ignored) {}
                        handler.removeCallbacks(timeout);
                        final double lo = loc.getLongitude();
                        final double la = loc.getLatitude();
                        picked[0] = lo;
                        picked[1] = la;
                        fillInputs.run();
                        curCoord.setText(String.format(java.util.Locale.US,
                                "坐标：%.6f, %.6f", lo, la));
                        curAddr.setText("（反查地址中…）");
                        new Thread(() -> {
                            GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(
                                    GrabLocActivity.this, lo, la);
                            runOnUiThread(() -> applyLoc.accept(gp, null));
                        }, "XiaoCanAssistant-RevGeo").start();
                    }
                    @Override
                    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                    @Override
                    public void onProviderEnabled(String provider) {}
                    @Override
                    public void onProviderDisabled(String provider) {}
                };
                try {
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER,
                                0L, 0f, listener[0], android.os.Looper.getMainLooper());
                    }
                    if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER,
                                0L, 0f, listener[0], android.os.Looper.getMainLooper());
                    }
                } catch (Throwable ignored) {}
                handler.postDelayed(timeout, 15000L);
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, "获取定位失败",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        locBtn.setOnClickListener(v -> {
            if (!isLocationEnabled()) {
                android.widget.Toast.makeText(this,
                        "请先打开手机 GPS 定位后再获取当前位置",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            if (!hasLocationPermission()) {
                pendingRealtimeLoc = true;
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_REALTIME_LOC);
                return;
            }
            realtimeLocAction.run();
        });
        // ── 搜索逻辑 ──
        searchBtn.setOnClickListener(v -> {
            String kw = searchInput.getText() == null ? ""
                    : searchInput.getText().toString().trim();
            if (kw.isEmpty()) return;
            android.widget.Toast.makeText(this, "正在搜索…",
                    android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String err = null;
                java.util.List<GeoResolver.GeoPlace> result = null;
                try {
                    double biasLon = Double.isNaN(picked[0]) ? 104.099938 : picked[0];
                    double biasLat = Double.isNaN(picked[1]) ? 30.649241 : picked[1];
                    result = GeoResolver.searchPlaces(this, kw, biasLon, biasLat);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final java.util.List<GeoResolver.GeoPlace> found = result;
                final String errFinal = err;
                runOnUiThread(() -> {
                    if (errFinal != null) {
                        android.widget.Toast.makeText(this, "搜索失败：" + errFinal,
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    resultItems.clear();
                    if (found != null) resultItems.addAll(found);
                    adapter.notifyDataSetChanged();
                    android.widget.Toast.makeText(this,
                            "共 " + found.size() + " 条结果",
                            android.widget.Toast.LENGTH_SHORT).show();
                    resultList.setOnItemClickListener((parent, view, pos, id) -> {
                        if (pos < 0 || pos >= found.size()) return;
                        GeoResolver.GeoPlace pl = found.get(pos);
                        double lo = pl.lon, la = pl.lat;
                        // 部分源（高德/OSM 搜索）不返回坐标，回退到搜索时中心点
                        if (lo == 0.0 && la == 0.0 && !Double.isNaN(picked[0])) {
                            lo = picked[0];
                            la = picked[1];
                        }
                        picked[0] = lo;
                        picked[1] = la;
                        fillInputs.run();
                        curCoord.setText(String.format(java.util.Locale.US,
                                "坐标：%.6f, %.6f", lo, la));
                        curAddr.setText(formatAddress(pl.name, pl.addr) + "（反查地址中…）");
                        // 搜索结果自带来源标签，先亮出来
                        srcTag.setText(pl.badge);
                        srcTag.setVisibility(android.view.View.VISIBLE);
                        final double flo = lo, fla = la;
                        new Thread(() -> {
                            GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(
                                    GrabLocActivity.this, flo, fla);
                            runOnUiThread(() -> applyLoc.accept(gp, pl.name));
                        }, "XiaoCanAssistant-RevGeo").start();
                    });
                });
            }, "XiaoCanAssistant-TdtSearch").start();
        });

        // ── 反查地址按钮：读取两个坐标输入框，反查后回填「当前位置」 ──
        revBtn.setOnClickListener(v -> {
            double lo;
            double la;
            try {
                lo = Double.parseDouble(lonInput.getText().toString().trim());
                la = Double.parseDouble(latInput.getText().toString().trim());
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, "坐标无效",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            picked[0] = lo;
            picked[1] = la;
            curCoord.setText(String.format(java.util.Locale.US,
                    "坐标：%.6f, %.6f", lo, la));
            curAddr.setText("（反查地址中…）");
            final double flo = lo;
            final double fla = la;
            new Thread(() -> {
                GeoResolver.GeoPlace gp = GeoResolver.resolveReverse(GrabLocActivity.this, flo, fla);
                runOnUiThread(() -> applyLoc.accept(gp, null));
            }, "XiaoCanAssistant-RevGeo").start();
        });

        // 底部按钮行（自绘）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(p14, dp2px(10), p14, dp2px(14));
        android.widget.Button cancelBtn = new android.widget.Button(this);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(13);
        cancelBtn.setAllCaps(false);
        cancelBtn.setBackground(roundBg(cBtn, cInputStroke));
        cancelBtn.setTextColor(cTextBody);
        cancelBtn.setMinWidth(dp2px(88));
        cancelBtn.setOnClickListener(v -> finish());
        android.widget.Button okBtn = new android.widget.Button(this);
        okBtn.setText("确定");
        okBtn.setTextSize(13);
        okBtn.setAllCaps(false);
        okBtn.setBackground(roundBg(cPrimary));
        okBtn.setTextColor(cOnPrimary);
        okBtn.setMinWidth(dp2px(88));
        okBtn.setOnClickListener(v -> {
            double lo;
            double la;
            try {
                lo = Double.parseDouble(lonInput.getText().toString().trim());
                la = Double.parseDouble(latInput.getText().toString().trim());
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, "坐标无效",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (Math.abs(lo) < 1 || Math.abs(la) < 1) {
                android.widget.Toast.makeText(this, "请先选点或填写坐标",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String shown = curAddr.getText() == null ? ""
                    : curAddr.getText().toString().split("\n")[0];
            if (shown.startsWith("（")) shown = "";
            int sep = shown.indexOf(ADDR_SEP);
            String shortName = sep > 0 ? shown.substring(0, sep).trim() : shown;
            String fullAddress = sep > 0
                    ? shown.substring(sep + ADDR_SEP.length()).trim() : "";
            if (!shortName.isEmpty() && fullAddress.isEmpty()) fullAddress = shortName;
            String addr = formatAddress(shortName, fullAddress);
            saveGrabLocHistory(shortName, fullAddress, lo, la);
            sendBroadcast(new android.content.Intent(
                    "com.genius.xiaocanpurify.ACTION_SET_GRAB_LOC")
                    .setPackage("com.realtech.xiaocan")
                    .putExtra("lat", la)
                    .putExtra("lng", lo)
                    .putExtra("address", fullAddress.isEmpty() ? shortName : fullAddress)
                    .putExtra("short_name", shortName));
            android.widget.Toast.makeText(this,
                    "已下发选定位置：小蚕定位请求将使用该坐标",
                    android.widget.Toast.LENGTH_LONG).show();
            LogStore.append(this, "I", "Loc",
                    "设置抢单位置 " + la + "," + lo + " " + addr);
            finish();
        });
        btnRow.addView(cancelBtn);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.setMarginStart(dp2px(10));
        btnRow.addView(okBtn, okLp);
        page.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 全页对无障碍服务屏蔽：设备上的自动化工具（自动点击器/抢单脚本）会扫描
        // 屏幕文本并自动点击「取消」等按钮，导致页面出现后立即被点掉（resume 后
        // ~150ms 被 finish）。真人触摸不受影响。
        hideFromAccessibility(page);
    }

    /** 递归把整棵视图树标记为无障碍不可见。 */
    private void hideFromAccessibility(View v) {
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                hideFromAccessibility(g.getChildAt(i));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_REALTIME_LOC) return;
        pendingRealtimeLoc = false;
        boolean granted = false;
        if (grantResults != null) {
            for (int g : grantResults) {
                if (g == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
        }
        if (granted) {
            if (realtimeLocAction != null) realtimeLocAction.run();
        } else {
            android.widget.Toast.makeText(this, "未授予定位权限，无法获取当前位置",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 生成圆角背景（8dp）。 */
    private android.graphics.drawable.GradientDrawable roundBg(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp2px(8));
        return g;
    }

    /** 生成带 1dp 描边的圆角背景（8dp）。 */
    private android.graphics.drawable.GradientDrawable roundBg(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = roundBg(fill);
        g.setStroke(dp2px(1), stroke);
        return g;
    }

    private int dp2px(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 手机 GPS 定位是否已打开（用户要求必须开 GPS 才能取实时定位）。 */
    private boolean isLocationEnabled() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(android.content.Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已获得定位权限（精确或粗略）。 */
    private boolean hasLocationPermission() {
        try {
            return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 展示格式：short_name·full_address。用带空格的分隔符，避免与短名内部的「·」混淆。 */
    private static final String ADDR_SEP = " · ";

    private static String formatAddress(String shortName, String fullAddress) {
        String s = shortName == null ? "" : shortName.trim();
        String f = fullAddress == null ? "" : fullAddress.trim();
        if (s.isEmpty()) return f;
        if (f.isEmpty() || f.equals(s)) return s;
        return s + ADDR_SEP + f;
    }

    /** 取历史条目的 short_name；兼容旧数据只有拼接 label 的情况。 */
    private static String shortOf(org.json.JSONObject it) {
        if (it == null) return "";
        String s = it.optString("short_name", "");
        if (!s.isEmpty()) return s;
        String label = it.optString("label", "");
        int i = label.indexOf(ADDR_SEP);
        return i > 0 ? label.substring(0, i).trim() : label.trim();
    }

    /** 取历史条目的 full_address；兼容旧数据。 */
    private static String fullOf(org.json.JSONObject it) {
        if (it == null) return "";
        String f = it.optString("full_address", "");
        if (!f.isEmpty()) return f;
        String label = it.optString("label", "");
        int i = label.indexOf(ADDR_SEP);
        return i > 0 ? label.substring(i + ADDR_SEP.length()).trim() : "";
    }

    private static String displayLabel(org.json.JSONObject it) {
        if (it == null) return "";
        String s = shortOf(it);
        String f = fullOf(it);
        if (!s.isEmpty() && !f.isEmpty()) return formatAddress(s, f);
        return it.optString("label", "");
    }

    /** 优先地图源短标（高德 > 百度 > OSM > 天地图），作为默认来源徽标。 */
    private String primaryMapSourceBadge() {
        try {
            for (GeoResolver.GeoSource s : GeoResolver.sources()) {
                if (s.isEnabled()) return s.badge();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void showPrimarySource(android.widget.TextView tag) {
        String b = primaryMapSourceBadge();
        if (b.isEmpty()) {
            tag.setVisibility(android.view.View.GONE);
            return;
        }
        tag.setText(b);
        tag.setVisibility(android.view.View.VISIBLE);
    }

    /** 从完整地址中提取城市名（如「成都市」→「成都」），用于顶部城市标签。
     *  先删除所有「XX省」（拼接串里省级前缀可能在中间），城市名限 2–3 字，
     *  否则「四川省成都市」会被 find() 从「川」匹配成「川省成都」。 */
    private String extractCity(String address) {
        if (address == null) return "";
        String s = address.replaceAll("[\\u4e00-\\u9fa5]{2,3}省", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,3})(?:市|自治州|州|盟|地区)")
                .matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** 历史位置读写（最近 5 条，JSON 数组）。 */
    private android.content.SharedPreferences historyPrefs() {
        return getSharedPreferences("purify_grab_loc_history", MODE_PRIVATE);
    }

    private void saveGrabLocHistory(String shortName, String fullAddress,
                                   double lon, double lat) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    historyPrefs().getString("items", "[]"));
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("short_name", shortName);
            item.put("full_address", fullAddress);
            item.put("label", formatAddress(shortName, fullAddress));
            item.put("lon", lon);
            item.put("lat", lat);
            next.put(item);
            for (int i = 0; i < arr.length() && i < 4; i++) {
                org.json.JSONObject old = arr.optJSONObject(i);
                if (old == null) continue;
                if (old.optDouble("lon", -1) == lon && old.optDouble("lat", -1) == lat) {
                    continue;
                }
                next.put(old);
            }
            historyPrefs().edit().putString("items", next.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
