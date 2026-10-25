package com.genius.xiaocanpurify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 小蚕网关 RPC 直接调用（参考开源项目 xiaocan-assistant 的协议实现）。
 *
 * 签名规则（重要：签名只含方法名+时间戳+随机 UUID，不含 body/URL）：
 *   X-Ashe = md5( md5((serverName.methodName).toLowerCase()) + 毫秒时间戳 + X-Nami )
 *
 * 凭证来源：不要求用户手填 —— 由 NetworkAdInterceptor 从宿主真实请求头
 * 自动抓取（X-Sivir=JWT、X-Teemo=user_id、x-City=城市码、User-Agent/X-Version、
 * X-Session-Id），并从请求体抓 silk_id，持久化到宿主私有 prefs。
 */
public final class XiaoCanRpc {
    private static final String GW = "https://gw.xiaocantech.com/rpc";
    private static final String PREFS = "purify_rpc";

    private static final String K_TOKEN = "token";
    private static final String K_UID = "uid";
    private static final String K_VAYNE = "vayne";
    private static final String K_SILK = "silk";
    private static final String K_CITY = "city";
    private static final String K_UA = "ua";
    private static final String K_VER = "ver";
    private static final String K_SID = "sid";
    private static final String K_SR = "sr";

    private XiaoCanRpc() {}

    // ---------------------------------------------------------------- 凭证抓取

    /** 从宿主请求头抓取凭证（App 原生认证集：X-Sivir/X-Vayne/x-Teemo/会话/UA 等）。 */
    public static void captureHeaders(Context ctx, String token, String uid, String city,
                                      String ua, String ver, String sid,
                                      String vayne, String sr) {
        SharedPreferences p = prefs(ctx);
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        boolean changed = false;
        if (token != null && token.length() > 20 && !token.equals(p.getString(K_TOKEN, null))) {
            e.putString(K_TOKEN, token);
            e.remove("token_exp"); // 令牌变化后重新解析过期时间
            changed = true;
        }
        if (uid != null && !uid.isEmpty() && !uid.equals(p.getString(K_UID, null))) {
            e.putString(K_UID, uid);
            changed = true;
        }
        if (vayne != null && !vayne.isEmpty() && !vayne.equals(p.getString(K_VAYNE, null))) {
            e.putString(K_VAYNE, vayne);
            changed = true;
        }
        if (city != null && !city.isEmpty() && !city.equals(p.getString(K_CITY, null))) {
            e.putString(K_CITY, city);
            changed = true;
        }
        if (ua != null && !ua.isEmpty() && !ua.equals(p.getString(K_UA, null))) {
            e.putString(K_UA, ua);
            changed = true;
        }
        if (ver != null && !ver.isEmpty() && !ver.equals(p.getString(K_VER, null))) {
            e.putString(K_VER, ver);
            changed = true;
        }
        if (sid != null && !sid.isEmpty() && !sid.equals(p.getString(K_SID, null))) {
            e.putString(K_SID, sid);
            changed = true;
        }
        if (sr != null && !sr.isEmpty() && !sr.equals(p.getString(K_SR, null))) {
            e.putString(K_SR, sr);
            changed = true;
        }
        if (changed) {
            e.apply();
            MainHook.log("RPC: credentials captured (vayne=" + vayne + " silk="
                    + p.getString(K_SILK, "-") + ")");
        }
    }

