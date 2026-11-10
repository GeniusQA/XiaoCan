package com.genius.xiaocanpurify;

/** WGS84 / GCJ-02 / BD-09 坐标系互转。
 *  天地图、OSM(Photon) 吃 WGS84；高德吃 GCJ-02；百度吃 BD-09。
 *  模块缓存坐标是腾讯 SDK 的 GCJ-02，对外统一以 WGS84 口径流转，再按需转换。 */
public final class CoordConverter {
    private static final double PI = Math.PI;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private CoordConverter() {}

    private static boolean outOfChina(double lat, double lon) {
        return (lon < 72.004 || lon > 137.8347) || (lat < 0.8293 || lat > 55.8271);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** 返回 {lat, lon}（GCJ-02）。 */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    public static double[] gcj02ToWgs84(double lat, double lon) {
        double[] g = wgs84ToGcj02(lat, lon);
        return new double[]{2 * lat - g[0], 2 * lon - g[1]};
    }

    /** 返回 {lat, lon}（BD-09）。 */
    public static double[] gcj02ToBd09(double lat, double lon) {
        double x = lon, y = lat;
        double z = Math.sqrt(x * x + y * y) + 0.00002 * Math.sin(y * Math.PI);
        double theta = Math.atan2(y, x) + 0.000003 * Math.cos(x * Math.PI);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    public static double[] bd09ToGcj02(double lat, double lon) {
        double x = lon - 0.0065, y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    public static double[] wgs84ToBd09(double lat, double lon) {
        double[] g = wgs84ToGcj02(lat, lon);
        return gcj02ToBd09(g[0], g[1]);
    }

    public static double[] bd09ToWgs84(double lat, double lon) {
        double[] g = bd09ToGcj02(lat, lon);
        return gcj02ToWgs84(g[0], g[1]);
    }
}
