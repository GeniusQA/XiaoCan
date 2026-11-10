package com.genius.xiaocanpurify;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 宿主侧定位广播接收：模块设置页「确定」下发 {@code ACTION_SET_GRAB_LOC}
 * （lat/lng/address/short_name），写入定位缓存快照。
 *
 * 只处理定位相关接收：自动化任务与通知推送已从模块移除。
 */
public final class LocationBroadcastHook {
    private static final String ACTION_SET_GRAB_LOC =
            "com.genius.xiaocanpurify.ACTION_SET_GRAB_LOC";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile boolean receiverRegistered;

    private LocationBroadcastHook() {}

    public static void install(XposedInterface xposed, ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", android.os.Bundle.class);
            xposed.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Activity a = (Activity) chain.getThisObject();
                    if (a != null && !receiverRegistered) {
                        receiverRegistered = true;
                        registerReceiver(a.getApplication());
                    }
                } catch (Throwable ignored) {}
                return result;
            });
        } catch (Throwable t) {
            MainHook.log("LocationBroadcast: hook Activity.onCreate failed: " + t);
        }
        MainHook.log("LocationBroadcast installed (grab loc receiver)");
    }

    private static void registerReceiver(Context app) {
        if (app == null) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null || !ACTION_SET_GRAB_LOC.equals(i.getAction())) return;
                    double lat = i.getDoubleExtra("lat", Double.NaN);
                    double lng = i.getDoubleExtra("lng", Double.NaN);
                    String addr = i.getStringExtra("address");
                    String shortName = i.getStringExtra("short_name");
                    MainHook.log("LocationCache: set grab loc " + lat + "," + lng
                            + (addr == null ? "" : " " + addr));
                    if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                        LocationCacheHook.cacheExplicitLocation(lat, lng, addr);
                        // 页面展示用 short_name·full_address，宿主展示侧只取短名
                        if (shortName != null && !shortName.isEmpty()) {
                            LocationCacheHook.setCachedShortName(shortName);
                        }
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_SET_GRAB_LOC);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            MainHook.log("LocationBroadcast: receiver registered");
        } catch (Throwable t) {
            MainHook.log("LocationBroadcast: register receiver failed: " + t);
        }
    }
}