    /** 从请求体抓 silk_id（宿主每次都带）。 */
    public static void captureSilkId(Context ctx, String body) {
        if (body == null || body.isEmpty()) return;
        SharedPreferences p = prefs(ctx);
        if (p == null) return;
        if (p.getString(K_SILK, null) != null) return;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"silk_id\"\\s*:\\s*\"?(\\d+)")
                    .matcher(body);
            if (m.find()) {
                p.edit().putString(K_SILK, m.group(1)).apply();
                MainHook.log("RPC: silk_id captured " + m.group(1));
            }
        } catch (Throwable ignored) {}
    }

    /** 从宿主的 OkHttp 请求对象抓取凭证（拦截器调用，失败静默）。 */
    public static void captureFromRequest(Object request) {
        try {
            Context ctx = currentApplication();
            if (ctx == null || request == null) return;
            java.lang.reflect.Method header =
                    request.getClass().getMethod("header", String.class);
            captureHeaders(ctx,
                    (String) header.invoke(request, "X-Sivir"),
                    (String) header.invoke(request, "X-Teemo"),
                    (String) header.invoke(request, "x-City"),
                    (String) header.invoke(request, "User-Agent"),
                    (String) header.invoke(request, "X-Version"),
                    (String) header.invoke(request, "X-Session-Id"),
                    (String) header.invoke(request, "X-Vayne"),
                    (String) header.invoke(request, "X-App-Sr"));
            SharedPreferences p = prefs(ctx);
            if (p != null && p.getString(K_SILK, null) == null) {
                Object b = request.getClass().getMethod("body").invoke(request);
                if (b != null) {
                    ClassLoader cl = request.getClass().getClassLoader();
                    Class<?> bufCls = Class.forName("okio.Buffer", false, cl);
                    Object buf = bufCls.getDeclaredConstructor().newInstance();
                    b.getClass().getMethod("writeTo",
                            Class.forName("okio.BufferedSink", false, cl)).invoke(b, buf);
                    captureSilkId(ctx, (String) bufCls.getMethod("readUtf8").invoke(buf));
                }
            }
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

    public static boolean hasCredentials(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p != null && p.getString(K_TOKEN, null) != null;
    }

    private static SharedPreferences prefs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 调用

    /** 从宿主拦截器链捕获的 OkHttpClient（带 HTTPDNS/连接池，DNS 兜底）。 */
    private static volatile Object capturedClient;

    /** 由 NetworkAdInterceptor 在宿主请求经过时捕获 App 自己的 OkHttpClient。 */
    public static void captureOkHttpClient(Object client) {
        if (client != null && capturedClient == null) {
            capturedClient = client;
            MainHook.log("RPC: captured host OkHttpClient (dns-safe)");
        }
    }

    static Object capturedClient() {
        return capturedClient;
    }

    /** 调用一个 RPC 方法；返回 {"code":0,...} 或抛异常。 */
    public static JSONObject invoke(Context ctx, String serverName, String methodName,
                                    JSONObject body) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (p == null) throw new IllegalStateException("prefs unavailable");
        String token = p.getString(K_TOKEN, null);
        if (token == null) throw new IllegalStateException("缺少凭证：请先打开小蚕产生一次请求");
        String vayne = p.getString(K_VAYNE, null);
        String silk = p.getString(K_SILK, "0");
        String ua = p.getString(K_UA, "XC;Android;3.21.5;");
        String ver = p.getString(K_VER, "3.21.5.4");
        String sid = p.getString(K_SID, null);
        String sr = p.getString(K_SR, "16");
        if (sid == null) sid = UUID.randomUUID().toString();

        long ts = System.currentTimeMillis();
        String nami = UUID.randomUUID().toString();
        String ashe = md5(md5((serverName + "." + methodName).toLowerCase()) + ts + nami);

        JSONObject payload = body == null ? new JSONObject() : new JSONObject(body.toString());
        if (!payload.has("app_id")) payload.put("app_id", 10);

        // 头集合 1:1 复刻宿主 App 的真实请求头（RPCDIAG[app-hdrs] 实测）：
        // servername/methodname/env=prod/X-Ashe/X-Nami/X-Garen/X-Platform=Android/
        // x-Annie=XC/X-Session-Id/User-Agent/x-channel/X-App-Sr/X-Vayne(用户ID)/
        // x-Teemo(silk_id)/X-Sivir(JWT)/X-Version —— 多发或少发任何头都会被
        // 腾讯 WAF 按指纹 403（用户实测：换成 XiaoCan UA + 加 Authorization
        // 等"增强"头后全量 403）。不加 Content-Type：OkHttp 从 RequestBody
        // 自动带；UrlConnection 路径单独补。
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("servername", serverName);
        headers.put("methodname", methodName);
        headers.put("env", "prod");
        headers.put("X-Ashe", ashe);
        headers.put("X-Nami", nami);
        headers.put("X-Garen", String.valueOf(ts));
        headers.put("X-Platform", "Android");
        headers.put("x-Annie", "XC");
        headers.put("X-Session-Id", sid);
        headers.put("User-Agent", ua);
        headers.put("x-channel", "xiaocan");
        headers.put("X-App-Sr", sr);
        if (vayne != null && !vayne.isEmpty()) headers.put("X-Vayne", vayne);
        if (silk != null && !silk.isEmpty()) headers.put("x-Teemo", silk);
        headers.put("X-Sivir", token);
        headers.put("X-Version", ver);
        // 内部请求标记：宿主拦截器对它直通（不改写/不拦截）
        headers.put("X-Purify-Internal", "1");

        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Object client = capturedClient;
                JSONObject res = (client != null)
                        ? executeWithOkHttp(client, GW, payload, headers)
                        : executeWithUrlConnection(GW, payload, headers);
                return normalize(ctx, serverName, methodName, res);
            } catch (IllegalStateException e) {
                // WAF 拦截不重试（避免延长封锁计时）
                if (String.valueOf(e.getMessage()).contains("WAF")) throw e;
                last = e;
                log("RPC: attempt" + (attempt + 1) + " failed " + e);
                try {
                    Thread.sleep(900);
                } catch (Throwable ignored) {}
            } catch (java.io.IOException e) {
                last = e;
                log("RPC: attempt" + (attempt + 1) + " failed " + e);
                try {
                    Thread.sleep(900);
                } catch (Throwable ignored) {}
            }
        }
        throw last != null ? last : new IllegalStateException("invoke failed");
    }

    /**
     * 响应判定归一化（对齐 xiaocan-assistant 的 invoke_rpc 逻辑）：
     *  1. 顶层 "error" = Go micro 网关错误（可能是 JSON 字符串，取 code/detail）；
     *  2. "status" 为对象时取 status.code/status.msg；
     *  3. 否则取顶层 code（非整数视为 500）；
     *  4. 无任何状态字段的未知形态：code=500 并记录响应体前缀，便于排查。
     * 信息性通知码（3/25/40002-40040，如「今日已完成」）视为成功。
     */
    private static JSONObject normalize(Context ctx, String serverName, String methodName,
                                        JSONObject res) {
        try {
            String prefix = "（响应体超长，见模块日志诊断）";
            if (res.has("error")) {
                int ec = 500;
                String em = String.valueOf(res.opt("error"));
                try {
                    if (em.startsWith("{")) {
                        JSONObject pe = new JSONObject(em);
                        ec = pe.optInt("code", 500);
                        em = pe.has("detail") ? pe.optString("detail")
                                : pe.optString("status", em);
                    }
                } catch (Throwable ignored) {}
                log("RPC: " + methodName + " gateway error code=" + ec + " msg=" + em);
                res.put("code", ec);
                res.put("msg", em);
                return res;
            }
            JSONObject status = res.optJSONObject("status");
            int effCode;
            String effMsg;
            if (status != null) {
                effCode = status.optInt("code", 0);
                effMsg = status.optString("msg", "ok");
            } else if (res.has("code") && (res.get("code") instanceof Integer)) {
                effCode = res.getInt("code");
                effMsg = res.optString("msg", res.optString("detail",
                        res.optString("status", "RPC Error")));
            } else {
                effCode = 500;
                effMsg = "未知响应形态";
            }
            if (effCode != 0) {
                boolean notice = effCode == 3 || effCode == 25
                        || (effCode >= 40002 && effCode <= 40040);
                log("RPC: " + methodName + (notice ? " notice" : " rejected")
                        + " code=" + effCode + " msg=" + effMsg);
            }
            res.put("code", effCode);
            res.put("msg", effMsg);
            return res;
        } catch (Throwable t) {
            log("RPC: normalize failed " + t);
            try {
                res.put("code", 500);
                res.put("msg", "normalize failed");
            } catch (Throwable ignored) {}
            return res;
        }
    }

    /** 优先路径：复用宿主 OkHttpClient（HTTPDNS 解析，规避系统 DNS 失败）。 */
    private static JSONObject executeWithOkHttp(Object client, String url,
            JSONObject payload, java.util.LinkedHashMap<String, String> headers)
            throws Exception {
        ClassLoader cl = client.getClass().getClassLoader();
        Class<?> reqCls = cl.loadClass("okhttp3.Request");
        Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
        Class<?> bodyCls = cl.loadClass("okhttp3.RequestBody");
        Class<?> mtCls = cl.loadClass("okhttp3.MediaType");

        Object builder = builderCls.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method urlM = builderCls.getMethod("url", String.class);
        urlM.invoke(builder, url);
        java.lang.reflect.Method headerM = builderCls.getMethod(
                "header", String.class, String.class);
        for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
            headerM.invoke(builder, h.getKey(), h.getValue());
        }
        Object mt = mtCls.getMethod("parse", String.class)
                .invoke(null, "application/json; charset=utf-8");
        Object reqBody = bodyCls.getMethod("create", mtCls, byte[].class)
                .invoke(null, mt, payload.toString().getBytes(StandardCharsets.UTF_8));
        builderCls.getMethod("post", bodyCls).invoke(builder, reqBody);
        Object request = builderCls.getMethod("build").invoke(builder);

        Object call = cl.loadClass("okhttp3.OkHttpClient")
                .getMethod("newCall", reqCls).invoke(client, request);
        Object response = cl.loadClass("okhttp3.Call")
                .getMethod("execute").invoke(call);
        try {
            int code = (Integer) response.getClass().getMethod("code").invoke(response);
            Object respBody = response.getClass().getMethod("body").invoke(response);
            String text = respBody == null ? ""
                    : (String) respBody.getClass().getMethod("string").invoke(respBody);
            String token = headers.get("X-Sivir");
            log("RPCDIAG[mod] " + headers.get("servername") + "."
                    + headers.get("methodname") + " HTTP " + code
                    + " sivir=" + (token == null ? "null"
                    : (token.length() > 14
                    ? token.substring(0, 14) + "..(" + token.length() + ")" : token))
                    + " teemo=" + headers.get("X-Teemo")
                    + " bodyLen=" + payload.toString().length());
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalStateException("空响应 (HTTP " + code + ")");
            }
            if (code == 403) throw new IllegalStateException("WAF 拦截(403)，暂停调用");
            JSONObject res = new JSONObject(text);
            if (!res.has("code") && !res.has("status") && !res.has("error")) {
                log("RPC: unknown shape body=" + (text.length() > 300
                        ? text.substring(0, 300) : text));
            }
            return res;
        } finally {
            try {
                response.getClass().getMethod("close").invoke(response);
            } catch (Throwable ignored) {}
        }
    }

    /** 兜底路径：HttpURLConnection（系统 DNS）。 */
    private static JSONObject executeWithUrlConnection(String url, JSONObject payload,
            java.util.LinkedHashMap<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            log("RPCDIAG[mod] " + headers.get("servername") + "."
                    + headers.get("methodname") + " HTTP " + code
                    + " respLen=" + (text == null ? 0 : text.length())
                    + " hdrs=" + String.join(",", headers.keySet()));
            if (code == 403) {
                throw new IllegalStateException("WAF 拦截(403)，暂停调用");
            }
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalStateException("空响应 (HTTP " + code + ")");
            }
            JSONObject res = new JSONObject(text);
            if (!res.has("code") && !res.has("status") && !res.has("error")) {
                log("RPC: unknown shape body=" + (text.length() > 300
                        ? text.substring(0, 300) : text));
            }
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void log(String msg) {
        try {
            MainHook.log(msg);
        } catch (Throwable t) {
            try {
                android.util.Log.i("XiaoCanAssistant", msg);
            } catch (Throwable ignored) {}
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "{}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 常用任务

    /** 天天赚元宝每日签到（ActivityTaskMobileService.SignIn）。 */
    public static JSONObject signIn(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "ActivityTask", "ActivityTaskMobileService.SignIn", b);
    }

    /** 会员成长值/每日礼包（SilkwormMobileMarketingService.GetUserUpVipGift）。 */
    public static JSONObject upVipGift(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormMarketing",
                "SilkwormMobileMarketingService.GetUserUpVipGift", b);
    }

    /** 会员每日签到抽奖（VipRightsService.SignInLottery）。 */
    public static JSONObject vipSignLottery(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormVip", "VipRightsService.SignInLottery", b);
    }

    /** 幸运转盘信息（含剩余次数）。 */
    public static JSONObject lotteryInfo(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormLottery", "SilkwormLotteryMobile.LotteryInfo", b);
    }

    /** 幸运转盘抽奖（prize_type=1）。 */
    public static JSONObject lotterySpin(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("prize_type", 1);
        return invoke(ctx, "SilkwormLottery", "SilkwormLotteryMobile.Lottery", b);
    }

    /** 一键收取待领元宝（ActivityTaskMobileService.CollectPoints）。 */
    public static JSONObject collectPoints(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "ActivityTask", "ActivityTaskMobileService.CollectPoints", b);
    }

    // ---------------------------------------------------------------- 元宝中心任务

    /** 完成天天赚元宝中心任务（5=官方社群 / 57=抖音电商浏览 / 59=打卡）。 */
    public static JSONObject completeTask(Context ctx, String silkId, int taskId)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("user_id", parseLong(userId(ctx)));
        b.put("task_id", taskId);
        return invoke(ctx, "ActivityTask", "ActivityTaskMobileService.CompleteTask", b);
    }

    /** 收取指定任务元宝（含气泡元宝）。 */
    public static JSONObject userClaimPoints(Context ctx, String silkId, int taskId)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("user_id", parseLong(userId(ctx)));
        b.put("task_id", taskId);
        return invoke(ctx, "ActivityTask", "ActivityTaskMobileService.UserClaimPoints", b);
    }

    /** 领券/社群加赠打卡（3=饿了么 / 4=美团 / 6=官方社群）。 */
    public static JSONObject completeTaskEvent(Context ctx, String silkId, int taskType)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("task_type", taskType);
        return invoke(ctx, "SilkwormMarketing",
                "SilkwormMobileMarketingService.CompleteTaskEvent", b);
    }

    // ---------------------------------------------------------------- 免费开红包

    /** 领取免费开红包机会（1=签到 2=分享 4=沾一沾 8=饿了么 9=美团 10=到店 11=福利）。 */
    public static JSONObject addLotteryTimes(Context ctx, String silkId, int type)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("type", type);
        return invoke(ctx, "SilkwormLottery", "SilkwormLotteryMobile.AddLotteryTimes", b);
    }

    /** 领取阶梯累计奖励（step 1=3次档 / 2=9次档）。 */
    public static JSONObject receiveExtraLottery(Context ctx, String silkId, int step)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("step", step);
        return invoke(ctx, "SilkwormLottery", "SilkwormLotteryMobile.ReceiveExtraLottery", b);
    }

    /** 阶梯累计抽奖进度。 */
    public static JSONObject lotteryProgress(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormLottery", "SilkwormLotteryMobile.GetLotteryProgress", b);
    }

    // ---------------------------------------------------------------- 整点红包雨

    public static JSONObject redPackRainEvent(Context ctx, String silkId, String cityCode)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("city_code", parseLong(cityCode == null ? "510100" : cityCode));
        return invoke(ctx, "SilkwormLottery",
                "SilkwormLotteryMobile.GetRedPackRainEvent", b);
    }

    public static JSONObject joinRedPackRain(Context ctx, String silkId, String cityCode,
                                             long eventId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("city_code", parseLong(cityCode == null ? "510100" : cityCode));
        if (eventId > 0) b.put("event_id", eventId);
        return invoke(ctx, "SilkwormLottery",
                "SilkwormLotteryMobile.JoinRedPackRainEvent", b);
    }

    public static JSONObject grabRedPackRain(Context ctx, String silkId, String cityCode,
                                             long eventId, int clickNum) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("click_num", clickNum);
        if (eventId > 0) b.put("event_id", eventId);
        return invoke(ctx, "SilkwormLottery",
                "SilkwormLotteryMobile.RedPackRainGrabNum", b);
    }

    // ---------------------------------------------------------------- 每月免单券

    public static JSONObject userFreeOrderInfo(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormVip", "VipRightsService.UserFreeOrderInfo", b);
    }

    public static JSONObject grabFreeOrderQuota(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        return invoke(ctx, "SilkwormVip", "VipRightsService.GrabFreeOrderQuota", b);
    }

    // ---------------------------------------------------------------- 凭据

    public static String userId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String s = p != null ? p.getString(K_UID, null) : null;
        return s == null ? "0" : s;
    }

    public static String cityCode(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String s = p != null ? p.getString(K_CITY, null) : null;
        return s == null ? "510100" : s;
    }

    /** 凭据（JWT）过期时间戳（毫秒），解析失败返回 -1。 */
    public static long tokenExpireAt(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p == null) return -1L;
        long cached = p.getLong("token_exp", -1L);
        if (cached > 0) return cached;
        String token = p.getString(K_TOKEN, null);
        if (token == null) return -1L;
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return -1L;
            String payload = new String(java.util.Base64.getUrlDecoder()
                    .decode(parts[1]), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"exp\"\\s*:\\s*(\\d+)").matcher(payload);
            if (m.find()) {
                long expMs = Long.parseLong(m.group(1)) * 1000L;
                p.edit().putLong("token_exp", expMs).apply();
                return expMs;
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    // ---------------------------------------------------------------- 元宝秒杀

    /** 秒杀商品列表（list 为 {vip:[...], svip:[...]} 多分组结构）。 */
    public static JSONObject listFlashSale(Context ctx, String silkId, boolean isPlus)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("new_level", 5);
        b.put("is_plus", isPlus);
        return invoke(ctx, "SilkwormCommunity",
                "SilkwormMobileCommunityService.ListFlashSaleExchanges", b);
    }

    /** 秒杀兑换（注意：库存为 0 时也会返回 status_code=0 并带随机无关商品，
     *  必须用 yb_point 扣款实证校验，不能以「不抛异常」判定成功）。 */
    public static JSONObject todayExchange(Context ctx, String silkId, long exchangeId)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        b.put("exchange_id", exchangeId);
        return invoke(ctx, "SilkwormCommunity",
                "SilkwormMobileCommunityService.TodayExchange", b);
    }

    /** 元宝余额（UserTaskV2 的 yb_point），用于兑换成功的实证校验。 */
    public static long ybPoint(Context ctx, String silkId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("silk_id", parseLong(silkId));
        JSONObject res = invoke(ctx, "ActivityTask",
                "ActivityTaskMobileService.UserTaskV2", b);
        Long v = findLong(res, "yb_point");
        return v == null ? -1L : v;
    }

    /** 递归查找第一个数值字段（yb_point 位置不固定）。 */
    private static Long findLong(JSONObject obj, String key) {
        if (obj == null) return null;
        if (obj.has(key)) {
            Object v = obj.opt(key);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v != null) {
                try {
                    return Long.parseLong(String.valueOf(v));
                } catch (Throwable ignored) {}
            }
        }
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            Object v = obj.opt(it.next());
            if (v instanceof JSONObject) {
                Long r = findLong((JSONObject) v, key);
                if (r != null) return r;
            } else if (v instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object o = arr.opt(i);
                    if (o instanceof JSONObject) {
                        Long r = findLong((JSONObject) o, key);
                        if (r != null) return r;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 授时

    /** 服务器时间偏移（毫秒），供抢单/倒计时对齐使用。 */
    private static volatile long serverOffsetMs = 0;

    public static long serverNow() {
        return System.currentTimeMillis() + serverOffsetMs;
    }

    /** 用网关响应头 Date 估算服务器时间偏移（无需外部 NTP 服务）。 */
    public static void syncServerTime(Context ctx) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://gw.xiaocantech.com/")
                    .openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            long localBefore = System.currentTimeMillis();
            c.getResponseCode();
            String date = c.getHeaderField("Date");
            c.disconnect();
            if (date == null) return;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            long server = fmt.parse(date).getTime();
            long local = (localBefore + System.currentTimeMillis()) / 2;
            serverOffsetMs = server - local;
            MainHook.log("Time: server offset " + serverOffsetMs + "ms");
            try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong("time_offset", serverOffsetMs).apply();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            MainHook.log("Time: sync failed " + t);
            try {
                SharedPreferences p = prefs(ctx);
                if (p != null) serverOffsetMs = p.getLong("time_offset", 0L);
            } catch (Throwable ignored) {}
        }
    }

    public static String silkId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String s = p != null ? p.getString(K_SILK, null) : null;
        return s == null ? "0" : s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
