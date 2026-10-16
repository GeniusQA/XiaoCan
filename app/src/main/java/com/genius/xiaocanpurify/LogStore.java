package com.genius.xiaocanpurify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模块应用日志存储（参考 HyperLyrics LogManager 的轻量方案）：
 *  - 追加写入 filesDir/logs/app_logs.log，行格式 MM-dd HH:mm:ss.SSS L/tag: message；
 *  - 超过 2MB 自动裁剪，保留尾部 1MB，防止无限膨胀；
 *  - Hook 侧日志经 ACTION_LOG_PUSH 广播回传后由 appendHookLogs 合并落盘
 *    （按行内容去重，重复推送不会产生重复条目）；
 *  - 自动清理：SharedPreferences 记录间隔（0=关闭/1/24/168 小时）与上次
 *    清理时间，maybeAutoCleanup 在设置页/日志页打开时惰性触发
 *    （替代 WorkManager，避免引入额外依赖）；
 *  - 清理记录持久化到 filesDir/cleanup_history.json（最多 50 条，最新在前）。
 */
public final class LogStore {
    private static final Object LOCK = new Object();
    private static final long MAX_LOG_SIZE = 2L * 1024 * 1024;
    private static final long TRIM_KEEP_SIZE = 1024L * 1024;
    private static final int MAX_HISTORY = 50;
    private static final int MAX_LINES_DISPLAY = 500;
    /** 同一清理动作的记录去重窗口（个别场景会重复触发清理回调）。 */
    private static final long RECORD_DEDUP_MS = 3000;
    /** Hook 推送去重集合容量上限。 */
    private static final int DEDUP_SET_MAX = 2000;

    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_SCHEDULED = "scheduled";

    private static final String PREFS_NAME = "purify_logs";
    private static final String KEY_INTERVAL = "log_auto_cleanup_interval";
    private static final String KEY_LAST_CLEANUP = "log_last_cleanup_time";

    /** 自动清理间隔可选项（小时），0 = 关闭。 */
    public static final int[] INTERVAL_CHOICES = {0, 1, 24, 168};

    /** 日志来源：应用日志（模块设置页自身）/ 模块日志（Hook 侧推送）。 */
    public static final String SOURCE_APP = "app";
    public static final String SOURCE_MODULE = "module";

    private static final java.util.LinkedHashSet<String> DEDUP = new java.util.LinkedHashSet<>();
    private static volatile long lastRecordTime;
    /** 采集暂停开关：暂停期间应用日志与 Hook 推送一律不落盘（查看页可切回）。 */
    private static volatile boolean paused;

