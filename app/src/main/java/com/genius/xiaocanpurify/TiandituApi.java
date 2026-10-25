package com.genius.xiaocanpurify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 天地图 Web API（地名搜索 V2.0 + 逆地理编码），纯模块侧 HTTP，无需宿主。
 *  密钥默认复用参考项目公开 key，可在偏好 tianditu_key 覆盖。 */
public final class TiandituApi {
    public static final String DEFAULT_KEY = "109d48B4f99e3c0472ab15fa38fe2ac";
    private static final String BASE = "https://api.tianditu.gov.cn";

    /** 一条搜索结果：名称 + 详细地址 + 经纬度。 */
    public static final class Place {
        public final String name;
        public final String addr;
        public final double lon;
        public final double lat;

        public Place(String name, String addr, double lon, double lat) {
            this.name = name;
            this.addr = addr == null ? "" : addr;
            this.lon = lon;
            this.lat = lat;
        }
    }

    private TiandituApi() {}

    public static String key(Context ctx) {
        try {
            return ctx.getSharedPreferences(PurifyPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("tianditu_key", DEFAULT_KEY);
        } catch (Throwable t) {
            return DEFAULT_KEY;
        }
    }
    /** 地名搜索 V2.0（queryType=1 普通搜索），返回候选地点列表。 */
    public static List<Place> search(Context ctx, String keyword) throws Exception {
        List<Place> out = new ArrayList<>();
        String post = "{\"keyWord\":\"" + keyword + "\",\"level\":12,"
                + "\"mapBound\":\"73.0,20.0,135.0,54.0\",\"queryType\":\"1\","
                + "\"start\":0,\"count\":10}";
        String url = BASE + "/v2/search?postStr=" + URLEncoder.encode(post, "UTF-8")
                + "&type=query&tk=" + URLEncoder.encode(key(ctx), "UTF-8");
        String text = httpGet(url);
        JSONObject res = new JSONObject(text);
        JSONArray pois = res.optJSONArray("pois");
        if (pois != null) {
            for (int i = 0; i < pois.length() && i < 10; i++) {
                JSONObject po = pois.optJSONObject(i);
                if (po == null) continue;
                String name = po.optString("name", "地点");
                String addr = po.optString("address", "");
                String lonlat = po.optString("lonlat", "");
                String[] parts = lonlat.split(",");
                if (parts.length < 2) continue;
                try {
                    out.add(new Place(name, addr,
                            Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim())));
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** 逆地理编码：坐标 → 地址描述；失败返回 null。 */
    public static String reverseGeocode(Context ctx, double lon, double lat) {
        try {
            String post = "{\"lon\":" + lon + ",\"lat\":" + lat + ",\"ver\":1}";
            String url = BASE + "/geocoder?postStr=" + URLEncoder.encode(post, "UTF-8")
                    + "&type=geocode&tk=" + URLEncoder.encode(key(ctx), "UTF-8");
            JSONObject res = new JSONObject(httpGet(url));
            JSONObject result = res.optJSONObject("result");
            if (result == null) return null;
            String addr = result.optString("formatted_address", "");
            return addr.isEmpty() ? null : addr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) != -1) bos.write(buf, 0, n);
            if (is != null) is.close();
            String text = bos.toString("UTF-8");
            if (code != 200 || text.trim().isEmpty()) {
                throw new IllegalStateException("天地图 HTTP " + code);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }
}
