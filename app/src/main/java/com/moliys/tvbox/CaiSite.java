package com.moliys.tvbox;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 采集页「打开站点」：把采集接口转成单站点配置，交给 webhtv 内核按配置方式加载。 */
public final class CaiSite {

    public static final String SITE_KEY = "moliys_cai";
    private static final String FILE_NAME = "moliys_cai.json";
    private static final long DETECT_TIMEOUT = 8000L;

    private CaiSite() {
    }

    /** 探测采集接口返回 XML(type 0) 还是 JSON(type 1)，探测失败按 JSON 处理。 */
    public static int detectType(final String api) {
        try {
            String body = OkHttp.string(api.trim(), DETECT_TIMEOUT);
            if (body != null) {
                String head = body.trim();
                if (head.startsWith("<?xml") || head.startsWith("<rss")) return 0;
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** 生成只含该采集站点的单仓配置 JSON。 */
    public static String buildConfig(final String name, final String api, final int type) {
        try {
            JSONObject site = new JSONObject();
            site.put("key", SITE_KEY);
            site.put("name", name);
            site.put("type", type);
            site.put("api", api);
            site.put("searchable", 1);
            site.put("quickSearch", 1);
            JSONArray sites = new JSONArray();
            sites.put(site);
            JSONObject root = new JSONObject();
            root.put("name", name);
            root.put("sites", sites);
            return root.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 写入本地配置文件，返回可被内核读取的 file:// 地址；失败返回空串。 */
    public static String write(final Context context, final String json) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            return "file://" + file.getAbsolutePath();
        } catch (Throwable e) {
            return "";
        }
    }
}
