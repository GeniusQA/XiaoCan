package com.genius.xiaocanpurify;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 事件通知：多渠道推送（企业微信 / 钉钉 / 飞书 / Server酱 / Bark / 自定义 Webhook / 邮件 SMTP）。
 *
 * 配置存储在模块设置页写入的 JSON 字符串（PurifyPrefs.NOTIFY_CONFIG），
 * 由宿主经远程偏好读取。发送在后台线程执行，失败只记模块日志不打断主流程。
 *
 * SMTP 为纯 Java 实现（SMTPS 465 / STARTTLS 587 + AUTH LOGIN + UTF-8 MIME），
 * 不依赖 JavaMail。
 */
public final class Notifier {
    public static final String EV_DAILY = "daily";
    public static final String EV_ERROR = "error";
    public static final String EV_SPIKE = "spike";

    private Notifier() {}

    /** 事件通知入口（宿主内调用）：event=daily/error，按配置渠道发送。 */
    public static void notifyEvent(String event, String title, String body) {
        try {
            Context ctx = currentApplication();
            if (ctx == null) return;
            JSONObject cfg = config(ctx);
            if (cfg == null) return;
            if (!"1".equals(cfg.optString("enabled", "0"))) return;
            String events = cfg.optString("events", "");
            if (!events.contains(event)) return;
            sendAsync(ctx, cfg, title, body);
        } catch (Throwable t) {
            log("Notify: dispatch failed " + t);
        }
    }

