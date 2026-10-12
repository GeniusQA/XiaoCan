package com.genius.xiaocanpurify;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * 模块侧缓存数据入口。目标 App（小蚕，hook 侧）通过 ContentResolver.insert 把最新定位
 * 缓存位置写进来，模块设置页直接读本模块自有存储展示。
 *
 * 相比「共享文件 / su / RemotePreferences」：ContentProvider 是跨进程 IPC，由系统自动拉起
 * 模块进程，不依赖模块 App 有 root、不依赖 /data/data 跨 uid 读权限、也不受对方进程生命周期影响。
 */
public class CacheProvider extends ContentProvider {
    public static final String AUTHORITY = "com.genius.xiaocanpurify.cache";
    /** 写入方（目标 App）使用的 ContentValues key。 */
    public static final String K_LAT = "lat";
    public static final String K_LNG = "lng";
    public static final String K_TIME = "time";
    public static final String K_ADDR = "address";
    /** 模块内部存储的 SharedPreferences 名。 */
    static final String INBOX_PREFS = "purify_cache_inbox";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            Context ctx = getContext();
            if (ctx == null || values == null) return null;
            double lat = values.containsKey(K_LAT) && values.getAsDouble(K_LAT) != null
                    ? values.getAsDouble(K_LAT) : 0d;
            double lng = values.containsKey(K_LNG) && values.getAsDouble(K_LNG) != null
                    ? values.getAsDouble(K_LNG) : 0d;
            long time = values.containsKey(K_TIME) && values.getAsLong(K_TIME) != null
                    ? values.getAsLong(K_TIME) : 0L;
            String addr = values.getAsString(K_ADDR);
            SharedPreferences sp = ctx.getSharedPreferences(INBOX_PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putLong(K_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(K_LNG, Double.doubleToRawLongBits(lng))
                    .putLong(K_TIME, time)
                    .putString(K_ADDR, addr == null ? "" : addr)
                    .apply();
            Log.i("CacheProvider", "cached location stored lat=" + lat + " lng=" + lng);
        } catch (Throwable t) {
            Log.w("CacheProvider", "insert error: " + t);
        }
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null; // 设置页直接读模块自有 SharedPreferences，无需 query
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }
}