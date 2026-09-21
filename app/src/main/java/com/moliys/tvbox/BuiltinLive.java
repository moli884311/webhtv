package com.moliys.tvbox;

import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.Config;

/**
 * 首次启动时写入内置央视直播源。
 *
 * <p>只在用户从未配置过直播源（配置表里没有 type=1 的记录）时写入一次；
 * {@code ConfigDao.findOne(1)} 按 time 倒序取最新一条，因此该默认记录是最旧的，
 * 用户之后自己选的直播源（包含从站点直播列表点「打开」的）永远优先，不会被覆盖。
 */
public final class BuiltinLive {

    private static final String URL = "https://tvbox.moliys.icu/tvbox/live/cctv.txt";
    private static final String NAME = "央视直播";

    public static void ensure() {
        try {
            if (AppDatabase.get().getConfigDao().findOne(1) != null) return;
            Config.create(1, URL, NAME);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private BuiltinLive() {
    }
}