    private LogStore() {}

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean p) {
        paused = p;
    }

    // ---------------------------------------------------------------- 写入

    /** 追加一条应用日志（多行内容会被压平以保持行格式可解析）。 */
    public static void append(Context ctx, String level, String tag, String msg) {
        if (ctx == null || msg == null || paused) return;
        try {
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date())
                    + " " + safeLevel(level) + "/" + (tag == null ? "App" : tag) + ": "
                    + flatten(msg) + "\n";
            synchronized (LOCK) {
                appendLineLocked(logFile(ctx, SOURCE_APP), line);
                File f = logFile(ctx, SOURCE_APP);
                if (f.length() > MAX_LOG_SIZE) trimLocked(f);
            }
        } catch (Throwable ignored) {}
    }

    /** 合并 Hook 侧推送的日志行到「模块日志」文件（已格式化；按行去重）。 */
    public static void appendHookLogs(Context ctx, String[] lines) {
        if (ctx == null || lines == null || lines.length == 0 || paused) return;
        boolean changed = false;
        synchronized (LOCK) {
            for (String l : lines) {
                if (l == null || l.isEmpty()) continue;
                if (!DEDUP.add(l)) continue;
                if (DEDUP.size() > DEDUP_SET_MAX) {
                    // 淘汰最早的元素（LinkedHashSet 迭代序即插入序）
                    java.util.Iterator<String> it = DEDUP.iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                appendLineLocked(logFile(ctx, SOURCE_MODULE), l.endsWith("\n") ? l : l + "\n");
                changed = true;
            }
            if (changed) {
                File f = logFile(ctx, SOURCE_MODULE);
                try {
                    if (f.length() > MAX_LOG_SIZE) trimLocked(f);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void appendLineLocked(File f, String line) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 保留尾部 1MB，其余丢弃（参考 HyperLyrics trimLogFile）。 */
    private static void trimLocked(File f) {
        RandomAccessFile raf = null;
        try {
            long size = f.length();
            long keep = Math.min(TRIM_KEEP_SIZE, size);
            raf = new RandomAccessFile(f, "r");
            raf.seek(size - keep);
            byte[] buf = new byte[(int) keep];
            raf.readFully(buf);
            raf.close();
            raf = null;
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(buf);
            out.close();
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- 读取

    /**
     * 读取日志（最新在前）。返回条目 {time, level, tag, message}。
     * levelFilter 为 null/"ALL" 时返回全部；query 非空时按包含匹配
     * （不区分大小写，匹配时间/等级/tag/消息原文）全量过滤后再截取；
     * 最多 MAX_LINES_DISPLAY 条。
     * source：SOURCE_APP=应用日志 / SOURCE_MODULE=模块日志。
     */
    public static List<String[]> readLogs(Context ctx, String levelFilter, String source,
                                          String query) {
        List<String[]> out = new ArrayList<>();
        String[] lines;
        synchronized (LOCK) {
            lines = readRawLinesLocked(ctx, levelFilter, source, query, MAX_LINES_DISPLAY);
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] e = parseLine(lines[i]);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** 导出文本：头部 + 全量过滤后的原始行（文件上限 2MB，直接构建字符串）。 */
    public static String exportText(Context ctx, String levelFilter, String source) {
        String[] lines;
        synchronized (LOCK) {
            lines = readRawLinesLocked(ctx, levelFilter, source, null, Integer.MAX_VALUE);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("XiaoCanPurify ")
                .append(SOURCE_MODULE.equals(source) ? "模块日志" : "应用日志").append('\n');
        sb.append("导出时间: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("等级过滤: ").append(levelFilter == null ? "ALL" : levelFilter).append('\n');
        if (lines.length == 0) {
            sb.append("(无日志)\n");
        } else {
            for (String l : lines) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** 返回按时间正序、经过等级与关键字过滤、从最新往前截取 maxLines 条的原始行。 */
    private static String[] readRawLinesLocked(Context ctx, String levelFilter, String source,
                                               String query, int maxLines) {
        File f = logFile(ctx, source);
        if (!f.exists() || maxLines <= 0) return new String[0];
        try {
            String[] all = readAll(f).split("\n");
            boolean allLevel = levelFilter == null || levelFilter.isEmpty()
                    || "ALL".equals(levelFilter);
            String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
            java.util.ArrayList<String> picked = new java.util.ArrayList<>();
            for (int i = all.length - 1; i >= 0 && picked.size() < maxLines; i--) {
                String l = all[i];
                if (l.isEmpty()) continue;
                if (!allLevel && !lineHasLevel(l, levelFilter.charAt(0))) {
                    continue;
                }
                if (!q.isEmpty() && !l.toLowerCase(Locale.US).contains(q)) {
                    continue;
                }
                picked.add(l);
            }
            String[] out = new String[picked.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = picked.get(out.length - 1 - i);
            }
            return out;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /**
     * 解析单行 "MM-dd HH:mm:ss.SSS L/tag: message"。
     * 注意第一个空格在日期与时间之间，不能用它定位等级——
     * 用正则整体匹配：日期 + 时间 + 等级/tag + 消息。
     */
    private static final java.util.regex.Pattern LINE_PATTERN =
            java.util.regex.Pattern.compile("^(\\S+) (\\S+) ([A-Z])/([^:]*): (.*)$");

    private static String[] parseLine(String l) {
        java.util.regex.Matcher m = LINE_PATTERN.matcher(l);
        if (!m.matches()) return null;
        return new String[]{
                m.group(1) + " " + m.group(2),  // 完整时间：MM-dd HH:mm:ss.SSS
                m.group(3),                     // 等级 I/W/E
                m.group(4),                     // tag
                unflatten(m.group(5))           // 消息
        };
    }

    /** 行格式 "MM-dd HH:mm:ss.SSS L/tag: ..."：等级是唯一 "/tag" 前的单字母。 */
    private static boolean lineHasLevel(String l, char level) {
        int slash = l.indexOf('/');
        if (slash < 2) return false;
        char lv = l.charAt(slash - 1);
        if ("IEWD".indexOf(lv) < 0) return false;
        return lv == level && l.charAt(slash - 2) == ' ';
    }

    private static String flatten(String s) {
        return s.replace("\r", "").replace("\n", "\\n");
    }

    private static String unflatten(String s) {
        return s.replace("\\n", "\n");
    }

    private static String safeLevel(String lv) {
        return "W".equals(lv) || "E".equals(lv) ? lv : "I";
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    // ---------------------------------------------------------------- 清理

    /** 清空应用日志与模块日志并写入清理记录；同时更新上次清理时间。 */
    public static boolean clearAll(Context ctx, String trigger) {
        boolean ok;
        String path;
        synchronized (LOCK) {
            File app = logFile(ctx, SOURCE_APP);
            File mod = logFile(ctx, SOURCE_MODULE);
            path = app.getName() + " + " + mod.getName();
            boolean okApp = deleteQuietly(app);
            boolean okMod = deleteQuietly(mod);
            ok = okApp && okMod;
        }
        try {
            prefs(ctx).edit().putLong(KEY_LAST_CLEANUP, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
        saveCleanupRecord(ctx, trigger, ok, path);
        return ok;
    }

    private static boolean deleteQuietly(File f) {
        try {
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void saveCleanupRecord(Context ctx, String trigger, boolean ok, String path) {
        long now = System.currentTimeMillis();
        long last = lastRecordTime;
        if (now - last < RECORD_DEDUP_MS && TRIGGER_SCHEDULED.equals(trigger)
                && TRIGGER_SCHEDULED.equals(lastRecordTrigger)) {
            return;
        }
        lastRecordTime = now;
        lastRecordTrigger = trigger;
        synchronized (LOCK) {
            try {
                JSONArray arr = readHistoryArrayLocked(ctx);
                JSONObject rec = new JSONObject();
                rec.put("time", now);
                rec.put("trigger", trigger);
                rec.put("ok", ok);
                rec.put("path", path);
                JSONArray out = new JSONArray();
                out.put(rec);
                for (int i = 0; i < arr.length() && out.length() < MAX_HISTORY; i++) {
                    out.put(arr.get(i));
                }
                File f = historyFile(ctx);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(out.toString().getBytes(StandardCharsets.UTF_8));
                fo.close();
            } catch (Throwable ignored) {}
        }
    }

    private static String lastRecordTrigger = "";

    /** 读取清理记录（最新在前）。条目 {time, trigger, ok, path}。 */
    public static List<String[]> readCleanupHistory(Context ctx) {
        List<String[]> out = new ArrayList<>();
        synchronized (LOCK) {
            try {
                JSONArray arr = readHistoryArrayLocked(ctx);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new String[]{
                            String.valueOf(o.optLong("time")),
                            o.optString("trigger"),
                            o.optBoolean("ok") ? "1" : "0",
                            o.optString("path")
                    });
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static JSONArray readHistoryArrayLocked(Context ctx) {
        try {
            File f = historyFile(ctx);
            if (f.exists()) {
                return new JSONArray(readAll(f));
            }
        } catch (Throwable ignored) {}
        return new JSONArray();
    }

    // ---------------------------------------------------------------- 自动清理

    /** 惰性自动清理：距上次清理超过设定间隔则执行（设置页/日志页打开时调用）。 */
    public static void maybeAutoCleanup(Context ctx) {
        try {
            int hours = getCleanupIntervalHours(ctx);
            if (hours <= 0) return;
            long last = prefs(ctx).getLong(KEY_LAST_CLEANUP, 0);
            if (System.currentTimeMillis() - last >= hours * 3600_000L) {
                clearAll(ctx, TRIGGER_SCHEDULED);
            }
        } catch (Throwable ignored) {}
    }

    public static int getCleanupIntervalHours(Context ctx) {
        try {
            return prefs(ctx).getInt(KEY_INTERVAL, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setCleanupIntervalHours(Context ctx, int hours) {
        try {
            prefs(ctx).edit().putInt(KEY_INTERVAL, hours).apply();
        } catch (Throwable ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static File logFile(Context ctx, String source) {
        String name = SOURCE_MODULE.equals(source) ? "logs/module_logs.log" : "logs/app_logs.log";
        return new File(ctx.getApplicationContext().getFilesDir(), name);
    }

    private static File historyFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "cleanup_history.json");
    }
}
