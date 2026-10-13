package com.genius.xiaocanpurify;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 自动完成「免费抽奖 → 看商城优惠得抽奖机会」浏览任务：
 * 点击任务卡「立即浏览」后宿主会跳转抖音补贴页，服务端要求停留约 10 秒才发奖。
 * 本类在点击时捕获所在 Activity，定时 PAGE_DWELL_MS 后校验当前页面确为
 * 抖音补贴页（装饰树含「超值补贴 / 抖音电商」文案），是则自动派发返回键，
 * 免去手动等待与返回；回到抽奖页后刷新即可看到抽奖次数 +1。
 */
public final class LotteryTaskAutomator {
    /** 服务端要求浏览 10s，留 1s 余量对齐其计时起点。 */
    private static final long PAGE_DWELL_MS = 11000L;
    /** 进入即发奖的浏览任务（霸王餐/福利页）：页面加载+发奖上报后立即返回。 */
    private static final long QUICK_BROWSE_MS = 3000L;
    /** 红包任务点击后的 RPC 探针窗口。 */
    private static final long DISCOVERY_MS = 20000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> lastTaskActivity = new WeakReference<>(null);

    private LotteryTaskAutomator() {}

    /** 同一任务的重复触发去重窗口（performClick 与 onTouchEvent 会连击两次）。 */
    private static int lastTriggerType = 0; // TYPE_NONE == 0
    private static long lastTriggerMs = 0L;

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method performClick = View.class.getMethod("performClick");
            xposed.hook(performClick).intercept(chain -> {
                View view = chain.getThisObject() instanceof View ? (View) chain.getThisObject() : null;
                if (view != null) {
                    handleTask(view, "click");
                }
                return chain.proceed();
            });
            // Kuikly/自绘页面点击可能不走 performClick，触摸层兜底
            Method onTouchEvent = View.class.getDeclaredMethod("onTouchEvent", MotionEvent.class);
            xposed.hook(onTouchEvent).intercept(chain -> {
                View view = chain.getThisObject() instanceof View ? (View) chain.getThisObject() : null;
                Object ev = chain.getArg(0);
                if (view != null && ev instanceof MotionEvent
                        && ((MotionEvent) ev).getActionMasked() == MotionEvent.ACTION_UP) {
                    handleTask(view, "touch");
                }
                return chain.proceed();
            });
            MainHook.log("LotteryTaskAutomator installed (click+touch)");
        } catch (Throwable t) {
            MainHook.log("LotteryTaskAutomator install error: " + t);
        }
    }

    private static void handleTask(View view, String source) {
        if (!PurifyPrefs.enabled(PurifyPrefs.LOTTERY_TASK_AUTO)) return;
        int type = taskTypeOf(view);
        if (type == TYPE_NONE) {
            // 诊断探针：命中按钮文案但未匹配到任务卡时记录实际文本，便于定位
            String text = nearestText(view);
            if (text.contains("立即浏览") || text.contains("立即前往") || text.contains("立即领取")) {
                MainHook.log("LotteryTaskAutomator probe(" + source + "): unmatched button text=" + text);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (type == lastTriggerType && now - lastTriggerMs < 1500L) return;
        lastTriggerType = type;
        lastTriggerMs = now;
        if (type == TYPE_MALL || type == TYPE_BROWSE) {
            lastTaskActivity = new WeakReference<>(activityOf(view));
            MainHook.log("LotteryTaskAutomator: task captured (" + source + ", type=" + type + ")");
            scheduleAutoBack(
                    type == TYPE_MALL ? PAGE_DWELL_MS : QUICK_BROWSE_MS,
                    type == TYPE_MALL);
        } else if (type == TYPE_REDPACK) {
            // 美团/饿了么红包任务：拦截小程序/App 外跳，并开启 RPC 探针
            // （任务模式：不受用户触摸影响，窗口内始终拦截）
            EnrollmentJumpGuard.armTaskNow();
            NetworkAdInterceptor.armDiscovery(DISCOVERY_MS);
            MainHook.log("LotteryTaskAutomator: red packet task (" + source + "), jump blocked, discovery armed");
        }
    }

    private static final int TYPE_NONE = 0;
    private static final int TYPE_MALL = 1;    // 看商城优惠（跳外部页，需校验目标页）
    private static final int TYPE_BROWSE = 2;  // 浏览霸王餐/福利页（站内页，直接返回）
    private static final int TYPE_REDPACK = 3; // 领取美团/饿了么红包（拦跳转+探针）

    /** 从被点视图向上查找：某层子树同时含按钮文案与任务卡标题即命中。 */
    private static int taskTypeOf(View view) {
        View node = view;
        for (int i = 0; i < 10 && node != null; i++) {
            String text = subtreeText(node);
            if (text.contains("立即浏览") || text.contains("立即前往") || text.contains("立即领取")) {
                if (text.contains("看商城优惠")) return TYPE_MALL;
                if (text.contains("外卖霸王餐") || text.contains("浏览福利页")) return TYPE_BROWSE;
                if (text.contains("领取美团红包") || text.contains("领取饿了么红包")) return TYPE_REDPACK;
            }
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return TYPE_NONE;
    }

    /** 找到最近一层含文本的祖先子树文本（探针用）。 */
    private static String nearestText(View view) {
        View node = view;
        for (int i = 0; i < 6 && node != null; i++) {
            String text = subtreeText(node);
            if (!text.isEmpty()) return text;
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return "";
    }

    private static void scheduleAutoBack(long delayMs, boolean verifyTargetPage) {
        MAIN.postDelayed(() -> {
            Activity activity = lastTaskActivity.get();
            if (activity == null || activity.isFinishing()) return;
            if (verifyTargetPage) {
                String text = subtreeText(activity.getWindow() != null
                        ? activity.getWindow().getDecorView() : null);
                // 仅当当前页面确实是目标页时才自动返回，避免误触
                if (!text.contains("超值补贴") && !text.contains("抖音电商") && !text.contains("抖音补贴")) {
                    MainHook.log("LotteryTaskAutomator: target page not detected, skip auto-back");
                    return;
                }
            }
            MainHook.log("LotteryTaskAutomator: dwell done, dispatching back");
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        }, delayMs);
    }

    private static Activity activityOf(View view) {
        android.content.Context ctx = view.getContext();
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static String subtreeText(View view) {
        if (view == null) return "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString().replaceAll("\\s+", "");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < group.getChildCount(); i++) {
                sb.append(subtreeText(group.getChildAt(i)));
            }
            return sb.toString();
        }
        return "";
    }
}
