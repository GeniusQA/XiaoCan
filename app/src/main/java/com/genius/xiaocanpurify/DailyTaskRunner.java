package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 一键日常自动化（参考 xiaocan-assistant 任务目录）：
 *   天天赚元宝签到 / 会员成长礼包 / 会员签到抽奖 / 幸运转盘 / 收取待领元宝。
 *
 * 触发方式：
 *   1. 「打开小蚕自动执行」开关开启时，每天首次进入 App 自动跑一次（延迟 20 秒等初始化）；
 *   2. 模块设置页「立即执行」按钮发 ACTION_DAILY_TASK 广播，宿主收到即刻执行。
 *
 * 所有调用走 XiaoCanRpc（凭证由宿主请求头自动抓取），默认关闭避免风控。
 */
public final class DailyTaskRunner {
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final String ACTION_RUN = "com.genius.xiaocanpurify.ACTION_DAILY_TASK";
    private static final String ACTION_FLASH_LIST =
            "com.genius.xiaocanpurify.ACTION_FLASH_LIST";
    private static final String ACTION_FLASH_LIST_RESULT =
            "com.genius.xiaocanpurify.ACTION_FLASH_LIST_RESULT";
    private static final String K_LAST_RUN = "daily_last_run";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean started;
    private static volatile long lastManualTriggerMs;