    /** 按给定配置发送（模块页「发送测试通知」也走这里）。 */
    public static void sendAsync(Context ctx, JSONObject cfg, String title, String body) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                send(app, cfg, title, body);
                log("Notify: sent via " + cfg.optString("channel"));
            } catch (Throwable t) {
                log("Notify: send failed (" + cfg.optString("channel") + ") " + t);
            }
        }, "XiaoCanPurify-Notify").start();
    }

    public static JSONObject config(Context ctx) {
        try {
            String raw = PurifyPrefs.getString(PurifyPrefs.NOTIFY_CONFIG, null);
            if (raw == null || raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void send(Context ctx, JSONObject cfg, String title, String body)
            throws Exception {
        String channel = cfg.optString("channel", "off");
        switch (channel) {
            case "wecom":
                postJson(cfg.optString("webhook"), "{\"msgtype\":\"text\",\"text\":{\"content\":"
                        + JSONObject.quote(title + "\n" + body) + "}}");
                break;
            case "dingtalk":
                postJson(cfg.optString("webhook"), "{\"msgtype\":\"text\",\"text\":{\"content\":"
                        + JSONObject.quote(title + "\n" + body) + "}}");
                break;
            case "feishu":
                postJson(cfg.optString("webhook"), "{\"msg_type\":\"text\",\"content\":{\"text\":"
                        + JSONObject.quote(title + "\n" + body) + "}}");
                break;
            case "serverchan": {
                String key = cfg.optString("serverchan_key");
                postForm("https://sctapi.ftqq.com/" + key + ".send",
                        "title=" + enc(title) + "&desp=" + enc(body));
                break;
            }
            case "bark": {
                String key = cfg.optString("bark_key");
                get("https://api.day.app/" + key + "/" + enc(title) + "/" + enc(body));
                break;
            }
            case "custom":
                postJson(cfg.optString("webhook"),
                        "{\"title\":" + JSONObject.quote(title)
                                + ",\"text\":" + JSONObject.quote(body) + "}");
                break;
            case "telegram":
                postForm("https://api.telegram.org/bot" + cfg.optString("tg_token")
                                + "/sendMessage",
                        "chat_id=" + enc(cfg.optString("tg_chat"))
                                + "&text=" + enc(title + "\n" + body)
                                + "&disable_web_page_preview=true");
                break;
            case "gotify":
                postJson(cfg.optString("gotify_url") + "/message?token="
                                + cfg.optString("gotify_token"),
                        "{\"title\":" + JSONObject.quote(title)
                                + ",\"message\":" + JSONObject.quote(body)
                                + ",\"priority\":5}");
                break;
            case "discord":
                postJson(cfg.optString("discord_webhook"),
                        "{\"content\":" + JSONObject.quote("**" + title + "**\n" + body) + "}");
                break;
            case "qq":
                postForm(cfg.optString("qq_url") + "/send_private_msg?access_token="
                                + cfg.optString("qq_token"),
                        "user_id=" + enc(cfg.optString("qq_uid"))
                                + "&message=" + enc(title + "\n" + body));
                break;
            case "wxpusher":
                postJson("https://wxpusher.zjiecode.com/api/send/message",
                        "{\"appToken\":" + JSONObject.quote(cfg.optString("wxpusher_token"))
                                + ",\"content\":" + JSONObject.quote(title + "\n" + body)
                                + ",\"summary\":" + JSONObject.quote(title)
                                + ",\"contentType\":1,\"uids\":["
                                + JSONObject.quote(cfg.optString("wxpusher_uid")) + "]}");
                break;
            case "smtp":
                smtpSend(cfg, title, body);
                break;
            default:
                log("Notify: channel off, skip");
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static void postJson(String url, String json) throws Exception {
        if (url == null || !url.startsWith("http")) throw new IllegalArgumentException("webhook 未配置");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.close();
            drain(c);
        } finally {
            c.disconnect();
        }
    }

    private static void postForm(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(form.getBytes(StandardCharsets.UTF_8));
            os.close();
            drain(c);
        } finally {
            c.disconnect();
        }
    }

    private static void get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + c.getResponseCode());
        } finally {
            c.disconnect();
        }
    }

    private static void drain(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
        }
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    // ---------------------------------------------------------------- SMTP

    private static void smtpSend(JSONObject cfg, String subject, String text) throws Exception {
        String host = cfg.optString("smtp_host");
        int port = cfg.optInt("smtp_port", 465);
        String user = cfg.optString("smtp_user");
        String pass = cfg.optString("smtp_pass");
        String from = cfg.optString("smtp_from", user);
        String fromName = cfg.optString("smtp_from_name", "小蚕助手");
        String to = cfg.optString("smtp_to");
        if (host.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("SMTP 配置不完整");

        java.net.Socket sock;
        if (port == 465) {
            javax.net.ssl.SSLSocketFactory f =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            sock = f.createSocket(host, port);
        } else {
            sock = new java.net.Socket(host, port);
        }
        sock.setSoTimeout(15000);
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        java.io.BufferedWriter out = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
        try {
            expect(in, 220);
            ehlo(in, out);
            if (port != 465) {
                send(out, "STARTTLS");
                expect(in, 220);
                javax.net.ssl.SSLSocketFactory f =
                        (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                sock = f.createSocket(sock, host, port, true);
                in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                out = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
                ehlo(in, out);
            }
            if (!user.isEmpty()) {
                send(out, "AUTH LOGIN");
                expect(in, 334);
                send(out, Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)));
                expect(in, 334);
                send(out, Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)));
                expect(in, 235);
            }
            send(out, "MAIL FROM:<" + from + ">");
            expect(in, 250);
            send(out, "RCPT TO:<" + to + ">");
            expect(in, 250);
            send(out, "DATA");
            expect(in, 354);
            String msg = "From: " + mimeEncode(fromName) + " <" + from + ">\r\n"
                    + "To: <" + to + ">\r\n"
                    + "Subject: " + mimeEncode(subject) + "\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n"
                    + "Date: " + new java.util.Date().toGMTString() + "\r\n\r\n"
                    + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                    + "\r\n.\r\n";
            out.write(msg);
            out.flush();
            expect(in, 250);
            send(out, "QUIT");
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void ehlo(java.io.BufferedReader in, java.io.BufferedWriter out) throws Exception {
        send(out, "EHLO xiaocan");
        int code = readCode(in);
        if (code != 250) throw new IllegalStateException("EHLO failed " + code);
    }

    private static void send(java.io.BufferedWriter out, String line) throws Exception {
        out.write(line + "\r\n");
        out.flush();
    }

    private static void expect(java.io.BufferedReader in, int code) throws Exception {
        int got = readCode(in);
        if (got != code) throw new IllegalStateException("SMTP expect " + code + " got " + got);
    }

    /** 读取多行响应，返回最后一行状态码。 */
    private static int readCode(java.io.BufferedReader in) throws Exception {
        int code = -1;
        for (int i = 0; i < 20; i++) {
            String line = in.readLine();
            if (line == null) throw new IllegalStateException("SMTP connection closed");
            if (line.length() >= 3) {
                try {
                    code = Integer.parseInt(line.substring(0, 3));
                } catch (Throwable ignored) {}
            }
            if (line.length() < 4 || line.charAt(3) != '-') break;
        }
        return code;
    }

    /** RFC2047 UTF-8 主题/显示名编码。 */
    private static String mimeEncode(String s) {
        try {
            return "=?UTF-8?B?" + Base64.getEncoder()
                    .encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
        } catch (Throwable t) {
            return s;
        }
    }

    /** 安全日志：Notifier 同时运行在宿主与模块进程，不能依赖 MainHook
     *（其依赖的 libxposed.api 只在宿主进程可用，模块内会 NoClassDefFoundError）。 */
    private static void log(String msg) {
        try {
            android.util.Log.i("XiaoCanPurify", msg);
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
