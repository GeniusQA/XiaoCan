package com.genius.xiaocanpurify;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Hook 侧日志缓冲与推送（参考 HyperLyrics「模块日志」方案，
 * 但不依赖 root 读 LSPosed 日志文件）：
 *  - MainHook.log 的输出进入内存环形缓冲（最多 500 条，宿主进程被杀即丢弃）；
 *  - 模块日志页打开时发 ACTION_LOG_PULL，宿主收到后把缓冲快照经
 *    ACTION_LOG_PUSH 广播回传模块（模块 LogStore 按行去重后落盘）。
 * 与 LocationCache 的缓存回推是同一套广播模式。
 */
public final class HostLogBridge {
    private static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 500;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static volatile boolean registered;

    private HostLogBridge() {}

    /** Hook 侧记录一条日志（MainHook.log 调用，格式与应用日志一致）。 */
    public static void record(String msg) {
        try {
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date())
                    + " I/" + MainHook.LOG_TAG + ": "
                    + (msg == null ? "" : msg.replace("\r", "").replace("\n", "\\n"));
            synchronized (LOCK) {
                RING.addLast(line);
                while (RING.size() > MAX_ENTRIES) RING.pollFirst();
            }
        } catch (Throwable ignored) {}
    }

    /** 注册日志拉取接收器（Application 未就绪时静默跳过，install 时已尽力注册）。 */
    public static void install() {
        register(currentApplication());
    }

    private static void register(Context app) {
        if (app == null || registered) return;
        synchronized (HostLogBridge.class) {
            if (registered) return;
            try {
                android.content.BroadcastReceiver receiver =
                        new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context c,
                                                  android.content.Intent i) {
                                push(c);
                            }
                        };
                android.content.IntentFilter f = new android.content.IntentFilter(
                        "com.genius.xiaocanpurify.ACTION_LOG_PULL");
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // 模块与宿主不同 uid，targetSdk 34+ 必须显式 exported
                    app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(receiver, f);
                }
                registered = true;
                MainHook.log("HostLogBridge: log pull receiver registered");
            } catch (Throwable t) {
                MainHook.log("HostLogBridge: register failed: " + t);
            }
        }
    }

    private static void push(Context ctx) {
        String[] snap;
        synchronized (LOCK) {
            snap = RING.toArray(new String[0]);
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    "com.genius.xiaocanpurify.ACTION_LOG_PUSH");
            i.setPackage("com.genius.xiaocanpurify");
            i.putExtra("lines", snap);
            ctx.sendBroadcast(i);
            MainHook.log("HostLogBridge: pushed " + snap.length + " log lines");
        } catch (Throwable ignored) {}
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
