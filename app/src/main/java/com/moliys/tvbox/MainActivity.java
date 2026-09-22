package com.moliys.tvbox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.Callback;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "TVBoxMain";
    private static final int REQ_NOTIFICATION = 0x7100;
    private static final int REQ_FILE_CHOOSER = 0x7101;
    private static final int REQ_STORAGE = 0x7102;
    private static final int NOTIF_READY = 0x7103;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private WebView webView;
    private LocalServer server;
    private FrameLayout rootView;
    private FrameLayout fullHolder;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String videoOrientation = "auto";
    private File pendingApk;
    private boolean awaitingInstallPermission;
    private ValueCallback<Uri[]> fileChooserCallback;
    private int insetTopCss;
    private int insetBottomCss;
    private boolean pageLoaded;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        UpdateManager.cleanupOldFiles(this);
        requestNotificationPermission();
        requestStoragePermission();
        DownloadHelper.ensureDir();

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF0F1115);
        rootView = root;

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        fullHolder = new FrameLayout(this);
        fullHolder.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullHolder.setBackgroundColor(0xFF000000);
        fullHolder.setVisibility(View.GONE);
        root.addView(fullHolder);

        setContentView(root);

        applyEdgeToEdge();
        watchInsets(root);
        configureWebView();

        try {
            loadSitePak();
            server = new LocalServer(getAssets());
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "local server start failed", e);
            Toast.makeText(this, "本地服务启动失败", Toast.LENGTH_LONG).show();
            return;
        }
        webView.loadUrl("http://127.0.0.1:" + server.getPort() + "/index.html");
    }

    /** 若存在加密资源包 assets/site.pak，则启用内存解密读取；否则回退到 assets/html。 */
    private void loadSitePak() {
        if (SiteKeys.SITE_KEY_HEX == null || SiteKeys.SITE_KEY_HEX.length() < 64) {
            return;
        }
        try {
            java.io.InputStream in = getAssets().open("site.pak");
            SitePak pak = SitePak.loadFromAssets(in, SiteKeys.SITE_KEY_HEX);
            if (pak != null) {
                SitePak.setShared(pak);
                Log.i(TAG, "site pak loaded, entries=" + pak.count());
            } else {
                Log.w(TAG, "site pak parse failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "site pak missing: " + e);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    /**
     * Android 10 及以下申请写入「下载」目录的权限；Android 11+ 需要「所有文件访问」
     * 才能自行创建 Download/沫离下载 目录并读取下载好的文件，否则会提示「目录不可用」。
     */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            requestAllFilesAccess();
            return;
        }
        if (Build.VERSION.SDK_INT <= 29) {
            try {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Android 11+：引导用户授予「所有文件访问」权限（每个安装只自动提示一次，之后可在下载页手动授权）。 */
    private void requestAllFilesAccess() {
        try {
            if (Environment.isExternalStorageManager()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        try {
            android.content.SharedPreferences sp = getSharedPreferences("tvbox_prefs", MODE_PRIVATE);
            if (sp.getBoolean("allfiles_prompted", false)) {
                return;
            }
            sp.edit().putBoolean("allfiles_prompted", true).apply();
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable ignored) {
        }
    }

    private int baseUiFlags() {
        if (Build.VERSION.SDK_INT >= 30) {
            return 0;
        }
        return View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    }

    private void applyEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(baseUiFlags());
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        applyBarIcons(false);
    }

    private void applyBarIcons(boolean light) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c == null) {
                return;
            }
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            if (Build.VERSION.SDK_INT >= 26) {
                mask |= android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            }
            c.setSystemBarsAppearance(light ? mask : 0, mask);
            return;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        int f = baseUiFlags();
        if (light) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        w.getDecorView().setSystemUiVisibility(f);
    }

    private void watchInsets(View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                setInsetsPx(top, bottom);
                return insets;
            }
        });
    }

    private void setInsetsPx(int topPx, int bottomPx) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        int top = Math.round(topPx / density);
        int bottom = Math.round(bottomPx / density);
        if (top == insetTopCss && bottom == insetBottomCss) {
            return;
        }
        insetTopCss = top;
        insetBottomCss = bottom;
        if (pageLoaded) {
            pushInsets();
        }
    }

    private void pushInsets() {
        callJs("__tvboxInsets", String.valueOf(insetTopCss), String.valueOf(insetBottomCss));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }
        webView.setBackgroundColor(0xFF0F1115);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                Intent intent = null;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                applyVideoOrientation(true);
                fullHolder.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullHolder.setVisibility(View.VISIBLE);
                fullHolder.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                fullHolder.removeView(customView);
                fullHolder.setVisibility(View.GONE);
                customView = null;
                applyVideoOrientation(false);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        });
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) {
        }
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
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                pushInsets();
            }
        });
        webView.addJavascriptInterface(new Bridge(), "TVBoxNative");
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                DownloadHelper.start(MainActivity.this, url, userAgent, contentDisposition, mimetype);
            }
        });
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    int count = clip.getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private static final String[] IN_APP_HOSTS = {
            "fm365.space", "www.fm365.space"
    };

    private static boolean isInAppHost(String lowerUrl) {
        for (String host : IN_APP_HOSTS) {
            if (lowerUrl.startsWith("https://" + host)
                    || lowerUrl.startsWith("http://" + host)
                    || lowerUrl.startsWith("https://www." + host)
                    || lowerUrl.startsWith("http://www." + host)) {
                return true;
            }
        }
        return false;
    }

    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.endsWith(".apk") || lower.contains(".apk?")) {
            // 页面里的 APK 链接一律当作普通下载保存到沫离下载，不再冒充应用更新；
            // 真正的自更新由版本检查经 TVBoxNative.downloadUpdate 触发。
            DownloadHelper.start(this, url, null, null, null);
            return true;
        }
        if (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")) {
            return false;
        }
        if (isInAppHost(lower)) {
            return false;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            openInApp(url);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
        return true;
    }

    /** 站内所有外部站点一律在应用内浏览与播放，不再跳出到系统浏览器。 */
    private void openInApp(String url) {
        try {
            Intent i = new Intent(this, WebActivity.class);
            i.putExtra(WebActivity.EXTRA_URL, url);
            startActivity(i);
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        }
    }

    private void startUpdate(String url, String sha256, String versionCode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "开始下载更新", Toast.LENGTH_SHORT).show();
            }
        });
        new UpdateManager(this, new UpdateManager.Listener() {
            @Override
            public void onProgress(int pct, long got, long total) {
                callJs("__tvboxOnProgress", pct + "," + got + "," + total);
            }

            @Override
            public void onReady(File apk) {
                pendingApk = apk;
                boolean launched = installOrRequestPermission(apk);
                callJs("__tvboxOnDone", launched ? "true" : "false",
                        quote(launched ? "下载完成，正在打开安装界面" : "请先允许「安装未知应用」，然后点通知栏继续安装"));
            }

            @Override
            public void onError(String message) {
                callJs("__tvboxOnError", quote(message));
            }
        }).start(url, sha256, versionCode);
    }

    private boolean allowedToInstall() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return getPackageManager().canRequestPackageInstalls();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private Intent installerIntent(File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apkUri(apk), APK_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** 更新包可能落在沫离下载目录或应用私有目录，按位置选择对应的 content provider。 */
    private Uri apkUri(File apk) {
        return DownloadHelper.uriForApk(this, apk);
    }

    private void gotoUnknownSourcesSettings() {
        try {
            Intent s = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            s.setData(Uri.parse("package:" + getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(s);
        } catch (Throwable ignored) {
        }
    }

    private void postReadyNotification(File apk) {
        try {
            Intent install = installerIntent(apk);
            PendingIntent pi = PendingIntent.getActivity(this, 0, install,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = UpdateManager.builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("更新包已下载完成")
                    .setContentText("点击安装")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            UpdateManager.notifyId(this, NOTIF_READY, b.build());
        } catch (Throwable ignored) {
        }
    }

    private boolean installOrRequestPermission(File apk) {
        postReadyNotification(apk);
        if (!allowedToInstall()) {
            awaitingInstallPermission = true;
            gotoUnknownSourcesSettings();
            return false;
        }
        awaitingInstallPermission = false;
        try {
            startActivity(installerIntent(apk));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingInstallPermission && pendingApk != null && pendingApk.exists() && allowedToInstall()) {
            awaitingInstallPermission = false;
            try {
                startActivity(installerIntent(pendingApk));
            } catch (Throwable ignored) {
            }
        }
    }

    private void callJs(final String fn, final String... args) {
        if (webView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("javascript:if(typeof ");
        sb.append(fn).append("==='function'){window.").append(fn).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(args[i]);
        }
        sb.append(");}");
        final String script = sb.toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(script, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void applyVideoOrientation(boolean entering) {
        int o;
        if (entering && "portrait".equals(videoOrientation)) {
            o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        } else if (entering && "landscape".equals(videoOrientation)) {
            o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else {
            o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        setRequestedOrientation(o);
    }

    private static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ") + "'";
    }

    /** 影视面板：进入首页 / 切换站源 / 自定义接口。 */
    private void showVideoPanel() {
        String[] items = {"进入影视首页", "切换站源", "输入/更换接口"};
        new AlertDialog.Builder(this)
                .setTitle("影视")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) openVideoHome();
                        else if (which == 1) loadThenShowSites();
                        else showInterfaceDialog();
                    }
                })
                .show();
    }

    /** 确保接口已加载，然后直接进入影视首页。 */
    private void enterVideo() {
        final Config cfg = Config.vod();
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().length() == 0) {
            showInterfaceDialog();
            return;
        }
        final List<Site> sites = VodConfig.get().getSites();
        if (sites != null && !sites.isEmpty()) {
            openVideoHome();
            return;
        }
        VodConfig.load(cfg, new Callback() {
            @Override
            public void success() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        openVideoHome();
                    }
                });
            }

            @Override
            public void error(String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "接口加载失败，请检查地址", Toast.LENGTH_SHORT).show();
                        showInterfaceDialog();
                    }
                });
            }
        });
    }

    /** 确保当前接口已加载，然后弹出站源列表。 */
    private void loadThenShowSites() {
        final Config cfg = Config.vod();
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().length() == 0) {
            showInterfaceDialog();
            return;
        }
        VodConfig.load(cfg, new Callback() {
            @Override
            public void success() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showSiteDialog();
                    }
                });
            }

            @Override
            public void error(String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "接口加载失败，请检查地址", Toast.LENGTH_SHORT).show();
                        showInterfaceDialog();
                    }
                });
            }
        });
    }

    /** 站源切换弹窗。 */
    private void showSiteDialog() {
        final List<Site> sites = VodConfig.get().getSites();
        if (sites == null || sites.isEmpty()) {
            showInterfaceDialog();
            return;
        }
        final String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            names[i] = sites.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择站源")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        VodConfig.get().setHome(sites.get(which));
                        Toast.makeText(MainActivity.this, "已切换：" + names[which], Toast.LENGTH_SHORT).show();
                        openVideoHome();
                    }
                })
                .setNegativeButton("输入接口", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showInterfaceDialog();
                    }
                })
                .show();
    }

    /** 自定义接口输入弹窗（不内置任何接口）。 */
    private void showInterfaceDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/config.json");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        Config cur = Config.vod();
        if (cur != null && cur.getUrl() != null) {
            input.setText(cur.getUrl());
        }
        new AlertDialog.Builder(this)
                .setTitle("输入接口地址")
                .setView(input)
                .setPositiveButton("加载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (url.length() == 0) {
                            return;
                        }
                        loadInterface(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadInterface(final String url) {
        final Config cfg = Config.create(0, url, "");
        VodConfig.load(cfg, new Callback() {
            @Override
            public void success() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        openVideoHome();
                    }
                });
            }

            @Override
            public void error(String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "接口加载失败，请检查地址", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** 打开内置影视（webhtv 内核）首页。 */
    private void openVideoHome() {
        startFongmi("com.fongmi.android.tv.ui.activity.HomeActivity", null);
    }

    /** 打开内置直播。 */
    private void openVideoLive() {
        startFongmi("com.fongmi.android.tv.ui.activity.LiveActivity", null);
    }

    /** 以 ACTION_VIEW 交给 webhtv 内核导入接口（单仓/多仓 JSON 地址）。 */
    private void openVideoInterface(final String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "导入接口失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void startFongmi(final String cls, final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(getPackageName(), cls);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (url != null && url.length() > 0) {
                        intent.setData(Uri.parse(url));
                    }
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "打开影视失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public String getVersion() {
            int code = BuildConfig.LITE_EDITION ? Integer.MAX_VALUE : Version.CODE;
            return "{\"code\":" + code + ",\"name\":\"" + Version.NAME + "\"}";
        }

        @JavascriptInterface
        public void setTheme(final boolean light) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyBarIcons(light);
                }
            });
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if ("portrait".equals(mode)) {
                        videoOrientation = "portrait";
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    } else if ("landscape".equals(mode)) {
                        videoOrientation = "landscape";
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    } else {
                        videoOrientation = "auto";
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                }
            });
        }

        @JavascriptInterface
        public void downloadUpdate(final String url, final String sha256, final String versionCode) {
            if (url == null || url.length() == 0) {
                return;
            }
            startUpdate(url, sha256, versionCode);
        }

        @JavascriptInterface
        public void installPending() {
            final File apk = UpdateManager.newestApk(MainActivity.this);
            if (apk == null || !apk.exists()) {
                return;
            }
            pendingApk = apk;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    installOrRequestPermission(apk);
                }
            });
        }

        @JavascriptInterface
        public void toast(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openDownloadDir() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    DownloadHelper.openDir(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public String getDownloadDir() {
            return DownloadHelper.dirPath();
        }

        @JavascriptInterface
        public void openVideo() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    enterVideo();
                }
            });
        }

        @JavascriptInterface
        public void openSites() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    loadThenShowSites();
                }
            });
        }

        @JavascriptInterface
        public void openLive() {
            openVideoLive();
        }

        @JavascriptInterface
        public void openInterface(final String url) {
            openVideoInterface(url);
        }

        @JavascriptInterface
        public String readAsset(String path) {
            if (path == null || path.contains("..")) {
                return "";
            }
            try {
                SitePak pak = SitePak.getShared();
                if (pak != null) {
                    byte[] data = pak.read(path);
                    if (data != null) {
                        return new String(data, "UTF-8");
                    }
                    return "";
                }
                java.io.InputStream in = getAssets().open("html/" + path);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(16 * 1024);
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
                in.close();
                return new String(buf.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.stop();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("TVBoxNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
