package com.moliys.tvbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 应用内浏览页：站内「在线影视」等外部站点全部在本页打开，播放同样在本页完成，不再跳出到系统浏览器。
 */
public class WebActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int BAR_BG = 0xFF1B2230;

    /** 弹幕服务地址（自建于服务器 A） */
    private static final String DANMU_SERVER = "http://8.130.134.173:5757";
    /** 可选远程配置，改颜色/渐变/速度无需重新打包；取不到就用脚本内置默认值 */
    private static final String DANMU_CONFIG_URL = "https://tvbox.moliys.icu/apk/danmu-config.json";
    private static final String TAG = "tvbox-danmu";
    /** 内置播放器页（离线资源，hls.js + 弹幕脚本） */
    private static final String PLAYER_PAGE = "file:///android_asset/player.html";

    private WebView webView;
    private WebChromeClient chrome;
    private TextView titleView;
    private TextView orientView;
    private LinearLayout topBar;
    private ProgressBar progress;
    private FrameLayout stage;
    private FrameLayout fullHolder;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean fullscreen;
    private volatile boolean landscape;
    private String remoteConfig;
    private boolean configTried;
    /** 页面里出现过的视频流地址（含子框架），供弹幕脚本诊断/兜底 */
    private final java.util.LinkedHashSet<String> streams = new java.util.LinkedHashSet<String>();
    /** 内置播放器待播放的地址与标题 */
    private volatile String pendingPlayerUrl;
    private volatile String pendingPlayerTitle;
    private volatile String pendingPlayerPref;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BAR_BG);
        if (Build.VERSION.SDK_INT >= 23) {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        Intent it = getIntent();
        final String url = it == null ? null : it.getStringExtra(EXTRA_URL);
        String title = it == null ? null : it.getStringExtra(EXTRA_TITLE);
        if (url == null || url.length() == 0) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(BAR_BG);
        topBar.setPadding(dp(8), 0, dp(8), 0);

        TextView backView = barButton("返回");
        backView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(backView);

        titleView = new TextView(this);
        titleView.setText(title == null || title.length() == 0 ? url : title);
        titleView.setTextColor(0xFFE8EEF6);
        titleView.setTextSize(14f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(dp(8), 0, dp(8), 0);
        topBar.addView(titleView, tp);

        orientView = barButton("横屏");
        orientView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyLandscape(!landscape);
            }
        });
        topBar.addView(orientView);

        TextView refreshView = barButton("刷新");
        refreshView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null) {
                    webView.reload();
                }
            }
        });
        topBar.addView(refreshView);

        TextView outView = barButton("浏览器");
        outView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openExternal(webView == null || webView.getUrl() == null ? url : webView.getUrl());
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.setMargins(dp(6), 0, 0, 0);
        topBar.addView(outView, op);

        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        stage.addView(progress, plp);

        webView = new WebView(this);
        stage.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullHolder = new FrameLayout(this);
        fullHolder.setBackgroundColor(Color.BLACK);
        fullHolder.setVisibility(View.GONE);
        root.addView(fullHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        configureWebView();
        webView.loadUrl(url);
    }

    private TextView barButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFE8EEF6);
        t.setTextSize(14f);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        t.setBackgroundColor(0x22FFFFFF);
        return t;
    }

    /** 横屏/竖屏切换：锁方向 + 隐藏系统栏，播放时体验更好 */
    private void applyLandscape(boolean b) {
        landscape = b;
        try {
            setRequestedOrientation(b
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Throwable ignored) {
        }
        if (orientView != null) {
            orientView.setText(b ? "竖屏" : "横屏");
        }
        applyImmersive(b);
    }

    private void applyImmersive(boolean b) {
        try {
            View d = getWindow().getDecorView();
            if (b) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                getWindow().setStatusBarColor(BAR_BG);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(UA);
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }
        // 内置播放器页是 file:///android_asset/player.html，hls.js 需要从 file 源发起 XHR 拉流
        try {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {
        }
        webView.setBackgroundColor(Color.BLACK);
        webView.setHorizontalScrollBarEnabled(false);
        try {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) {
        }
        webView.addJavascriptInterface(new DanmuBridge(), "TVDanmu");
        fetchRemoteConfig();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return false;
                }
                if (!request.isForMainFrame()) {
                    return false;
                }
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    sniffStream(request.getUrl().toString());
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CharSequence t = view.getTitle();
                if (t != null && t.length() > 0 && titleView != null) {
                    titleView.setText(t);
                }
                if (url == null || !url.startsWith(PLAYER_PAGE)) {
                    injectDanmu(view);
                }
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                String target = null;
                WebView.HitTestResult hit = view.getHitTestResult();
                if (hit != null) {
                    target = hit.getExtra();
                }
                if (target != null && isHttp(target)) {
                    webView.loadUrl(target);
                    return false;
                }
                final WebView relay = new WebView(WebActivity.this);
                relay.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        if (req != null && req.getUrl() != null) {
                            webView.loadUrl(req.getUrl().toString());
                        }
                        v.destroy();
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String u) {
                        if (u != null) {
                            webView.loadUrl(u);
                        }
                        v.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(relay);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                fullscreen = true;
                applyLandscape(true);
                topBar.setVisibility(View.GONE);
                stage.setVisibility(View.GONE);
                fullHolder.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullHolder.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                fullHolder.removeView(customView);
                fullHolder.setVisibility(View.GONE);
                customView = null;
                fullscreen = false;
                applyLandscape(false);
                stage.setVisibility(View.VISIBLE);
                topBar.setVisibility(View.VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        };
        webView.setWebChromeClient(chrome);

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                DownloadHelper.start(WebActivity.this, url, userAgent, contentDisposition, mimetype);
            }
        });
    }

    private static boolean isHttp(String url) {
        String l = url.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /* ---------------- 站点直连解析（跳过站内自带播放器） ---------------- */

    /**
     * 从站点页面源码里取出真实播放地址：
     * 播放页 player_aaaa.url -> 第三方解析页里的 m3u8 直链。
     * 详情页则先取第一个剧集链接再走同一条路。
     */
    private String resolveDirect(String pageUrl) throws Exception {
        String referer = origin(pageUrl) + "/";
        String html = httpGet(pageUrl, 20000, referer);
        if (html == null) {
            throw new IllegalStateException("页面打不开");
        }
        String parser = playerAaaaUrl(html);
        if (parser.length() == 0) {
            String ep = firstEpisodeUrl(html, pageUrl);
            if (ep == null) {
                return "";
            }
            Log.i(TAG, "direct episode -> " + ep);
            html = httpGet(ep, 20000, referer);
            if (html == null) {
                throw new IllegalStateException("剧集页打不开");
            }
            parser = playerAaaaUrl(html);
        }
        if (parser.length() == 0) {
            return "";
        }
        if (parser.startsWith("//")) {
            parser = "https:" + parser;
        } else if (parser.startsWith("/")) {
            parser = origin(pageUrl) + parser;
        }
        // 有的源 player_aaaa.url 本身就是直链
        if (parser.contains(".m3u8")) {
            Log.i(TAG, "direct m3u8 -> " + parser);
            return parser;
        }
        Log.i(TAG, "direct parser -> " + parser);
        String ph = httpGet(parser, 20000, referer);
        if (ph == null || ph.length() < 10) {
            throw new IllegalStateException("解析页无内容");
        }
        String out = m3u8In(ph, parser);
        if (out.length() == 0) {
            throw new IllegalStateException("解析页里没有播放地址");
        }
        return out;
    }

    private static String origin(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 播放页里的 player_aaaa.url；encrypt 非 0 时无法直接取用 */
    private String playerAaaaUrl(String html) {
        int i = html.indexOf("player_aaaa=");
        if (i < 0) {
            return "";
        }
        int s = html.indexOf('{', i);
        if (s < 0) {
            return "";
        }
        int depth = 0;
        int e = -1;
        for (int p = s; p < html.length(); p++) {
            char c = html.charAt(p);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    e = p + 1;
                    break;
                }
            }
        }
        if (e < 0) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(html.substring(s, e));
            if (o.optInt("encrypt", 0) != 0) {
                Log.w(TAG, "player_aaaa encrypted, skip");
                return "";
            }
            return o.optString("url", "");
        } catch (Throwable t) {
            Log.w(TAG, "player_aaaa parse failed: " + t);
            return "";
        }
    }

    /** 详情页里第一个剧集链接（用于站点还没进入播放页的情况） */
    private String firstEpisodeUrl(String html, String base) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("href=\"(/(?:tv|movie|dongman|zongyi|duanju|vod)/[^\"'#?\\s]+/[0-9]+[^\"'#\\s]*)\"")
                    .matcher(html);
            if (m.find()) {
                return new URL(new URL(base), m.group(1)).toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 解析页源码里的 m3u8（优先取 url = "..." 赋值） */
    private String m3u8In(String html, String base) {
        String[] pats = {
                "(?:const|var|let)\\s+url\\s*=\\s*[\"']([^\"']+?\\.m3u8[^\"']*)[\"']",
                "[\"']([^\"']*?/[^\"']*?\\.m3u8[^\"']*)[\"']"
        };
        for (int p = 0; p < pats.length; p++) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pats[p]).matcher(html);
                while (m.find()) {
                    String s = m.group(1);
                    if (s == null || s.length() == 0 || s.startsWith("data:") || s.contains("${")) {
                        continue;
                    }
                    return new URL(new URL(base), s).toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        if (isHttp(url)) {
            return false;
        }
        openExternal(url);
        return true;
    }

    private void openExternal(String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 弹幕 ---------------- */

    private void injectDanmu(WebView view) {
        String js = readAsset("danmu.js");
        if (js == null) {
            Log.w(TAG, "danmu.js missing in assets");
            return;
        }
        try {
            String boot = "try{window.__tvDanmuConfig="
                    + (remoteConfig == null ? "null" : remoteConfig) + "}catch(e){}";
            view.evaluateJavascript(boot, null);
            view.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "inject failed: " + t);
        }
    }

    private void fetchRemoteConfig() {
        if (configTried) {
            return;
        }
        configTried = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = httpGet(DANMU_CONFIG_URL, 8000);
                    if (body == null || body.length() < 2) {
                        return;
                    }
                    String json = new JSONObject(body).toString();
                    remoteConfig = json;
                    final String script = "try{window.__tvDanmuSetConfig&&window.__tvDanmuSetConfig("
                            + json + ")}catch(e){}";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript(script, null);
                            }
                        }
                    });
                    Log.i(TAG, "remote config loaded");
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String httpGet(String url, int readTimeout) throws Exception {
        return httpGet(url, readTimeout, null);
    }

    private String httpGet(String url, int readTimeout, String referer) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(readTimeout);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (referer != null) {
                c.setRequestProperty("Referer", referer);
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            if (in == null) {
                throw new IllegalStateException("HTTP " + code);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            if (code < 200 || code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void sniffStream(String url) {
        String l = url.toLowerCase();
        if (!(l.contains(".m3u8") || l.contains(".mp4") || l.contains(".flv") || l.contains(".mpd"))) {
            return;
        }
        boolean added = false;
        synchronized (streams) {
            if (!streams.contains(url)) {
                streams.add(url);
                added = true;
                while (streams.size() > 12) {
                    java.util.Iterator<String> it = streams.iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
        if (added) {
            Log.i(TAG, "stream: " + url);
        }
    }

    private JSONArray fetchCandidates(String name, int episode) throws Exception {
        String url = DANMU_SERVER + "/api/v2/fongmi/danmaku?name="
                + URLEncoder.encode(name, "UTF-8") + "&episode=" + episode;
        String body = httpGet(url, 20000);
        if (body == null) {
            return null;
        }
        String s = body.trim();
        if (s.length() == 0 || s.charAt(0) != '[') {
            return null;
        }
        return new JSONArray(s);
    }

    private JSONObject chooseCandidate(JSONArray cands, int episode) {
        if (cands == null || cands.length() == 0) {
            return null;
        }
        if (episode > 0) {
            String[] pats = {"第" + episode + "集", "第" + episode + "话", "第" + episode + "期",
                    "第" + episode + "部", "第0" + episode + "集"};
            for (int i = 0; i < cands.length(); i++) {
                JSONObject o = cands.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String nm = o.optString("name", "");
                for (int p = 0; p < pats.length; p++) {
                    if (nm.contains(pats[p])) {
                        return o;
                    }
                }
            }
        }
        return cands.optJSONObject(0);
    }

    private JSONArray fetchComments(String url) throws Exception {
        String u = url;
        if (u.contains("format=")) {
            u = u.replaceAll("format=[a-zA-Z]+", "format=json");
        } else {
            u = u + (u.contains("?") ? "&" : "?") + "format=json";
        }
        String body = httpGet(u, 30000);
        if (body == null) {
            throw new IllegalStateException("弹幕内容接口无响应");
        }
        JSONObject o = new JSONObject(body);
        JSONArray src = o.optJSONArray("comments");
        JSONArray out = new JSONArray();
        if (src == null) {
            return out;
        }
        for (int i = 0; i < src.length(); i++) {
            JSONObject c = src.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String m = c.optString("m", "");
            if (m.length() == 0) {
                continue;
            }
            String p = c.optString("p", "");
            String[] parts = p.split(",");
            double t = 0;
            int mode = 1;
            int color = 16777215;
            try {
                if (parts.length > 0) {
                    t = Double.parseDouble(parts[0].trim());
                }
            } catch (Throwable ignored) {
            }
            try {
                if (parts.length > 1) {
                    mode = Integer.parseInt(parts[1].trim());
                }
            } catch (Throwable ignored) {
            }
            try {
                if (parts.length > 2) {
                    color = Integer.parseInt(parts[2].trim());
                }
            } catch (Throwable ignored) {
            }
            JSONArray row = new JSONArray();
            row.put(t);
            row.put(mode);
            row.put(color);
            row.put(m);
            out.put(row);
        }
        return out;
    }

    /** 直连解析失败时通知页面，让页面回退到已嗅探到的地址 */
    private void directFailed(final String msg) {
        final String script = "try{window.__tvDanmuDirectFail&&window.__tvDanmuDirectFail("
                + JSONObject.quote(msg == null ? "" : msg) + ")}catch(e){}";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        webView.evaluateJavascript(script, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private void deliverDanmu(final String cb, final JSONObject payload) {        final String script = "try{window.__tvDanmuData("
                + JSONObject.quote(cb) + "," + payload.toString() + ")}catch(e){}";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        webView.evaluateJavascript(script, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 供注入脚本调用的原生桥：取弹幕 + 提示 + 日志。只允许访问固定的弹幕服务。 */
    public class DanmuBridge {
        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, String.valueOf(message));
        }

        @JavascriptInterface
        public void toast(final String message) {
            if (message == null || message.length() == 0) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(WebActivity.this, message, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public String streams() {
            JSONArray a = new JSONArray();
            synchronized (streams) {
                for (String s : streams) {
                    a.put(s);
                }
            }
            return a.toString();
        }

        /** 内置播放器页读取：待播放地址 */
        @JavascriptInterface
        public String getPlayerUrl() {
            String u = pendingPlayerUrl;
            return u == null ? "" : u;
        }

        /** 内置播放器页读取：标题（用于弹幕自动匹配） */
        @JavascriptInterface
        public String getTitle() {
            String t = pendingPlayerTitle;
            return t == null ? "" : t;
        }

        /** 用内置播放器（hls.js + 弹幕）播放捕获到的流 */
        @JavascriptInterface
        public void play(final String url, final String title) {
            if (url == null || url.length() == 0 || !isHttp(url)) {
                return;
            }
            pendingPlayerUrl = url;
            final String t = title == null ? "" : title;
            pendingPlayerTitle = t;
            Log.i(TAG, "play builtin -> " + url);
            // 地址与标题同时挂在 URL 上：桥调用即使失败（老 WebView / 参数不匹配）也能播
            String page = PLAYER_PAGE + "?u=" + enc(url) + "&t=" + enc(t);
            String pref = pendingPlayerPref;
            if (pref != null && pref.length() > 0) {
                // 站点页与播放器页不同源，localStorage 不互通，弹幕设置只能随 URL 带过去
                page = page + "&p=" + enc(pref);
            }
            final String pageToLoad = page;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webView != null) {
                        webView.loadUrl(pageToLoad);
                    }
                }
            });
        }

        /** 站点页把当前弹幕设置交给原生，再由原生拼进播放器页 URL */
        @JavascriptInterface
        public void savePref(final String json) {
            pendingPlayerPref = (json == null || json.length() > 4000) ? null : json;
        }

        /** 站点直连：从页面源码取真实 m3u8 后直接用内置播放器播（跳过站内播放器） */
        @JavascriptInterface
        public void direct(final String pageUrl, final String title) {
            if (pageUrl == null || pageUrl.length() == 0 || !isHttp(pageUrl)) {
                return;
            }
            final String t = title == null ? "" : title;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String u = resolveDirect(pageUrl);
                        if (u != null && u.length() > 0) {
                            Log.i(TAG, "direct ok -> " + u);
                            play(u, t);
                            toast("解析成功，正在播放");
                        } else {
                            Log.w(TAG, "direct empty");
                            toast("解析失败：没找到播放地址");
                            directFailed("没找到播放地址");
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "direct failed: " + e);
                        toast("解析失败：" + e.getMessage());
                        directFailed(String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        /** 页面控制横屏：'1'/'true' 横屏，其余竖屏 */
        @JavascriptInterface
        public void setLandscape(final String v) {
            final boolean b = "1".equals(v) || "true".equalsIgnoreCase(v);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyLandscape(b);
                }
            });
        }

        @JavascriptInterface
        public boolean isLandscape() {
            return landscape;
        }

        @JavascriptInterface
        public void load(final String args) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String cb = "0";
                    JSONObject payload = new JSONObject();
                    StringBuilder tried = new StringBuilder();
                    try {
                        JSONObject a = new JSONObject(args == null ? "{}" : args);
                        cb = a.optString("cb", "0");
                        int episode = a.optInt("episode", 1);
                        JSONArray names = a.optJSONArray("names");
                        String picked = null;
                        String commentsUrl = null;
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                String nm = names.optString(i, "");
                                if (nm.length() == 0) {
                                    continue;
                                }
                                if (tried.length() > 0) {
                                    tried.append('/');
                                }
                                tried.append(nm);
                                JSONArray cands = fetchCandidates(nm, episode);
                                JSONObject pick = chooseCandidate(cands, episode);
                                if (pick == null) {
                                    continue;
                                }
                                picked = pick.optString("name", "");
                                commentsUrl = pick.optString("url", "");
                                break;
                            }
                        }
                        if (commentsUrl == null || commentsUrl.length() == 0) {
                            payload.put("ok", false);
                            payload.put("msg", "没有匹配到弹幕（试过：" + tried + "）");
                        } else {
                            JSONArray c = fetchComments(commentsUrl);
                            payload.put("ok", true);
                            payload.put("count", c.length());
                            payload.put("pick", picked);
                            payload.put("tried", tried.toString());
                            payload.put("c", c);
                        }
                    } catch (Throwable t) {
                        try {
                            payload.put("ok", false);
                            payload.put("msg", t.getClass().getSimpleName() + ": " + t.getMessage());
                        } catch (Throwable ignored) {
                        }
                    }
                    deliverDanmu(cb, payload);
                }
            }).start();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullscreen) {
                chrome.onHideCustomView();
                return true;
            }
            if (landscape) {
                applyLandscape(false);
                return true;
            }
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.setWebChromeClient(null);
            stage.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