    private DailyTaskRunner() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        // 安装去重：宿主内多个 hook 入口会重复调用 installAll（实测 ×4）
        if (!INSTALLED.compareAndSet(false, true)) return;
        // 宿主首次 Activity 创建后：每天自动执行一次
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", android.os.Bundle.class);
            xposed.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Activity a = (Activity) chain.getThisObject();
                    if (a != null && !started) {
                        started = true;
                        registerReceiver(a.getApplication());
                        maybeRunToday(a.getApplicationContext());
                        // 凭据到期巡检 + 免单券抢购排程（14:00 窗口内）
                        credentialExpireCheck(a.getApplicationContext());
                        FreeOrderGrabber.armIfDue(a.getApplicationContext());
                        // 启动后异步校准服务器时间（抢单/倒计时对齐用）
                        final Context appCtx = a.getApplicationContext();
                        new Thread(() -> XiaoCanRpc.syncServerTime(appCtx),
                                "XiaoCanAssistant-TimeSync").start();
                    }
                } catch (Throwable ignored) {}
                return result;
            });
        } catch (Throwable t) {
            MainHook.log("DailyTaskRunner: hook Activity.onCreate failed: " + t);
        }
        MainHook.log("DailyTaskRunner installed (rpc daily tasks)");
    }

    private static void registerReceiver(Context app) {
        if (app == null) return;
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    String action = i.getAction();
                    if (ACTION_FLASH_LIST.equals(action)) {
                        handleFlashListQuery(c);
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    // 模块端会对同一触发连发 3 次广播（5/12/20 秒，防宿主
                    // 冷启动时接收器未注册），25 秒窗口内只执行首次
                    if (now - lastManualTriggerMs < 25_000L) return;
                    lastManualTriggerMs = now;
                    MainHook.log("DailyTask: manual trigger received, running");
                    runAsync(c);
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_RUN);
            f.addAction(ACTION_FLASH_LIST);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            MainHook.log("DailyTask: manual trigger receiver registered");
        } catch (Throwable t) {
            MainHook.log("DailyTask: register receiver failed: " + t);
        }
    }

    /** 每天首次自动执行（开关开启时）。 */
    private static void maybeRunToday(Context ctx) {
        if (ctx == null) return;
        if (!enabled()) {
            MainHook.log("DailyTask: auto daily disabled, skip");
            return;
        }
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences(
                    "purify_rpc", Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(p.getString(K_LAST_RUN, null))) {
                MainHook.log("DailyTask: already ran today");
                return;
            }
            MainHook.log("DailyTask: first launch today, run in 20s");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> runAsync(ctx), 20_000L);
        } catch (Throwable ignored) {}
    }

    /** 开关：PurifyPrefs 字符串通道（"1" 开启，默认关闭）。 */
    private static boolean enabled() {
        try {
            return "1".equals(PurifyPrefs.getString(PurifyPrefs.RPC_AUTO_DAILY, "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void runAsync(Context ctx) {
        if (ctx == null || !RUNNING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                run(app);
            } catch (Throwable t) {
                MainHook.log("DailyTask: run error " + t);
            } finally {
                RUNNING.set(false);
            }
        }, "XiaoCanAssistant-DailyTask").start();
    }

    private static void run(Context ctx) {
        if (!XiaoCanRpc.hasCredentials(ctx)) {
            MainHook.log("DailyTask: no credentials yet (open 小蚕 to capture), skip");
            Notifier.notifyEvent(Notifier.EV_ERROR, "小蚕一键日常未执行",
                    "尚未抓取到协议凭证：请先打开小蚕正常使用一次，再重试。");
            return;
        }
        String silk = XiaoCanRpc.silkId(ctx);
        MainHook.log("DailyTask: start (silk_id=" + silk + ")");
        int ok = 0, fail = 0;
        StringBuilder detail = new StringBuilder();

        ok += step("元宝签到", () -> XiaoCanRpc.signIn(ctx, silk), detail);
        sleep(1200);
        ybCenterTasks(ctx, silk, detail);
        sleep(1200);
        ok += step("成长礼包", () -> XiaoCanRpc.upVipGift(ctx, silk), detail);
        sleep(1200);
        ok += step("会员签到抽奖", () -> XiaoCanRpc.vipSignLottery(ctx, silk), detail);
        sleep(1200);
        groupLottery(ctx, silk, detail);
        sleep(1200);
        redPackRain(ctx, silk, detail);

        // 幸运转盘：查剩余次数后逐次抽
        try {
            JSONObject info = XiaoCanRpc.lotteryInfo(ctx, silk);
            int times = extractLotteryTimes(info);
            MainHook.log("DailyTask: lottery times=" + times);
            for (int i = 0; i < Math.min(times, 10); i++) {
                sleep(1500);
                ok += step("转盘第" + (i + 1) + "次", () -> XiaoCanRpc.lotterySpin(ctx, silk), detail);
            }
        } catch (Throwable t) {
            fail++;
            MainHook.log("DailyTask: lottery info failed " + t);
            detail.append("转盘: 查询失败\n");
        }

        sleep(1200);
        ok += step("收取待领元宝", () -> XiaoCanRpc.collectPoints(ctx, silk), detail);

        sleep(1500);
        flashSaleCheck(ctx, silk, detail);

        try {
            ctx.getSharedPreferences("purify_rpc", Context.MODE_PRIVATE)
                    .edit().putString(K_LAST_RUN,
                            new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()))
                    .apply();
        } catch (Throwable ignored) {}
        MainHook.log("DailyTask: done, ok=" + ok + " fail=" + fail);
        Notifier.notifyEvent(Notifier.EV_DAILY, "小蚕一键日常完成",
                "成功 " + ok + " 项" + (fail > 0 ? "，失败 " + fail + " 项" : "")
                        + "\n" + detail);
    }

    private interface RpcStep {
        JSONObject call() throws Exception;
    }

    /** 错误码人话解释（来源：实测 + xiaocan-assistant 的判定逻辑）。 */
    private static String codeMeaning(int code) {
        switch (code) {
            case 0:
                return "成功";
            case 2:
                return "参数错误（可能今日无可用礼包）";
            case 3:
                return "今日已签到";
            case 25:
                return "重复提交";
            case 10001:
                return "重复完成任务（今日已完成）";
            case 20006:
                return "任务不可重复提交（今日已完成）";
            case 30001:
                return "未登录或登录已过期";
            case 50010:
                return "该功能需小程序协议，暂不支持自动执行";
            default:
                if (code >= 40002 && code <= 40040) return "活动信息通知";
                if (code == 500) return "服务器内部错误";
                return "";
        }
    }

    private static boolean isNoticeCode(int code) {
        return code == 3 || code == 25 || code == 10001 || code == 20006
                || (code >= 40002 && code <= 40040);
    }

    private static int step(String name, RpcStep s, StringBuilder detail) {
        try {
            JSONObject res = s.call();
            String msg = res.optString("msg", "");
            int code = res.optInt("code", -1);
            // 信息性通知码（今日已完成/重复提交等）视为成功
            boolean ok = code == 0 || isNoticeCode(code);
            String mean = codeMeaning(code);
            MainHook.log("DailyTask: " + name + " -> code=" + code
                    + (msg.isEmpty() ? "" : " msg=" + msg));
            detail.append(name).append(": ")
                    .append(ok ? "成功" : "code=" + code)
                    .append(mean.isEmpty() ? "" : "（" + mean + "）")
                    .append(msg.isEmpty() ? "" : " " + msg).append('\n');
            return ok ? 1 : 0;
        } catch (Throwable t) {
            MainHook.log("DailyTask: " + name + " failed " + t);
            detail.append(name).append(": 失败 ").append(t.getMessage()).append('\n');
            return 0;
        }
    }

    /** 模块设置页请求在架商品列表：查询后回广播给模块 UI 做勾选。 */
    private static void handleFlashListQuery(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                if (!XiaoCanRpc.hasCredentials(app)) {
                    result = new JSONObject()
                            .put("error", "未抓取到凭证：请先打开小蚕正常使用一次").toString();
                } else {
                    JSONObject res = XiaoCanRpc.listFlashSale(
                            app, XiaoCanRpc.silkId(app), true);
                    JSONObject list = findObject(res, "list");
                    org.json.JSONArray out = new org.json.JSONArray();
                    if (list != null) {
                        for (String g : new String[]{"vip", "svip"}) {
                            org.json.JSONArray arr = list.optJSONArray(g);
                            if (arr == null) continue;
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject it = arr.optJSONObject(i);
                                if (it == null) continue;
                                JSONObject o = new JSONObject();
                                o.put("id", it.optLong("exchange_id"));
                                o.put("name", it.optString("goods_name", "商品"));
                                o.put("price", it.optLong("community_silk", 0));
                                o.put("left", it.optInt("left", 0));
                                o.put("group", g);
                                out.put(o);
                            }
                        }
                    }
                    result = out.toString();
                }
            } catch (Throwable t) {
                MainHook.log("FlashSale: list query failed " + t);
                // 用 JSONObject 构造：错误消息可能含英文引号（如 host 名），
                // 手工拼接会破坏 JSON 结构导致模块端解析失败
                try {
                    result = new JSONObject()
                            .put("error", String.valueOf(t.getMessage())).toString();
                } catch (Throwable ignored) {
                    result = "{\"error\":\"rpc failed\"}";
                }
            }
            try {
                android.content.Intent back = new android.content.Intent(ACTION_FLASH_LIST_RESULT)
                        .setPackage("com.genius.xiaocanpurify")
                        .putExtra("json", result);
                app.sendBroadcast(back);
            } catch (Throwable ignored) {}
        }, "XiaoCanAssistant-FlashList").start();
    }

    // ------------------------------------------------------------ 元宝中心任务

    /** 天天赚元宝中心任务（5 官方社群 / 57 抖音电商浏览 / 59 打卡）+ 领券与社群加赠事件。 */
    private static void ybCenterTasks(Context ctx, String silk, StringBuilder detail) {
        int[][] tasks = {{5, 500}, {57, 500}, {59, 1000}};
        for (int[] t : tasks) {
            try {
                JSONObject r = XiaoCanRpc.completeTask(ctx, silk, t[0]);
                int code = r.optInt("code", -1);
                MainHook.log("DailyTask: center task " + t[0] + " code=" + code);
                detail.append("元宝任务#").append(t[0]).append(": ")
                        .append(code == 0 ? "成功" : "code=" + code).append('\n');
                sleep(400);
                try {
                    XiaoCanRpc.userClaimPoints(ctx, silk, t[0]);
                } catch (Throwable ignored) {}
            } catch (Throwable e) {
                MainHook.log("DailyTask: center task " + t[0] + " failed " + e);
                detail.append("元宝任务#").append(t[0]).append(": 失败 ")
                        .append(e.getClass().getSimpleName()).append(' ')
                        .append(e.getMessage() == null ? "" : e.getMessage()).append('\n');
            }
            sleep(350);
        }
        for (int tt : new int[]{3, 4, 6}) {
            try {
                JSONObject r = XiaoCanRpc.completeTaskEvent(ctx, silk, tt);
                MainHook.log("DailyTask: task event " + tt + " code=" + r.optInt("code", -1));
            } catch (Throwable e) {
                MainHook.log("DailyTask: task event " + tt + " failed " + e);
            }
            sleep(300);
        }
    }

    // ------------------------------------------------------------ 免费开红包

    /** 领取全部免费开红包机会 + 阶梯奖励（转盘抽奖由主流程随后执行）。 */
    private static void groupLottery(Context ctx, String silk, StringBuilder detail) {
        int[] types = {1, 2, 4, 8, 9, 10, 11};
        int added = 0;
        for (int t : types) {
            try {
                JSONObject r = XiaoCanRpc.addLotteryTimes(ctx, silk, t);
                if (r.optInt("code", -1) == 0) added++;
            } catch (Throwable ignored) {}
            sleep(350);
        }
        MainHook.log("DailyTask: lottery opportunities added=" + added);
        for (int step : new int[]{1, 2}) {
            try {
                JSONObject r = XiaoCanRpc.receiveExtraLottery(ctx, silk, step);
                MainHook.log("DailyTask: extra lottery step" + step
                        + " code=" + r.optInt("code", -1));
            } catch (Throwable ignored) {}
            sleep(300);
        }
        detail.append("免费开红包: 领取机会 ").append(added).append(" 项\n");
    }

    // ------------------------------------------------------------ 整点红包雨

    /** 整点红包雨：场次内报名并抢抓（六场 10/11/12/14/16/19）。 */
    private static void redPackRain(Context ctx, String silk, StringBuilder detail) {
        try {
            String city = XiaoCanRpc.cityCode(ctx);
            JSONObject res = XiaoCanRpc.redPackRainEvent(ctx, silk, city);
            JSONObject ev = res.optJSONObject("event");
            boolean has = res.optBoolean("has_event", false);
            long now = XiaoCanRpc.serverNow();
            if (!has || ev == null) {
                detail.append("红包雨: 当前无场次\n");
                return;
            }
            long eid = ev.optLong("event_id");
            long begin = ev.optLong("begin_time") * 1000L;
            long end = ev.optLong("end_time") * 1000L;
            MainHook.log("DailyTask: rain event=" + eid + " begin=" + begin
                    + " end=" + end + " now=" + now);
            if (end > 0 && now > end) {
                detail.append("红包雨: 场次已结束\n");
                return;
            }
            if (begin > 0 && now < begin) {
                long waitMin = (begin - now) / 60000L;
                detail.append("红包雨: 场次 ").append(waitMin).append(" 分钟后开始\n");
                return;
            }
            JSONObject join = XiaoCanRpc.joinRedPackRain(ctx, silk, city, eid);
            sleep(600);
            JSONObject grab = XiaoCanRpc.grabRedPackRain(ctx, silk, city, eid, 15);
            int gcode = grab.optInt("code", -1);
            MainHook.log("DailyTask: rain join=" + join.optInt("code", -1)
                    + " grab=" + gcode);
            detail.append("红包雨: 参与并抢抓（")
                    .append(gcode == 0 ? "成功" : "code=" + gcode).append("）\n");
        } catch (Throwable t) {
            MainHook.log("DailyTask: rain failed " + t);
            detail.append("红包雨: 查询失败\n");
        }
    }

    // ------------------------------------------------------------ 凭据到期巡检

    /** 登录凭据（X-Sivir）到期巡检：临期 3 天内推送一次提醒（每天最多一次）。 */
    static void credentialExpireCheck(Context ctx) {
        try {
            long exp = XiaoCanRpc.tokenExpireAt(ctx);
            if (exp <= 0) return;
            long days = (exp - System.currentTimeMillis()) / 86_400_000L;
            if (days > 3) return;
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("purify_rpc", Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(p.getString("exp_notice_day", null))) return;
            p.edit().putString("exp_notice_day", today).apply();
            MainHook.log("DailyTask: credential expires in " + days + " days");
            Notifier.notifyEvent(Notifier.EV_ERROR, "登录凭据即将过期",
                    days <= 0 ? "凭据已过期，请重新打开小蚕完成登录"
                            : "凭据剩余约 " + days + " 天，过期后自动化任务将失效");
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 元宝秒杀

    /**
     * 元宝秒杀巡检/兑换。两处修复（参考上游 issue #4）：
     *  1) ListFlashSaleExchanges 的 list 是 {vip: [...], svip: [...]} 多分组结构，
     *     必须合并全部分组，否则 svip 商品永远匹配不到、自动优选也缺货；
     *  2) TodayExchange 在库存为 0 时也返回 status_code=0 并带随机无关商品，
     *     只判「不抛异常」必然假报成功 → 用兑换前后 yb_point 扣款金额实证校验，
     *     扣款 >= 商品单价才记成功、才发成功通知。
     */
    private static void flashSaleCheck(Context ctx, String silk, StringBuilder detail) {
        try {
            JSONObject res = XiaoCanRpc.listFlashSale(ctx, silk, true);
            JSONObject list = findObject(res, "list");
            java.util.List<JSONObject> items = new java.util.ArrayList<>();
            if (list != null) {
                collectGroup(list.optJSONArray("vip"), items);
                collectGroup(list.optJSONArray("svip"), items); // 修复二：合并 svip 分组
            }
            MainHook.log("FlashSale: items=" + items.size()
                    + " (vip+svip merged), rounds=" + res.optString("rounds"));
            if (items.isEmpty()) {
                detail.append("元宝秒杀: 今日无在架商品\n");
                return;
            }
            JSONObject target = pickTarget(items);
            if (target == null) {
                detail.append("元宝秒杀: 无可兑换商品\n");
                return;
            }
            long eid = target.optLong("exchange_id");
            String name = target.optString("goods_name", "商品#" + eid);
            long price = target.optLong("community_silk", 0);
            int left = target.optInt("left", 0);
            MainHook.log("FlashSale: target=" + name + " id=" + eid
                    + " price=" + price + " left=" + left);
            detail.append("元宝秒杀: 【").append(name).append("】库存 ").append(left).append('\n');
            if (left <= 0) return;

            boolean auto = "1".equals(PurifyPrefs.getString(PurifyPrefs.FLASH_SALE_AUTO, "0"));
            if (!auto) {
                Notifier.notifyEvent(Notifier.EV_SPIKE, "元宝秒杀有货提醒",
                        "【" + name + "】库存 " + left + "，单价 " + price
                                + " 元宝（自动兑换未开启，需手动下单）");
                return;
            }

            // 修复一：实证校验——兑换前后元宝余额对比
            long before = XiaoCanRpc.ybPoint(ctx, silk);
            JSONObject ex = XiaoCanRpc.todayExchange(ctx, silk, eid);
            sleep(2500);
            long after = XiaoCanRpc.ybPoint(ctx, silk);
            long cost = (before >= 0 && after >= 0) ? before - after : -1;
            boolean ok = cost >= 0 && (price <= 0 || cost >= price);
            MainHook.log("FlashSale: exchange result cost=" + cost + " expected=" + price
                    + " ok=" + ok + " resp=" + ex.optString("status_code"));
            if (ok) {
                detail.append("元宝秒杀: 【").append(name).append("】兑换成功（扣 ")
                        .append(cost).append(" 元宝）\n");
                Notifier.notifyEvent(Notifier.EV_SPIKE, "元宝秒杀成功",
                        "已兑换【" + name + "】，扣除 " + cost + " 元宝");
            } else {
                detail.append("元宝秒杀: 【").append(name)
                        .append("】未抢到（扣款校验未通过，不发送成功通知）\n");
            }
        } catch (Throwable t) {
            MainHook.log("FlashSale: check failed " + t);
            detail.append("元宝秒杀: 查询失败 ").append(t.getMessage()).append('\n');
        }
    }

    private static void collectGroup(org.json.JSONArray arr, java.util.List<JSONObject> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
    }

    /** 选目标：配置的 exchange_id 优先（多组已合并），否则第一个有库存的商品。 */
    private static JSONObject pickTarget(java.util.List<JSONObject> items) {
        String goods = "";
        try {
            goods = PurifyPrefs.getString(PurifyPrefs.FLASH_SALE_GOODS, "");
        } catch (Throwable ignored) {}
        if (goods != null && !goods.trim().isEmpty()) {
            for (String s : goods.split(",")) {
                long id;
                try {
                    id = Long.parseLong(s.trim());
                } catch (Throwable t) {
                    continue;
                }
                for (JSONObject it : items) {
                    if (it.optLong("exchange_id") == id) return it;
                }
            }
        }
        for (JSONObject it : items) {
            if (it.optInt("left", 0) > 0) return it;
        }
        return items.isEmpty() ? null : items.get(0);
    }

    /** 递归查找 JSONObject 字段。 */
    private static JSONObject findObject(JSONObject obj, String key) {
        if (obj == null) return null;
        JSONObject v = obj.optJSONObject(key);
        if (v != null) return v;
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            Object o = obj.opt(it.next());
            if (o instanceof JSONObject) {
                JSONObject r = findObject((JSONObject) o, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int extractLotteryTimes(JSONObject info) {
        try {
            JSONObject data = info.optJSONObject("data");
            if (data == null) data = info;
            for (String key : new String[]{"lottery_times", "remain_times", "left_times",
                    "times", "num", "lottery_num"}) {
                if (data.has(key)) return data.optInt(key, 0);
            }
            // 兜底：扫描字段名含 time/num 的整数
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String key = it.next().toLowerCase(Locale.ROOT);
                if (key.contains("time") || key.contains("num")) {
                    int v = data.optInt(key, 0);
                    if (v > 0) return v;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {}
    }
}
