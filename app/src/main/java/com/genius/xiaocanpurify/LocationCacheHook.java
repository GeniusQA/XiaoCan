package com.genius.xiaocanpurify;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

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
 * 宿主 com.realtech.location.LocationManager 底层使用腾讯定位 SDK
 * (com.tencent.map.geolocation.*)。这里在 SDK 层拦截：
 *  - 定位成功（error == ERROR_OK）时，把 TencentLocation 全量快照缓存到宿主私有 SharedPreferences；
 *  - 定位失败且已有缓存时，直接回调缓存的 TencentLocation（error=0），宿主自然走「使用腾讯定位」成功链路；
 *  - getLastKnownLocation 类方法返回 null 时也回放缓存。
 * 用户开 GPS 时仍走真实定位并自动刷新缓存。
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

    private LocationCacheHook() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        if (!PurifyPrefs.enabled(PurifyPrefs.LOCATION_CACHE)) {
            MainHook.log("LocationCache disabled by user, skip.");
            return;
        }
        hookTencentLocationManager(xposed, cl);
        hookFrameworkCapture(xposed);
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
            if ("onLocationChanged".equals(method.getName()) && args != null && args.length >= 2) {
                int error = (Integer) args[1];
                if (error == errorOk()) {
                    // 定位成功：刷新缓存
                    cacheSnapshot(args[0]);
                    return dispatchOriginal(method, args);
                }
                // 定位失败：有缓存则回放成功回调，屏蔽失败回调
                if (hasCache()) {
                    MainHook.log("LocationCache: location failed(error=" + error + "), replay cached location");
                    Object cached = buildCachedTencentLocation(args[0] != null ? args[0].getClass() : null);
                    if (cached != null) {
                        Object[] replay = args.clone();
                        replay[0] = cached;
                        replay[1] = 0;
                        if (replay.length >= 3) replay[2] = "purify_cache";
                        return dispatchOriginal(method, replay);
                    }
                }
                return dispatchOriginal(method, args);
            }
            return dispatchOriginal(method, args);
        }

        private Object dispatchOriginal(Method method, Object[] args) throws Throwable {
            // 直接用代理方法对象调用真实对象：对 equals/hashCode/toString 等 Object 方法也成立，
            // 否则接口未声明 equals 时 listenerClass.getMethod 会抛 NoSuchMethodException 导致宿主崩溃。
            return method.invoke(original, args);
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
                if (!m.getDeclaringClass().getName().contains("TencentLocation")
                        && !m.getDeclaringClass().getName().contains("android.location")) {
                    continue;
                }
                if (m.getParameterTypes().length != 0) continue;
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
        } catch (Throwable t) {
            MainHook.log("LocationCache: cacheSnapshot failed: " + t);
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
        try {
            if (returnType == Location.class) {
                Location loc = new Location("purify_cache");
                double[] ll = cachedLatLng();
                loc.setLatitude(ll[0]);
                loc.setLongitude(ll[1]);
                loc.setAccuracy(30f);
                loc.setTime(cachedTime());
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
        return Proxy.newProxyInstance(locationIface.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    String name = method.getName();
                    Class<?> rt = method.getReturnType();
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
                        Object[] args = chain.getArgs().toArray();
                        if (args[fIdx] != null) {
                            final Object orig = args[fIdx];
                            final java.util.concurrent.atomic.AtomicBoolean gotReal =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                            args[fIdx] = Proxy.newProxyInstance(
                                    listenerClass.getClassLoader(),
                                    new Class<?>[]{listenerClass},
                                    (proxy, method, mArgs) -> {
                                        if ("onLocationChanged".equals(method.getName())
                                                && mArgs != null && mArgs.length >= 1
                                                && mArgs[0] instanceof Location) {
                                            gotReal.set(true);
                                            Location loc = (Location) mArgs[0];
                                            recordFrameworkLocation(loc.getLatitude(), loc.getLongitude());
                                        }
                                        Method real = method;
                                        return real.invoke(orig, mArgs);
                                    });
                            // 注册后若始终无真实定位（如 GPS 关闭），延迟注入缓存位置，
                            // 让直接使用系统定位的页面（如「选择地址」）也能拿到结果
                            if (hasCache()) {
                                final Object cached = buildCachedResult(Location.class);
                                final Method onLoc = safeOnLocationChanged(listenerClass);
                                if (cached != null && onLoc != null) {
                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                            .postDelayed(() -> {
                                                if (gotReal.get()) return;
                                                try {
                                                    onLoc.invoke(orig, cached);
                                                    MainHook.log("LocationCache: injected cached location to framework listener");
                                                } catch (Throwable ignored) {
                                                }
                                            }, 600L);
                                }
                            }
                        }
                        return chain.proceed(args);
                    });
                } catch (Throwable ignored) {
                }
            }

            // getLastKnownLocation：返回 null 且有缓存时回放缓存，覆盖「选择地址」等直接读系统最后位置的页面
            for (Method m : lmClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if ("getLastKnownLocation".equals(m.getName()) && ps.length >= 1 && ps[0] == String.class) {
                    try {
                        xposed.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            if (result == null && hasCache()) {
                                MainHook.log("LocationCache: framework getLastKnownLocation null, replay cache");
                                return buildCachedResult(Location.class);
                            }
                            return result;
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }

            // isProviderEnabled：定位服务关闭但已有缓存时放行为 true，
            // 避免宿主在请求定位前就判定「定位失败，请先开启定位服务」
            for (Method m : lmClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if ("isProviderEnabled".equals(m.getName()) && ps.length >= 1 && ps[0] == String.class) {
                    try {
                        xposed.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            if (Boolean.FALSE.equals(result) && hasCache()) {
                                MainHook.log("LocationCache: framework isProviderEnabled=false -> true (cache exists)");
                                return Boolean.TRUE;
                            }
                            return result;
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

    private static void recordFrameworkLocation(double lat, double lng) {
        // 仅当腾讯层尚无缓存时，借用系统定位结果作为兜底数据源
        if (hasCache()) return;
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
