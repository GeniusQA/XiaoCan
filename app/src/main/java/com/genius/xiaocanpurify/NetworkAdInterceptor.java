package com.genius.xiaocanpurify;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

public final class NetworkAdInterceptor {
    private static final Map<Object, Boolean> INJECTED_BUILDERS = new WeakHashMap<>();

    /** RPC 探针窗口截止时间：红包任务点击后临时记录宿主全部请求，用于发现领奖上报接口。 */
    private static volatile long DISCOVERY_UNTIL = 0L;

    /** RPC 请求探针节流（1 秒一条）。 */
    private static volatile long lastRpcProbeMs;

    /** 抽奖浏览任务窗口：点击「立即浏览」后，窗口期内放行穿山甲内容流量
     *（任务落地页本身是穿山甲广告页，全拦截会导致页面空白）。 */
    private static volatile long taskPageUntilMs;

    /** 由 LotteryTaskAutomator 在任务点击时布防。 */
    public static void armTaskPageWindow(long ms) {
        taskPageUntilMs = android.os.SystemClock.uptimeMillis() + ms;
    }

    private static boolean isTaskPageWindowActive() {
        return android.os.SystemClock.uptimeMillis() < taskPageUntilMs;
    }

    private NetworkAdInterceptor() {}

    static void armDiscovery(long durationMs) {
        DISCOVERY_UNTIL = android.os.SystemClock.uptimeMillis() + durationMs;
    }

