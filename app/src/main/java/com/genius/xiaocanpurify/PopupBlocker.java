package com.genius.xiaocanpurify;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

public final class PopupBlocker {
    private static final Map<Object, Boolean> TAOBAO_INSTALL_PROMPTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PopupBlocker() {}

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        hookDialogUtils(xposed, classLoader);
        hookAppUpdateService(xposed, classLoader);
        hookMainViewModelPopups(xposed, classLoader);
        hookSpecificDialogClasses(xposed, classLoader);
        hookKuiklyDialogQueue(xposed, classLoader);
        hookTaobaoInstallPrompt(xposed, classLoader);
        hookDialogShowByContent(xposed);
        hookResumeSweep(xposed);
        EnrollmentJumpGuard.install(xposed, classLoader);
    }

    /** 页面加载装配的弹窗（首页免单弹窗等）在 resume 后延迟清扫兜底。 */
    private static void hookResumeSweep(XposedInterface xposed) {
        try {
            xposed.hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    sweepMarketingPopups();
                } catch (Throwable ignored) {}
                return result;
            });
            MainHook.log("Hooked Activity.onResume for popup sweep");
        } catch (Throwable t) {
            MainHook.log("Failed to hook Activity.onResume for sweep: " + t);
        }
    }

    /**
     * 按文案拦截指定弹窗（始终开启，不受开关影响）：
     *  - 「愉悦推SDK」想要读取您的应用列表 —— 任务校验类隐私授权弹窗（元宝任务页）；
     *  - 「小蚕卫士」安全保障 / 检测中弹窗（下单流程页）。
     * 宿主自定义 Dialog 均继承 android.app.Dialog，show 前扫描窗口内容，
     * 命中关键词则跳过展示；若内容在 show 过程中才装配，show 后立即 dismiss。
     */
    private static void hookDialogShowByContent(XposedInterface xposed) {
        try {
            for (Method m : Dialog.class.getDeclaredMethods()) {
                if (!"show".equals(m.getName()) || m.getParameterTypes().length != 0
                        || Modifier.isStatic(m.getModifiers())) continue;
                xposed.hook(m).intercept(chain -> {
                    Dialog dialog = chain.getThisObject() instanceof Dialog
                            ? (Dialog) chain.getThisObject() : null;
                    if (dialog != null && containsBlockedText(dialog)) {
                        MainHook.log("Blocked dialog by content before show");
                        return null;
                    }
                    Object result = chain.proceed();
                    if (dialog != null && containsBlockedText(dialog)) {
                        MainHook.log("Blocked dialog by content after show, dismissing");
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                    return result;
                });
                break;
            }
            MainHook.log("Content-based dialog guard installed");
        } catch (Throwable t) {
            MainHook.log("Failed to hook Dialog.show by content: " + t);
        }
    }

    private static boolean containsBlockedText(Dialog dialog) {
        try {
            android.view.Window window = dialog.getWindow();
            if (window == null) return false;
            View decor = window.getDecorView();
            if (decor == null) return false;
            StringBuilder sb = new StringBuilder();
            collectText(decor, sb);
            String text = sb.toString();
            if (text.contains("愉悦推")
                    || text.contains("读取您的应用列表")
                    || text.contains("小蚕卫士")) {
                return true;
            }
            // 营销弹窗（免单弹窗 / 签到弹窗 / 订单完成弹窗），特征库与网络层共用
            if (PopupMarkers.contains(text)) {
                return true;
            }
            // 页面内弹层营销弹窗（ Dialog 变体）：隐藏并触发页面级清扫兜底
            if (text.contains("今日免单难度降低")
                    || text.contains("当前店铺不在营业时间")
                    || text.contains("开心收下")
                    || text.contains("再签")
                    || text.contains("订单审核通过")
                    || text.contains("入群新人礼包")) {
                sweepMarketingPopups();
                return true;
            }
            // 抽奖页（免费开红包）中奖弹窗：命中后同时布防外跳窗口，
            // 拦下弹窗「即将自动跳转领取」触发的微信小程序自动拉起；
            // 并触发页面级清扫（中奖弹窗可能是页面内弹层而非 Dialog）
            if (PurifyPrefs.hide(PurifyPrefs.LOTTERY_POPUP)
                    && (text.contains("即将自动跳转") || text.contains("大额出行福利券")
                        || text.contains("跳转领取大额福利"))) {
                EnrollmentJumpGuard.armNow();
                sweepMarketingPopups();
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void collectText(View view, StringBuilder sb) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) sb.append(text).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectText(group.getChildAt(i), sb);
            }
        }
    }

    // ==================== 营销弹窗清扫（按文案锚点，页面内弹层通杀） ====================

    /**
     * 弹窗组：group[0] 为必要锚点（容器子树必须包含），其余为辅助锚点；
     * 容器需同时包含必要锚点 + ≥1 个辅助锚点才隐藏——防止与页面背景
     * 同文案的内容（如首页免单横幅与免单弹窗并存）被误伤整页。
     */
    private static final String[][] SWEEP_GROUPS = {
            // 免费开红包：中奖弹窗
            {"大额出行福利券", "即将自动跳转领取"},
            // 免费开红包：跳转确认框
            {"跳转领取大额福利", "仅限本次", "始终允许"},
            // 首页：免单活动弹窗
            {"立即免单", "0元吃"},
            // 活动详情：营业时间提示
            {"当前店铺不在营业时间", "报名前建议进店查看", "我知道了"},
            // 免费开红包：签到成功弹窗
            {"签到成功", "再签", "开心收下", "必得奖励"},
            // 卡券/红包页：外卖红包广告横幅（去播放为广告特征，真实红包只有去使用）
            {"外卖红包", "今日有效", "去播放"},
            // 我的订单：上传凭证后「订单审核通过」关注引导弹窗
            {"订单审核通过", "立即关注", "关注即可获得", "秒杀预告"},
            // 我的订单：上传凭证后「入群新人礼包」弹窗
            {"入群新人礼包", "社群专属福利", "一小时延时券", "超前抢单券"},
    };
    private static final android.os.Handler SWEEP_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean sweepScheduled;

    /**
     * 营销弹窗清扫：部分营销弹窗是页面内弹层（Kuikly/普通 View）而非
     * Dialog，Dialog.show 拦截覆盖不到。按文案锚点在当前页面上定位
     * 弹窗最小容器并 GONE，多次延迟重扫（弹窗可能带动画延迟装配）。
     * 触发点：Dialog 内容命中、网络层中奖载荷检测、Kuikly 队列放行、
     * Activity.onResume（覆盖页面加载装配场景）。
     */
    public static void sweepMarketingPopups() {
        SWEEP_HANDLER.post(() -> doSweep());
        if (!sweepScheduled) {
            sweepScheduled = true;
            SWEEP_HANDLER.postDelayed(() -> doSweep(), 400);
            SWEEP_HANDLER.postDelayed(() -> doSweep(), 1000);
            SWEEP_HANDLER.postDelayed(() -> { doSweep(); sweepScheduled = false; }, 2200);
        }
    }

    private static void doSweep() {
        try {
            Activity a = EnrollmentJumpGuard.topActivity();
            if (a == null || a.isFinishing() || a.getWindow() == null) return;
            View root = a.getWindow().getDecorView();
            if (!(root instanceof ViewGroup)) return;
            for (String[] group : SWEEP_GROUPS) {
                java.util.List<View> hits = new java.util.ArrayList<>();
                collectAnchorHits((ViewGroup) root, group, hits);
                if (hits.size() < 2) continue;
                View container = smallestContainerContainingAll(hits);
                if (container != null && container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    MainHook.log("Marketing popup container hidden ("
                            + container.getClass().getSimpleName()
                            + " anchor=" + group[0] + ")");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void collectAnchorHits(ViewGroup group, String[] anchors,
            java.util.List<View> hits) {
        if (group.getVisibility() != View.VISIBLE) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence t = ((TextView) child).getText();
                if (t != null) {
                    for (String anchor : anchors) {
                        if (t.toString().contains(anchor)) { hits.add(child); break; }
                    }
                }
            }
            if (child instanceof ViewGroup) collectAnchorHits((ViewGroup) child, anchors, hits);
        }
    }

    /** 包含全部命中 TextView 的最小公共容器（找不到返回 null）。 */
    private static View smallestContainerContainingAll(java.util.List<View> hits) {
        if (hits.isEmpty()) return null;
        View first = hits.get(0);
        for (View cur = first; cur != null; cur = (View) cur.getParent()) {
            boolean all = true;
            for (View h : hits) {
                if (!isDescendantOrSelf(h, cur)) { all = false; break; }
            }
            if (all) return cur;
        }
        return null;
    }

    private static boolean isDescendantOrSelf(View node, View ancestor) {
        for (View cur = node; cur != null; cur = (View) cur.getParent()) {
            if (cur == ancestor) return true;
        }
        return false;
    }

    private static void hookTaobaoInstallPrompt(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> customDialog = Class.forName("com.kongzue.dialogx.dialogs.CustomDialog", false, cl);
            Class<?> redPackUtils = Class.forName("com.realtech.promotion.utils.RedPackUtils", false, cl);
            Object unit = Class.forName("kotlin.Unit", false, cl).getField("INSTANCE").get(null);
            Method configure = redPackUtils.getDeclaredMethod("toElemRedPack$lambda$1",
                    Activity.class, String.class, customDialog);
            int showHooks = 0;
            for (Method method : customDialog.getDeclaredMethods()) {
                if (!"show".equals(method.getName()) || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != customDialog) continue;
                xposed.hook(method).intercept(chain -> {
                    Object dialog = chain.getThisObject();
                    if (!TAOBAO_INSTALL_PROMPTS.containsKey(dialog)) return chain.proceed();
                    // 用户主动点击「第一步·领红包」后 2 秒内的确认框属于
                    // 领红包流程本身（跳淘宝闪购领红包），放行；广告场景的
                    // 自动弹窗无近期触摸，继续拦截
                    if (EnrollmentJumpGuard.lastTouchAgeMs() < 2000L) {
                        MainHook.log("Allowed Taobao red-pack prompt (user-initiated)");
                        TAOBAO_INSTALL_PROMPTS.remove(dialog);
                        return chain.proceed();
                    }
                    MainHook.log("Blocked Taobao install coupon prompt");
                    return dialog;
                });
                showHooks++;
            }
            if (showHooks == 0) throw new NoSuchMethodException("CustomDialog.show");
            xposed.hook(configure).intercept(chain -> {
                Object dialog = chain.getArg(2);
                if (dialog == null) return chain.proceed();
                // 用户主动点击引发的 configure（绑定确认/取消跳转回调）
                // 必须放行，否则确认框无回调、跳转链路断；广告自动弹窗
                // （关掉也会拉起优惠券小程序）无近期触摸，继续吞掉
                if (EnrollmentJumpGuard.lastTouchAgeMs() < 2000L) {
                    return chain.proceed();
                }
                // Even dismissing this prompt launches a coupon mini-app. Do not attach its callbacks.
                TAOBAO_INSTALL_PROMPTS.put(dialog, true);
                return unit;
            });
            MainHook.log("Taobao install prompt guard installed");
        } catch (Throwable t) {
            MainHook.log("Taobao install prompt hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook DialogX CustomDialog to dismiss marketing/promo popups (e.g. "大额外卖券", OPS_POPUP)
     * while safely triggering onDismiss so coroutines/continuations resume without hanging.
     */
    private static void hookCustomDialogX(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> customDialogClass = Class.forName("com.kongzue.dialogx.dialogs.CustomDialog", false, cl);
            Method dismissMethod = customDialogClass.getMethod("dismiss");

            for (Method m : customDialogClass.getDeclaredMethods()) {
                if ("show".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object dialog = chain.getThisObject();
                        MainHook.log("Intercepted DialogX CustomDialog.show, auto-dismissing");
                        try {
                            dismissMethod.invoke(dialog);
                        } catch (Throwable t) {
                            MainHook.log("CustomDialog dismiss error: " + t);
                        }
                        return dialog;
                    });
                }
            }
            MainHook.log("Hooked DialogX CustomDialog.show");
        } catch (Throwable t) {
            MainHook.log("Failed to hook DialogX CustomDialog: " + t);
        }
    }

    private static void hookDialogUtils(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> dialogUtilsClass = Class.forName("com.realtech.common.dialog.DialogUtils", false, cl);
            for (Method m : dialogUtilsClass.getDeclaredMethods()) {
                if ("addDialog".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        String tag = null;
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof String) {
                                    tag = (String) arg;
                                    break;
                                }
                            }
                        }
                        if (isMarketingTag(tag)) {
                            MainHook.log("Blocked DialogUtils dialog tag: " + tag);
                            return null;
                        }
                        return chain.proceed();
                    });
                    MainHook.log("Hooked DialogUtils.addDialog");
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("Failed to hook DialogUtils: " + t);
        }
    }

    private static void hookAppUpdateService(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> updateSvc = Class.forName("com.realtech.xiaocan.service.AppUpdateServiceImpl", false, cl);
            for (Method m : updateSvc.getDeclaredMethods()) {
                if ("checkUpdate".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        MainHook.log("Blocked AppUpdateServiceImpl.checkUpdate");
                        return null;
                    });
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void hookMainViewModelPopups(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> vmClass = Class.forName("com.realtech.xiaocan.MainViewModel", false, cl);
            // 注意：不要拦截 checkSchoolStarts（开学季活动检查，置空会导致
            // 「我」页活动行整体不渲染）和 takeGiftReq（领取礼物请求，置空
            // 会导致下单流程「领红包」按钮无任何反应）——这两个是活动功能
            // 逻辑，弹窗抑制由按内容/类名的 dialog hook 兜底
            String[] targetMethods = {
                    "checkUpdate",
                    "checkSyceeUpdateDialog"
            };
            for (Method m : vmClass.getDeclaredMethods()) {
                for (String tm : targetMethods) {
                    if (tm.equals(m.getName())) {
                        xposed.hook(m).intercept(chain -> {
                            MainHook.log("Blocked MainViewModel." + tm);
                            return null;
                        });
                        break;
                    }
                }
            }
            MainHook.log("Hooked MainViewModel popup triggers");
        } catch (Throwable t) {
            MainHook.log("Failed to hook MainViewModel popup triggers: " + t);
        }
    }

    private static void hookSpecificDialogClasses(XposedInterface xposed, ClassLoader cl) {
        String[] dialogClasses = {
                "com.realtech.promotion.dialog.SharerHomePopupDialog",
                "com.realtech.xiaocan.dialog.agency.FirstOrderFullAmountAgencyDialog",
                "com.realtech.xiaocan.dialog.DouyinMallBonusDialog",
                "com.realtech.common.ui.dialog.update.AppUpdateDialog",
                "com.realtech.promotion.dialog.OpenSchoolshareDialog",
                "com.realtech.promotion.dialog.details.AnnualReportShareDialog",
                "com.realtech.promotion.dialog.share.AnnualReportSecondShareDialog",
                "com.realtech.promotion.dialog.share.SecondShareDialog",
                "com.realtech.vip.dialog.SyceeUpdateNoticeDialog"
        };

        for (String cName : dialogClasses) {
            try {
                Class<?> dClass = Class.forName(cName, false, cl);
                for (Method m : dClass.getMethods()) {
                    if ("show".equals(m.getName())) {
                        xposed.hook(m).intercept(chain -> {
                            Object dialog = chain.getThisObject();
                            if (!dClass.isInstance(dialog)) return chain.proceed();
                            MainHook.log("Blocked " + cName + ".show");
                            return m.getReturnType().isInstance(dialog) ? dialog : null;
                        });
                    }
                }
                MainHook.log("Hooked dialog class: " + cName);
            } catch (Throwable ignored) {}
        }
    }

    private static void hookKuiklyDialogQueue(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> gdqClass = Class.forName("com.realtech.xiaocankuikly.utils.GlobalDialogQueue", false, cl);
            for (Method m : gdqClass.getDeclaredMethods()) {
                if ("show".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object dialogInfo = chain.getArg(0);
                        if (isMarketingDialogInfo(dialogInfo)) {
                            MainHook.log("Blocked GlobalDialogQueue marketing dialog");
                            return null;
                        }
                        dumpDialogInfo("GlobalDialogQueue", dialogInfo);
                        return chain.proceed();
                    });
                    MainHook.log("Hooked GlobalDialogQueue.show");
                    break;
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> dqmClass = Class.forName("com.realtech.xiaocankuikly.utils.DialogQueueManager", false, cl);
            for (Method m : dqmClass.getDeclaredMethods()) {
                if ("showDialog".equals(m.getName())) {
                    xposed.hook(m).intercept(chain -> {
                        Object dialogInfo = chain.getArg(0);
                        if (isMarketingDialogInfo(dialogInfo)) {
                            MainHook.log("Blocked DialogQueueManager.showDialog marketing dialog");
                            return null;
                        }
                        dumpDialogInfo("DialogQueueManager", dialogInfo);
                        return chain.proceed();
                    });
                    MainHook.log("Hooked DialogQueueManager.showDialog");
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 弹窗标识 dump 上次时间（3 秒节流）。 */
    private static volatile long lastDialogInfoDumpMs;

    /**
     * 诊断：dump 未被拦截的 Kuikly 弹窗队列入参标识，便于按真实标识
     * 补充关键词（首页免单弹窗 / 关注引导弹窗 / 绑定客服弹窗）。
     */
    private static void dumpDialogInfo(String from, Object dialogInfo) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDialogInfoDumpMs < 3000L) return;
            lastDialogInfoDumpMs = now;
            String s = String.valueOf(dialogInfo);
            MainHook.log("DialogQueueInfo(" + from + "): "
                    + (s.length() > 160 ? s.substring(0, 160) : s));
            // 队列放行的弹窗可能是页面内弹层：触发文案锚点清扫兜底
            sweepMarketingPopups();
        } catch (Throwable ignored) {}
    }

    private static boolean isMarketingTag(String tag) {
        if (tag == null) return false;
        String t = tag.toLowerCase(Locale.ROOT);
        return t.contains("maintomin")
                || t.startsWith("home_up_")
                || t.contains("openschoolshare")
                || t.contains("past_board_permission")
                || t.contains("timeerror")
                || t.contains("app_update")
                || t.contains("update")
                || t.contains("bonus")
                || t.contains("sycee")
                || t.contains("cake")
                || t.contains("popup")
                || t.contains("gift")
                || t.contains("red_packet")
                || t.contains("agency")
                || t.contains("first_order")
                || t.contains("share")
                || t.contains("annual")
                || t.contains("ops_popup")
                || t.contains("invite")
                || t.contains("withdraw")
                || t.contains("withdrawal")
                || t.contains("lottery")
                || t.contains("coupon")
                // 首页免单资格弹窗 / 关注引导弹窗 / 绑定客服弹窗
                || t.contains("qualify")
                || t.contains("kefu")
                || t.contains("customer")
                || t.contains("follow")
                || t.contains("attention");
    }

    private static boolean isMarketingDialogInfo(Object dialogInfo) {
        if (dialogInfo == null) return false;
        String str = dialogInfo.toString().toLowerCase(Locale.ROOT);
        return str.contains("bonus")
                || str.contains("cake")
                || str.contains("invite")
                || str.contains("red_packet")
                || str.contains("redpacket")
                || str.contains("vip")
                || str.contains("sycee")
                || str.contains("update")
                || str.contains("popup")
                || str.contains("gift")
                || str.contains("share")
                || str.contains("ops")
                || str.contains("annual")
                || str.contains("withdraw")
                || str.contains("withdrawal")
                // 首页免单资格弹窗 / 订单页关注引导弹窗 / 绑定客服弹窗
                || str.contains("qualify")
                || str.contains("free_order")
                || str.contains("freeorder")
                || str.contains("kefu")
                || str.contains("customer")
                || str.contains("follow")
                || str.contains("attention")
                || str.contains("bind");
    }

    private static void dismissIfDialog(Object obj) {
        if (obj instanceof Dialog) {
            try {
                ((Dialog) obj).dismiss();
            } catch (Throwable ignored) {}
        }
    }
}
