package com.genius.xiaocanpurify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 每月免单券抢购（14:00 放量）——参考 xiaocan-assistant 的 appointment_worker 思路简化：
 *
 *  timing：用 XiaoCanRpc.serverNow()（网关 Date 头校准的时间偏移）计算目标时刻，
 *          放量前 10 秒预热会话（UserFreeOrderInfo），到点连发 3 次 GrabFreeOrderQuota。
 *  校验：抢完复查 UserFreeOrderInfo 的 info.had —— 只有真的持券才算成功，
 *        避免「接口返回 0 但没抢到」的假报（同元宝秒杀 issue 的教训）。
 *  限制：模块运行在宿主进程内，App 未运行时无法执行，需用户在放量前打开小蚕。
 */
public final class FreeOrderGrabber {
    private static final int HOUR = 14;
    private static final int MINUTE = 0;
    private static final long WINDOW_MS = 30 * 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean scheduled;

    private FreeOrderGrabber() {}

    /** App 启动时调用：若开启且在 30 分钟窗口内，安排预热与开火。 */
    static void armIfDue(Context ctx) {
        try {
            if (!enabled()) return;
            if (scheduled) return;
            long target = nextTargetServerMs();
            long now = XiaoCanRpc.serverNow();
            long diff = target - now;
            if (diff < 0) return;
            if (diff > WINDOW_MS) {
                MainHook.log("FreeOrder: not in window, skip (in "
                        + (diff / 60000L) + " min)");
                return;
            }
            scheduled = true;
            MainHook.log("FreeOrder: armed, warmup in " + (diff / 1000L) + "s");
            final Context app = ctx.getApplicationContext();
            if (diff > 12_000L) {
                MAIN.postDelayed(() -> warmup(app), diff - 10_000L);
            }
            MAIN.postDelayed(() -> fire(app), diff);
        } catch (Throwable t) {
            MainHook.log("FreeOrder: arm failed " + t);
        }
    }

    private static boolean enabled() {
        try {
            return "1".equals(PurifyPrefs.getString(PurifyPrefs.FREE_ORDER_GRAB, "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 今天 14:00（服务器时间，毫秒）。已过则返回明天 14:00。 */
    private static long nextTargetServerMs() {
        long serverNow = XiaoCanRpc.serverNow();
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(serverNow);
        c.set(Calendar.HOUR_OF_DAY, HOUR);
        c.set(Calendar.MINUTE, MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis();
        if (t <= serverNow) t += 86_400_000L;
        return t;
    }

    private static void warmup(Context ctx) {
        try {
            String silk = XiaoCanRpc.silkId(ctx);
            XiaoCanRpc.userFreeOrderInfo(ctx, silk);
            MainHook.log("FreeOrder: warmup done");
        } catch (Throwable t) {
            MainHook.log("FreeOrder: warmup failed " + t);
        }
    }

    private static void fire(Context ctx) {
        new Thread(() -> {
            try {
                String silk = XiaoCanRpc.silkId(ctx);
                if (hasQuota(ctx, silk)) {
                    MainHook.log("FreeOrder: already has quota, skip");
                    Notifier.notifyEvent(Notifier.EV_SPIKE, "免单券抢购跳过",
                            "本月已持有免单券，无需重复抢购");
                    return;
                }
                int fired = 0;
                for (int i = 0; i < 3; i++) {
                    try {
                        JSONObject r = XiaoCanRpc.grabFreeOrderQuota(ctx, silk);
                        int code = r.optInt("code", -1);
                        MainHook.log("FreeOrder: attempt" + (i + 1) + " code=" + code);
                        fired++;
                        if (code == 0) break;
                    } catch (Throwable t) {
                        MainHook.log("FreeOrder: attempt" + (i + 1) + " failed " + t);
                    }
                    try {
                        Thread.sleep(250);
                    } catch (Throwable ignored) {}
                }
                try {
                    Thread.sleep(1500);
                } catch (Throwable ignored) {}
                boolean ok = hasQuota(ctx, silk);
                MainHook.log("FreeOrder: fired=" + fired + " verified=" + ok);
                if (ok) {
                    Notifier.notifyEvent(Notifier.EV_SPIKE, "免单券抢购成功",
                            "已抢到本月免单券，请尽快去使用");
                } else {
                    Notifier.notifyEvent(Notifier.EV_SPIKE, "免单券未抢到",
                            "已尝试 " + fired + " 次，复查未持券（可能已抢完）");
                }
            } catch (Throwable t) {
                MainHook.log("FreeOrder: fire error " + t);
            } finally {
                scheduled = false;
            }
        }, "XiaoCanPurify-FreeOrder").start();
    }

    /** 复查是否持券（info.had）。 */
    private static boolean hasQuota(Context ctx, String silk) {
        try {
            JSONObject res = XiaoCanRpc.userFreeOrderInfo(ctx, silk);
            JSONObject info = res.optJSONObject("info");
            if (info == null) info = res.optJSONObject("data");
            if (info == null) return false;
            JSONObject f = info.optJSONObject("info");
            if (f != null) info = f;
            return info.optBoolean("had", false);
        } catch (Throwable t) {
            return false;
        }
    }
}
