package com.genius.xiaocanpurify;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * 定位缓存：解决「不打开 GPS 就无法定位、拿不到商家活动数据」的问题。
 *
 * 策略（GPS 关闭时只回放缓存，不做网络定位降级）：
 *  - GPS 开启：真实定位照常透传；定位成功（error == ERROR_OK）时把 TencentLocation
 *    全量快照缓存到宿主私有 SharedPreferences，缓存始终跟随最新 GPS 位置；
 *  - GPS 关闭：一律不使用降级的网络（基站/WiFi）定位——
 *      有缓存时直接回调缓存的 TencentLocation（error=0），宿主自然走「使用腾讯定位」成功链路；
 *      无缓存时吞掉定位回调/返回 null，让宿主按定位失败处理（报错）。
 *  - getLastKnownLocation 类方法在 GPS 关闭时同样只回放缓存。
 * 用户点「重新定位」拿到的是固定缓存位置，不会再随机漂移。
 */
public final class LocationCacheHook {
    private static final String PREFS_NAME = "purify_loc_cache";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LNG = "lng";
    private static final String KEY_TIME = "time";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final String KEY_SNAPSHOT_DBL = "snapshot_dbl";

    private static volatile SharedPreferences prefs;
    private static volatile boolean cacheValid;

    /**
     * 重入保护：isGpsActuallyEnabled 内部会调用 lm.isProviderEnabled()，
     * 而该方法本身被本类 hook —— 不加保护会形成
     * intercept -> isGpsActuallyEnabled -> isProviderEnabled -> intercept ... 无限递归，
     * 最终 LSPosed HookBridge.invokeExactOriginalMethod 在 JNI 层拿到 null 导致 ART abort(SIGSEGV)。
     * 置位期间 hook 直接放行原始调用。
     */
    private static final ThreadLocal<Boolean> PROVIDER_CHECKING = new ThreadLocal<>();

