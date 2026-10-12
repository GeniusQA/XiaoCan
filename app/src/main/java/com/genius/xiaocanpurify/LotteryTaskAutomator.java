package com.genius.xiaocanpurify;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.WeakReference;

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

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> lastTaskActivity = new WeakReference<>(null);

    private LotteryTaskAutomator() {}

    public static void install(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method performClick = View.class.getMethod("performClick");
            xposed.hook(performClick).intercept(chain -> {
                View view = chain.getThisObject() instanceof View ? (View) chain.getThisObject() : null;
                if (view != null && isBrowseTaskClick(view)) {
                    lastTaskActivity = new WeakReference<>(activityOf(view));
                    MainHook.log("LotteryTaskAutomator: browse task click captured");
                    scheduleAutoBack();
                }
                return chain.proceed();
            });
            MainHook.log("LotteryTaskAutomator installed");
        } catch (Throwable t) {
            MainHook.log("LotteryTaskAutomator install error: " + t);
        }
    }

    /** 点击的按钮文案为「立即浏览」，且向上所在卡片包含「看商城优惠」任务标题。 */
    private static boolean isBrowseTaskClick(View view) {
        if (!PurifyPrefs.enabled(PurifyPrefs.LOTTERY_TASK_AUTO)) return false;
        if (!subtreeText(view).contains("立即浏览")) return false;
        View parent = view;
        for (int i = 0; i < 8 && parent != null; i++) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            if (parent != null && subtreeText(parent).contains("看商城优惠")) {
                return true;
            }
        }
        return false;
    }

    private static void scheduleAutoBack() {
        MAIN.postDelayed(() -> {
            Activity activity = lastTaskActivity.get();
            if (activity == null || activity.isFinishing()) return;
            String text = subtreeText(activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null);
            // 仅当当前页面确实是抖音补贴页时才自动返回，避免误触
            if (!text.contains("超值补贴") && !text.contains("抖音电商") && !text.contains("抖音补贴")) {
                MainHook.log("LotteryTaskAutomator: target page not detected, skip auto-back");
                return;
            }
            MainHook.log("LotteryTaskAutomator: dwell done, dispatching back");
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        }, PAGE_DWELL_MS);
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
