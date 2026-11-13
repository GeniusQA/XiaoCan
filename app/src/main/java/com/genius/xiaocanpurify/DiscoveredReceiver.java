package com.genius.xiaocanpurify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Set;
import java.util.TreeSet;

/**
 * 接收宿主（小蚕）回推的「最近发现的资源位」slug 列表（显式广播，与 CacheReceiver 同款），
 * 存入本模块自有 SharedPreferences（PLACEMENT_DISCOVERED）供设置页展示 / 一键复制。
 * RemotePreferences 仅「模块→宿主」单向，故宿主无法回写，改走广播回推。
 */
public class DiscoveredReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.genius.xiaocanpurify.ACTION_DISCOVERED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        String slugs = intent.getStringExtra("slugs");
        if (slugs == null || slugs.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PurifyPrefs.PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> all = new TreeSet<>();
            String old = prefs.getString(PurifyPrefs.PLACEMENT_DISCOVERED, "");
            if (old != null && !old.isEmpty()) {
                for (String s : old.split("[,，\\s]+")) {
                    if (!s.trim().isEmpty()) all.add(s.trim());
                }
            }
            for (String s : slugs.split("[,，\\s]+")) {
                if (!s.trim().isEmpty()) all.add(s.trim());
            }
            prefs.edit().putString(PurifyPrefs.PLACEMENT_DISCOVERED,
                    TextUtils.join(",", all)).apply();
        } catch (Throwable ignored) {}
    }
}
