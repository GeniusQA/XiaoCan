package com.genius.xiaocanpurify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 多地图源编排层：按优先级串联反查/搜索，并做坐标系转换。
 *  优先级：高德 > 百度 > OSM(Photon) > 天地图兜底。 */
public final class GeoResolver {

    /** 一条结果：名称 + 地址 + 经纬度 + 来源标识。 */
    public static final class GeoPlace {
        public final String name;
        public final String addr;
        public final double lon;
        public final double lat;
        public final String source;   // 来源 id
        public final String badge;    // 短标：高德 / 百度 / OSM / 天地图

        public GeoPlace(String name, String addr, double lon, double lat, String source, String badge) {
            this.name = name;
            this.addr = addr == null ? "" : addr;
            this.lon = lon;
            this.lat = lat;
            this.source = source;
            this.badge = badge;
        }

        /** 列表展示文案：名称 · 来源。 */
        public String display() {
            return name + " · " + badge;
        }
    }

    /** 一个地图源（反查 + 搜索）。 */
    public interface GeoSource {
        String id();
        String name();
        String badge();
        boolean requiresKey();
        boolean isEnabled();
        String getKey();
        String reverse(Context ctx, double lonWgs, double latWgs);
        List<GeoPlace> search(Context ctx, String keyword, double lonWgs, double latWgs);
    }

    private GeoResolver() {}

    /** 优先级：高德 > 百度。 */
    public static List<GeoSource> sources() {
        List<GeoSource> all = new ArrayList<>();
        all.add(new AmapSource());
        all.add(new BaiduSource());
        return all;
    }

