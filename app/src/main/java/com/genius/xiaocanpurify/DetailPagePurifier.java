package com.genius.xiaocanpurify;

import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

final class DetailPagePurifier {
    private static final WeakHashMap<Object, Boolean> WATCHED = new WeakHashMap<>();

    private DetailPagePurifier() {}

    static void install(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> loader = Class.forName("com.realtech.promotion.pages.detail.ext.PromotionDetailTobidAdViewExtKt", false, cl);
            for (Method method : loader.getDeclaredMethods()) {
                if (!"loadPromotionDetailBotAdA".equals(method.getName()) || method.getParameterCount() != 3) continue;
                xposed.hook(method).intercept(chain -> {
                    collapse(chain.getArg(2));
                    MainHook.log("Blocked detail ToBid ad load");
                    return null;
                });
            }
        } catch (Throwable t) {
            MainHook.log("Detail ad hook unavailable: " + t.getClass().getSimpleName());
        }
        String[] bindings = {"LayoutPromotionDetailContentBinding", "LayoutMtPromotionDetailContentBinding",
                "LayoutElmPromotionDetailContentBinding", "LayoutDpPromotionDetailContentBinding",
                "DialogQuickPromotionDetailBinding", "DialogMtQuickPromotionDetailBinding",
                "DialogElmQuickPromotionDetailBinding", "DialogDpQuickPromotionDetailBinding"};
        for (String name : bindings) {
            try {
                Class<?> binding = Class.forName("com.realtech.promotion.databinding." + name, false, cl);
                Method bind = binding.getDeclaredMethod("bind", View.class);
                xposed.hook(bind).intercept(chain -> {
                    Object result = chain.proceed();
                    cleanBinding(result);
                    if (result != null && WATCHED.put(result, true) == null) {
                        View root = (View) binding.getMethod("getRoot").invoke(result);
                        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> cleanBinding(result));
                    }
                    return result;
                });
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                MainHook.log("Detail binding hook unavailable: " + name);
            }
        }
    }

    private static void cleanBinding(Object binding) {
        if (binding == null) return;
        // ToBid 广告容器通常是纯广告，应当屏蔽；但活动详情底部「进店看餐品/进店逛逛」
        // 按钮有时就嵌在这个容器里，整块 GONE 会把按钮一起干掉（按钮由 DETAIL_ENTER_SHOP
        // 开关单独控制）。因此：只有该容器【不含】进店按钮时才整块折叠；
        // 含进店按钮时则保留容器（ToBid 广告加载已被 loadPromotionDetailBotAdA 拦截）。
        try {
            Object tobid = binding.getClass().getField("flTobidContainer").get(binding);
            if (tobid instanceof View) {
                boolean hostsEnterShop = false;
                if (tobid instanceof ViewGroup) {
                    StringBuilder sb = new StringBuilder();
                    collectText((ViewGroup) tobid, sb);
                    String t = sb.toString();
                    hostsEnterShop = t.contains("进店看餐品") || t.contains("进店逛逛");
                }
                if (!hostsEnterShop) {
                    collapse(tobid);
                } else {
                    MainHook.log("flTobidContainer hosts enter-shop button: keep visible");
                }
            }
        } catch (ReflectiveOperationException ignored) {}
        if (PurifyPrefs.hide(PurifyPrefs.DETAIL_SHARE)) {
            try { collapse(binding.getClass().getField("ivShare").get(binding)); }
            catch (ReflectiveOperationException ignored) {}
        }
        if (PurifyPrefs.hideOptIn(PurifyPrefs.DETAIL_ENTER_SHOP)) {
            try {
                Object root = binding.getClass().getMethod("getRoot").invoke(binding);
                if (root instanceof ViewGroup) collapseEnterShopButtons((ViewGroup) root);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 按文案隐藏底部「进店看餐品 / 进店逛逛」按钮（独立开关，默认关闭 = 按钮显示）。
     * 命中后折叠其所在的可点击按钮整体；宿主重渲染由 GlobalLayout 守卫再次折叠。
     */
    private static void collapseEnterShopButtons(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) child).getText();
                if (text != null) {
                    String s = text.toString().trim();
                    if ("进店看餐品".equals(s) || "进店逛逛".equals(s)) {
                        View btn = enclosingClickable(child);
                        if (btn != child && isEnterShopButtonOnly(btn)) {
                            collapse(btn);
                        } else {
                            collapse(child);
                        }
                        MainHook.log("Hidden detail enter-shop button: " + s);
                    }
                }
            }
            if (child instanceof ViewGroup) collapseEnterShopButtons((ViewGroup) child);
        }
    }

    /** 校验爬到的可点击节点只包含进店按钮自身内容（防止爬升过头折叠整个底栏）。 */
    private static boolean isEnterShopButtonOnly(View btn) {
        if (!(btn instanceof ViewGroup)) return true;
        StringBuilder sb = new StringBuilder();
        collectText((ViewGroup) btn, sb);
        String text = sb.toString();
        return !text.contains("立即报名") && !text.contains("客服") && !text.contains("领红包");
    }

    private static void collectText(ViewGroup group, StringBuilder sb) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView
                    && ((android.widget.TextView) child).getText() != null) {
                sb.append(((android.widget.TextView) child).getText()).append('\n');
            }
            if (child instanceof ViewGroup) collectText((ViewGroup) child, sb);
        }
    }

    private static View enclosingClickable(View v) {
        View cur = v;
        while (cur.getParent() instanceof View) {
            View parent = (View) cur.getParent();
            if (parent.isClickable()) return parent;
            cur = parent;
        }
        return cur;
    }

    private static void collapse(Object object) {
        if (!(object instanceof View)) return;
        View view = (View) object;
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != 0) {
            params.height = 0;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).topMargin = 0;
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = 0;
            }
            view.setLayoutParams(params);
        }
    }
}
