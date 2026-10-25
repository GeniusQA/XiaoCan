package com.genius.xiaocanpurify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** SVIP 抢券与卡券提醒统一调度（参考 xiaocan-assistant）。
 *  svip_rebate 09:00 返利券；brand_extra 09:30 大牌放量券（GrabBrandCard
 *  扣保底张数绝不可定时调用，只用 GrabExtraBrandCard type=99）；
 *  media_vip 10/17/20 点影音周卡；card_expire 08:30 卡券到期提醒。 */
public final class VipGrabRunner {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean armed;

    private VipGrabRunner() {}

    /** App 首次进入时调用：为每个启用且当天未执行的任务排程。 */
    public static void armAll(Context ctx) {
        if (armed) return;
        armed = true;
        final Context app = ctx.getApplicationContext();
        schedule(app, "svip_rebate", "SVIP返利券", 9, 0, 0);
        schedule(app, "brand_extra", "SVIP大牌放量券", 9, 30, 0);
        schedule(app, "media_vip", "影音会员周卡", 10, 0, 0);
        schedule(app, "media_vip", "影音会员周卡", 17, 0, 0);
        schedule(app, "media_vip", "影音会员周卡", 20, 0, 0);
        schedule(app, "card_expire", "卡券到期提醒", 8, 30, 0);
    }
    private static void schedule(Context app, String key, String title, int hour, int min, int seq) {
        try {
            if (!PurifyPrefs.getString("grab_" + key, "0").equals("1")) return;
            android.content.SharedPreferences p =
                    app.getSharedPreferences("purify_rpc", Context.MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new java.util.Date());
            if (today.equals(p.getString("grab_" + key + "_last", null))) return;
            long target = timeTodayOrTomorrow(hour, min, seq);
            long diff = target - XiaoCanRpc.serverNow();
            if (diff <= 0) {
                if (diff > -15 * 60_000L) run(app, key, title); // 刚过期 15 分钟内补跑
                return;
            }
            if (diff > 30 * 60_000L) return;
            MAIN.postDelayed(() -> run(app, key, title), diff);
            MainHook.log("VipGrab: " + title + " scheduled in " + (diff / 1000L) + "s");
        } catch (Throwable t) {
            MainHook.log("VipGrab: schedule " + key + " failed " + t);
        }
    }

    private static long timeTodayOrTomorrow(int hour, int min, int seq) {
        long serverNow = XiaoCanRpc.serverNow();
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(serverNow);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, min + seq);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis();
        if (t <= serverNow) t += 86_400_000L;
        return t;
    }
    private static void run(Context app, String key, String title) {
        new Thread(() -> {
            try {
                if (!XiaoCanRpc.hasCredentials(app)) {
                    MainHook.log("VipGrab: " + title + " no credentials, skip");
                    return;
                }
                markDone(app, key);
                String silk = XiaoCanRpc.silkId(app);
                JSONObject res;
                switch (key) {
                    case "svip_rebate":
                        try { XiaoCanRpc.vipRebateInfo(app, silk); } catch (Throwable ignored) {}
                        Thread.sleep(400);
                        res = XiaoCanRpc.grabRebateCardQuota(app, silk);
                        break;
                    case "brand_extra":
                        res = XiaoCanRpc.grabExtraBrandCard(app, silk, 99);
                        break;
                    case "media_vip":
                        try { XiaoCanRpc.userTencentVipInfo(app, silk); } catch (Throwable ignored) {}
                        Thread.sleep(400);
                        res = XiaoCanRpc.grabTencentVipQuota(app, silk);
                        break;
                    case "card_expire":
                        res = XiaoCanRpc.userCardList(app, silk, 0, 0, 20);
                        notifyExpiring(app, res);
                        return;
                    default:
                        return;
                }
                int code = res.optInt("code", -1);
                String msg = res.optString("msg", "");
                boolean notice = DailyTaskRunner.isNoticeCode(code);
                boolean ok = code == 0 || notice;
                MainHook.log("VipGrab: " + title + " -> code=" + code + " msg=" + msg);
                Notifier.notifyEvent(Notifier.EV_SPIKE,
                        title + (ok ? " 执行成功" : " 执行失败"),
                        "code=" + code + (msg.isEmpty() ? "" : " " + msg));
            } catch (Throwable t) {
                MainHook.log("VipGrab: " + title + " error " + t);
            }
        }, "XiaoCanAssistant-VipGrab").start();
    }

    private static void markDone(Context app, String key) {
        try {
            app.getSharedPreferences("purify_rpc", Context.MODE_PRIVATE)
                    .edit().putString("grab_" + key + "_last",
                            new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                    .format(new java.util.Date())).apply();
        } catch (Throwable ignored) {}
    }
    /** 卡券到期提醒：扫未使用卡券，临期（≤3 天）推送汇总。 */
    private static void notifyExpiring(Context app, JSONObject res) {
        try {
            List<String> expiring = new ArrayList<>();
            scanExpiring(res, expiring);
            if (expiring.isEmpty()) {
                MainHook.log("VipGrab: card scan done, none expiring");
                return;
            }
            StringBuilder sb = new StringBuilder("以下卡券即将过期：\n");
            for (String s : expiring) sb.append(s).append('\n');
            Notifier.notifyEvent(Notifier.EV_ERROR, "卡券到期提醒", sb.toString());
        } catch (Throwable t) {
            MainHook.log("VipGrab: notifyExpiring failed " + t);
        }
    }

    /** 递归扫描响应里的卡券对象：name/title + expire/end_time 字段。 */
    private static void scanExpiring(Object node, List<String> out) {
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                String name = o.optString("name", o.optString("title", ""));
                String expire = o.optString("expire_time", o.optString("expire", ""));
                if (!name.isEmpty() && !expire.isEmpty()) {
                    long exp = 0;
                    try { exp = Long.parseLong(expire); } catch (Throwable ignored) {}
                    boolean expiringSoon = false;
                    if (exp > 0) {
                        long days = (exp * (exp < 10_000_000_000L ? 1000L : 1L)
                                - System.currentTimeMillis()) / 86_400_000L;
                        expiringSoon = days <= 3;
                        if (expiringSoon) {
                            out.add(name + "（剩余 " + days + " 天）");
                            return;
                        }
                    }
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) scanExpiring(o.opt(it.next()), out);
            } else if (node instanceof JSONArray) {
                JSONArray arr = (JSONArray) node;
                for (int i = 0; i < arr.length(); i++) scanExpiring(arr.opt(i), out);
            }
        } catch (Throwable ignored) {}
    }
}
