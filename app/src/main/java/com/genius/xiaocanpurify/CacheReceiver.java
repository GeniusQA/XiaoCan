package com.genius.xiaocanpurify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收目标 App（小蚕）推送的最新定位缓存位置（显式广播，绕过 Android 11+ 包可见性限制），
 * 存入本模块自有 SharedPreferences 供设置页展示。模块 App 本身无需 root。
 */
public class CacheReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.genius.xiaocanpurify.ACTION_LOC_CACHE";
    static final String INBOX_PREFS = "purify_cache_inbox";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        double lat = intent.getDoubleExtra("lat", 0d);
        double lng = intent.getDoubleExtra("lng", 0d);
        long time = intent.getLongExtra("time", 0L);
        String addr = intent.getStringExtra("address");
        android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(INBOX_PREFS, Context.MODE_PRIVATE).edit()
                        .putLong("lat", Double.doubleToRawLongBits(lat))
                        .putLong("lng", Double.doubleToRawLongBits(lng))
                        .putLong("time", time);
        // 仅当新地址非空时覆盖：framework 定位无 POI 地址，避免清空腾讯 SDK 已缓存的地址
        if (addr != null && !addr.isEmpty()) {
            e.putString("address", addr);
        }
        e.apply();
    }
}