    /** 按优先级反查，返回首个成功结果（含来源）。 */
    public static GeoPlace resolveReverse(Context ctx, double lonWgs, double latWgs) {
        for (GeoSource s : sources()) {
            if (!s.isEnabled()) continue;
            try {
                String addr = s.reverse(ctx, lonWgs, latWgs);
                if (addr != null && !addr.isEmpty()) {
                    return new GeoPlace(addr, addr, lonWgs, latWgs, s.id(), s.badge());
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 按优先级搜索，只返回首个有结果来源的候选（高德 > 百度 > OSM > 天地图）。
     * 与 resolveReverse 一致：多源同时开启时默认只匹配高德；仅当高德无结果
     * 或异常时才依次降级，不把多个来源的结果混在一起（对齐参考项目）。
     */
    public static List<GeoPlace> searchPlaces(Context ctx, String keyword,
                                              double lonWgs, double latWgs) {
        List<GeoPlace> out = new ArrayList<>();
        for (GeoSource s : sources()) {
            if (!s.isEnabled()) continue;
            try {
                List<GeoPlace> r = s.search(ctx, keyword, lonWgs, latWgs);
                if (r == null || r.isEmpty()) continue;
                for (GeoPlace p : r) {
                    if (out.size() >= 30) break;
                    out.add(p);
                }
                return out;
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 连通性测试：返回 null 表示成功，否则返回失败原因（便于设置页显示）。 */
    public static String testSource(Context ctx, GeoSource s) {
        String id = s.id();
        String key = null;
        String keyPref = "amap".equals(id) ? PurifyPrefs.AMAP_KEY
                : "baidu".equals(id) ? PurifyPrefs.BAIDU_KEY : null;
        if (keyPref != null) {
            key = s.getKey();
            if (isEmpty(key)) return "未配置密钥";
        }
        try {
            if ("amap".equals(id)) {
                double[] g = CoordConverter.wgs84ToGcj02(39.90923, 116.397428);
                String url = "https://restapi.amap.com/v3/geocode/regeo?location="
                        + g[1] + "," + g[0] + "&radius=1000&extensions=all&key=" + key;
                JSONObject res = new JSONObject(httpGet(url));
                if ("1".equals(res.optString("status"))) return null;
                return "高德 " + res.optString("info") + "(" + res.optString("infocode") + ")";
            }
            if ("baidu".equals(id)) {
                double[] b = CoordConverter.wgs84ToBd09(39.90923, 116.397428);
                String url = "https://api.map.baidu.com/reverse_geocoding/v3/?location="
                        + b[0] + "," + b[1] + "&output=json&ak=" + key;
                JSONObject res = new JSONObject(httpGet(url));
                if (res.optInt("status", -1) == 0) return null;
                return "百度 " + res.optString("message") + "(status=" + res.optInt("status") + ")";
            }
            String r = s.reverse(ctx, 116.397428, 39.90923);
            return (r != null && !r.isEmpty()) ? null : "接口无数据";
        } catch (Throwable t) {
            return "异常：" + t.getMessage();
        }
    }

    public static GeoSource findSource(String id) {
        for (GeoSource s : sources()) if (s.id().equals(id)) return s;
        return null;
    }

    // ---------------------------------------------------------------- HTTP 工具
    static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json");
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
                throw new IllegalStateException("HTTP " + code);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- 高德（GCJ-02，需 Key）
    public static final class AmapSource implements GeoSource {
        public String id() { return "amap"; }
        public String name() { return "高德地图"; }
        public String badge() { return "高德"; }
        public boolean requiresKey() { return true; }
        public boolean isEnabled() {
            return PurifyPrefs.isOn(PurifyPrefs.MAP_SRC_AMAP)
                    && !isEmpty(PurifyPrefs.getString(PurifyPrefs.AMAP_KEY, ""));
        }
        public String getKey() { return PurifyPrefs.getString(PurifyPrefs.AMAP_KEY, ""); }
        public String reverse(Context ctx, double lon, double lat) {
            double[] g = CoordConverter.wgs84ToGcj02(lat, lon);
            try {
                String url = "https://restapi.amap.com/v3/geocode/regeo?location="
                        + g[1] + "," + g[0] + "&radius=1000&extensions=all&key=" + getKey();
                JSONObject re = new JSONObject(httpGet(url)).optJSONObject("regeocode");
                if (re == null) return null;
                return re.optString("formatted_address", "");
            } catch (Throwable t) { return null; }
        }
        public List<GeoPlace> search(Context ctx, String keyword, double lon, double lat) {
            List<GeoPlace> out = new ArrayList<>();
            try {
                double[] g = CoordConverter.wgs84ToGcj02(lat, lon);
                String url = "https://restapi.amap.com/v3/assistant/inputtips?keywords="
                        + URLEncoder.encode(keyword, "UTF-8") + "&location=" + g[1] + "," + g[0]
                        + "&citylimit=false&key=" + getKey();
                JSONArray tips = new JSONObject(httpGet(url)).optJSONArray("tips");
                if (tips != null) {
                    for (int i = 0; i < tips.length() && out.size() < 10; i++) {
                        JSONObject t = tips.optJSONObject(i);
                        if (t == null) continue;
                        double[] ll = parseLonLat(t.optString("location", ""));
                        // 高德 location 是 GCJ-02，转回 WGS84 存，避免点击回填后二次偏移
                        double[] w = CoordConverter.gcj02ToWgs84(ll[1], ll[0]);
                        out.add(new GeoPlace(t.optString("name", "地点"),
                                t.optString("district", ""), w[1], w[0], id(), badge()));
                    }
                }
            } catch (Throwable ignored) {}
            return out;
        }
    }

    // ---------------------------------------------------------------- 百度（BD-09，需 Key）
    public static final class BaiduSource implements GeoSource {
        public String id() { return "baidu"; }
        public String name() { return "百度地图"; }
        public String badge() { return "百度"; }
        public boolean requiresKey() { return true; }
        public boolean isEnabled() {
            return PurifyPrefs.isOn(PurifyPrefs.MAP_SRC_BAIDU)
                    && !isEmpty(PurifyPrefs.getString(PurifyPrefs.BAIDU_KEY, ""));
        }
        public String getKey() { return PurifyPrefs.getString(PurifyPrefs.BAIDU_KEY, ""); }
        public String reverse(Context ctx, double lon, double lat) {
            double[] b = CoordConverter.wgs84ToBd09(lat, lon);
            try {
                String url = "https://api.map.baidu.com/reverse_geocoding/v3/?location="
                        + b[0] + "," + b[1] + "&output=json&ak=" + getKey();
                JSONObject result = new JSONObject(httpGet(url)).optJSONObject("result");
                if (result == null) return null;
                return result.optString("formatted_address", "");
            } catch (Throwable t) { return null; }
        }
        public List<GeoPlace> search(Context ctx, String keyword, double lon, double lat) {
            List<GeoPlace> out = new ArrayList<>();
            try {
                double[] b = CoordConverter.wgs84ToBd09(lat, lon);
                String url = "https://api.map.baidu.com/place/v2/search?query="
                        + URLEncoder.encode(keyword, "UTF-8") + "&location=" + b[0] + "," + b[1]
                        + "&radius=3000&output=json&ak=" + getKey();
                JSONObject res = new JSONObject(httpGet(url));
                JSONArray rs = res.optJSONObject("result").optJSONArray("results");
                if (rs != null) {
                    for (int i = 0; i < rs.length() && out.size() < 10; i++) {
                        JSONObject r = rs.optJSONObject(i);
                        if (r == null) continue;
                        JSONObject loc = r.optJSONObject("location");
                        double blat = 0, blon = 0;
                        if (loc != null) { blat = loc.optDouble("lat", 0); blon = loc.optDouble("lng", 0); }
                        double[] w = CoordConverter.bd09ToWgs84(blat, blon);
                        out.add(new GeoPlace(r.optString("name", "地点"),
                                r.optString("address", ""), w[1], w[0], id(), badge()));
                    }
                }
            } catch (Throwable ignored) {}
            return out;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 解析 "lon,lat" 字符串，失败返回 {0,0}。 */
    private static double[] parseLonLat(String s) {
        if (s == null) return new double[]{0, 0};
        String[] p = s.split(",");
        if (p.length < 2) return new double[]{0, 0};
        try {
            return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
        } catch (Throwable t) {
            return new double[]{0, 0};
        }
    }
}
