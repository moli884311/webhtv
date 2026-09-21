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
 * F1/自动站点：调用用户自备的 OpenAI 兼容对话接口。
 *
 * <p>两件事：{@link #detect} 从网页 HTML 里识别现成的苹果CMS/XML 采集接口（走 response_format JSON 模式）；
 * {@link #writeSpider} 按 {@link AiSiteProbe} 采到的样本写一个可执行的 Spider 源（纯文本模式，产物是源码而不是 JSON）。
 *
 * <p>本类不负责落盘；是否可用由 {@link AiSite#validateForAdd}（接口站点）或设备端自检（爬虫源）决定。
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

    // ---------------------------------------------------------------- 写源提示词

    /** 语言标记：首行 {@code #!lang=py} / {@code //!lang=js}，解析时剥离并由其决定落盘扩展名。 */
    public static final String LANG_PY = "py";

    public static final String LANG_JS = "js";

    private static final Pattern LANG_PY_MARK = Pattern.compile("^#!\\s*lang\\s*=\\s*py\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern LANG_JS_MARK = Pattern.compile("^//!\\s*lang\\s*=\\s*js\\s*$", Pattern.CASE_INSENSITIVE);

    /** 系统提示词：内联《写源技能书》v2.1 的硬契约，钉死产物形态。 */
    public static String spiderSystemPrompt() {
        return "你是 TVBox Spider 源作者。用户会给你一个视频网站的探测样本（首页、分类列表、详情、播放、搜索页的真实正文片段，可能已被截断），"
                + "你要据此写出一个能被宿主直接加载运行的 Spider 源文件。\n"
                + "硬契约（必须全部满足）：\n"
                + "1. 文件里必须有 class Spider，且不要 import 或继承任何 base.spider；Spider() 必须能无参实例化。\n"
                + "2. 宿主用 SourceFileLoader(...).load_module().Spider() 加载，文件顶层不要写会立即执行且可能抛异常的代码。\n"
                + "3. 必备方法：getDependence、init、homeContent、homeVideoContent、categoryContent、detailContent、searchContent、"
                + "playerContent、localProxy、manualVideoCheck、isVideoFormat、action、destroy。用不到的方法也要保留并返回空值。\n"
                + "4. 多值分隔符固定为 $$$、#、$。\n"
                + "5. playerContent 返回的 header 必须是 dict（可以是空 dict）。\n"
                + "6. localProxy 必须返回 4 项。\n"
                + "7. Python 源可用库仅限 requests、lxml、pyquery、bs4、ujson、cachetools、pycryptodome（Python 3.10）；"
                + "JS 源使用宿主注入的 http 模块发起请求。\n"
                + "8. 只使用样本里出现过的地址与字段，不要编造域名、接口或字段名。样本不足时选最保守的实现，并在 description 里说明。\n"
                + "语言选择由你判断：需要 HTML 解析、加解密或较多分支时用 Python；以拼地址和正则为主时可以用 JavaScript。\n"
                + "输出格式：第一行输出语言标记（Python 写 #!lang=py，JavaScript 写 //!lang=js），从第二行起输出源文件全文。"
                + "不要解释、不要 markdown 代码块围栏、不要在源码前后加任何说明文字。";
    }

    /** 用户提示词：目标地址 + 探测样本正文。 */
    public static String spiderUserPrompt(final String targetUrl, final String samples) {
        return "目标网站：" + AiSite.normalize(targetUrl) + "\n"
                + "探测样本：\n"
                + (samples == null ? "" : samples);
    }

    /** 首次请求体：不带 {@code response_format}，因为产物是源码原文而不是 JSON。 */
    public static JSONObject buildSpiderPayload(final String model, final String targetUrl, final String samples) throws Exception {
        return spiderMessage(model, targetUrl, samples, null, null);
    }

    /** 重试请求体：把不能用的输出与原因回灌，要求重新给出完整源文件。 */
    public static JSONObject buildSpiderRetryPayload(final String model, final String targetUrl, final String samples,
                                                     final String badOutput, final String reason) throws Exception {
        return spiderMessage(model, targetUrl, samples, badOutput, reason);
    }

    private static JSONObject spiderMessage(final String model, final String targetUrl, final String samples,
                                            final String badOutput, final String reason) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(message("system", spiderSystemPrompt()));
        messages.put(message("user", spiderUserPrompt(targetUrl, samples)));
        if (badOutput != null) {
            messages.put(message("assistant", badOutput));
            messages.put(message("user", "你上一次的输出无法直接运行：" + (reason == null ? "不符合契约" : reason)
                    + "。请重新输出完整的源文件全文，第一行先写语言标记。"));
        }
        JSONObject out = new JSONObject();
        String name = model == null ? "" : model.trim();
        if (!name.isEmpty()) out.put("model", name);
        out.put("messages", messages);
        out.put("temperature", 0.2);
        out.put("stream", false);
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
        String endpoint = endpoint(apiUrl);
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
                String body = post(endpoint, secret, payload);
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

    /**
     * 让 AI 按探测样本写一个可执行的 Spider 源。
     *
     * @return 成功 {@code {"ok":true,"lang":"py"|"js","source":"源码全文"}}；失败 {@code {"ok":false,"error"}}
     */
    public static JSONObject writeSpider(final String targetUrl, final String samples, final String apiUrl,
                                         final String key, final String model) {
        return writeSpider(targetUrl, samples, apiUrl, key, model, "", "", AiSiteProgress.NONE);
    }

    /**
     * 带回灌上下文再写一次：把上一版源码与设备端自检的失败原因一起发给模型（§12.3 第 ③ 步的修正轮）。
     *
     * @param previous 上一版源码；为空时等价于 {@link #writeSpider(String, String, String, String, String)}
     * @param reason   自检或加载给出的失败原因
     */
    public static JSONObject writeSpider(final String targetUrl, final String samples, final String apiUrl,
                                         final String key, final String model, final String previous, final String reason) {
        return writeSpider(targetUrl, samples, apiUrl, key, model, previous, reason, AiSiteProgress.NONE);
    }

    /** 首轮写源，并实时回报「正在写/正在修」的进度。 */
    public static JSONObject writeSpider(final String targetUrl, final String samples, final String apiUrl,
                                         final String key, final String model, final AiSiteProgress progress) {
        return writeSpider(targetUrl, samples, apiUrl, key, model, "", "", progress);
    }

    /** 写源（含修正轮）的完整实现；{@code progress} 为 null 时等价于不回报进度。 */
    public static JSONObject writeSpider(final String targetUrl, final String samples, final String apiUrl,
                                         final String key, final String model, final String previous, final String reason,
                                         final AiSiteProgress progress) {
        AiSiteProgress step = progress == null ? AiSiteProgress.NONE : progress;
        if (!AiSite.isHttpUrl(apiUrl)) return failure("AI 接口地址无效");
        if (key == null || key.trim().isEmpty()) return failure("请先填写 AI Key");
        if (!AiSite.isHttpUrl(targetUrl)) return failure("目标网站地址无效");
        if (samples == null || samples.trim().isEmpty()) return failure("探测样本为空，无法写源");
        String secret = key.trim();
        String endpoint = endpoint(apiUrl);
        String lastError = "";
        String payload;
        try {
            payload = seedPayload(model, targetUrl, samples, previous, reason);
        } catch (Throwable e) {
            return failure("请求构造失败");
        }
        boolean repairEntry = previous != null && !previous.trim().isEmpty();
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            String content = "";
            step.step(attempt == 0
                    ? (repairEntry ? "带着上一版的失败原因让 AI 重写" : "把探测样本发给 AI 写第一版源")
                    : "AI 正在按失败原因重写（第 " + (attempt + 1) + " 次）");
            try {
                String body = post(endpoint, secret, payload);
                content = extractContent(body);
                if (content.isEmpty()) {
                    lastError = "AI 未返回内容";
                    String hint = errorOf(body);
                    if (!hint.isEmpty()) lastError = lastError + "：" + hint;
                } else {
                    JSONObject parsed = parseSpider(content);
                    if (parsed.optBoolean("ok")) {
                        step.step("AI 给出一份 " + parsed.optString("lang") + " 源，开始本机自检");
                        return parsed;
                    }
                    lastError = parsed.optString("error", "AI 返回的内容不是 Spider 源");
                }
            } catch (HttpError e) {
                lastError = e.getMessage();
            } catch (Throwable e) {
                lastError = redact(e.getClass().getSimpleName() + "：" + e.getMessage(), secret);
            }
            if (attempt >= RETRY) break;
            step.step("这一版没通过，准备带着原因让 AI 再写一次");
            try {
                payload = buildSpiderRetryPayload(model, targetUrl, samples, content, lastError).toString();
            } catch (Throwable e) {
                break;
            }
        }
        return failure(lastError);
    }

    /** 首轮请求体：有回灌上下文时用「上一版源码 + 失败原因」，否则用干净的写源提示词。 */
    private static String seedPayload(final String model, final String targetUrl, final String samples,
                                     final String previous, final String reason) throws Exception {
        if (previous != null && !previous.trim().isEmpty()) {
            String text = reason == null ? "" : reason.trim();
            return buildSpiderRetryPayload(model, targetUrl, samples, previous, text.isEmpty() ? "上一版没有通过自检" : text).toString();
        }
        return buildSpiderPayload(model, targetUrl, samples).toString();
    }

    /** 剥离围栏与首行语言标记，并校验产物确实是 Spider 源。 */
    public static JSONObject parseSpider(final String content) {
        String text = stripCodeFence(content);
        if (text.isEmpty()) return failure("AI 未返回源码");
        String[] lines = text.split("\n", -1);
        String lang = "";
        int start = 0;
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String line = lines[i].trim();
            if (LANG_PY_MARK.matcher(line).matches()) {
                lang = LANG_PY;
                start = i + 1;
                break;
            }
            if (LANG_JS_MARK.matcher(line).matches()) {
                lang = LANG_JS;
                start = i + 1;
                break;
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(lines[i]);
        }
        String source = builder.toString().trim();
        if (!source.contains("class Spider")) return failure("AI 返回的内容里没有 class Spider");
        if (lang.isEmpty()) lang = guessLang(source);
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("lang", lang);
            out.put("source", source);
            return out;
        } catch (Throwable e) {
            return failure("写源结果无法解析");
        }
    }

    /** 模型没写语言标记时按源码特征兜底。 */
    public static String guessLang(final String source) {
        String text = source == null ? "" : source;
        if (text.contains("class Spider:")) return LANG_PY;
        if (text.contains("import ") && text.contains("def ")) return LANG_PY;
        if (text.contains("function ") || text.contains("=>") || text.contains("var ")) return LANG_JS;
        return LANG_PY;
    }

    /** 用户常只填服务根地址（如 {@code https://api.deepseek.com}），这里补全成 chat/completions 端点。 */
    public static String endpoint(final String apiUrl) {
        String text = apiUrl == null ? "" : apiUrl.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) return text;
        if (lower.endsWith("/v1")) return text + "/chat/completions";
        return text + "/v1/chat/completions";
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
                throw new HttpError(response.code(), apiUrl, redact(hint, key));
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

        HttpError(final int code, final String url, final String body) {
            super("AI 接口返回 HTTP " + code + "（请求 " + url + "）" + (body == null || body.isEmpty() ? "" : "：" + body));
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
