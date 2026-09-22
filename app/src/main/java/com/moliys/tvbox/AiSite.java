package com.moliys.tvbox;

import android.content.Context;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「自动站点」：把用户给的一个视频网站地址变成可被内核加载的站点。
 *
 * <p>站点持久化复用壳子既有的「自定义源」注册表 {@link CustomCspSetting}：条目在配置加载时被注入
 * 当前接口配置的站点列表（{@code CustomCspSetting.inject}，调用点 {@code VodConfig}），因此新站与
 * 原配置里的源并存，不切换、不覆盖用户既有配置。条目 {@code id} 由接口地址确定性派生，重复识别同一
 * 站点会覆盖原条目，不产生重复项。
 *
 * <p>纯逻辑（id 生成、类型判定、结构校验）与需要 {@link Context} 的落盘逻辑分开，
 * 便于在不具备 Android 运行时的环境下直接验证。
 */
public final class AiSite {

    /** 自定义源条目 id 前缀：用于把「AI 建站」产生的条目与用户手工添加的条目区分开。 */
    public static final String ID_PREFIX = "ai_";

    /** 网页 / XML 源。 */
    public static final int TYPE_WEB = 0;

    /** 苹果CMS JSON 源。 */
    public static final int TYPE_JSON = 1;

    /** 可执行爬虫源（{@code .py} / {@code .js}），由 AI 按站点特征生成。 */
    public static final int TYPE_SPIDER = 3;

    private static final long DETECT_TIMEOUT = 8000L;

    private static final Pattern API_ABSOLUTE = Pattern.compile(
            "(https?://[^\"'\\s<>]{0,200}?/provide/vod[^\"'\\s<>]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern API_RELATIVE = Pattern.compile(
            "[\"'](//?[^\"'\\s<>]{0,200}?/provide/vod[^\"'\\s<>]*)[\"']", Pattern.CASE_INSENSITIVE);

    private AiSite() {
    }

    // ---------------------------------------------------------------- 条目 id

    /** 条目 id：{@value #ID_PREFIX} + sha1(host|规范化 api) 前 10 位；同一站点稳定、不同站点不冲突。 */
    public static String idOf(final String api) {
        String normalized = normalize(api);
        return ID_PREFIX + sha1(hostOf(normalized) + "|" + normalized).substring(0, 10);
    }

    /** 去掉首尾空白与尾部斜杠，保证同一地址的不同写法得到同一 id。 */
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
        int type = site.optInt("type", TYPE_WEB);
        if (type == TYPE_SPIDER) {
            String lower = api.toLowerCase(Locale.ROOT);
            return lower.endsWith(".py") || lower.endsWith(".js") ? "" : "爬虫源的接口地址必须以 .py 或 .js 结尾";
        }
        if (!isHttpUrl(api)) return "接口地址必须是 http/https 链接";
        if (type != TYPE_WEB && type != TYPE_JSON) return "站点类型只支持 0（网页/XML）、1（苹果CMS JSON）或 3（爬虫源）";
        return "";
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

    // ---------------------------------------------------------------- 落盘（自定义源注册表）

    /** 全部「AI 建站」条目（id 以 {@value #ID_PREFIX} 开头）；按注册表顺序返回。 */
    private static List<CustomCspSetting.Item> aiItems() {
        List<CustomCspSetting.Item> out = new ArrayList<>();
        for (CustomCspSetting.Item item : CustomCspSetting.load().getItems()) {
            if (item == null) continue;
            String id = item.getId();
            if (id == null || !id.startsWith(ID_PREFIX)) continue;
            out.add(item);
        }
        return out;
    }

