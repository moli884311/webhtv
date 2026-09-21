package com.moliys.tvbox;

import android.content.Context;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「自动站点」：把用户给的一个视频网站地址（或现成 JSON 配置）变成可被内核加载的站点配置。
 *
 * <p>单体文件 {@value #FILE_NAME} 承载全部自动站点，统一由一条配置加载。站点 {@code key} 由接口地址
 * 确定性派生，保证同一站点稳定、不同站点不冲突（{@code Site.equals} 只比较 {@code key}，同 key 会被当成同一站点）。
 *
 * <p>纯逻辑（key 生成、类型判定、结构校验、合并去重）与需要 {@link Context} 的落盘逻辑分开，
 * 便于在不具备 Android 运行时的环境下直接验证。
 */
public final class AiSite {

    /** 承载全部自动站点的单文件配置。 */
    public static final String FILE_NAME = "moliys_ai_sites.json";

    /** 分组名：内核站点列表里显示的名字。 */
    public static final String GROUP_NAME = "AI 自动站点";

    /** 站点 key 前缀，避免与既有 {@link CaiSite#SITE_KEY} 等其他来源冲突。 */
    public static final String KEY_PREFIX = "moliys_ai_";

    /** 网页 / XML 源。 */
    public static final int TYPE_WEB = 0;

    /** 苹果CMS JSON 源。 */
    public static final int TYPE_JSON = 1;

    /** 可执行爬虫，本方案不支持（无法凭空生成可运行爬虫）。 */
    public static final int TYPE_SPIDER = 3;

    private static final long DETECT_TIMEOUT = 8000L;

    private static final Pattern API_ABSOLUTE = Pattern.compile(
            "(https?://[^\"'\\s<>]{0,200}?/provide/vod[^\"'\\s<>]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern API_RELATIVE = Pattern.compile(
            "[\"'](//?[^\"'\\s<>]{0,200}?/provide/vod[^\"'\\s<>]*)[\"']", Pattern.CASE_INSENSITIVE);

    private AiSite() {
    }

    // ---------------------------------------------------------------- key 生成

    /** 站点 key：{@value #KEY_PREFIX} + sha1(host|规范化 api) 前 10 位。 */
    public static String siteKey(final String api) {
        String normalized = normalize(api);
        return KEY_PREFIX + sha1(hostOf(normalized) + "|" + normalized).substring(0, 10);
    }

    /** 去掉首尾空白与尾部斜杠，保证同一地址的不同写法得到同一 key。 */
    static String normalize(final String api) {
        String text = api == null ? "" : api.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    static String hostOf(final String api) {
        try {
            String host = new URI(normalize(api)).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }

    static String sha1(final String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Throwable e) {
            return "0000000000000000000000000000000000000000";
        }
    }

    // ---------------------------------------------------------------- 类型判定

    /** 按响应体判定类型：XML 头或 {@code <rss} 视为网页/XML 源，其余按 JSON 源。 */
    public static int detectType(final String body) {
        if (body == null) return TYPE_JSON;
        String head = body.trim();
        if (head.startsWith("<?xml") || head.startsWith("<rss")) return TYPE_WEB;
        return TYPE_JSON;
    }

    /** 抓取接口并按响应体判定类型；抓取失败返回 {@value #TYPE_JSON}。 */
    public static int fetchType(final String api) {
        try {
            return detectType(OkHttp.string(normalize(api), DETECT_TIMEOUT));
        } catch (Throwable e) {
            return TYPE_JSON;
        }
    }

    /** 在页面 HTML 里找绝对形式的 {@code .../provide/vod} 接口线索，找不到返回空串。 */
    public static String findApiInHtml(final String html) {
        return matchFirst(API_ABSOLUTE, html);
    }

    /** 在页面 HTML 里找相对形式的 {@code /api.php/provide/vod} 线索，找不到返回空串。 */
    public static String findApiPathInHtml(final String html) {
        String path = matchFirst(API_RELATIVE, html);
        return path.startsWith("/") ? path : "";
    }

    /** 把相对路径拼到站点地址上。 */
    public static String resolve(final String baseUrl, final String path) {
        if (path == null || path.isEmpty()) return "";
        if (isHttpUrl(path)) return normalize(path);
        try {
            URI base = new URI(normalize(baseUrl));
            return normalize(new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null).toString());
        } catch (Throwable e) {
            return "";
        }
    }

    private static String matchFirst(final Pattern pattern, final String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    static boolean isHttpUrl(final String url) {
        String text = normalize(url);
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false;
        return !hostOf(text).isEmpty();
    }

    // ---------------------------------------------------------------- 探测优先编排（D7）

    /** D7 步骤 1：输入本身就像采集接口（含 {@code provide/vod}、含 {@code ac=}、或以 {@code .php} 结尾且含 api）。 */
    public static boolean looksLikeApi(final String url) {
        String text = normalize(url).toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return false;
        if (text.contains("provide/vod")) return true;
        if (text.contains("ac=")) return true;
        return pathOf(text).endsWith(".php") && text.contains("api");
    }

    /** D7 步骤 2：从站点首页 HTML 里直接找出采集接口；命中返回站点 JSON，未命中返回 null。不发起网络请求。 */
    public static JSONObject fromHomepageHtml(final String baseUrl, final String html) {
        if (html == null || html.isEmpty()) return null;
        try {
            String api = findApiInHtml(html);
            if (api.isEmpty()) {
                String path = findApiPathInHtml(html);
                if (path.startsWith("//")) path = schemeOf(baseUrl) + ":" + path;
                api = path.isEmpty() ? "" : resolve(baseUrl, path);
            }
            if (!isHttpUrl(api)) return null;
            JSONObject site = new JSONObject();
            site.put("name", hostOf(baseUrl));
            site.put("api", normalize(api));
            site.put("type", TYPE_JSON);
            site.put("source", "probe");
            return site;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String pathOf(final String url) {
        try {
            String path = new URI(url).getPath();
            return path == null ? "" : path;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String schemeOf(final String url) {
        try {
            String scheme = new URI(normalize(url)).getScheme();
            return scheme == null ? "http" : scheme.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "http";
        }
    }

    // ---------------------------------------------------------------- 结构校验

    /** 站点结构校验；通过返回空串，不通过返回可读原因。不发起任何网络请求。 */
    public static String validateShape(final JSONObject site) {
        if (site == null) return "站点数据为空";
        if (site.optString("name", "").trim().isEmpty()) return "站点名称为空";
        String api = site.optString("api", "").trim();
        if (api.isEmpty()) return "接口地址为空";
        if (!isHttpUrl(api)) return "接口地址必须是 http/https 链接";
        int type = site.optInt("type", TYPE_WEB);
        if (type != TYPE_WEB && type != TYPE_JSON) return "站点类型只支持 0（网页/XML）或 1（苹果CMS JSON）";
        return "";
    }

    /** 补齐站点必备字段并生成唯一 key；key 为空或等于采集站的固定 key 时按接口地址重新派生。 */
    public static JSONObject normalizeSite(final JSONObject site) {
        try {
            String api = normalize(site.optString("api", ""));
            String key = site.optString("key", "").trim();
            if (key.isEmpty() || CaiSite.SITE_KEY.equals(key)) key = siteKey(api);
            JSONObject out = new JSONObject();
            out.put("key", key);
            out.put("name", site.optString("name", "").trim());
            out.put("type", site.optInt("type", TYPE_WEB));
            out.put("api", api);
            out.put("searchable", 1);
            out.put("quickSearch", 1);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 网络实测

    /** 实测接口是否真的返回影片列表（JSON 源看 {@code list}/{@code class}，XML 源看 {@code <list}/{@code <rss}）。 */
    public static boolean apiReturnsList(final String api, final int type) {
        try {
            String url = normalize(api);
            if (type == TYPE_JSON && url.indexOf('?') < 0) url = url + "?ac=list";
            String body = OkHttp.string(url, DETECT_TIMEOUT);
            if (body == null) return false;
            if (type == TYPE_WEB) {
                String head = body.trim();
                return head.startsWith("<?xml") || head.startsWith("<rss") || head.contains("<list");
            }
            JSONObject json = new JSONObject(body);
            return json.has("list") || json.has("class");
        } catch (Throwable e) {
            return false;
        }
    }

    /** 落盘前的完整校验：结构校验 + （JSON 源时）实测接口能返回列表。通过返回空串。 */
    public static String validateForAdd(final JSONObject site) {
        String shape = validateShape(site);
        if (!shape.isEmpty()) return shape;
        if (site.optInt("type", TYPE_WEB) == TYPE_JSON
                && !apiReturnsList(site.optString("api", ""), TYPE_JSON)) {
            return "该接口没有返回影片列表，已放弃添加";
        }
        return "";
    }

    // ---------------------------------------------------------------- 纯 JSON 合并

    /** 同 key 视为同一站点并替换，其余保持原顺序，新站点追加在末尾。 */
    public static JSONArray mergeSite(final JSONArray sites, final JSONObject site) {
        JSONArray out = new JSONArray();
        if (site == null) return sites == null ? out : sites;
        String key = site.optString("key", "");
        for (int i = 0; i < length(sites); i++) {
            JSONObject item = sites.optJSONObject(i);
            if (item == null) continue;
            if (!key.isEmpty() && key.equals(item.optString("key", ""))) continue;
            out.put(item);
        }
        out.put(site);
        return out;
    }

    /** 按 key 删除站点。 */
    public static JSONArray dropSite(final JSONArray sites, final String key) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < length(sites); i++) {
            JSONObject item = sites.optJSONObject(i);
            if (item == null) continue;
            if (key != null && key.equals(item.optString("key", ""))) continue;
            out.put(item);
        }
        return out;
    }

    /** 生成可被内核按配置加载的分组配置 JSON。 */
    public static String buildConfig(final JSONArray sites) {
        try {
            JSONObject root = new JSONObject();
            root.put("name", GROUP_NAME);
            root.put("sites", sites == null ? new JSONArray() : sites);
            return root.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static int length(final JSONArray array) {
        return array == null ? 0 : array.length();
    }

    // ---------------------------------------------------------------- 落盘

    /** 读取本地全部自动站点；文件不存在或损坏时返回空数组。 */
    public static JSONArray loadSites(final Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return new JSONArray();
            JSONArray sites = new JSONObject(readText(file)).optJSONArray("sites");
            return sites == null ? new JSONArray() : sites;
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    /** 原子覆盖写入本地自动站点配置。 */
    public static boolean saveSites(final Context context, final JSONArray sites) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(buildConfig(sites).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 校验并加入一个站点；成功返回空串，失败返回可读原因且不改动现有配置。 */
    public static String addSite(final Context context, final JSONObject site) {
        String error = validateForAdd(site);
        if (!error.isEmpty()) return error;
        JSONObject normalized = normalizeSite(site);
        if (normalized == null) return "站点数据无法解析";
        return saveSites(context, mergeSite(loadSites(context), normalized)) ? "" : "写入站点配置失败";
    }

    /** 按 key 删除站点。 */
    public static boolean removeSite(final Context context, final String key) {
        if (key == null || key.trim().isEmpty()) return false;
        return saveSites(context, dropSite(loadSites(context), key.trim()));
    }

    /** 本地站点数量。 */
    public static int countSites(final Context context) {
        return loadSites(context).length();
    }

    /** 可交给 {@code Config.create(0, url, name)} 加载的本地地址。 */
    public static String configUri(final Context context) {
        return "file://" + new File(context.getFilesDir(), FILE_NAME).getAbsolutePath();
    }

    /**
     * 把「AI 自动站点」分组设为当前接口配置，使其出现在影视主页的站源列表里。
     *
     * <p>与采集页「打开站点」走同一条路径（{@code Config.find} + {@code VodConfig.load}），因此需要在主线程调用，
     * 且回调可能发生在任意线程。一个站点都没有时不切换配置，避免把主页清空。无论成功失败都会回调 {@code done}。
     */
    public static void activate(final Context context, final Runnable done) {
        if (countSites(context) == 0) {
            if (done != null) done.run();
            return;
        }
        try {
            VodConfig.load(Config.find(configUri(context), GROUP_NAME, 0), new Callback() {
                @Override
                public void success() {
                    if (done != null) done.run();
                }

                @Override
                public void error(String msg) {
                    if (done != null) done.run();
                }
            });
        } catch (Throwable e) {
            if (done != null) done.run();
        }
    }

    private static String readText(final File file) {
        try {
            InputStream in = new java.io.FileInputStream(file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "";
        }
    }
}
