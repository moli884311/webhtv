package com.moliys.tvbox;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * F1/自动站点：调用用户自备的 OpenAI 兼容对话接口，从网页 HTML 里识别采集接口。
 *
 * <p>本类只做「取回一段 JSON」，不负责落盘；是否可用由 {@link AiSite#validateForAdd} 决定。
 * 用户 Key 只随请求头发送，不写入任何配置文件，也不出现在提示词、日志与错误文案里。
 */
public final class AiSiteClient {

    /** 单次请求超时。 */
    public static final long TIMEOUT = 60000L;

    /** 送给模型的 HTML 上限，超出部分截断。 */
    public static final int MAX_HTML_BYTES = 100 * 1024;

    /** 解析失败后的额外尝试次数。 */
    private static final int RETRY = 1;

    private static final int MAX_TEXT = 200;

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b.*?</style>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\r\\n]+");
    private static final Pattern NOSCRIPT = Pattern.compile("(?is)<(script|style|noscript|iframe)\\b[^>]*/?>");

    private AiSiteClient() {
    }

    // ---------------------------------------------------------------- 提示词

    /** 系统提示词：钉死角色、判定规则与输出格式。 */
    public static String systemPrompt() {
        return "你是视频网站的接口分析专家。用户会给你一个网页的 HTML，你要判断该网站是否提供可被影视应用直接使用的采集接口，并只输出 JSON。\n"
                + "判定规则：\n"
                + "1. 苹果CMS、海螺CMS 一类的 JSON 采集接口，路径通常是 /api.php/provide/vod、/provide/vod、/api.php/at/json 等，能确认时 type 填 1。\n"
                + "2. 页面里出现 <rss>、<list>、<channel> 等 XML 结构的采集或订阅地址时，type 填 0。\n"
                + "3. api 只能从给定 HTML 里出现过的地址推断，禁止编造、猜测或改写域名。\n"
                + "4. api 必须是可直接请求的完整地址；HTML 里是相对路径时补全为绝对地址。\n"
                + "5. 找不到任何可用接口时，把 api 留空，并在 reason 里说明原因。\n"
                + "输出要求：\n"
                + "只输出一个 JSON 对象，不要解释、不要注释、不要 markdown 代码块、不要多余文字。\n"
                + "格式：{\"name\":\"站点名称\",\"api\":\"接口地址\",\"type\":1,\"reason\":\"判断依据\"}\n"
                + "type 只能是 0（网页或 XML 接口）或 1（苹果CMS JSON 接口）。";
    }

    /** 用户提示词：目标地址 + 已清洗的页面内容。 */
    public static String userPrompt(final String targetUrl, final String page) {
        return "目标网站页面地址：" + AiSite.normalize(targetUrl) + "\n"
                + "页面 HTML（已移除 script/style，可能被截断）：\n"
                + (page == null ? "" : page);
    }

    /** 首次请求体：OpenAI 兼容的 chat/completions 负载。 */
    public static JSONObject buildPayload(final String model, final String targetUrl, final String page) throws Exception {
        return buildPayload(model, targetUrl, page, true);
    }

    /** 请求体；{@code jsonMode} 为真时附上 {@code response_format}，接口不支持时由调用方退回纯文本模式。 */
    public static JSONObject buildPayload(final String model, final String targetUrl, final String page,
                                          final boolean jsonMode) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(message("system", systemPrompt()));
        messages.put(message("user", userPrompt(targetUrl, page)));
        JSONObject out = new JSONObject();
        String name = model == null ? "" : model.trim();
        if (!name.isEmpty()) out.put("model", name);
        out.put("messages", messages);
        out.put("temperature", 0.2);
        out.put("stream", false);
        if (jsonMode) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            out.put("response_format", format);
        }
        return out;
    }

    /** 重试请求体：把上一轮不合格的原文回灌，要求只输出 JSON。 */
    public static JSONObject buildRetryPayload(final String model, final String targetUrl, final String page,
                                               final String badOutput, final boolean jsonMode) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(message("system", systemPrompt()));
        messages.put(message("user", userPrompt(targetUrl, page)));
        messages.put(message("assistant", badOutput == null ? "" : badOutput));
        messages.put(message("user", "你上一次的输出不是合法 JSON，请重新输出，只输出 JSON 对象，不要任何解释或代码块。"));
        JSONObject out = new JSONObject();
        String name = model == null ? "" : model.trim();
        if (!name.isEmpty()) out.put("model", name);
        out.put("messages", messages);
        out.put("temperature", 0.2);
        out.put("stream", false);
        if (jsonMode) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            out.put("response_format", format);
        }
        return out;
    }

    private static JSONObject message(final String role, final String content) throws Exception {
        JSONObject out = new JSONObject();
        out.put("role", role);
        out.put("content", content == null ? "" : content);
        return out;
    }

    // ---------------------------------------------------------------- 页面清洗

    /** 移除脚本与样式并截断到 {@value #MAX_HTML_BYTES} 字节。 */
    public static String sanitizeHtml(final String html) {
        return sanitizeHtml(html, MAX_HTML_BYTES);
    }

    /** 移除脚本、样式与注释，压缩空白，再按 UTF-8 字节数截断（不会截断多字节字符）。 */
    public static String sanitizeHtml(final String html, final int maxBytes) {
        if (maxBytes <= 0) return "";
        String text = html == null ? "" : html;
        text = SCRIPT.matcher(text).replaceAll(" ");
        text = STYLE.matcher(text).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = NOSCRIPT.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        return cut(text, maxBytes);
    }

    private static String cut(final String text, final int maxBytes) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length <= maxBytes) return text;
        int length = maxBytes;
        while (length > 0 && (data[length] & 0xC0) == 0x80) length--;
        return new String(data, 0, length, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 响应解析

    /** 从 OpenAI 兼容响应里取出 {@code choices[0].message.content}，取不到返回空串。 */
    public static String extractContent(final String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) return "";
            JSONObject message = choice.optJSONObject("message");
            if (message == null) return "";
            String content = message.optString("content", "");
            return content == null ? "" : content.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 取出接口返回的错误说明，取不到返回空串；调用方需自行脱敏。 */
    public static String errorOf(final String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.trim().isEmpty()) return message.trim();
            }
            String message = json.optString("message", "");
            return message == null ? "" : message.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 去掉 markdown 代码块围栏并截出最外层 {@code {}}；失败返回 null。 */
    public static JSONObject parseSite(final String content) {
        String text = stripCodeFence(content);
        if (text.isEmpty()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 去掉首尾的 ``` 围栏与语言标记。 */
    public static String stripCodeFence(final String text) {
        String out = text == null ? "" : text.trim();
        if (!out.startsWith("```")) return out;
        int first = out.indexOf('\n');
        out = first < 0 ? "" : out.substring(first + 1);
        int end = out.lastIndexOf("```");
        if (end >= 0) out = out.substring(0, end);
        return out.trim();
    }

    // ---------------------------------------------------------------- 结果整形

    /** 把模型输出整形为可落盘的站点；无法成形返回 null。 */
    public static JSONObject toSite(final JSONObject raw, final String targetUrl) {
        if (raw == null) return null;
        String api = AiSite.normalize(raw.optString("api", ""));
        if (api.isEmpty()) return null;
        if (!api.startsWith("http://") && !api.startsWith("https://")) api = AiSite.resolve(targetUrl, api);
        if (!AiSite.isHttpUrl(api)) return null;
        String name = raw.optString("name", "").trim();
        if (name.isEmpty()) name = hostName(targetUrl);
        if (name.isEmpty()) name = "AI 站点";
        int type = raw.optInt("type", -1);
        if (type != AiSite.TYPE_WEB && type != AiSite.TYPE_JSON) type = guessType(api);
        try {
            JSONObject out = new JSONObject();
            out.put("name", name);
            out.put("api", api);
            out.put("type", type);
            out.put("reason", raw.optString("reason", "").trim());
            out.put("ok", true);
            out.put("source", "ai");
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 模型没给类型时按接口形态兜底：带 api.php/provide/vod/at/json 视为 JSON 源，其余视为网页源。 */
    public static int guessType(final String api) {
        String text = AiSite.normalize(api).toLowerCase(Locale.ROOT);
        if (text.contains("api.php") || text.contains("provide/vod") || text.contains("at/json")
                || text.contains("vodjson") || text.endsWith(".json")) {
            return AiSite.TYPE_JSON;
        }
        return AiSite.TYPE_WEB;
    }

    /** 失败结果。 */
    public static JSONObject failure(final String reason) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            String text = reason == null ? "" : reason.trim();
            out.put("error", text.isEmpty() ? "识别失败" : text);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String hostName(final String url) {
        try {
            String host = new URI(AiSite.normalize(url)).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 网络调用

    /**
     * 识别一个页面上的采集接口。
     *
     * @param targetUrl 目标网站页面地址
     * @param html      页面 HTML（内部会清洗截断）
     * @param apiUrl    用户自备的 OpenAI 兼容接口
     * @param key       用户自备的 Key，仅随请求头发送
     * @param model     模型名，可为空
     * @return 成功 {@code {"ok":true,"name","api","type","reason","source"}}；失败 {@code {"ok":false,"error"}}
     */
    public static JSONObject detect(final String targetUrl, final String html, final String apiUrl,
                                    final String key, final String model) {
        if (!AiSite.isHttpUrl(apiUrl)) return failure("AI 接口地址无效");
        if (key == null || key.trim().isEmpty()) return failure("请先填写 AI Key");
        if (!AiSite.isHttpUrl(targetUrl)) return failure("目标网站地址无效");
        String page = sanitizeHtml(html);
        if (page.isEmpty()) return failure("页面内容为空，无法识别");
        String secret = key.trim();
        String lastError = "";
        boolean jsonMode = true;
        String payload;
        try {
            payload = buildPayload(model, targetUrl, page, true).toString();
        } catch (Throwable e) {
            return failure("请求构造失败");
        }
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            String content = "";
            try {
                String body = post(apiUrl, secret, payload);
                content = extractContent(body);
                if (content.isEmpty()) {
                    lastError = "AI 未返回内容";
                    String hint = errorOf(body);
                    if (!hint.isEmpty()) lastError = lastError + "：" + hint;
                } else {
                    JSONObject raw = parseSite(content);
                    if (raw != null) {
                        JSONObject site = toSite(raw, targetUrl);
                        if (site != null) return site;
                        String reason = raw.optString("reason", "").trim();
                        return failure(reason.isEmpty() ? "AI 未能识别出可用的采集接口" : reason);
                    }
                    lastError = "AI 返回的内容不是合法 JSON";
                }
            } catch (HttpError e) {
                lastError = e.getMessage();
                if (e.code == 400 && jsonMode && e.body.contains("response_format")) {
                    jsonMode = false;
                    lastError = "该接口不支持 JSON 模式";
                }
            } catch (Throwable e) {
                lastError = redact(e.getClass().getSimpleName() + "：" + e.getMessage(), secret);
            }
            if (attempt >= RETRY) break;
            try {
                payload = buildRetryPayload(model, targetUrl, page, content, jsonMode).toString();
            } catch (Throwable e) {
                break;
            }
        }
        return failure(lastError);
    }

    /** 发送一次 chat/completions 请求，返回响应体原文。 */
    private static String post(final String apiUrl, final String key, final String payload) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        OkHttpClient client = OkHttp.client(TIMEOUT);
        Request request = new Request.Builder()
                .url(apiUrl)
                .headers(Headers.of(headers))
                .post(RequestBody.create(payload, JSON_TYPE))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                String hint = errorOf(text);
                if (hint.isEmpty()) hint = text.trim();
                throw new HttpError(response.code(), redact(hint, key));
            }
            return text;
        }
    }

    /** 把 Key 替换掉并截断，用于任何可能被展示的文案。 */
    static String redact(final String text, final String key) {
        String out = text == null ? "" : text;
        if (key != null && key.length() >= 8) out = out.replace(key, "***");
        out = SPACES.matcher(out).replaceAll(" ").trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }

    /** 带状态码的 HTTP 异常，便于区分「接口不支持 JSON 模式」这类可恢复错误。 */
    private static final class HttpError extends IOException {

        final int code;

        final String body;

        HttpError(final int code, final String body) {
            super("AI 接口返回 HTTP " + code + (body == null || body.isEmpty() ? "" : "：" + body));
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