    static boolean discoveryActive() {
        return android.os.SystemClock.uptimeMillis() < DISCOVERY_UNTIL;
    }

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookOkHttp(xposed, classLoader);
        hookNativeHttpBridge(xposed, classLoader);
    }

    private static void hookOkHttp(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName("okhttp3.OkHttpClient$Builder", false, classLoader);
            Class<?> interceptorClass = Class.forName("okhttp3.Interceptor", false, classLoader);

            Method buildMethod = builderClass.getDeclaredMethod("build");
            Method addInterceptorMethod = builderClass.getMethod("addInterceptor", interceptorClass);

            xposed.hook(buildMethod).intercept(chain -> {
                Object builder = chain.getThisObject();
                if (builder != null && !isBuilderInjected(builder)) {
                    try {
                        Object interceptorProxy = Proxy.newProxyInstance(
                                classLoader,
                                new Class<?>[]{interceptorClass},
                                new AdInterceptorInvocationHandler(classLoader)
                        );
                        addInterceptorMethod.invoke(builder, interceptorProxy);
                        MainHook.log("Injected OkHttp ad filter interceptor");
                    } catch (Throwable t) {
                        MainHook.log("Failed to inject OkHttp interceptor: " + t);
                    }
                }
                return chain.proceed();
            });
            MainHook.log("Hooked OkHttpClient$Builder.build");
        } catch (Throwable t) {
            MainHook.log("Failed to hook OkHttp builder: " + t);
        }
    }

    private static void hookNativeHttpBridge(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> bridgeClass = Class.forName("com.realtech.xiaocan.flutter.NativeHttpBridge", false, cl);
            Method replySuccess = Class.forName("io.flutter.plugin.common.MethodChannel$Result", false, cl)
                    .getMethod("success", Object.class);
            final Class<?> resultInterface =
                    Class.forName("io.flutter.plugin.common.MethodChannel$Result", false, cl);
            for (Method m : bridgeClass.getDeclaredMethods()) {
                if ("createBridge$lambda$2".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object call = chain.getArg(0);
                        Object result = chain.getArg(1);
                        if (call != null && result != null) {
                            try {
                                Method argumentMethod = call.getClass().getMethod("argument", String.class);
                                String service = (String) argumentMethod.invoke(call, "service");
                                String serverName = (String) argumentMethod.invoke(call, "servername");
                                String path = (String) argumentMethod.invoke(call, "path");
                                if (path == null) {
                                    path = (String) argumentMethod.invoke(call, "url");
                                }
                                // 定位缓存契约：GPS 关闭时 App 应表现为处于缓存位置——
                                // 把请求载荷中同区域的定位坐标改写为缓存坐标
                                try {
                                    Object data = argumentMethod.invoke(call, "data");
                                    if (data != null && LocationCacheHook.rewritePayloadCoords(data)) {
                                        MainHook.log("RPC payload coords rewritten to cache");
                                    }
                                } catch (Throwable ignored) {}
                                String s = ((service == null ? "" : service) + " "
                                        + (serverName == null ? "" : serverName) + " "
                                        + (path == null ? "" : path)).toLowerCase(Locale.ROOT);
                                // 探针：节流记录 Flutter RPC 请求，定位页面按钮
                                // 点击后实际触发的接口（广告拦截误杀排查）
                                long nowMs = android.os.SystemClock.uptimeMillis();
                                if (nowMs - lastRpcProbeMs > 1000L) {
                                    lastRpcProbeMs = nowMs;
                                    MainHook.log("RPC[probe] " + s);
                                }
                                if (discoveryActive()) {
                                    MainHook.log("RPC[discover][flutter] " + s);
                                }
                                Object data = argumentMethod.invoke(call, "data");
                                if (isBlockedFlutterService(s) || WithdrawAdPolicy.isAdPlacement(s, data)) {
                                    MainHook.log(isDiscoveryName(s)
                                            ? "Blocked search discovery: " + s
                                            : "Blocked withdraw card request: " + s);
                                    replySuccess.invoke(result, isDiscoveryName(s) ? EMPTY_DISCOVERY : null);
                                    if (isWithdrawCardName(s)) FlutterPageGuard.onWithdrawResponse();
                                    return null;
                                }
                            } catch (Throwable ignored) {}
                        }
                        // 包装响应回调：捕获逆地理接口返回的 POI 地址，推送给模块设置页展示
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length > 1 && args[1] != null) {
                                args[1] = wrapFlutterResult(resultInterface, args[1]);
                                return chain.proceed(args);
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    MainHook.log("Hooked NativeHttpBridge.createBridge$lambda$2");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook NativeHttpBridge: " + t);
        }
    }

    /** 中文地址特征（XX市XX区 / XX市XX镇 等）。 */
    private static final java.util.regex.Pattern CN_ADDR = java.util.regex.Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,6}[市州][\\u4e00-\\u9fa5]{2,8}(?:[区县]|路|街|镇|街道)");

    /**
     * 仅匹配「用户定位逆地理」的地址字段（standard_address/address/formatted_addresses）。
     * 刻意排除 name/title——网关 RPC 的门店列表里也有 name+地址，会把商家地址误当用户位置。
     */
    private static final java.util.regex.Pattern JSON_ADDR_KEY = java.util.regex.Pattern.compile(
            "\"(?:standard_address|address|formatted_addresses)\"\\s*:\\s*\"([^\"]{4,80})\"");

    private static final java.util.regex.Pattern MAP_ADDR_KEY = java.util.regex.Pattern.compile(
            "(?:standard_address|address|formatted_addresses)=([^,}\\)]{4,80})");

    /** 把 MethodChannel.Result 包一层代理：success() 载荷含中文地址时提取并推送。 */
    private static Object wrapFlutterResult(Class<?> resultInterface, Object result) {
        try {
            return java.lang.reflect.Proxy.newProxyInstance(
                    resultInterface.getClassLoader(),
                    new Class<?>[]{resultInterface},
                    (proxy, method, margs) -> {
                        if ("success".equals(method.getName())
                                && margs != null && margs.length == 1 && margs[0] != null) {
                            try {
                                String s = String.valueOf(margs[0]);
                                if (s.length() <= 300000) {
                                    String addr = extractAddressText(s);
                                    if (addr != null) {
                                        LocationCacheHook.broadcastAddressToModule(addr);
                                        // 注入定位缓存快照：GPS 关闭回放时 App 直接展示该地址
                                        LocationCacheHook.injectAddressIntoSnapshot(addr);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        return method.invoke(result, margs);
                    });
        } catch (Throwable t) {
            return result;
        }
    }

    /** 响应载荷须含中文地址特征才算逆地理结果，再择优提取地址字段。 */
    private static String extractAddressText(String s) {
        // 网关 RPC 会把逆地理 JSON 作为字符串嵌入响应，且可能多层转义（\\\"address\\\"），
        // 把「任意数量反斜杠+引号」统一归一为裸引号后再匹配
        String body = s.replaceAll("\\\\+\"", "\"");
        // 仅处理「用户定位逆地理」响应：含 standard_address 标志（门店列表没有该字段）
        boolean gate = body.contains("standard_address");
        int fa = body.indexOf("formatted_addresses");
        MainHook.log("ADDR[dbg] gate=" + gate + " faIdx=" + fa
                + " bodyLen=" + body.length()
                + (fa >= 0 ? " snippet=" + body.substring(Math.max(0, fa - 40),
                        Math.min(body.length(), fa + 200)) : ""));
        if (!gate) return null;
        // 1) formatted_addresses.recommend（腾讯标准结构的推荐展示地址）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"formatted_addresses\"\\s*:\\s*\\{[^{}]*?\"recommend\"\\s*:\\s*\"([^\"]{4,80})\"")
                .matcher(body);
        if (m.find()) {
            MainHook.log("ADDR[extract] recommend=" + m.group(1));
            return m.group(1);
        }
        // 2) 紧邻 standard_address 之前的字段值——后端扁平结构里 App「当前地址」
        //    展示的字段（键名不固定），值形如「成华区水碾河社区西北(双桥路南六街南)」
        m = java.util.regex.Pattern
                .compile("\"([^\"]{4,80})\"\\s*,\\s*\"standard_address\"")
                .matcher(body);
        if (m.find()) {
            String v = m.group(1);
            boolean cnLike = v.matches(".*[\\u4e00-\\u9fa5].*")
                    && (v.contains("区") || v.contains("路") || v.contains("街") || v.contains("社区"));
            if (cnLike) return v;
        }
        // 3) recommend 字段兜底
        m = java.util.regex.Pattern.compile("\"recommend\"\\s*:\\s*\"([^\"]{4,80})\"").matcher(body);
        if (m.find()) return m.group(1);
        // 4) standard_address / address 兜底
        m = JSON_ADDR_KEY.matcher(body);
        while (m.find()) {
            String v = m.group(1);
            if (CN_ADDR.matcher(v).find()) return v;
        }
        return null;
    }

    private static final String EMPTY_DISCOVERY =
            "{\"code\":0,\"status\":{\"code\":0},\"msg\":\"ok\",\"data\":{\"list\":[],\"words\":[],\"keywords\":[],\"items\":[],\"search_words\":[],\"recommendations\":[]},\"list\":[],\"words\":[],\"keywords\":[]}";

    /** 请求体读取节流：同区域坐标的请求体只在间隔 >3s 时记录一次诊断。 */
    private static volatile long lastReqLogMs;

    /**
     * 把请求体中的定位坐标改写为缓存坐标（GPS 关闭时 App 用自身旧定位发起
     * 逆地理/附近查询，统一表现为缓存位置）。请求体经 okio 读取后按
     * 「同区域坐标对（<2km）」识别并整串替换，再用 Builder 重建 Request。
     *
     * @return 改写后的 Request；无需改写或改写失败返回 null（走原始请求）
     */
    private static Object rewriteRequestCoords(ClassLoader classLoader, Object request) {
        try {
            Object reqBody = request.getClass().getMethod("body").invoke(request);
            if (reqBody == null) {
                // body 为空（GET 等）：坐标可能放在 URL 查询参数里
                return rewriteRequestUrl(classLoader, request);
            }
            Class<?> bufferClass = classLoader.loadClass("okio.Buffer");
            Object buf = bufferClass.getDeclaredConstructor().newInstance();
            reqBody.getClass().getMethod("writeTo", classLoader.loadClass("okio.BufferedSink"))
                    .invoke(reqBody, buf);
            String body = (String) buf.getClass().getMethod("readUtf8").invoke(buf);
            if (body == null || body.isEmpty() || body.length() > 200000) {
                return rewriteRequestUrl(classLoader, request);
            }

            String rewritten = LocationCacheHook.rewriteCoordsInString(body);
            if (rewritten == null) {
                // 结构诊断：携带同区域坐标的请求体（节流），用于确认请求格式
                if (LocationCacheHook.hasVicinityCoordsInText(body)
                        && System.currentTimeMillis() - lastReqLogMs > 3000L) {
                    lastReqLogMs = System.currentTimeMillis();
                    MainHook.log("REQ[geoloc body] " + body.substring(0, Math.min(body.length(), 300)));
                }
                // 请求体没有可改写坐标时兜底改写 URL 查询参数
                return rewriteRequestUrl(classLoader, request);
            }
            MainHook.log("REQ coords rewritten in body");
            // 用 Builder 重建 Request：url/method/headers 保持，替换 body
            Class<?> builderClass = classLoader.loadClass("okhttp3.Request$Builder");
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            Object url = request.getClass().getMethod("url").invoke(request);
            builder.getClass().getMethod("url", classLoader.loadClass("okhttp3.HttpUrl"))
                    .invoke(builder, url);
            String method = (String) request.getClass().getMethod("method").invoke(request);
            Class<?> requestBodyClass = classLoader.loadClass("okhttp3.RequestBody");
            Object mediaType = reqBody.getClass().getMethod("contentType").invoke(reqBody);
            Object newBody = requestBodyClass.getMethod("create", classLoader.loadClass("okhttp3.MediaType"),
                    String.class, java.nio.charset.Charset.class)
                    .invoke(null, mediaType, rewritten, java.nio.charset.StandardCharsets.UTF_8);
            builder.getClass().getMethod("method", String.class, requestBodyClass)
                    .invoke(builder, method, newBody);
            Object headers = request.getClass().getMethod("headers").invoke(request);
            builder.getClass().getMethod("headers", classLoader.loadClass("okhttp3.Headers"))
                    .invoke(builder, headers);
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    /** URL 查询参数坐标改写兜底：GET 请求的 lat/lng 在 query string 里时 body 改写会漏掉。 */
    private static Object rewriteRequestUrl(ClassLoader classLoader, Object request) {
        try {
            Object url = request.getClass().getMethod("url").invoke(request);
            String u = url == null ? null : url.toString();
            if (u == null || u.isEmpty() || u.length() > 2000) return null;
            String rewritten = LocationCacheHook.rewriteCoordsInString(u);
            if (rewritten == null || rewritten.equals(u)) return null;
            Class<?> httpUrlClass = classLoader.loadClass("okhttp3.HttpUrl");
            Object newUrl = httpUrlClass.getMethod("parse", String.class).invoke(null, rewritten);
            if (newUrl == null) return null;
            Object builder = request.getClass().getMethod("newBuilder").invoke(request);
            builder.getClass().getMethod("url", httpUrlClass).invoke(builder, newUrl);
            MainHook.log("REQ coords rewritten in url");
            return classLoader.loadClass("okhttp3.Request$Builder")
                    .getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBlockedFlutterService(String s) {
        return isWithdrawCardName(s) || isDiscoveryName(s);
    }

    private static boolean isWithdrawCardName(String s) {
        // 注意：不要按方法名拦截 experimentservice.checkuseringroup（实验
        // 分组检查）与 silkwormwxgroupmangerservice.ismemberbysilkid（会员
        // 身份检查）——它们不是广告位，mock 成空响应会导致宿主不再渲染
        // 「我」页活动行（挑战赛/免费抽奖入口消失）。提现页广告拦截由
        // WithdrawAdPolicy 内容级检查与下方明确的 banner/popup 名称兜底
        return s != null && (s.contains("groupdetail")
                || s.contains("wechat_group")
                || s.contains("bawangcan_banner")
                || s.contains("candoutx_banner")
                || s.contains("user_withdraw_banner")
                || s.contains("user_withdraw_dialog_ad")
                || s.contains("withdrawal_success_popup")
                || s.contains("withdrawalpage_popup")
                || s.contains("withdraw_pop_up")
                || s.contains("withdraw_placement")
                || s.contains("withdraw_flow")
                || s.contains("withdrawupactivitydialog")
                || s.contains("listexchangeproduct"));
    }

    private static boolean isDiscoveryName(String s) {
        // Search-page recommendations only. History stays on the local repository.
        return s != null && (s.contains("listsearchword")
                || s.contains("searchinviteword")
                || s.contains("listsilkrecommendation")
                || s.contains("keywordcategory")
                || s.contains("searchdiscovery"));
    }

    private static synchronized boolean isBuilderInjected(Object builder) {
        if (INJECTED_BUILDERS.containsKey(builder)) {
            return true;
        }
        INJECTED_BUILDERS.put(builder, Boolean.TRUE);
        return false;
    }

    private static final class AdInterceptorInvocationHandler implements InvocationHandler {
        private final ClassLoader classLoader;
        /** 轮播列表响应上次 dump 时间（30 秒节流）。 */
        private static volatile long lastCarouselDumpMs;
        /** 入口配置响应上次 dump 时间（30 秒节流）。 */
        private static volatile long lastMenuCfgDumpMs;

    /** 位置参数请求抓包上次时间（5 秒节流，同 methodname 全局节流）。 */
    private static volatile long lastLocReqDumpMs;
    /** RPC 401 诊断节流。 */
    private static volatile long lastRpcDiagMs;
    private static volatile String lastRpcDiagTag = "";
    /** App /rpc 请求完整头已转储（一次性）。 */
    private static volatile boolean rpcHeaderDumped;
    /** 逆地理请求原文转储上次时间（5 秒节流）。 */
    private static volatile long lastAddrReqDumpMs;

        AdInterceptorInvocationHandler(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("intercept".equals(method.getName()) && args != null && args.length == 1) {
                try {
                    return handleIntercept(proxy, method, args);
                } catch (Throwable t) {
                    // 实测：chain.proceed 里的网络异常（如广告域连接失败
                    // EAGAIN）经反射包装成 InvocationTargetException，代理
                    // 接口未声明受检异常，最终变成 UndeclaredThrowableException
                    // 击穿 OkHttp 异步线程直接闪退。统一降级为 502 响应，
                    // 宿主按普通网络错误处理
                    MainHook.log("AdInterceptor degraded to 502: " + t);
                    try {
                        Object chain = args[0];
                        Object request = chain.getClass().getMethod("request").invoke(chain);
                        return createMockErrorResponse(request);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
            if ("equals".equals(method.getName())) {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(method.getName())) {
                return "XiaoCanPurifyAdInterceptor";
            }
            return null;
        }

        private Object handleIntercept(Object proxy, Method method, Object[] args) throws Throwable {
            {
                Object chain = args[0];
                Method requestMethod = chain.getClass().getMethod("request");
                Object request = requestMethod.invoke(chain);

                // 模块内部 RPC（X-Purify-Internal）直通：不改写/不拦截/不抓取，
                // 否则会被本拦截器二次处理造成死循环
                try {
                    Object marker = request.getClass()
                            .getMethod("header", String.class)
                            .invoke(request, "X-Purify-Internal");
                    if ("1".equals(marker)) {
                        return chain.getClass()
                                .getMethod("proceed", Object.class)
                                .invoke(chain, request);
                    }
                } catch (Throwable ignored) {}

                // 捕获宿主 OkHttpClient（模块 RPC 复用其 HTTPDNS/连接池）
                try {
                    if (XiaoCanRpc.capturedClient() == null) {
                        Object clientObj = null;
                        try {
                            Object callObj = chain.getClass().getMethod("call").invoke(chain);
                            clientObj = callObj.getClass().getMethod("client").invoke(callObj);
                        } catch (Throwable t1) {
                            MainHook.log("RPC: client capture failed " + t1);
                        }
                        if (clientObj != null) {
                            XiaoCanRpc.captureOkHttpClient(clientObj);
                        } else {
                            MainHook.log("RPC: client capture returned null");
                        }
                    }
                } catch (Throwable ignored) {}

                Method urlMethod = request.getClass().getMethod("url");
                Object httpUrl = urlMethod.invoke(request);
                String urlStr = httpUrl != null ? httpUrl.toString().toLowerCase(Locale.ROOT) : "";
                // 协议凭证抓取（供一键日常等模块内 RPC 调用使用）
                if (urlStr.contains("gw.xiaocantech.com/rpc")) {
                    try {
                        XiaoCanRpc.captureFromRequest(request);
                    } catch (Throwable ignored) {}
                    // 一次性完整头转储：与模块构造请求逐项对比，定位 401 差异
                    if (!rpcHeaderDumped) {
                        rpcHeaderDumped = true;
                        try {
                            Object hdrs = request.getClass().getMethod("headers").invoke(request);
                            int count = (Integer) hdrs.getClass().getMethod("size").invoke(hdrs);
                            Method nameM = hdrs.getClass().getMethod("name", int.class);
                            Method valM = hdrs.getClass().getMethod("value", int.class);
                            StringBuilder sb = new StringBuilder("RPCDIAG[app-hdrs]");
                            for (int i = 0; i < count; i++) {
                                String n = (String) nameM.invoke(hdrs, i);
                                String v = (String) valM.invoke(hdrs, i);
                                if (n.toLowerCase(Locale.ROOT).contains("sivir")
                                        || n.toLowerCase(Locale.ROOT).contains("ashe")) {
                                    v = v.length() > 14 ? v.substring(0, 14) + "..(" + v.length() + ")" : v;
                                }
                                sb.append(' ').append(n).append('=').append(v);
                            }
                            MainHook.log(sb.toString());
                        } catch (Throwable ignored) {}
                    }
                    // 401 排查诊断：记录 App 自身请求的关键头（节流）
                    try {
                        long nowMs = System.currentTimeMillis();
                        java.lang.reflect.Method hm = request.getClass()
                                .getMethod("header", String.class);
                        String mn = String.valueOf(hm.invoke(request, "methodname"));
                        String tag = String.valueOf(hm.invoke(request, "servername")) + "." + mn;
                        boolean diff = !tag.equals(lastRpcDiagTag);
                        if (diff || nowMs - lastRpcDiagMs > 5000L) {
                            lastRpcDiagTag = tag;
                            lastRpcDiagMs = nowMs;
                            String tk = String.valueOf(hm.invoke(request, "X-Sivir"));
                            MainHook.log("RPCDIAG[app] " + tag
                                    + " sivir=" + (tk.length() > 14
                                    ? tk.substring(0, 14) + "..(" + tk.length() + ")" : tk)
                                    + " teemo=" + hm.invoke(request, "X-Teemo"));
                        }
                    } catch (Throwable ignored) {}
                }
                if (discoveryActive()) {
                    String mn = "";
                    try {
                        Object m = request.getClass().getMethod("header", String.class)
                                .invoke(request, "methodname");
                        mn = m != null ? m.toString() : "";
                    } catch (Throwable ignored) {}
                    MainHook.log("RPC[discover][okhttp] " + mn + " " + urlStr);
                }

                // Check methodname header for blocked RPC calls
                try {
                    Method headerMethod = request.getClass().getMethod("header", String.class);
                    String methodName = (String) headerMethod.invoke(request, "methodname");
                    if (methodName != null) {
                        String mLower = methodName.toLowerCase(Locale.ROOT);
                        if (isWithdrawCardName(mLower)) {
                            MainHook.log("Blocked withdraw card RPC: " + mLower);
                            FlutterPageGuard.onWithdrawResponse();
                            return createMockJsonResponse(request, "{\"code\":0,\"data\":{},\"msg\":\"ok\"}");
                        }
                        if (isDiscoveryName(mLower)) {
                            MainHook.log("Blocked search discovery RPC");
                            return createMockJsonResponse(request, EMPTY_DISCOVERY);
                        }
                    }
                } catch (Throwable ignored) {}

                if (isDiscoveryName(urlStr)) {
                    MainHook.log("Blocked search discovery URL");
                    try {
                        return createMockJsonResponse(request, EMPTY_DISCOVERY);
                    } catch (Throwable t) {
                        MainHook.log("Mock response error: " + t);
                    }
                }

                if (isAdOrTrackingUrl(urlStr)) {
                    // 抽奖浏览任务的落地页本身是穿山甲广告页（TTFullAdActivity）：
                    // 窗口期内放行其内容流量，否则页面空白无内容（用户实测）
                    if (isTaskPageWindowActive() && isAdContentHost(urlStr)) {
                        MainHook.log("Allowed ad content for task page: " + urlStr);
                    } else {
                        MainHook.log("Blocked ad network request: " + urlStr);
                        try {
                            return createMockJsonResponse(request, "{\"code\":0,\"data\":{},\"msg\":\"ok\"}");
                        } catch (Throwable t) {
                            MainHook.log("Mock response error: " + t);
                        }
                    }
                }

                // 定位缓存：逆地理/附近查询请求的坐标改写为缓存坐标，
                // 使 GPS 关闭时 App 的地址与附近结果表现为缓存位置
                try {
                    Object newRequest = rewriteRequestCoords(classLoader, request);
                    if (newRequest != null) {
                        MainHook.log("REQ coords rewritten to cache");
                        Method proceedRewritten = chain.getClass().getMethod("proceed", newRequest.getClass());
                        Object respRewritten = proceedRewritten.invoke(chain, newRequest);
                        return filterPopupPayload(request, urlStr, respRewritten);
                    }
                } catch (Throwable ignored) {
                }

                Method proceedMethod = chain.getClass().getMethod("proceed", request.getClass());
                Object response = proceedMethod.invoke(chain, request);
                return filterPopupPayload(request, urlStr, response);
            }
        }

        /** 502 降级响应：宿主按普通网络错误处理，不中断异步线程。 */
        private Object createMockErrorResponse(Object request) throws Exception {
            Class<?> responseClass = Class.forName("okhttp3.Response", false, classLoader);
            Class<?> responseBuilderClass = Class.forName("okhttp3.Response$Builder", false, classLoader);
            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, classLoader);
            Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, classLoader);
            Class<?> protocolClass = Class.forName("okhttp3.Protocol", false, classLoader);

            Method parseMediaType = mediaTypeClass.getMethod("parse", String.class);
            Object plainType = parseMediaType.invoke(null, "text/plain; charset=utf-8");

            Method createBody = responseBodyClass.getMethod("create", mediaTypeClass, String.class);
            Object responseBody = createBody.invoke(null, plainType, "network unavailable");

            Object http11 = Enum.valueOf((Class<Enum>) protocolClass, "HTTP_1_1");

            Object builder = responseBuilderClass.getConstructor().newInstance();
            responseBuilderClass.getMethod("request", request.getClass()).invoke(builder, request);
            responseBuilderClass.getMethod("protocol", protocolClass).invoke(builder, http11);
            responseBuilderClass.getMethod("code", int.class).invoke(builder, 502);
            responseBuilderClass.getMethod("message", String.class).invoke(builder, "Bad Gateway");
            responseBuilderClass.getMethod("body", responseBodyClass).invoke(builder, responseBody);

            return responseBuilderClass.getMethod("build").invoke(builder);
        }

        /** 广告内容域名（渲染任务落地页所需）；埋点/展示类拦截域名不在此列。 */
        private static boolean isAdContentHost(String url) {
            return url.contains("pangolin")
                    || url.contains("openadsdk")
                    || url.contains("pangle")
                    || url.contains("csj")
                    || url.contains("ad.oceanengine")
                    || url.contains("douyin");
        }

        private static boolean isAdOrTrackingUrl(String url) {
            return url.contains("adprolink")
                    || url.contains("sigmob")
                    || url.contains("1rtb.net")
                    || url.contains("midongtech")
                    || url.contains("etoolads")
                    || url.contains("ad66.top")
                    || url.contains("openadsdk")
                    || url.contains("pangolin")
                    || url.contains("kwad")
                    || url.contains("mobads.baidu.com")
                    // 米盟广告域：实测连接失败 EAGAIN，连接风暴拖慢全局并
                    // 触发闪退——拦截后不再发起真实建连
                    || url.contains("mentamob")
                    || url.contains("buryingapi.xiaocantech.com")
                    || url.contains("sensorsdata.xiaocanapp.com")
                    || url.contains("user_withdraw_dialog_ad")
                    || url.contains("user_withdraw_banner")
                    || url.contains("withdrawal_success_popup")
                    || url.contains("withdrawalpage_popup")
                    || url.contains("withdraw_pop_up");
        }

        /**
         * 响应体内容扫描：营销弹窗配置由服务端原文下发，命中特征文案即视为弹窗数据。
         * 响应体较小（<=20KB，独立弹窗配置接口）直接返回空成功响应；
         * 大响应体（页面聚合配置）不能整体吞掉，只记日志留待按接口名精确拦截。
         */
        private Object filterPopupPayload(Object request, String url, Object response) {
            if (response == null) return null;
            try {
                Method peekMethod = response.getClass().getMethod("peekBody", long.class);
                Object peeked = peekMethod.invoke(response, 1048576L);
                String body = (String) peeked.getClass().getMethod("string").invoke(peeked);
                if (body == null || body.isEmpty()) {
                    return response;
                }
                if (discoveryActive() && body.length() > 2) {
                    MainHook.log("RESP[discover] " + url + " => "
                            + body.substring(0, Math.min(body.length(), 300)));
                }
                // 轮播列表（活动入口行数据源）响应结构诊断：30 秒节流，
                // 确认入口字段结构后实现数据层按开关剔除隐藏入口。
                // 方法名在 methodname header 里（URL 统一为 /rpc）
                if (body.length() > 2) {
                    String mn = "";
                    try {
                        Object m = request.getClass()
                                .getMethod("header", String.class)
                                .invoke(request, "methodname");
                        mn = m != null ? m.toString().toLowerCase(Locale.ROOT) : "";
                    } catch (Throwable ignored) {}
                    if (mn.contains("getcarousellist")
                            && System.currentTimeMillis() - lastCarouselDumpMs > 30000L) {
                        lastCarouselDumpMs = System.currentTimeMillis();
                        MainHook.log("Carousel resp: " + mn + " => "
                                + body.substring(0, Math.min(body.length(), 1200)));
                    }
                    // 活动入口行数据源诊断：CheckUserInGroup（实验分组下发
                    // challengeList 入口配置）/ ismemberbysilkid 响应 dump，
                    // 定位 challengeList 数据结构后实现数据层按开关剔除
                    if ((mn.contains("checkuseringroup")
                            || mn.contains("ismemberbysilkid"))
                            && System.currentTimeMillis() - lastMenuCfgDumpMs > 30000L) {
                        lastMenuCfgDumpMs = System.currentTimeMillis();
                        MainHook.log("MenuCfg resp: " + mn + " => "
                                + body.substring(0, Math.min(body.length(), 1500)));
                    }
                }
                // 地址来源探测：响应含中文地址特征时记录并推送给模块设置页展示
                if (body.length() <= 100000) {
                    // 抓包：dump 携带位置参数的请求（清数据后排查哪些接口需要位置
                    // 信息、参数从哪来）——URL/query、methodname、请求体（RPC 参数）
                    // 统一检查 lat/lng/city/merchant 等关键词，5 秒全局节流
                    try {
                        String mn2 = "";
                        try {
                            Object m2 = request.getClass().getMethod("header", String.class)
                                    .invoke(request, "methodname");
                            mn2 = m2 != null ? m2.toString() : "";
                        } catch (Throwable ignored) {}
                        String reqBody = "";
                        try {
                            Object b2 = request.getClass().getMethod("body").invoke(request);
                            if (b2 != null) {
                                Class<?> bufCls = Class.forName("okio.Buffer");
                                Object buf = bufCls.getDeclaredConstructor().newInstance();
                                b2.getClass().getMethod("writeTo",
                                        Class.forName("okio.BufferedSink")).invoke(b2, buf);
                                reqBody = (String) bufCls.getMethod("readUtf8").invoke(buf);
                            }
                        } catch (Throwable ignored) {}
                        String hay = (url + " " + mn2 + " " + reqBody).toLowerCase(Locale.ROOT);
                        boolean hasLoc = hay.contains("lat") || hay.contains("lng")
                                || hay.contains("location") || hay.contains("city")
                                || hay.contains("province") || hay.contains("address")
                                || hay.contains("merchant") || hay.contains("nearby")
                                || hay.contains("shop");
                        if (hasLoc && System.currentTimeMillis() - lastLocReqDumpMs > 5000L) {
                            lastLocReqDumpMs = System.currentTimeMillis();
                            // 上报类请求（UploadClientUserLocation 等）body 常为空，
                            // 坐标可能藏在 header——一并列出定位坐标改写才有抓手
                            String hdrDump = "";
                            try {
                                Object hs2 = request.getClass().getMethod("headers").invoke(request);
                                hdrDump = hs2 == null ? "" : hs2.toString();
                            } catch (Throwable ignored) {}
                            MainHook.log("LocReqDump: method=" + mn2 + " url=" + url
                                    + " reqBody=" + reqBody.substring(0,
                                            Math.min(320, reqBody.length()))
                                    + " headers=" + hdrDump.substring(0,
                                            Math.min(800, hdrDump.length())));
                        }
                    } catch (Throwable ignored) {}
                    java.util.regex.Matcher am = java.util.regex.Pattern
                            .compile("[\\u4e00-\\u9fa5]{2,6}[市州][\\u4e00-\\u9fa5]{2,8}[区县]")
                            .matcher(body);
                    if (am.find()) {
                        MainHook.log("ADDR[probe] " + url + " => "
                                + body.substring(Math.max(0, am.start() - 40),
                                        Math.min(body.length(), am.start() + 140)));
                        // 请求原文转储（5 秒节流）：确认逆地理请求的坐标载体
                        // （body/url/header），坐标改写未生效时定位请求格式
                        try {
                            if (System.currentTimeMillis() - lastAddrReqDumpMs > 5000L) {
                                lastAddrReqDumpMs = System.currentTimeMillis();
                                String mn3 = "";
                                try {
                                    Object m3 = request.getClass()
                                            .getMethod("header", String.class)
                                            .invoke(request, "methodname");
                                    mn3 = m3 != null ? m3.toString() : "";
                                } catch (Throwable ignored) {}
                                StringBuilder hdr = new StringBuilder();
                                try {
                                    Object hs = request.getClass().getMethod("headers")
                                            .invoke(request);
                                    hdr.append(hs == null ? "" : hs.toString());
                                } catch (Throwable ignored) {}
                                String rb = "";
                                try {
                                    Object b3 = request.getClass().getMethod("body").invoke(request);
                                    if (b3 != null) {
                                        Class<?> bufCls = Class.forName("okio.Buffer");
                                        Object buf = bufCls.getDeclaredConstructor().newInstance();
                                        b3.getClass().getMethod("writeTo",
                                                Class.forName("okio.BufferedSink")).invoke(b3, buf);
                                        rb = (String) bufCls.getMethod("readUtf8").invoke(buf);
                                    }
                                } catch (Throwable ignored) {}
                                MainHook.log("ADDR[req] method=" + mn3
                                        + " body=" + rb.substring(0, Math.min(500, rb.length()))
                                        + " headers=" + hdr.substring(0,
                                                Math.min(600, hdr.length())));
                            }
                        } catch (Throwable ignored) {}
                        // 结构诊断：逆地理响应里 standard_address 前的完整字段结构
                        int sa = body.indexOf("standard_address");
                        if (sa > 0) {
                            MainHook.log("ADDR[ctx] " + body.substring(Math.max(0, sa - 500),
                                    Math.min(body.length(), sa + 120)));
                            // 采集缓存坐标对应的区划码（响应 location 与缓存同区域才可信）
                            LocationCacheHook.maybeStoreAdcode(body);
                        }
                        String addr = extractAddressText(body);
                        if (addr != null) {
                            LocationCacheHook.broadcastAddressToModule(addr);
                            // 注入定位缓存快照：GPS 关闭回放时 App 直接展示该地址
                            LocationCacheHook.injectAddressIntoSnapshot(addr);
                        }
                    }
                }
                // 红包抽奖中奖数据包（「大额出行福利券 / 即将自动跳转」）到达即布防，
                // 拦下随后自动拉起的小程序跳转，并触发页面级弹窗清扫
                //（中奖弹窗与跳转确认框可能为页面内弹层，Dialog 拦截覆盖不到）
                if (PurifyPrefs.hide(PurifyPrefs.LOTTERY_POPUP)
                        && (body.contains("大额出行福利券") || body.contains("即将自动跳转"))) {
                    MainHook.log("Lottery prize payload detected, arming jump guard: " + url);
                    EnrollmentJumpGuard.armNow();
                    PopupBlocker.sweepMarketingPopups();
                }
                // 卡券/红包页：外卖红包广告横幅数据到达 → 触发页面清扫
                //（横幅随红包列表异步渲染，resume 时机的清扫会错过）
                if (body.contains("外卖红包") && body.contains("去播放")) {
                    PopupBlocker.sweepMarketingPopups();
                }
                // 诊断：dump 商家/附近列表类请求与响应片段——判断请求用的
                // 是注入的缓存坐标还是宿主自取的其它坐标（锦江区来源定位）
                String ul = url.toLowerCase(Locale.ROOT);
                if (ul.contains("merchant") || ul.contains("shop")
                        || ul.contains("store") || ul.contains("nearby")
                        || ul.contains("home")) {
                    MainHook.log("MerchantReq: " + url);
                    MainHook.log("MerchantResp: " + body.substring(0,
                            Math.min(300, body.length())));
                }
                // 诊断：dump 携带定位坐标参数的请求（选择地址页/逆地理/POI
                // 请求的位置来源核验——应与缓存坐标一致；坐标可能在 URL
                // 参数或 POST body 中）
                if (ul.contains("lat=") || ul.contains("lng=")
                        || ul.contains("latitude=") || ul.contains("longitude=")
                        || ul.contains("location=")
                        || (body.length() > 0 && body.length() < 8000
                                && (body.contains("\"lat\"") || body.contains("lat=")
                                        || body.contains("\"longitude\"")
                                        || body.contains("longitude=")))) {
                    MainHook.log("GeoReq: " + url + " body="
                            + body.substring(0, Math.min(400, body.length())));
                }
                if (!PopupMarkers.contains(body)) {
                    return response;
                }
                MainHook.log("Popup marker in response: " + url);
                if (body.length() <= 20000) {
                    MainHook.log("Mocked popup payload response: " + url);
                    return createMockJsonResponse(request,
                            "{\"code\":0,\"status\":{\"code\":0},\"msg\":\"ok\",\"data\":{}}");
                }
            } catch (Throwable ignored) {}
            return response;
        }

        private Object createMockJsonResponse(Object request, String json) throws Exception {
            Class<?> responseClass = Class.forName("okhttp3.Response", false, classLoader);
            Class<?> responseBuilderClass = Class.forName("okhttp3.Response$Builder", false, classLoader);
            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, classLoader);
            Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, classLoader);
            Class<?> protocolClass = Class.forName("okhttp3.Protocol", false, classLoader);

            Method parseMediaType = mediaTypeClass.getMethod("parse", String.class);
            Object jsonType = parseMediaType.invoke(null, "application/json; charset=utf-8");

            Method createBody = responseBodyClass.getMethod("create", mediaTypeClass, String.class);
            Object responseBody = createBody.invoke(null, jsonType, json);

            Object http11 = Enum.valueOf((Class<Enum>) protocolClass, "HTTP_1_1");

            Object builder = responseBuilderClass.getConstructor().newInstance();
            responseBuilderClass.getMethod("request", request.getClass()).invoke(builder, request);
            responseBuilderClass.getMethod("protocol", protocolClass).invoke(builder, http11);
            responseBuilderClass.getMethod("code", int.class).invoke(builder, 200);
            responseBuilderClass.getMethod("message", String.class).invoke(builder, "OK");
            responseBuilderClass.getMethod("body", responseBodyClass).invoke(builder, responseBody);

            return responseBuilderClass.getMethod("build").invoke(builder);
        }
    }
}
