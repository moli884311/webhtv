package com.moliys.tvbox;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 站内统一下载：自建下载线程，落到「下载/沫离下载」。
 * 通知栏只显示文件名与进度；下载完成后按类型处理：
 * 安装包直接拉起安装，其它文件提示保存目录并允许一键打开目录。
 */
public final class DownloadHelper {

    static {
        // 部分网络下 Java 会优先走不可用的 IPv6 导致连接超时，强制 IPv4 更稳
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }
    }

    /** 下载子目录名（位于系统「下载」目录下）。 */
    public static final String DIR_NAME = "沫离下载";
    private static final String CHANNEL_ID = "tvbox_download";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int NOTIF_BASE = 0x8300;
    private static final int MAX_ATTEMPTS = 3;
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DownloadHelper() {
    }

    /** 下载目录（不保证已创建）。 */
    public static File dir() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DIR_NAME);
    }

    /** 下载目录绝对路径，用于界面提示。 */
    public static String dirPath() {
        return dir().getAbsolutePath();
    }

    /** 确保下载目录存在，返回该目录。 */
    public static File ensureDir() {
        File d = dir();
        try {
            if (!d.exists()) {
                d.mkdirs();
            }
        } catch (Throwable ignored) {
        }
        return d;
    }

    /** Android 11+ 需要「所有文件访问」才能自行创建目录并读取下载的文件。 */
    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** 是否具备写入公共「下载」目录的权限（决定文件能否落在沫离下载）。 */
    public static boolean canWritePublic(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
            return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文件是否位于沫离下载目录内。 */
    public static boolean isInDir(File f) {
        if (f == null) {
            return false;
        }
        try {
            return f.getAbsolutePath().startsWith(dir().getAbsolutePath() + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目录内的文件，按修改时间倒序（最新在前）。 */
    public static List<File> listFiles() {
        List<File> out = new ArrayList<File>();
        File[] arr = null;
        try {
            arr = dir().listFiles();
        } catch (Throwable ignored) {
        }
        if (arr == null) {
            return out;
        }
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : arr) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                out.add(f);
            }
        }
        return out;
    }

    /** 按扩展名推断 MIME，供打开文件用。 */
    public static String mimeOf(String name) {
        if (name == null) {
            return "*/*";
        }
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return APK_MIME;
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".m3u")) return "text/plain";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        return "*/*";
    }

    /** 打开文件对应的 content://（按下落目录选择 provider），供安装器/第三方应用读取。 */
    public static Uri uriForApk(Context ctx, File apk) {
        if (isInDir(apk)) {
            return DownloadProvider.uriFor(ctx, apk);
        }
        return ApkProvider.uriFor(apk);
    }

    /** 开始下载；返回结果通过通知栏与 Toast 反馈。 */
    public static void start(final Context ctx, final String url, final String userAgent,
                             final String contentDisposition, final String mimeType) {
        if (ctx == null || url == null || url.length() == 0) {
            return;
        }
        final int notifId = NOTIF_BASE + (SEQ.incrementAndGet() & 0x3FF);
        if (!canWritePublic(ctx)) {
            // 没有公共存储权限时退回系统下载服务（仍可写入公共下载目录）
            systemDownload(ctx, url, userAgent, contentDisposition, mimeType);
            return;
        }
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                work(app, ctx, url, userAgent, contentDisposition, mimeType, notifId);
            }
        });
        t.setDaemon(true);
        t.setName("tvbox-download");
        t.start();
    }

    /** 无存储权限时的兜底：交给系统下载管理器。 */
    private static void systemDownload(Context ctx, String url, String userAgent,
                                       String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                throw new IllegalStateException("系统下载服务不可用");
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (userAgent != null && userAgent.length() > 0) {
                req.addRequestHeader("User-Agent", userAgent);
            }
            if (mimeType != null && mimeType.length() > 0) {
                req.setMimeType(mimeType);
            }
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, DIR_NAME + "/" + fileName);
            dm.enqueue(req);
            toast(ctx, "开始下载：" + fileName);
        } catch (Throwable t) {
            String m = t.getMessage();
            toast(ctx, "下载失败：" + (m == null ? "未知错误" : m));
        }
    }

    private static void work(Context app, Context uiCtx, String url, String userAgent,
                             String hintDisposition, String hintMime, int notifId) {
        Throwable last = null;
        String name = null;
        boolean started = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            File part = null;
            try {
                conn = open(url, userAgent);
                String disposition = conn.getHeaderField("Content-Disposition");
                if (disposition == null || disposition.length() == 0) {
                    disposition = hintDisposition;
                }
                String ctype = conn.getContentType();
                if (ctype == null || ctype.length() == 0) {
                    ctype = hintMime;
                }
                int total = conn.getContentLength();
                File dir = ensureDir();
                name = uniqueName(dir, resolveName(url, disposition, ctype));
                if (!started) {
                    started = true;
                    ensureChannel(app);
                    postStart(uiCtx, name);
                }

                part = new File(dir, name + ".part");
                InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                FileOutputStream fos = new FileOutputStream(part);
                try {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int lastPct = -1;
                    long lastPost = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        long now = System.currentTimeMillis();
                        if (pct != lastPct || now - lastPost > 500) {
                            lastPct = pct;
                            lastPost = now;
                            postProgress(app, name, notifId, pct, got, total);
                        }
                    }
                } finally {
                    try {
                        fos.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                File out = new File(dir, name);
                if (out.exists()) {
                    out.delete();
                }
                if (!part.renameTo(out)) {
                    throw new IOException("无法保存文件");
                }
                onFinished(app, uiCtx, out, notifId);
                return;
            } catch (Throwable e) {
                last = e;
                if (part != null && part.exists()) {
                    part.delete();
                }
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(800L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        String msg = last == null ? "下载失败" : last.getClass().getSimpleName()
                + (last.getMessage() == null ? "" : (": " + last.getMessage()));
        if (last instanceof UnknownHostException) {
            msg = "网络无法解析下载地址";
        } else if (last instanceof SocketTimeoutException) {
            msg = "连接超时，请检查网络后重试";
        } else if (last instanceof ConnectException || last instanceof SocketException) {
            msg = "网络连接失败，请检查网络后重试";
        }
        onFailed(app, uiCtx, name, notifId, msg);
    }

    private static HttpURLConnection open(String url, String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        String ua = (userAgent != null && userAgent.length() > 0)
                ? userAgent
                : "Mozilla/5.0 (Linux; Android) TVBox/" + Version.NAME;
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IOException("HTTP " + code);
        }
        return conn;
    }

    private static void onFinished(Context app, Context uiCtx, File out, int notifId) {
        cancel(app, notifId);
        if (looksLikeApk(app, out)) {
            if (!installApk(uiCtx, out)) {
                toast(uiCtx, "安装包已保存到 " + DIR_NAME + "：" + out.getName());
            }
            return;
        }
        notifyDone(app, out, notifId);
        toast(uiCtx, "已保存到 " + DIR_NAME + "：" + out.getName());
    }

    private static boolean installApk(Context ctx, File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uriForApk(ctx, apk), APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否是安装包：按扩展名或能被系统识别为 APK 判断。 */
    private static boolean looksLikeApk(Context ctx, File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".apk")) {
            return true;
        }
        try {
            return ctx.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从响应头/URL 解析真实文件名（优先 filename*，并修正中文乱码）。 */
    private static String resolveName(String url, String disposition, String mime) {
        String name = filenameFromDisposition(disposition);
        if (name == null || name.trim().length() == 0) {
            try {
                name = URLUtil.guessFileName(url, disposition, mime);
            } catch (Throwable ignored) {
            }
        }
        if (name == null || name.trim().length() == 0 || "downloadfile".equals(name)) {
            name = "file_" + System.currentTimeMillis();
        }
        name = name.replace('\\', '_').replace('/', '_').replace('\n', '_').replace('\r', '_').trim();
        if (name.length() == 0) {
            name = "file_" + System.currentTimeMillis();
        }
        if (name.indexOf('.') < 0) {
            String ext = null;
            try {
                ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            } catch (Throwable ignored) {
            }
            if (ext != null && ext.length() > 0) {
                name = name + "." + ext;
            }
        }
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        return name;
    }

    /** 解析 Content-Disposition，优先 RFC5987 的 filename*，其次 filename。 */
    private static String filenameFromDisposition(String cd) {
        if (cd == null || cd.length() == 0) {
            return null;
        }
        int star = indexOfIgnoreCase(cd, "filename*=");
        if (star >= 0) {
            String raw = paramValue(cd.substring(star + 10));
            int sep = raw.indexOf("''");
            if (sep >= 0) {
                String charset = raw.substring(0, sep).trim();
                String value = raw.substring(sep + 2);
                try {
                    return URLDecoder.decode(value,
                            charset.length() == 0 ? "UTF-8" : charset);
                } catch (UnsupportedEncodingException ignored) {
                } catch (Throwable ignored) {
                }
            }
        }
        int plain = indexOfIgnoreCase(cd, "filename=");
        if (plain >= 0) {
            return fixMojibake(paramValue(cd.substring(plain + 9)));
        }
        return null;
    }

    /** 取参数值：带引号取到闭合引号，否则取到分号。 */
    private static String paramValue(String s) {
        String v = s.trim();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            if (end > 0) {
                return v.substring(1, end);
            }
            return v.substring(1);
        }
        int semi = v.indexOf(';');
        if (semi >= 0) {
            v = v.substring(0, semi);
        }
        return v.trim();
    }

    /** 响应头按 Latin-1 解析导致中文变乱码时，按原始字节还原为 UTF-8。 */
    private static String fixMojibake(String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        try {
            String fixed = new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (fixed.indexOf('\uFFFD') < 0) {
                return fixed;
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.US).indexOf(needle.toLowerCase(Locale.US));
    }

    private static String uniqueName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return name;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(ctx, CHANNEL_ID);
        }
        return new Notification.Builder(ctx);
    }

    private static void postStart(Context ctx, final String name) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                toast(ctx, "开始下载：" + name);
            }
        });
    }

    private static void postProgress(Context ctx, String name, int id, int pct, long got, long total) {
        try {
            ensureChannel(ctx);
            Notification.Builder b = builder(ctx)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(name)
                    .setContentText(total > 0
                            ? (pct + "%  " + human(got) + " / " + human(total))
                            : human(got))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            if (total > 0) {
                b.setProgress(100, pct, false);
            } else {
                b.setProgress(0, 0, true);
            }
            notify(ctx, id, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static void notifyDone(Context ctx, File out, int id) {
        try {
            ensureChannel(ctx);
            Intent open = new Intent(ctx, DownloadActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = builder(ctx)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(out.getName())
                    .setContentText("下载完成 · 点击查看目录")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            notify(ctx, id, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static void onFailed(Context app, Context uiCtx, String name, int id, final String message) {
        cancel(app, id);
        try {
            ensureChannel(app);
            Notification.Builder b = builder(app)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(name == null ? "下载失败" : name)
                    .setContentText(message)
                    .setAutoCancel(true);
            notify(app, id, b.build());
        } catch (Throwable ignored) {
        }
        toast(uiCtx, "下载失败：" + message);
    }

    private static void notify(Context ctx, int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(id, n);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancel(Context ctx, int id) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(id);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void toast(final Context ctx, final String msg) {
        if (ctx == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String human(long n) {
        if (n >= 1048576L) {
            return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        }
        if (n >= 1024L) {
            return (n / 1024L) + " KB";
        }
        return n + " B";
    }

    /** 打开应用内置的下载目录页。 */
    public static void openDir(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, DownloadActivity.class);
            if (!(ctx instanceof android.app.Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "文件保存在：" + dirPath(), Toast.LENGTH_LONG).show();
        }
    }

    /** 用系统「下载」界面打开（内置页不可用时的兜底）。 */
    public static void openSystemDownloads(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        Toast.makeText(ctx, "文件保存在：" + dirPath(), Toast.LENGTH_LONG).show();
    }
}