    private LocationCacheHook() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        if (!PurifyPrefs.enabled(PurifyPrefs.LOCATION_CACHE)) {
            MainHook.log("LocationCache disabled by user, skip.");
            return;
        }
        hookTencentLocationManager(xposed, cl);
        hookFrameworkCapture(xposed);
        hookSearchAddressCapture(xposed, cl);
    }

    // ---------------------------------------------------------------- Tencent SDK layer

    private static void hookTencentLocationManager(XposedInterface xposed, ClassLoader cl) {
        Class<?> managerClass;
        Class<?> listenerClass;
        try {
            managerClass = Class.forName("com.tencent.map.geolocation.TencentLocationManager", false, cl);
            listenerClass = Class.forName("com.tencent.map.geolocation.TencentLocationListener", false, cl);
        } catch (Throwable t) {
            MainHook.log("LocationCache: Tencent SDK classes not found: " + t);
            return;
        }

        int wrapped = 0;
        for (Method m : managerClass.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            int listenerIdx = -1;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i] == listenerClass) {
                    listenerIdx = i;
                    break;
                }
            }
            String name = m.getName();
            if (listenerIdx >= 0) {
                final int idx = listenerIdx;
                try {
                    xposed.hook(m).intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        if (args[idx] != null) {
                            args[idx] = wrapListener(listenerClass, args[idx]);
                        }
                        return chain.proceed(args);
                    });
                    wrapped++;
                } catch (Throwable t) {
                    MainHook.log("LocationCache: hook " + name + " failed: " + t);
                }
            } else if (name.toLowerCase().contains("lastknownlocation") || name.toLowerCase().contains("lastlocation")) {
                try {
                    xposed.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result == null && hasCache()) {
                            MainHook.log("LocationCache: " + name + " returned null, replay cache");
                            return buildCachedResult(m.getReturnType());
                        }
                        return result;
                    });
                    wrapped++;
                } catch (Throwable t) {
                    MainHook.log("LocationCache: hook " + name + " failed: " + t);
                }
            }
        }
        MainHook.log("LocationCache: hooked " + wrapped + " TencentLocationManager methods");
    }

    private static Object wrapListener(Class<?> listenerClass, Object original) {
        try {
            return Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new ListenerInvocationHandler(original, listenerClass));
        } catch (Throwable t) {
            MainHook.log("LocationCache: wrap listener failed: " + t);
            return original;
        }
    }

    private static final class ListenerInvocationHandler implements InvocationHandler {
        private final Object original;
        private final Class<?> listenerClass;

        ListenerInvocationHandler(Object original, Class<?> listenerClass) {
            this.original = original;
            this.listenerClass = listenerClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if ("onLocationChanged".equals(method.getName()) && args != null && args.length >= 2) {
                    int error = (Integer) args[1];
                    if (isGpsActuallyEnabled()) {
                        if (error == errorOk()) {
                            // GPS 开启：定位成功，刷新缓存并透传真实位置
                            cacheSnapshot(args[0]);
                            return dispatchOriginal(method, args);
                        }
                        // GPS 开启但本次定位失败：有缓存则回放成功回调，屏蔽失败回调
                        if (hasCache()) {
                            MainHook.log("LocationCache: location failed(error=" + error + "), replay cached location");
                            return replayCache(method, args);
                        }
                        return dispatchOriginal(method, args);
                    }
                    // GPS 关闭：不使用降级网络定位，只回放缓存
                    if (hasCache()) {
                        MainHook.log("LocationCache: gps off, replay cached location (error=" + error + ")");
                        return replayCache(method, args);
                    }
                    // GPS 关闭且无缓存：吞掉成功回调（宿主按定位失败处理并报错），失败回调照常透传
                    if (error == errorOk()) {
                        MainHook.log("LocationCache: gps off, no cache, drop degraded network fix");
                        return null;
                    }
                    return dispatchOriginal(method, args);
                }
                return dispatchOriginal(method, args);
            } catch (Throwable t) {
                MainHook.log("LocationCache: Tencent listener invoke error: " + t);
                // 回调异常绝不能逃逸到宿主的 JNI/平台线程边界，否则进程会被 SIGSEGV(SI_USER) 强杀
                return dispatchOriginalSafe(method, args);
            }
        }

        /** 用缓存位置替换本次定位参数，以成功回调（error=0）派发给原始监听器。 */
        private Object replayCache(Method method, Object[] args) throws Throwable {
            Object cached = buildCachedTencentLocation(args[0] != null ? args[0].getClass() : null);
            if (cached != null) {
                Object[] replay = args.clone();
                replay[0] = cached;
                replay[1] = 0;
                // 保留原始第 3 参（provider/reason），不再注入自定义标记字符串——
                // 非法 provider 名（如 purify_cache）上报后会被风控识别为伪造定位
                return dispatchOriginal(method, replay);
            }
            return dispatchOriginal(method, args);
        }

        private Object dispatchOriginal(Method method, Object[] args) throws Throwable {
            // 直接用代理方法对象调用真实对象：对 equals/hashCode/toString 等 Object 方法也成立，
            // 否则接口未声明 equals 时 listenerClass.getMethod 会抛 NoSuchMethodException 导致宿主崩溃。
            return method.invoke(original, args);
        }

        private Object dispatchOriginalSafe(Method method, Object[] args) {
            try {
                return method.invoke(original, args);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static int errorOk() {
        // TencentLocation.ERROR_OK == 0；反射读取以防 SDK 常量变化
        try {
            Class<?> cls = Class.forName("com.tencent.map.geolocation.TencentLocation");
            return cls.getField("ERROR_OK").getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- Snapshot cache

    private static void cacheSnapshot(Object tencentLocation) {
        try {
            if (tencentLocation == null) return;
            double lat = readDouble(tencentLocation, "getLatitude");
            double lng = readDouble(tencentLocation, "getLongitude");
            if (lat == 0.0 && lng == 0.0) return;

            Map<String, String> strSnap = new LinkedHashMap<>();
            Map<String, Double> dblSnap = new LinkedHashMap<>();
            for (Method m : tencentLocation.getClass().getMethods()) {
                // 不能限定 declaringClass 含 "TencentLocation"：SDK 内部实现类多为混淆短名，
                // 会把 getAddress 等地址字段全部漏掉——离线回放时 App 拿不到地址文字，
                // 只能退去打 Web 逆地理接口兜底，返回与 SDK 自带地址不同的 POI 标签。
                // 因此对任意实现类的无参 getter 一律采集（跳过 Object 自身方法）。
                if (m.getDeclaringClass() == Object.class) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getName().startsWith("wait")
                        || m.getName().startsWith("notify")
                        || m.getName().startsWith("hashCode")
                        || m.getName().startsWith("getClass")) continue;
                try {
                    Object v = m.invoke(tencentLocation);
                    if (v == null) continue;
                    if (v instanceof String) {
                        strSnap.put(m.getName(), (String) v);
                    } else if (v instanceof Double) {
                        dblSnap.put(m.getName(), (Double) v);
                    }
                } catch (Throwable ignored) {
                }
            }
            strSnap.put("getLatitude", String.valueOf(lat));
            strSnap.put("getLongitude", String.valueOf(lng));
            dblSnap.put("getLatitude", lat);
            dblSnap.put("getLongitude", lng);

            SharedPreferences p = getPrefs();
            if (p == null) return;
            SharedPreferences.Editor e = p.edit()
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .putString(KEY_SNAPSHOT, joinMap(strSnap))
                    .putString(KEY_SNAPSHOT_DBL, joinMap2(dblSnap));
            e.putLong(KEY_LAT, Double.doubleToRawLongBits(lat));
            e.putLong(KEY_LNG, Double.doubleToRawLongBits(lng));
            e.apply();
            cacheValid = true;
            MainHook.log("LocationCache: cached location " + lat + "," + lng);
            mirrorToRemote();
        } catch (Throwable t) {
            MainHook.log("LocationCache: cacheSnapshot failed: " + t);
        }
    }

    /** 远程镜像 key：模块设置页据此展示缓存位置的地址与经纬度。 */
    private static final String REMOTE_LOC_INFO = "loc_cache_info";

    /**
     * 把缓存位置镜像写入 libxposed 远程偏好（宿主私有 SharedPreferences 设置页读不到）。
     * 格式：lat \u0001 lng \u0001 timeMillis [\u0001 address]；地址取回放时实际使用的
     * 快照 getAddress，与离线展示的 POI 标签保持一致。
     */
    private static void mirrorToRemote() {
        try {
            SharedPreferences p = getPrefs();
            if (p == null) return;
            double[] ll = cachedLatLng();
            if (ll[0] == 0.0 && ll[1] == 0.0) return;
            String address = readStrSnapshot().get("getAddress");
            StringBuilder sb = new StringBuilder()
                    .append(ll[0]).append('\u0001')
                    .append(ll[1]).append('\u0001')
                    .append(cachedTime());
            if (address != null && !address.isEmpty()) {
                sb.append('\u0001').append(address);
            }
            PurifyPrefs.putRemoteString(REMOTE_LOC_INFO, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static java.util.List<Class<?>> collectLocationInterfaces(Class<?> cls) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        collectInterfaces(cls, out);
        return out;
    }

    private static void collectInterfaces(Class<?> cls, java.util.List<Class<?>> out) {
        if (cls == null) return;
        if (cls.isInterface() && !out.contains(cls)) out.add(cls);
        for (Class<?> i : cls.getInterfaces()) collectInterfaces(i, out);
        collectInterfaces(cls.getSuperclass(), out);
    }

    private static double readDouble(Object obj, String getter) {
        try {
            Method m = obj.getClass().getMethod(getter);
            Object v = m.invoke(obj);
            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static Object buildCachedResult(Class<?> returnType) {
        return buildCachedResult(returnType, null);
    }

    /**
     * 构造回放用的 Location。坐标固定为缓存值（重新定位不漂移），
     * 但动态字段每次回放都刷新：时间戳取当前、elapsedRealtimeNanos 取当前、
     * 精度在 22~38m 间轻微浮动——避免「请求是新的、定位时间却冻结在缓存时刻、
     * 精度字节级一致」这类可被风控识别的假定位指纹。
     * provider 使用真实合法值（优先镜像宿主请求的 provider），不再使用自定义标记。
     */
    private static Object buildCachedResult(Class<?> returnType, String providerHint) {
        try {
            if (returnType == Location.class) {
                String provider = providerHint != null && !providerHint.isEmpty()
                        ? providerHint : "network";
                // 校验 provider 合法，防止宿主传入奇怪值
                if (!"gps".equals(provider) && !"network".equals(provider)
                        && !"fused".equals(provider) && !"passive".equals(provider)) {
                    provider = "network";
                }
                Location loc = new Location(provider);
                double[] ll = cachedLatLng();
                loc.setLatitude(ll[0]);
                loc.setLongitude(ll[1]);
                loc.setAccuracy(22f + (float) (Math.random() * 16f));
                loc.setTime(System.currentTimeMillis());
                loc.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                loc.setSpeed(0f);
                loc.setBearing(0f);
                return loc;
            }
            return buildCachedTencentLocation(returnType);
        } catch (Throwable t) {
            MainHook.log("LocationCache: buildCachedResult failed: " + t);
            return null;
        }
    }

    /**
     * 构造缓存版 TencentLocation。优先用宿主传入的实例做模板（克隆实例并反射改字段），
     * 失败则退化为动态代理实现接口。
     */
    private static Object buildCachedTencentLocation(Class<?> templateHint) {
        double[] ll = cachedLatLng();
        Map<String, String> strSnap = readStrSnapshot();
        Map<String, Double> dblSnap = readDblSnapshot();
        if (dblSnap.isEmpty()) {
            dblSnap.put("getLatitude", ll[0]);
            dblSnap.put("getLongitude", ll[1]);
        }
        final Map<String, String> sSnap = strSnap;
        final Map<String, Double> dSnap = dblSnap;

        Class<?> iface = null;
        try {
            iface = Class.forName("com.tencent.map.geolocation.TencentLocation");
        } catch (Throwable ignored) {
        }
        if (iface == null) return null;
        final Class<?> locationIface = iface;

        Class<?>[] interfaces = collectLocationInterfaces(locationIface).toArray(new Class<?>[0]);
        // 每次回放生成新的动态字段（时间戳取当前、精度轻微浮动），
        // 避免所有请求携带字节级一致、时间冻结的定位元数据被风控识别
        final long freshTime = System.currentTimeMillis();
        final long freshElapsed = android.os.SystemClock.elapsedRealtimeNanos();
        final float freshAccuracy = 22f + (float) (Math.random() * 16f);
        return Proxy.newProxyInstance(locationIface.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    String name = method.getName();
                    Class<?> rt = method.getReturnType();
                    // 动态字段优先：刷新为当前值，覆盖快照中的冻结值
                    if ("getTime".equals(name) && (rt == long.class || rt == Long.class)) {
                        return freshTime;
                    }
                    if ("getElapsedRealtimeNanos".equals(name) && (rt == long.class || rt == Long.class)) {
                        return freshElapsed;
                    }
                    if ("getAccuracy".equals(name) && (rt == float.class || rt == Float.class)) {
                        return freshAccuracy;
                    }
                    if (("getSpeed".equals(name) || "getBearing".equals(name))
                            && (rt == double.class || rt == Double.class || rt == float.class || rt == Float.class)) {
                        return rt == float.class || rt == Float.class ? 0.0f : 0.0d;
                    }
                    if (dSnap.containsKey(name) && (rt == double.class || rt == Double.class)) {
                        return dSnap.get(name);
                    }
                    if (sSnap.containsKey(name) && rt == String.class) {
                        return sSnap.get(name);
                    }
                    if (rt == double.class || rt == Double.class) return 0.0d;
                    if (rt == float.class || rt == Float.class) return 0.0f;
                    if (rt == int.class || rt == Integer.class) return 0;
                    if (rt == long.class || rt == Long.class) return 0L;
                    if (rt == boolean.class || rt == Boolean.class) return false;
                    if (rt == String.class) return "";
                    return null;
                });
    }

    // ---------------------------------------------------------------- Search address capture

    /**
     * 「选择地址」页搜索选点捕获：用户在 LocationSearchActivity 搜索并点击
     * 某个地址（搜索建议 / 历史搜索条目都会走 pickLocation），把该位置的
     * 经纬度 + 地址文案写入定位缓存并镜像到远程偏好。
     * 效果：不开 GPS 时回放的是用户搜索确认过的位置，而不是上次 GPS 定位点。
     */
    private static void hookSearchAddressCapture(XposedInterface xposed, ClassLoader cl) {
        String[] classNames = {"com.realtech.location.search.LocationSearchActivity"};
        for (String cn : classNames) {
            try {
                Class<?> cls = Class.forName(cn, false, cl);
                int hooked = 0;
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"pickLocation".equals(m.getName())) continue;
                    final boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    try {
                        xposed.hook(m).intercept(chain -> {
                            try {
                                SearchScan scan = new SearchScan();
                                for (Object arg : chain.getArgs()) {
                                    if (isStatic && arg instanceof android.content.Context) continue;
                                    scan.scan(arg, 0);
                                }
                                scan.commitIfPlausible();
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                        hooked++;
                    } catch (Throwable ignored) {
                    }
                }
                if (hooked > 0) {
                    MainHook.log("LocationCache: hooked " + cn + ".pickLocation x" + hooked);
                } else {
                    MainHook.log("LocationCache: pickLocation not found in " + cn);
                }
            } catch (Throwable t) {
                MainHook.log("LocationCache: search capture unavailable: " + cn);
            }
        }
    }

    /** pickLocation 参数扫描器：聚合一次调用里的数值/字符串，命中合理经纬度后写入缓存。 */
    private static final class SearchScan {
        private double latHint = Double.NaN;
        private double lngHint = Double.NaN;
        private double latRange = Double.NaN;
        private double lngRange = Double.NaN;
        private String addressHint;
        private String titleHint;
        private final StringBuilder plainStrings = new StringBuilder();

        void scan(Object obj, int depth) {
            if (obj == null || depth > 3) return;
            if (obj instanceof Number) {
                double v = ((Number) obj).doubleValue();
                assignCoordinate(v, null);
                return;
            }
            if (obj instanceof Character || obj instanceof Boolean) return;
            if (obj instanceof CharSequence) {
                collectString(null, obj.toString());
                return;
            }
            Class<?> c = obj.getClass();
            if (c.isPrimitive() || c.isArray() || c.getName().startsWith("android.")
                    || c.getName().startsWith("java.")) return;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v == null) continue;
                        String name = f.getName();
                        if (v instanceof Number) {
                            assignCoordinate(((Number) v).doubleValue(), name);
                        } else if (v instanceof CharSequence) {
                            collectString(name, v.toString());
                        } else if (!(v instanceof Boolean) && !(v instanceof Character)) {
                            scan(v, depth + 1);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void assignCoordinate(double v, String name) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return;
            String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
            if (n.contains("lat") && (v > -90 && v < 90)) {
                if (Double.isNaN(latHint) || Math.abs(v) > 1) latHint = v;
            } else if ((n.contains("lng") || n.contains("lon")) && (v > -180 && v < 180)) {
                if (Double.isNaN(lngHint) || Math.abs(v) > 1) lngHint = v;
            } else if (v > 17 && v < 55 && Double.isNaN(latRange)) {
                latRange = v; // 中国大陆纬度范围兜底
            } else if (v > 73 && v < 137 && Double.isNaN(lngRange)) {
                lngRange = v; // 中国大陆经度范围兜底
            }
        }

        private void collectString(String name, String value) {
            if (value == null || value.isEmpty()) return;
            String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
            if (n.contains("address")) {
                if (addressHint == null || value.length() > addressHint.length()) addressHint = value;
            } else if (n.contains("title") || n.contains("name")) {
                if (titleHint == null || value.length() > titleHint.length()) titleHint = value;
            } else if (value.length() >= 6 && value.length() <= 200
                    && !value.matches("[0-9.\\-Ee]+")) {
                if (plainStrings.length() < 4000) {
                    if (plainStrings.length() > 0) plainStrings.append('\u0001');
                    plainStrings.append(value);
                }
            }
        }

        /** 扫描结束后择优写入：命名命中 > 中国范围数值兜底；地址 > 标题 > 最长普通字符串。 */
        void commitIfPlausible() {
            double lat = !Double.isNaN(latHint) ? latHint : latRange;
            double lng = !Double.isNaN(lngHint) ? lngHint : lngRange;
            if (Double.isNaN(lat) || Double.isNaN(lng)) return;
            String address = addressHint != null ? addressHint
                    : (titleHint != null ? titleHint : null);
            if (address == null) {
                String best = null;
                for (String s : plainStrings.toString().split("\u0001")) {
                    if (best == null || s.length() > best.length()) best = s;
                }
                address = best;
            }
            cacheExternalLocation(lat, lng, address);
        }
    }

    /**
     * 把「搜索选点」得到的位置写入定位缓存（与 GPS 缓存同格式、互相同步镜像）。
     * 写入的快照携带地址文案，离线回放时 App 直接展示同一 POI 标签。
     */
    public static void cacheExternalLocation(double lat, double lng, String address) {
        if (Double.isNaN(lat) || Double.isInfinite(lat) || Double.isNaN(lng) || Double.isInfinite(lng)) return;
        if (lat == 0.0 && lng == 0.0) return;
        try {
            Map<String, String> str = new HashMap<>();
            if (address != null && !address.isEmpty()) str.put("getAddress", address);
            Map<String, Double> dbl = new HashMap<>();
            dbl.put("getLatitude", lat);
            dbl.put("getLongitude", lng);
            SharedPreferences p = getPrefs();
            if (p == null) return;
            p.edit()
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .putString(KEY_SNAPSHOT, joinMap(str))
                    .putString(KEY_SNAPSHOT_DBL, joinMap2(dbl))
                    .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_LNG, Double.doubleToRawLongBits(lng))
                    .apply();
            cacheValid = true;
            MainHook.log("LocationCache: cached searched location " + lat + "," + lng
                    + (address != null && !address.isEmpty() ? " addr=" + address : ""));
            mirrorToRemote();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Framework passive capture

    private static void hookFrameworkCapture(XposedInterface xposed) {
        try {
            Class<?> lmClass = Class.forName("android.location.LocationManager");
            Class<?> listenerClass = Class.forName("android.location.LocationListener");
            for (Method m : lmClass.getDeclaredMethods()) {
                if (!"requestLocationUpdates".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                int idx = -1;
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == listenerClass) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) continue;
                final int fIdx = idx;
                try {
                    xposed.hook(m).intercept(chain -> {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (args[fIdx] != null) {
                                final Object orig = args[fIdx];
                                final java.util.concurrent.atomic.AtomicBoolean handled =
                                        new java.util.concurrent.atomic.AtomicBoolean(false);
                                final Method onLoc = safeOnLocationChanged(listenerClass);
                                args[fIdx] = Proxy.newProxyInstance(
                                        listenerClass.getClassLoader(),
                                        new Class<?>[]{listenerClass},
                                        (proxy, method, mArgs) -> {
                                            try {
                                                if ("onLocationChanged".equals(method.getName())
                                                        && mArgs != null && mArgs.length >= 1
                                                        && mArgs[0] instanceof Location) {
                                                    Location loc = (Location) mArgs[0];
                                                    if (!isGpsActuallyEnabled()) {
                                                        // GPS 关闭：屏蔽降级网络定位，改为回放缓存；
                                                        // 无缓存则丢弃回调（宿主按定位失败处理）
                                                        if (handled.compareAndSet(false, true)
                                                                && hasCache() && onLoc != null) {
                                                            try {
                                                                onLoc.invoke(orig, buildCachedResult(
                                                                        Location.class, loc.getProvider()));
                                                                MainHook.log("LocationCache: gps off, injected cached location (framework)");
                                                            } catch (Throwable ignored) {
                                                            }
                                                        }
                                                        return null;
                                                    }
                                                    handled.set(true);
                                                    recordFrameworkLocation(loc.getLatitude(), loc.getLongitude());
                                                    return method.invoke(orig, mArgs);
                                                }
                                                return method.invoke(orig, mArgs);
                                            } catch (Throwable t) {
                                                MainHook.log("LocationCache: framework listener callback error: " + t);
                                                // 回调异常绝不能逃逸到宿主的 JNI/平台线程边界，
                                                // 否则 ART 会以 SIGSEGV(SI_USER) 强杀进程
                                                try {
                                                    return method.invoke(orig, mArgs);
                                                } catch (Throwable ignored) {
                                                    return null;
                                                }
                                            }
                                        });
                                // 注册后若始终无定位回调，延迟注入缓存位置兜底：
                                // GPS 关闭时网络定位已被屏蔽，尽快（500ms）回放缓存；
                                // GPS 开启时给真实定位 3 秒时间，超时未定到才用缓存兜底。
                                if (hasCache()) {
                                    // provider 镜像宿主注册时请求的 provider，保持元数据一致
                                    String providerHint = null;
                                    for (Object arg : args) {
                                        if (arg instanceof String) {
                                            providerHint = (String) arg;
                                            break;
                                        }
                                    }
                                    final Object cached = buildCachedResult(Location.class, providerHint);
                                    final long delay = isGpsActuallyEnabled() ? 3000L : 500L;
                                    if (cached != null && onLoc != null) {
                                        new android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed(() -> {
                                                    if (handled.get()) return;
                                                    try {
                                                        onLoc.invoke(orig, cached);
                                                        MainHook.log("LocationCache: injected cached location to framework listener");
                                                    } catch (Throwable ignored) {
                                                    }
                                                }, delay);
                                    }
                                }
                            }
                            return chain.proceed(args);
                        } catch (Throwable t) {
                            MainHook.log("LocationCache: requestLocationUpdates hook error: " + t);
                            return chain.proceed(chain.getArgs().toArray());
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            // getLastKnownLocation：GPS 关闭时不返回降级网络位置，只回放缓存；
            // GPS 开启时仅在返回 null 且有缓存时回放。覆盖「选择地址」等直接读系统最后位置的页面
            for (Method m : lmClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if ("getLastKnownLocation".equals(m.getName()) && ps.length >= 1 && ps[0] == String.class) {
                    try {
                        xposed.hook(m).intercept(chain -> {
                            try {
                                Object result = chain.proceed();
                                // provider 镜像宿主查询的 provider（gps/network/passive 等）
                                Object providerArg = chain.getArgs().toArray().length > 0
                                        ? chain.getArgs().toArray()[0] : null;
                                String providerHint = providerArg instanceof String ? (String) providerArg : null;
                                if (!isGpsActuallyEnabled()) {
                                    // GPS 关闭：只回放缓存；无缓存返回 null，宿主按定位失败处理
                                    if (hasCache()) {
                                        if (result == null) {
                                            MainHook.log("LocationCache: gps off, getLastKnownLocation null, replay cache");
                                        }
                                        return buildCachedResult(Location.class, providerHint);
                                    }
                                    MainHook.log("LocationCache: gps off, no cache, getLastKnownLocation -> null");
                                    return null;
                                }
                                if (result == null && hasCache()) {
                                    MainHook.log("LocationCache: framework getLastKnownLocation null, replay cache");
                                    return buildCachedResult(Location.class, providerHint);
                                }
                                return result;
                            } catch (Throwable t) {
                                MainHook.log("LocationCache: getLastKnownLocation hook error: " + t);
                                return chain.proceed();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }

            // isProviderEnabled：定位服务关闭但已有缓存时放行为 true，
            // 避免宿主在请求定位前就判定「定位失败，请先开启定位服务」
            // （随后由缓存回放链路提供位置）。无缓存时保持 false，宿主直接报错。
            for (Method m : lmClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if ("isProviderEnabled".equals(m.getName()) && ps.length >= 1 && ps[0] == String.class) {
                    try {
                        xposed.hook(m).intercept(chain -> {
                            try {
                                Object result = chain.proceed();
                                // 重入调用（由 isGpsActuallyEnabled 内部发起）：直接返回真实结果，防止无限递归
                                if (Boolean.TRUE.equals(PROVIDER_CHECKING.get())) {
                                    return result;
                                }
                                if (Boolean.FALSE.equals(result) && hasCache()) {
                                    MainHook.log("LocationCache: framework isProviderEnabled=false -> true (cache exists)");
                                    return Boolean.TRUE;
                                }
                                return result;
                            } catch (Throwable t) {
                                MainHook.log("LocationCache: isProviderEnabled hook error: " + t);
                                return chain.proceed();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            MainHook.log("LocationCache: framework capture hook failed: " + t);
        }
    }

    private static Method safeOnLocationChanged(Class<?> listenerClass) {
        try {
            return listenerClass.getMethod("onLocationChanged", Location.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 查询 GPS 定位源的真实可用状态（绕过本类对 isProviderEnabled 的伪造）。
     * GPS 关闭时模块只回放缓存、不使用降级网络定位；
     * 查询失败（无法取到 Context 等）时按 GPS 可用处理，保持真实定位透传。
     */
    private static boolean isGpsActuallyEnabled() {
        try {
            Context ctx = currentApplication();
            if (ctx == null) return true;
            Object lm = ctx.getSystemService(Context.LOCATION_SERVICE);
            if (!(lm instanceof android.location.LocationManager)) return true;
            android.location.LocationManager manager = (android.location.LocationManager) lm;
            PROVIDER_CHECKING.set(Boolean.TRUE);
            try {
                return manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            } finally {
                PROVIDER_CHECKING.remove();
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private static void recordFrameworkLocation(double lat, double lng) {
        // 仅在 GPS 开启时被调用（GPS 关闭的网络定位在调用前已被屏蔽），
        // 缓存始终跟随最新 GPS 真实位置
        if (lat == 0.0 && lng == 0.0) return;
        try {
            Map<String, Double> dbl = new HashMap<>();
            dbl.put("getLatitude", lat);
            dbl.put("getLongitude", lng);
            SharedPreferences p = getPrefs();
            if (p == null) return;
            p.edit()
                    .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_LNG, Double.doubleToRawLongBits(lng))
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .putString(KEY_SNAPSHOT_DBL, joinMap2(dbl))
                    .apply();
            cacheValid = true;
            MainHook.log("LocationCache: cached framework location " + lat + "," + lng);
            mirrorToRemote();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Prefs helpers

    private static SharedPreferences getPrefs() {
        SharedPreferences p = prefs;
        if (p != null) return p;
        synchronized (LocationCacheHook.class) {
            if (prefs == null) {
                try {
                    Context ctx = currentApplication();
                    if (ctx != null) {
                        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    }
                } catch (Throwable t) {
                    MainHook.log("LocationCache: getPrefs failed: " + t);
                }
            }
            return prefs;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasCache() {
        if (cacheValid) return true;
        SharedPreferences p = getPrefs();
        if (p == null) return false;
        double lat = Double.longBitsToDouble(p.getLong(KEY_LAT, 0));
        double lng = Double.longBitsToDouble(p.getLong(KEY_LNG, 0));
        cacheValid = !(lat == 0.0 && lng == 0.0);
        return cacheValid;
    }

    private static double[] cachedLatLng() {
        SharedPreferences p = getPrefs();
        if (p == null) return new double[]{0.0, 0.0};
        return new double[]{
                Double.longBitsToDouble(p.getLong(KEY_LAT, 0)),
                Double.longBitsToDouble(p.getLong(KEY_LNG, 0))
        };
    }

    private static long cachedTime() {
        SharedPreferences p = getPrefs();
        return p != null ? p.getLong(KEY_TIME, 0L) : 0L;
    }

    private static Map<String, String> readStrSnapshot() {
        Map<String, String> out = new HashMap<>();
        SharedPreferences p = getPrefs();
        if (p == null) return out;
        String raw = p.getString(KEY_SNAPSHOT, null);
        if (raw == null || raw.isEmpty()) return out;
        for (String kv : raw.split("\u0001")) {
            int i = kv.indexOf('\u0002');
            if (i > 0) out.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return out;
    }

    private static Map<String, Double> readDblSnapshot() {
        Map<String, Double> out = new HashMap<>();
        SharedPreferences p = getPrefs();
        if (p == null) return out;
        String raw = p.getString(KEY_SNAPSHOT_DBL, null);
        if (raw == null || raw.isEmpty()) return out;
        for (String kv : raw.split("\u0001")) {
            int i = kv.indexOf('\u0002');
            if (i > 0) {
                try {
                    out.put(kv.substring(0, i), Double.parseDouble(kv.substring(i + 1)));
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static String joinMap(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(e.getKey()).append('\u0002').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private static String joinMap2(Map<String, Double> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(e.getKey()).append('\u0002').append(e.getValue());
        }
        return sb.toString();
    }
}
