package com.genius.xiaocanpurify;

/**
 * 营销弹窗特征文案库（服务端下发的弹窗配置原文）：
 *  - 「你有一单可免单 / 邀请1人即得抽免单资格 / 立即免单」—— 首页免单营销弹窗；
 *  - 「签到成功 再签N天必得奖励 / 开心收下」—— 免费开红包页签到弹窗；
 *  - 「恭喜订单完成 恭喜获得蚕豆已到账」—— 上传凭证后订单完成庆祝弹窗；
 *  - 「关注小蚕霸王餐 / 关注订单状态自动推送 / 立即关注」—— 订单页关注引导弹窗；
 *  - 「添加专属客服 / 绑定专属客服 / 获取重要通知」—— 上传凭证结果页绑定客服弹窗。
 * 供网络层响应体扫描（NetworkAdInterceptor）与 Dialog 文案拦截（PopupBlocker）共用。
 */
final class PopupMarkers {
    private static final String[] MARKERS = {
            "你有一单可免单",
            "抽免单资格",
            "立即免单",
            "必得奖励",
            "开心收下",
            "恭喜订单完成",
            // 订单页「关注小蚕霸王餐」引导弹窗
            "关注小蚕霸王餐",
            "关注订单状态自动推送",
            // 上传凭证结果页「绑定专属客服」引导弹窗
            "添加专属客服",
            "绑定专属客服",
            "获取重要通知",
    };

    private PopupMarkers() {}

    static boolean contains(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String marker : MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
