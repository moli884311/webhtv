package com.moliys.tvbox;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * F1/自动站点：写源后的设备端自检。
 *
 * <p>按生产同路径加载（{@link BaseLoader#getSpider}）：`.py` 走 Chaquopy、`.js` 走 QuickJS、`csp_` 走 Jar；
 * <b>全部通过才允许落盘</b>。加载器内部已经调用过 `getDependence`/`init`，这里只跑内容契约（§12.6）。
 */
public final class AiSiteSelfTest {

    /** 单步请求超时。 */
    public static final int TIMEOUT = 20000;

    /** 错误文案上限。 */
    private static final int MAX_ERROR = 200;

    /** 探测站内搜索用的关键词（用户 2026-09-21 指定）。 */
    public static final String SEARCH_WORD = "电影";

    private static final String LOAD = "load";
    private static final String HOME = "home";
    private static final String CATEGORY = "category";
    private static final String DETAIL = "detail";
    private static final String SEARCH = "search";
    private static final String PLAY = "play";

    private static final String SKIP = "不适用";

    private AiSiteSelfTest() {
    }

    /**
     * 加载并自检一个源。
     *
     * @param searchable 探测阶段是否确认该站有搜索能力；为 false 时搜索项记「不适用」
     * @return `{"ok":true,"steps":[...]}` 或 `{"ok":false,"step":"...","error":"..."}`
     */
    public static JSONObject verify(final String key, final String api, final String ext, final String jar, final boolean searchable) {
        Spider spider;
        try {
            spider = BaseLoader.get().getSpider(key, api, ext, jar);
        } catch (Throwable e) {
            return fail(LOAD, e);
        }
        if (spider == null || spider instanceof SpiderNull) return fail(LOAD, new IllegalStateException("源加载失败（语法错误或依赖缺失）"));
        return check(spider, searchable);
    }

    /** 只跑内容契约，便于单测注入替身 Spider。 */
    static JSONObject check(final Spider spider, final boolean searchable) {
        JSONArray steps = new JSONArray();
        try {
            JSONObject home = new JSONObject(spider.homeContent(false));
            JSONArray classes = home.optJSONArray("class");
            JSONArray homeList = home.optJSONArray("list");
            if (empty(classes) && empty(homeList)) return drop(steps, HOME, "首页既无分类也无内容");
            steps.put(step(HOME, (empty(homeList) ? 0 : homeList.length()) + (empty(classes) ? 0 : classes.length())));

            String tid = firstId(classes, "type_id");
            String vodId = firstId(homeList, "vod_id");
            String detailId = vodId;

            JSONObject category = new JSONObject(spider.categoryContent(tid, "1", false, new HashMap<>()));
            JSONArray categoryList = category.optJSONArray("list");
            if (empty(categoryList)) return drop(steps, CATEGORY, "分类第一页取不到内容");
            steps.put(step(CATEGORY, categoryList.length()));
            if (detailId.isEmpty()) detailId = firstId(categoryList, "vod_id");

            if (detailId.isEmpty()) return drop(steps, DETAIL, "没有可用的详情 id");

            List<String> ids = new ArrayList<>();
            ids.add(detailId);
            JSONObject detail = new JSONObject(spider.detailContent(ids));
            JSONArray detailList = detail.optJSONArray("list");
            if (empty(detailList)) return drop(steps, DETAIL, "详情取不到内容");
            String playUrl = detailList.optJSONObject(0) == null ? "" : detailList.optJSONObject(0).optString("vod_play_url");
            if (playUrl.trim().isEmpty()) return drop(steps, DETAIL, "详情没有播放列表");
            steps.put(step(DETAIL, detailList.length()));

            if (!searchable) {
                steps.put(skip(SEARCH));
            } else {
                JSONObject search = new JSONObject(spider.searchContent(SEARCH_WORD, false));
                JSONArray searchList = search.optJSONArray("list");
                if (empty(searchList)) return drop(steps, SEARCH, "站内搜索「" + SEARCH_WORD + "」无结果");
                steps.put(step(SEARCH, searchList.length()));
            }

            String target = firstSegment(playUrl);
            if (target.isEmpty()) return drop(steps, PLAY, "播放列表里没有可用的播放地址");
            List<String> vip = new ArrayList<>();
            vip.add(target);
            JSONObject played = new JSONObject(spider.playerContent("", "", vip));
            String url = played.optString("url");
            if (url.isEmpty()) return drop(steps, PLAY, "playerContent 没有返回 url");
            String probed = confirm(url, spider);
            if (probed.isEmpty()) return drop(steps, PLAY, "播放地址不是 m3u8/mp4：" + url);
            steps.put(step(PLAY, probed));

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("steps", steps);
            return result;
        } catch (Throwable e) {
            return drop(steps, current(steps), message(e));
        }
    }

    /** 真请求一次播放地址，确认为 m3u8/mp4；无法确认时返回空串。 */
    private static String confirm(final String url, final Spider spider) {
        String lower = url.toLowerCase();
        try {
            String body = OkHttp.string(url, TIMEOUT);
            if (body != null && body.startsWith("#EXTM3U")) return "m3u8";
            if (lower.contains(".m3u8")) return "m3u8";
            if (lower.contains(".mp4")) return "mp4";
        } catch (Throwable ignored) {
        }
        try {
            if (spider.isVideoFormat(url)) return "video";
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String firstSegment(final String playUrl) {
        String text = playUrl.replace("$$$", "#");
        int cut = text.indexOf('#');
        if (cut >= 0) text = text.substring(0, cut);
        int dollar = text.indexOf('$');
        return dollar >= 0 ? text.substring(dollar + 1).trim() : text.trim();
    }

    private static String current(final JSONArray steps) {
        String[] order = {HOME, CATEGORY, DETAIL, SEARCH, PLAY};
        for (String name : order) {
            if (!has(steps, name)) return name;
        }
        return PLAY;
    }

    private static boolean has(final JSONArray steps, final String name) {
        for (int i = 0; i < steps.length(); i++) if (name.equals(steps.optJSONObject(i).optString("step"))) return true;
        return false;
    }

    private static boolean empty(final JSONArray array) {
        return array == null || array.length() == 0;
    }

    private static String firstId(final JSONArray array, final String key) {
        if (empty(array)) return "";
        JSONObject item = array.optJSONObject(0);
        return item == null ? "" : item.optString(key, "").trim();
    }

    private static void put(final JSONObject json, final String key, final Object value) {
        try {
            json.put(key, value);
        } catch (Throwable e) {
        }
    }

    private static JSONObject step(final String name, final Object value) {
        JSONObject json = new JSONObject();
        put(json, "step", name);
        put(json, "ok", true);
        put(json, "value", value);
        return json;
    }

    private static JSONObject skip(final String name) {
        JSONObject json = new JSONObject();
        put(json, "step", name);
        put(json, "ok", true);
        put(json, "value", SKIP);
        return json;
    }

    private static JSONObject drop(final JSONArray steps, final String name, final String error) {
        JSONObject json = new JSONObject();
        put(json, "ok", false);
        put(json, "step", name);
        put(json, "error", truncate(error));
        put(json, "steps", steps);
        return json;
    }

    private static JSONObject fail(final String name, final Throwable e) {
        JSONObject json = new JSONObject();
        put(json, "ok", false);
        put(json, "step", name);
        put(json, "error", truncate(message(e)));
        put(json, "steps", new JSONArray());
        return json;
    }

    private static String message(final Throwable e) {
        String text = e == null ? "" : e.getMessage();
        return text == null || text.isEmpty() ? (e == null ? "未知错误" : e.getClass().getSimpleName()) : text;
    }

    private static String truncate(final String text) {
        String out = text == null ? "" : text.replace('\n', ' ').trim();
        return out.length() > MAX_ERROR ? out.substring(0, MAX_ERROR) : out;
    }
}
