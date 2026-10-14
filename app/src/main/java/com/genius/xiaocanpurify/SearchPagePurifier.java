package com.genius.xiaocanpurify;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 搜索页净化：隐藏「小蚕情报」板块（标题 + 商户情报列表整块）。
 *
 * 宿主为加固包，无法静态定位搜索页类；这里用通用但不侵入的方式：
 * hook {@code android.app.Activity.onResume}，对含「小蚕情报」的页面
 * 挂一次性 OnGlobalLayoutListener（节流），按文案锚点向上寻找「只属于
 * 情报板块」的容器并 GONE —— 判据是：逐层向上时该层文本仍含「小蚕情报」
 * 且不含「历史搜索」；一旦出现「历史搜索」说明已到页面级容器，停止。
 */
public final class SearchPagePurifier {
    private static final String TITLE = "小蚕情报";
    private static final String PAGE_ANCHOR = "历史搜索";

    /** 节流：全局布局回调很频繁，1.5 秒内只处理一次。 */
    private static volatile long lastRunMs;
    /** 已挂监听的 DecorView（避免重复挂）。 */
    private static final java.util.WeakHashMap<View, Boolean> LISTENED =
            new java.util.WeakHashMap<>();
    /** 已输出过诊断的页面根（只 dump 一次）。 */
    private static final java.util.WeakHashMap<View, Boolean> DIAGNOSED =
            new java.util.WeakHashMap<>();

    private SearchPagePurifier() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> activityClass = Class.forName("android.app.Activity", false, cl);
            for (Method m : activityClass.getDeclaredMethods()) {
                if (!"onResume".equals(m.getName()) || m.getParameterCount() != 0) continue;
                xposed.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof android.app.Activity) {
                            attach((android.app.Activity) self);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
                MainHook.log("SearchIntel: hooked Activity.onResume");
                break;
            }
        } catch (Throwable t) {
            MainHook.log("SearchIntel install error: " + t);
        }
    }

    private static void attach(android.app.Activity activity) {
        try {
            if (!PurifyPrefs.hide(PurifyPrefs.HIDE_SEARCH_INTEL)) return;
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor == null) return;
            synchronized (LISTENED) {
                if (Boolean.TRUE.equals(LISTENED.get(decor))) return;
                LISTENED.put(decor, Boolean.TRUE);
            }
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastRunMs < 1500L) return;
                    lastRunMs = now;
                    if (!PurifyPrefs.hide(PurifyPrefs.HIDE_SEARCH_INTEL)) return;
                    apply(decor, activity.getClass().getSimpleName());
                } catch (Throwable ignored) {}
            });
            MainHook.log("SearchIntel: layout listener attached ("
                    + activity.getClass().getSimpleName() + ")");
        } catch (Throwable t) {
            MainHook.log("SearchIntel attach error: " + t);
        }
    }

    private static void apply(View root, String activityName) {
        View title = findTextViewContaining(root, TITLE);
        if (title == null) return;
        View card = enclosingIntelCard(title, root);
        if (card == null) return;
        // 首次命中时输出一次诊断：宿主页面与卡片链（便于后续精确化）
        synchronized (DIAGNOSED) {
            if (!Boolean.TRUE.equals(DIAGNOSED.get(root))) {
                DIAGNOSED.put(root, Boolean.TRUE);
                StringBuilder chain = new StringBuilder();
                View cur = title;
                for (int i = 0; cur != null && i < 8; i++) {
                    chain.append(cur.getClass().getSimpleName()).append('[')
                            .append(cur.getWidth()).append('x').append(cur.getHeight())
                            .append(cur.getVisibility() == View.VISIBLE ? "" : " G")
                            .append("]<=");
                    cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                }
                MainHook.log("SearchIntel: page=" + activityName + " hitCard="
                        + card.getClass().getSimpleName() + " " + card.getWidth()
                        + "x" + card.getHeight() + " titleChain " + chain);
            }
        }
        if (card.getVisibility() != View.GONE) {
            card.setVisibility(View.GONE);
            MainHook.log("SearchIntel: hidden " + card.getClass().getSimpleName()
                    + " " + card.getWidth() + "x" + card.getHeight());
        }
    }

    /**
     * 从标题向上寻找「只属于情报板块」的最大容器：逐层要求该层文本仍含
     * 「小蚕情报」且不含页面锚点「历史搜索」；出现页面锚点即停止，返回
     * 上一层（即情报板块卡片自身）。
     */
    private static View enclosingIntelCard(View title, View root) {
        View best = null;
        View cur = title;
        int depth = 0;
        while (cur != null && cur != root && depth < 12) {
            String text = subtreeText(cur);
            if (text.contains(PAGE_ANCHOR)) break;
            if (!text.contains(TITLE)) break;
            best = cur;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            depth++;
        }
        return best;
    }

    /** 深度优先查找自身文本包含关键词的 TextView。 */
    private static View findTextViewContaining(View v, String keyword) {
        if (v == null) return null;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().contains(keyword)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findTextViewContaining(g.getChildAt(i), keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 递归收集子树内所有 TextView 文本。 */
    private static String subtreeText(View v) {
        StringBuilder sb = new StringBuilder();
        collectText(v, sb);
        return sb.toString();
    }

    private static void collectText(View v, StringBuilder sb) {
        if (v == null) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) sb.append(cs);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), sb);
            }
        }
    }
}
