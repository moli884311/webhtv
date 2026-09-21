package com.moliys.tvbox;

import com.github.catvod.net.OkHttp;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「AI 建站」的探测阶段：只抓样本，不调 AI。
 *
 * <p>按《写源技能书》的采集顺序走一遍：首页 → 分类列表 → 详情 → 播放 → 搜索；页面几乎是空壳时再补抓
 * 一个脚本文件，供 AI 从中提取后端接口。样本连同真实可达的播放地址一起交给 {@link AiSiteClient}，
 * 由 AI 汇总成可执行的 Spider 源。
 *
 * <p>请求数有硬上限（{@value #MAX_FETCH} 次：6 个页面 + 1 次播放地址确认），拿不到就如实少给样本，
 * 由调用方决定是否继续。
 */
public final class AiSiteProbe {

    public static final String ROLE_HOME = "home";

    public static final String ROLE_CATEGORY = "category";

    public static final String ROLE_DETAIL = "detail";

    public static final String ROLE_PLAY = "play";

    public static final String ROLE_SEARCH = "search";

    public static final String ROLE_SCRIPT = "script";

    /** 单个样本正文上限，与 D6「只发清洗后前 100 KB」一致。 */
    public static final int MAX_BODY = 100 * 1024;

    /**
     * 总请求预算：首页最多 4 次（换 User-Agent 重试）+ 分类 1 + 详情 1 + 播放 1 + 播放确认 1
     * + 搜索 1 + 脚本包 2 + 余量。
     */
    static final int MAX_FETCH = 12;

    /** 首页/脚本最多尝试的 User-Agent 个数。 */
    static final int MAX_UA = 4;

    /** 一次探测最多补抓几个脚本包。 */
    static final int MAX_SCRIPT = 2;

    static final long TIMEOUT = 15000L;

    /**
     * 依次尝试的 User-Agent。「抓不到结构」很多时候是站点按 UA 拦截或不认默认 UA，
     * 桌面 Chrome → 移动 Chrome → Android TV → iOS Safari 覆盖常见的三种放行策略。
     */
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 9; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    };

    private static final String[] UA_NAMES = {"桌面 Chrome", "移动 Chrome", "Android TV", "iOS Safari"};

    /** 命中则视为「被拦截/验证页」，换 UA 重试。 */
    private static final String[] BLOCKED_WORDS = {
            "just a moment", "cf-browser-verification", "checking your browser", "attention required",
            "access denied", "403 forbidden", "人机验证", "安全验证", "请稍候", "请开启javascript",
            "请启用javascript", "需要开启javascript", "请求过于频繁", "访问受限", "滑动验证",
    };

    /** 站内搜索的探测词（用户 2026-09-21 确认）。 */
    static final String SEARCH_WORD = "电影";

    private static final String TRUNCATED = "\n...(正文已截断)";

    private static final Pattern LINK = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** 内联脚本正文（没有 src 的 script 标签）。 */
    private static final Pattern SCRIPT_BODY = Pattern.compile(
            "<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern MEDIA = Pattern.compile(
            "(https?://[^\"'\\s<>\\\\]{0,300}?\\.(?:m3u8|mp4)[^\"'\\s<>\\\\]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FORM_OPEN = Pattern.compile("<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ACTION = Pattern.compile(
            "action\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern NAME_ATTR = Pattern.compile(
            "name\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final String[] SEARCH_NAMES = {"wd", "keyword", "k", "q", "search"};

    private static final String[] CATEGORY_WORDS = {
            "电影", "电视剧", "动漫", "综艺", "纪录片", "剧集", "连续剧", "movie", "film", "tv"};

    private static final String[] DETAIL_HINTS = {
            "/show/", "/detail/", "/voddetail/", "/movie/", "/vod/", "/info/", "/subject/"};

    private static final String[] PLAY_HINTS = {
            "/play/", "/vodplay/", "/watch/", "/episode/", "/index.php/vod/play/"};

    private static final String[] PLAY_WORDS = {"播放", "第", "集", "play"};

    private static final Pattern DIGITS = Pattern.compile("\\d{3,}");

    private AiSiteProbe() {
    }

    /** 一个探测样本：角色 + 真实 URL + 截断后的正文。 */
    public static class Sample {

        private final String role;

        private final String url;

        private final String body;

        Sample(final String role, final String url, final String body) {
            this.role = role;
            this.url = url;
            this.body = body;
        }

        public String getRole() {
            return role;
        }

        public String getUrl() {
            return url;
        }

        public String getBody() {
            return body;
        }
    }

    /** 探测结果：成功时 {@code error} 为空；搜索模板与播放地址可能缺失，属正常。 */
    public static class Result {

        private final List<Sample> samples = new ArrayList<>();

        private String target = "";

        private String error = "";

        private String playUrl = "";

        private boolean playConfirmed;

        private String searchTemplate = "";

        public boolean ok() {
            return error.isEmpty();
        }

        public List<Sample> getSamples() {
            return samples;
        }

        public String getTarget() {
            return target;
        }

        public String getError() {
            return error;
        }

        public String getPlayUrl() {
            return playUrl;
        }

        public boolean isPlayConfirmed() {
            return playConfirmed;
        }

        /** 形如 {@code /search.php?wd={wd}}；未探测到站内搜索时为空。 */
        public String getSearchTemplate() {
            return searchTemplate;
        }

        /** 是否探测到站内搜索：自检契约第 6 项据此决定「跑」还是记「不适用」。 */
        public boolean isSearchable() {
            if (!searchTemplate.isEmpty()) return true;
            for (Sample sample : samples) {
                if (ROLE_SEARCH.equals(sample.getRole())) return true;
            }
            return false;
        }

        /** 首页正文；未抓到首页时为空串。供「能直接命中 maccms 接口」的捷径复用同一份 HTML。 */
        public String homeBody() {
            for (Sample sample : samples) {
                if (ROLE_HOME.equals(sample.getRole())) return sample.getBody();
            }
            return "";
        }
    }

    // ---------------------------------------------------------------- 主流程

    /** 探测一个站点。不抛异常：失败信息放在 {@link Result#getError()} 里。 */
    public static Result probe(final String target) {
        return probe(target, AiSiteProgress.NONE);
    }

    /** 探测一个站点，并实时回报进度。 */
    public static Result probe(final String target, final AiSiteProgress progress) {
        AiSiteProgress step = progress == null ? AiSiteProgress.NONE : progress;
        Result result = new Result();
        result.target = AiSite.normalize(target);
        if (!AiSite.isHttpUrl(result.target)) {
            result.error = "网址必须是 http/https 链接";
            return result;
        }
        int[] budget = {MAX_FETCH};
        String ua = "";
        String home = "";
        for (int i = 0; i < Math.min(MAX_UA, USER_AGENTS.length); i++) {
            step.step("用「" + UA_NAMES[i] + "」打开首页");
            String raw = fetchRaw(result.target, budget, USER_AGENTS[i]);
            if (!raw.isEmpty() && !blocked(raw)) {
                home = raw;
                ua = USER_AGENTS[i];
                break;
            }
            step.step(raw.isEmpty()
                    ? "这个 User-Agent 没拿到内容"
                    : "这个 User-Agent 被拦了（疑似需要验证）");
        }
        if (home.isEmpty()) {
            result.error = "打不开这个网站：" + result.target + "（已换 " + Math.min(MAX_UA, USER_AGENTS.length) + " 个 User-Agent）";
            return result;
        }
        step.step("首页已抓到 " + home.length() + " 字节");
        result.samples.add(new Sample(ROLE_HOME, result.target, trim(home)));
        List<Link> homeLinks = links(home, result.target);
        step.step("首页解析出 " + homeLinks.size() + " 个链接");

        Link category = pickCategory(homeLinks, result.target);
        if (category != null) {
            step.step("抓分类页：" + category.url);
            String body = fetch(category.url, budget, ua);
            if (!body.isEmpty()) {
                result.samples.add(new Sample(ROLE_CATEGORY, category.url, trim(body)));
                probeDetail(result, body, category.url, budget, ua, step);
            } else {
                step.step("分类页没抓到内容");
            }
        } else {
            step.step("首页里没找到分类入口");
        }

        String template = searchUrl(home, result.target);
        if (!template.isEmpty()) {
            String url = template.replace("{wd}", encode(SEARCH_WORD));
            step.step("抓站内搜索：" + url);
            String body = fetch(url, budget, ua);
            if (!body.isEmpty()) {
                result.searchTemplate = template;
                result.samples.add(new Sample(ROLE_SEARCH, url, trim(body)));
            } else {
                step.step("搜索页没抓到内容");
            }
        } else {
            step.step("没找到站内搜索入口（该项接下来记「不适用」）");
        }

        if (homeLinks.size() < 3 || textOf(home).length() < 200) {
            step.step("页面像前端空壳，开始翻脚本找真接口");
            probeScripts(result, home, result.target, budget, ua, step);
        }

        if (result.samples.size() < 2 && homeLinks.isEmpty()) {
            result.error = "这个网站没有抓到可用的页面结构";
        }
        return result;
    }

    /** 空壳页面（SPA）的补救：把内联脚本与脚本包正文一起交给 AI 找后端接口。 */
    private static void probeScripts(final Result result, final String html, final String base,
                                     final int[] budget, final String ua, final AiSiteProgress step) {
        for (Sample inline : inlineScripts(html, base)) {
            result.samples.add(inline);
            step.step("收下内联脚本 " + inline.getBody().length() + " 字节");
        }
        int taken = 0;
        for (String url : scriptUrls(html, base, MAX_SCRIPT)) {
            if (taken >= MAX_SCRIPT) break;
            step.step("抓脚本包：" + url);
            String body = fetch(url, budget, ua);
            if (body.isEmpty()) {
                step.step("脚本包没抓到内容");
                continue;
            }
            result.samples.add(new Sample(ROLE_SCRIPT, url, trim(body)));
            taken++;
        }
    }

    private static void probeDetail(final Result result, final String listBody, final String listUrl,
                                    final int[] budget, final String ua, final AiSiteProgress step) {
        Link detail = pickDetail(links(listBody, listUrl), listUrl);
        if (detail == null) {
            step.step("分类页里没找到详情入口");
            return;
        }
        step.step("抓详情页：" + detail.url);
        String body = fetch(detail.url, budget, ua);
        if (body.isEmpty()) {
            step.step("详情页没抓到内容");
            return;
        }
        result.samples.add(new Sample(ROLE_DETAIL, detail.url, trim(body)));

        String media = firstMedia(body);
        if (media.isEmpty()) {
            Link play = pickPlay(links(body, detail.url), detail.url);
            if (play == null) {
                step.step("详情页里没找到播放入口，也没看到 m3u8/mp4");
                return;
            }
            step.step("抓播放页：" + play.url);
            String playBody = fetch(play.url, budget, ua);
            if (playBody.isEmpty()) {
                step.step("播放页没抓到内容");
                return;
            }
            result.samples.add(new Sample(ROLE_PLAY, play.url, trim(playBody)));
            media = firstMedia(playBody);
        }
        if (media.isEmpty()) {
            step.step("没找到播放地址");
            return;
        }
        result.playUrl = media;
        step.step("确认播放地址：" + media);
        result.playConfirmed = isMedia(fetch(media, budget, ua), media);
        step.step(result.playConfirmed ? "播放地址可访问" : "播放地址没确认下来（可能是需要 referer 或已失效）");
    }

    // ---------------------------------------------------------------- 交给 AI

    /** 把探测结果整理成提示词正文。 */
    public static String toPrompt(final Result result) {
        StringBuilder builder = new StringBuilder();
        builder.append("目标站点：").append(result.target).append('\n');
        builder.append("站内搜索地址模板：").append(result.searchTemplate.isEmpty() ? "未探测到" : result.searchTemplate).append('\n');
        builder.append("播放地址：").append(result.playUrl.isEmpty() ? "未探测到" : result.playUrl)
                .append(result.playUrl.isEmpty() ? "" : (result.playConfirmed ? "（已确认可访问）" : "（未确认）")).append('\n');
        builder.append("正文说明：以下每段都是页面/脚本的真实正文片段，可能已截断。\n");
        for (Sample sample : result.samples) {
            builder.append("\n### [").append(sample.getRole()).append("] ").append(sample.getUrl()).append('\n');
            builder.append(sample.getBody()).append('\n');
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 取候选链接

    private static final class Link {

        private final String url;

        private final String text;

        Link(final String url, final String text) {
            this.url = url;
            this.text = text;
        }
    }

    private static List<Link> links(final String html, final String base) {
        List<Link> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = LINK.matcher(html);
        while (matcher.find()) {
            String url = AiSite.resolve(base, matcher.group(1).trim());
            if (!AiSite.isHttpUrl(url) || !seen.add(url)) continue;
            out.add(new Link(url, textOf(matcher.group(2))));
        }
        return out;
    }

    private static Link pickCategory(final List<Link> links, final String homeUrl) {
        for (Link link : links) {
            if (!sameHost(link.url, homeUrl) || link.url.equals(homeUrl)) continue;
            String text = link.text.toLowerCase(Locale.ROOT);
            for (String word : CATEGORY_WORDS) {
                if (text.contains(word.toLowerCase(Locale.ROOT))) return link;
            }
        }
        for (Link link : links) {
            if (sameHost(link.url, homeUrl) && !link.url.equals(homeUrl) && segments(link.url) >= 2) return link;
        }
        return links.isEmpty() ? null : links.get(0);
    }

    private static Link pickDetail(final List<Link> links, final String base) {
        for (Link link : links) {
            String url = link.url.toLowerCase(Locale.ROOT);
            for (String hint : DETAIL_HINTS) {
                if (url.contains(hint)) return link;
            }
        }
        for (Link link : links) {
            if (sameHost(link.url, base) && DIGITS.matcher(pathOf(link.url)).find()) return link;
        }
        return null;
    }

    private static Link pickPlay(final List<Link> links, final String base) {
        for (Link link : links) {
            String url = link.url.toLowerCase(Locale.ROOT);
            for (String hint : PLAY_HINTS) {
                if (url.contains(hint)) return link;
            }
        }
        for (Link link : links) {
            if (!sameHost(link.url, base)) continue;
            String text = link.text.toLowerCase(Locale.ROOT);
            for (String word : PLAY_WORDS) {
                if (text.contains(word)) return link;
            }
        }
        return null;
    }

    private static String firstMedia(final String html) {
        String text = html == null ? "" : html.replace("\\/", "/");
        Matcher matcher = MEDIA.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 从首页的表单或搜索链接推导搜索模板；找不到返回空串（站内搜索记为「不适用」）。 */
    private static String searchUrl(final String html, final String base) {
        String text = html == null ? "" : html;
        Matcher form = FORM_OPEN.matcher(text);
        while (form.find()) {
            int start = form.end();
            int end = text.indexOf("</form>", start);
            String inner = text.substring(start, end < 0 ? Math.min(text.length(), start + 4000) : end);
            String action = matchAttr(ACTION, form.group(1));
            Matcher input = INPUT.matcher(inner);
            while (input.find()) {
                String field = matchAttr(NAME_ATTR, input.group());
                if (field.isEmpty() || !isSearchField(field)) continue;
                String url = AiSite.resolve(base, action.isEmpty() ? base : action);
                if (!AiSite.isHttpUrl(url)) continue;
                return url + (url.indexOf('?') < 0 ? "?" : "&") + field + "={wd}";
            }
        }
        Matcher link = LINK.matcher(text);
        while (link.find()) {
            String href = link.group(1);
            if (!isSearchField(href.toLowerCase(Locale.ROOT))) continue;
            String url = AiSite.resolve(base, href);
            if (!AiSite.isHttpUrl(url)) continue;
            return replaceQueryValue(url);
        }
        return "";
    }

    private static String matchAttr(final Pattern pattern, final String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static boolean isSearchField(final String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : SEARCH_NAMES) {
            if (lower.equals(name) || lower.contains(name + "=")) return true;
        }
        return lower.contains("search") && lower.contains("=");
    }

    private static String replaceQueryValue(final String url) {
        int index = url.indexOf('=');
        if (index < 0) return url + (url.indexOf('?') < 0 ? "?" : "&") + "wd={wd}";
        return url.substring(0, index + 1) + "{wd}";
    }

    // ---------------------------------------------------------------- 工具

    private static String fetch(final String url, final int[] budget, final String ua) {
        String body = fetchRaw(url, budget, ua);
        return blocked(body) ? "" : body;
    }

    /** 取原始正文（可能是拦截页），由调用方决定是否当作失败。 */
    private static String fetchRaw(final String url, final int[] budget, final String ua) {
        if (budget[0] <= 0) return "";
        budget[0]--;
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (ua != null && !ua.isEmpty()) headers.put("User-Agent", ua);
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            String body = OkHttp.string(url, headers, TIMEOUT);
            return body == null ? "" : body;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 内容像拦截页/验证页时视为没抓到，交给换 UA 重试。 */
    private static boolean blocked(final String body) {
        if (body == null) return true;
        if (body.trim().isEmpty()) return true;
        if (body.length() > 8192) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String word : BLOCKED_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    /** 外链脚本包，同域的排前面。 */
    private static List<String> scriptUrls(final String html, final String base, final int max) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = SCRIPT_SRC.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String url = AiSite.resolve(base, matcher.group(1).trim());
            if (!AiSite.isHttpUrl(url) || !seen.add(url) || isInlineUrl(url)) continue;
            if (sameHost(url, base)) out.add(0, url);
            else out.add(url);
        }
        return out.subList(0, Math.min(max, out.size()));
    }

    private static boolean isInlineUrl(final String url) {
        return url.startsWith("data:") || url.startsWith("javascript:") || url.startsWith("blob:");
    }

    /** 内联脚本：SPA 常把接口地址与签名逻辑写在这里，不用发请求就能拿到。 */
    private static List<Sample> inlineScripts(final String html, final String base) {
        List<Sample> out = new ArrayList<>();
        if (html == null) return out;
        Matcher matcher = SCRIPT_BODY.matcher(html);
        int index = 0;
        while (matcher.find() && out.size() < 2) {
            String body = matcher.group(1);
            if (body == null) continue;
            body = body.trim();
            if (body.length() < 40 || body.length() > MAX_BODY) continue;
            index++;
            out.add(new Sample(ROLE_SCRIPT, base + "#inline-" + index, body));
        }
        return out;
    }

    private static boolean isMedia(final String body, final String url) {
        if (body == null || body.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) return body.contains("#EXTM3U") || body.contains("#EXTINF");
        return !body.trim().startsWith("<");
    }

    private static String trim(final String body) {
        if (body == null) return "";
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + TRUNCATED;
    }

    private static String textOf(final String html) {
        if (html == null) return "";
        return SPACES.matcher(TAG.matcher(html).replaceAll(" ")).replaceAll(" ").trim();
    }

    private static String encode(final String word) {
        try {
            return URLEncoder.encode(word, "UTF-8");
        } catch (Throwable e) {
            return word;
        }
    }

    private static String pathOf(final String url) {
        try {
            String path = new java.net.URI(url).getPath();
            return path == null ? "" : path;
        } catch (Throwable e) {
            return "";
        }
    }

    private static int segments(final String url) {
        int count = 0;
        for (String part : pathOf(url).split("/")) {
            if (!part.isEmpty()) count++;
        }
        return count;
    }

    private static boolean sameHost(final String a, final String b) {
        String host = AiSite.hostOf(a);
        return !host.isEmpty() && host.equals(AiSite.hostOf(b));
    }
}