    /** 本地 AI 站点列表；元素含 {@code key}/{@code id}/{@code name}/{@code api}/{@code type}，供界面展示与删除。 */
    public static JSONArray loadSites(final Context context) {
        JSONArray out = new JSONArray();
        for (CustomCspSetting.Item item : aiItems()) {
            try {
                JSONObject json = new JSONObject();
                json.put("key", item.getId());
                json.put("id", item.getId());
                json.put("name", item.getName());
                json.put("api", item.getApi());
                json.put("type", item.getType() == null ? TYPE_WEB : item.getType());
                out.put(json);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 校验并加入一个站点；成功返回空串，失败返回可读原因且不改动现有配置。 */
    public static String addSite(final Context context, final JSONObject site) {
        String error = validateForAdd(site);
        if (!error.isEmpty()) return error;
        try {
            String api = normalize(site.optString("api", ""));
            String requested = site.optString("id", "").trim();
            final String id = requested.isEmpty() ? idOf(api) : requested;
            CustomCspSetting.Registry registry = CustomCspSetting.load();
            List<CustomCspSetting.Item> items = new ArrayList<>(registry.getItems());
            items.removeIf(item -> item != null && id.equals(item.getId()));
            items.add(newItem(id, site, api));
            registry.setItems(items);
            registry.setEnabled(true);
            CustomCspSetting.save(registry);
            return "";
        } catch (Throwable e) {
            return messageOf(e);
        }
    }

    /** 构造自定义源条目：通用 CSP 类型（非 WebHome），启用并参与配置注入。 */
    private static CustomCspSetting.Item newItem(final String id, final JSONObject site, final String api) {
        CustomCspSetting.Item item = new CustomCspSetting.Item();
        item.setId(id);
        item.setName(site.optString("name", "").trim());
        item.setType(site.optInt("type", TYPE_WEB));
        item.setApi(api);
        item.setWebHome(false);
        item.setSearchable(site.has("searchable") ? Math.max(0, Math.min(1, site.optInt("searchable", 1))) : 1);
        item.setEnabled(true);
        return item;
    }

    /**
     * 把写好的源码先落到自定义源目录，供自检按生产路径加载。
     *
     * <p>只写文件、<b>不动注册表</b>：自检不通过时注册表保持原样，用户看见的站点列表不变；
     * 同一 id 重复识别会覆盖同一文件，不会堆孤儿。注册表下次保存时也会清掉未登记的目录。
     *
     * @return 供站点使用的本地接口地址（{@code file://.../<name>}）；失败返回空串
     */
    public static String stageSource(final String id, final String name, final String source) {
        if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty()) return "";
        if (source == null || source.trim().isEmpty()) return "";
        try {
            Path.write(CustomCspSetting.file(id.trim(), name.trim()), source.getBytes(StandardCharsets.UTF_8));
            return CustomCspSetting.localUrl(id.trim(), name.trim());
        } catch (Throwable e) {
            return "";
        }
    }

    /** 按条目 id 删除站点；注册表保存时会一并清理该条目的文件目录。 */
    public static boolean removeSite(final Context context, final String id) {
        String target = id == null ? "" : id.trim();
        if (target.isEmpty()) return false;
        try {
            CustomCspSetting.Registry registry = CustomCspSetting.load();
            List<CustomCspSetting.Item> items = new ArrayList<>(registry.getItems());
            if (!items.removeIf(item -> item != null && target.equals(item.getId()))) return false;
            registry.setItems(items);
            CustomCspSetting.save(registry);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 本地 AI 站点数量。 */
    public static int countSites(final Context context) {
        return context == null ? 0 : aiItems().size();
    }

    /**
     * 重新加载当前接口配置，让自定义源注册表的改动立即生效。
     *
     * <p>加载的是用户当前的那份配置（不切换配置），重载期间 {@code CustomCspSetting.inject} 会把注册表
     * 里的条目注入站点列表，因此原配置自带的源保留，只是多出或减少一个 AI 站源。需要在主线程调用。
     */
    public static void reloadConfigs() {
        try {
            VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback() {
            });
        } catch (Throwable e) {
            // 重载失败不影响已落盘的注册表，下次加载配置时会再次注入
        }
    }

    private static String messageOf(final Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.isEmpty()) message = error == null ? "" : error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
