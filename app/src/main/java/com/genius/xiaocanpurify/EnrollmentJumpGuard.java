package com.genius.xiaocanpurify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

final class EnrollmentJumpGuard {
    private static final AutoJumpWindow WINDOW = new AutoJumpWindow();
    private static WeakReference<Activity> current = new WeakReference<>(null);

    private EnrollmentJumpGuard() {}

    /** 供弹窗拦截等外部场景布防：命中后 30 秒内拦截自动外跳/小程序拉起。 */
    static void armNow() {
        WINDOW.arm(SystemClock.uptimeMillis());
    }

    /** 任务场景布防（抽奖红包任务拦截外跳）：不受用户触摸影响，始终拦截。 */
    static void armTaskNow() {
        WINDOW.armTask(SystemClock.uptimeMillis());
    }

    static void install(XposedInterface xposed, ClassLoader cl) {
        hookSubmissionStart(xposed, cl);
        hookLaunchDialogs(xposed, cl);
        hookManualStoreRequest(xposed, cl);
        String[] observers = {
                "com.realtech.promotion.pages.detail.ext.PromotionDetailsCreateObserverKt$initEffect$1",
                "com.realtech.promotion.pages.detail.mt.ext.MtCreateObserverKt$initEffect$1",
                "com.realtech.promotion.pages.detail.elm.ext.ElmCreateObserverKt$initEffect$1",
                "com.realtech.promotion.pages.detail.dp.ext.DpPromotionDetailsCreateObserverKt$initEffect$1"
        };
        try {
            Method top = Class.forName("com.blankj.utilcode.util.ActivityUtils", false, cl).getMethod("getTopActivity");
            for (String name : observers) {
                try {
                    Class<?> observer = Class.forName(name, false, cl);
                    for (Method m : observer.getDeclaredMethods()) {
                        if (!"emit".equals(m.getName()) || m.isBridge() || m.getParameterCount() != 2) continue;
                        xposed.hook(m).intercept(chain -> {
                            Object effect = chain.getArg(0);
                            String event = effect == null ? "" : effect.getClass().getSimpleName();
                            if ("NavToOrderVoucher".equals(event) || "ShowNewUserFirstOrderSignupSuccessDialog".equals(event)) {
                                Activity activity = (Activity) top.invoke(null);
                                current = new WeakReference<>(activity);
                                WINDOW.arm(SystemClock.uptimeMillis());
                                MainHook.log("Enrollment auto-jump guard armed");
                            }
                            return chain.proceed();
                        });
                    }
                    MainHook.log("Hooked enrollment result: " + name);
                } catch (Throwable t) {
                    MainHook.log("Enrollment observer unavailable: " + name);
                }
            }
            xposed.hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
                current = new WeakReference<>((Activity) chain.getThisObject());
                return chain.proceed();
            });
            // 跟踪用户触摸：布防窗口内的手动操作（小程序进店、下单流程
            // 领红包按钮等）放行；自动外跳（弹窗倒计时）无触摸，照拦
            xposed.hook(Activity.class.getDeclaredMethod("dispatchTouchEvent",
                    android.view.MotionEvent.class)).intercept(chain -> {
                WINDOW.onTouch(SystemClock.uptimeMillis());
                return chain.proceed();
            });
            for (Method m : Instrumentation.class.getDeclaredMethods()) {
                if (!"execStartActivity".equals(m.getName())) continue;
                xposed.hook(m).intercept(chain -> {
                    Intent intent = null;
                    Activity owner = current.get();
                    for (Object arg : chain.getArgs()) {
                        if (arg instanceof Intent) intent = (Intent) arg;
                        if (arg instanceof Activity) owner = (Activity) arg;
                    }
                    // 探针：记录所有离开宿主包的跳转尝试（含目标与布防状态），
                    // 用于定位「下单流程」等页面按钮的跳转路径与拦截情况
                    if (intent != null) {
                        String pkg = intent.getComponent() != null
                                ? intent.getComponent().getPackageName()
                                : intent.getPackage();
                        if (pkg != null && !pkg.equals("com.realtech.xiaocan")) {
                            long now = SystemClock.uptimeMillis();
                            MainHook.log("JumpProbe: "
                                    + (owner != null ? owner.getClass().getSimpleName() : "?")
                                    + " -> " + pkg + " "
                                    + (intent.getComponent() != null
                                            ? intent.getComponent().getClassName()
                                            : intent.getAction())
                                    + " data=" + intent.getDataString()
                                    + " armed=" + WINDOW.isActive(now)
                                    + " manual=" + WINDOW.manualSinceArm(now));
                        }
                    }
                    // 抽奖中奖后跳微信小程序的兜底拦截（命中开关且布防窗口内
                    // 且用户无触摸）：此前为无条件拦截，会误拦「小程序进店」
                    // 等手动跳转，现改为与窗口一致——弹窗命中时已布防，
                    // 自动外跳发生在弹窗后数秒内，窗口足够覆盖
                    if (PurifyPrefs.hide(PurifyPrefs.LOTTERY_POPUP) && owner != null && intent != null) {
                        String cls = intent.getComponent() != null
                                ? intent.getComponent().getClassName() : "";
                        String pkg = intent.getComponent() != null
                                ? intent.getComponent().getPackageName()
                                : intent.getPackage();
                        boolean wechatMini = "com.tencent.mm".equals(pkg)
                                && (cls.contains("appbrand") || cls.contains("WXBizEntry"));
                        if (wechatMini) {
                            long now = SystemClock.uptimeMillis();
                            if (WINDOW.isActive(now) && !WINDOW.manualSinceArm(now)) {
                                MainHook.log("LotteryProbe startActivity -> WeChat mini-program: " + cls);
                                MainHook.log("Blocked lottery WeChat mini-program launch (LOTTERY_POPUP)");
                                return null;
                            }
                            MainHook.log("LotteryProbe wechat mini-program allowed"
                                    + (WINDOW.isActive(now) ? " (manual)" : " (window inactive)")
                                    + ": " + cls);
                        }
                    }
                    long now = SystemClock.uptimeMillis();
                    if (!WINDOW.isActive(now)) return chain.proceed();
                    // 布防后有过用户触摸：手动操作放行（报名成功瞬间的自动
                    // 外跳无触摸，仍被拦截）
                    if (WINDOW.manualSinceArm(now)) {
                        if (owner != null && intent != null && isExternal(owner, intent)) {
                            MainHook.log("Allow manual external launch after enrollment");
                        }
                        return chain.proceed();
                    }
                    if (owner != null && intent != null && isExternal(owner, intent)) {
                        MainHook.log("Blocked automatic external launch after enrollment");
                        return null;
                    }
                    return chain.proceed();
                });
            }
            Class<?> wx = Class.forName("com.tencent.mm.opensdk.openapi.WXApiImplV10", false, cl);
            for (Method m : wx.getMethods()) {
                if (!"sendReq".equals(m.getName()) || m.getParameterCount() != 1) continue;
                xposed.hook(m).intercept(chain -> {
                    Object request = chain.getArg(0);
                    if (request != null && request.getClass().getName().contains("WXLaunchMiniProgram")) {
                        MainHook.log("LotteryProbe sendReq WXLaunchMiniProgram " + request);
                        long now = SystemClock.uptimeMillis();
                        if (PurifyPrefs.hide(PurifyPrefs.LOTTERY_POPUP)
                                && WINDOW.isActive(now) && !WINDOW.manualSinceArm(now)) {
                            MainHook.log("Blocked lottery mini-app launch (LOTTERY_POPUP)");
                            return false;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            MainHook.log("Enrollment guard setup failed: " + t.getClass().getSimpleName());
        }
    }

    private static void hookSubmissionStart(XposedInterface xposed, ClassLoader cl) {
        String[] classes = {
                "com.realtech.promotion.pages.detail.PromotionDetailViewModel",
                "com.realtech.promotion.pages.detail.dp.DpPromotionDetailViewModel",
                "com.realtech.promotion.pages.detail.mt.vm.MtPromotionSubmitKt",
                "com.realtech.promotion.pages.detail.elm.vm.ElmPromotionSubmitKt"
        };
        for (String name : classes) {
            try {
                for (Method method : Class.forName(name, false, cl).getDeclaredMethods()) {
                    if (!"submitOrder".equals(method.getName())) continue;
                    xposed.hook(method).intercept(chain -> {
                        WINDOW.arm(SystemClock.uptimeMillis());
                        MainHook.log("Enrollment submission guard armed");
                        return chain.proceed();
                    });
                    MainHook.log("Hooked enrollment submission: " + name);
                }
            } catch (Throwable t) {
                MainHook.log("Enrollment submission hook unavailable: " + name);
            }
        }
    }

    private static void hookLaunchDialogs(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> launch = Class.forName("com.realtech.common.ui.util.LaunchUrlUtils", false, cl);
            for (Method method : launch.getDeclaredMethods()) {
                if (!"openUrl".equals(method.getName()) && !"openMini".equals(method.getName())) continue;
                xposed.hook(method).intercept(chain -> {
                    boolean storeTarget = "openMini".equals(method.getName())
                            || isStoreUrl(chain.getArg(0));
                    long now = SystemClock.uptimeMillis();
                    if (storeTarget && WINDOW.isActive(now) && !WINDOW.manualSinceArm(now)) {
                        MainHook.log("Blocked enrollment launch prompt: " + method.getName());
                        return Boolean.FALSE;
                    }
                    if (PurifyPrefs.hide(PurifyPrefs.LOTTERY_POPUP)) {
                        MainHook.log("LotteryProbe " + method.getName()
                                + " store=" + storeTarget + " arg=" + chain.getArg(0));
                    }
                    return chain.proceed();
                });
            }
            MainHook.log("Enrollment launch-dialog guard installed");
        } catch (Throwable t) {
            MainHook.log("Enrollment launch-dialog hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static boolean isStoreUrl(Object value) {
        if (!(value instanceof String)) return false;
        try {
            java.net.URI uri = java.net.URI.create((String) value);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
                case "imeituan": case "meituanwaimai": case "meituan":
                case "eleme": case "tbopen": case "taobao":
                case "openapp.jdmobile": case "dianping": return true;
                case "https": case "http":
                    String host = uri.getHost();
                    if (host == null) return false;
                    host = host.toLowerCase(java.util.Locale.ROOT);
                    for (String domain : new String[]{"meituan.com", "ele.me", "jd.com", "taobao.com", "dianping.com"})
                        if (host.equals(domain) || host.endsWith("." + domain)) return true;
                    return false;
                default: return false;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void hookManualStoreRequest(XposedInterface xposed, ClassLoader cl) {
        String[] classes = {
                "com.realtech.promotion.pages.detail.ext.PromotionDetailsDialogExtKt",
                "com.realtech.promotion.pages.detail.mt.ext.MtDetailDialogExtKt",
                "com.realtech.promotion.pages.detail.elm.ext.ElmDetailDialogExtKt"
        };
        for (String name : classes) {
            try {
                for (Method method : Class.forName(name, false, cl).getDeclaredMethods()) {
                    if (!"showPopToStoreWaySelect".equals(method.getName())) continue;
                    xposed.hook(method).intercept(chain -> {
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof Enum) {
                                String source = ((Enum<?>) arg).name();
                                if ("PROMOTION_DETAIL_HEADER".equals(source) || "PROMOTION_DETAIL_BOTTOM".equals(source)
                                        || "ORDER_LIST".equals(source)) WINDOW.onManualStoreRequest();
                            }
                        }
                        return chain.proceed();
                    });
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isExternal(Activity owner, Intent intent) {
        String target = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
        if (target == null) {
            ResolveInfo resolved = owner.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null || resolved.activityInfo == null) return false;
            target = resolved.activityInfo.packageName;
        }
        // Auth/payment targets are not store launches and must remain usable.
        // 微信仅用于小程序/优惠券拉起，布防窗口内同样视为自动外跳目标。
        return "com.sankuai.meituan".equals(target) || "com.sankuai.meituan.takeoutnew".equals(target)
                || "me.ele".equals(target) || "com.jingdong.app.mall".equals(target)
                || "com.taobao.taobao".equals(target) || "com.dianping.v1".equals(target)
                || "com.tencent.mm".equals(target);
    }
}
