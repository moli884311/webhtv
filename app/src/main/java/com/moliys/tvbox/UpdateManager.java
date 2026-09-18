package com.moliys.tvbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public class UpdateManager {
    public interface Listener {
        void onProgress(int pct, long got, long total);

        void onReady(File apk);

        void onError(String message);
    }

    public static final String CHANNEL_ID = "tvbox_update";
    private static final int NOTIF_PROGRESS = 0x7101;
    private static final int NOTIF_ERROR = 0x7102;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public UpdateManager(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public static String human(long n) {
        if (n >= 1048576L) {
            return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        }
        if (n >= 1024L) {
            return (n / 1024L) + " KB";
        }
        return n + " B";
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "下载更新", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    public static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(ctx, CHANNEL_ID);
        }
        return new Notification.Builder(ctx);
    }

    public static void notifyId(Context ctx, int id, Notification n) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(id, n);
        }
    }

    public static void cancel(Context ctx, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(id);
        }
    }

    public static int progressNotifId() {
        return NOTIF_PROGRESS;
    }

    public static void cleanupOldFiles(Context ctx) {
        cleanupDir(DownloadHelper.dir());
        cleanupDir(ApkProvider.updateDir(ctx));
    }

    private static void cleanupDir(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) {
                return;
            }
            for (File f : fs) {
                if (f.getName().endsWith(".part")) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 每次下载前清掉历史更新包：文件名按内容摘要区分，旧包留着只占空间。 */
    private static void prune(File dir, String keepName) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("update-")) {
                continue;
            }
            if (n.equals(keepName)) {
                continue;
            }
            f.delete();
        }
    }

    /** 待安装的更新包（优先沫离下载目录，多个历史包时取最新的一个）。 */
    public static File newestApk(Context ctx) {
        File best = newestApkIn(DownloadHelper.dir());
        File fallback = newestApkIn(ApkProvider.updateDir(ctx));
        if (fallback != null && (best == null || fallback.lastModified() > best.lastModified())) {
            best = fallback;
        }
        return best;
    }

    private static File newestApkIn(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return null;
        }
        File best = null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("update-") || !n.endsWith(".apk")) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    /** 更新包落盘目录：有公共存储权限就放沫离下载（用户可见），否则退回应用私有目录。 */
    private File pickDir() {
        if (DownloadHelper.canWritePublic(ctx)) {
            return DownloadHelper.ensureDir();
        }
        return ApkProvider.updateDir(ctx);
    }

    /**
     * 下载文件名带上内容标识。所有版本共用一个 update.apk 时，安装器会同 URI 复用
     * 上一次解析结果，导致「提示新版、装完还是旧版」。
     */
    private static String tagFor(String expectedSha) {
        if (expectedSha != null && expectedSha.length() == 64) {
            return expectedSha.substring(0, 12);
        }
        return Long.toString(System.currentTimeMillis(), 36);
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void start(final String url, final String expectedSha, final String expectedVc) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                work(url, expectedSha, expectedVc);
            }
        });
        t.setDaemon(true);
        t.setName("tvbox-update");
        t.start();
    }

    /** 读下载文件的真实 versionCode；解析不出来返回空串。 */
    private String apkVersionCode(File f) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) {
                return "";
            }
            return String.valueOf(pi.versionCode);
        } catch (Throwable t) {
            return "";
        }
    }

    private void work(String url, String expectedSha, String expectedVc) {
        HttpURLConnection conn = null;
        File dir = pickDir();
        String tag = tagFor(expectedSha);
        File part = new File(dir, "update-" + tag + ".apk.part");
        File apk = new File(dir, "update-" + tag + ".apk");
        prune(dir, apk.getName());
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVBox/" + Version.NAME);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            int total = conn.getContentLength();
            ensureChannel(ctx);
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
                    if (pct != lastPct || now - lastPost > 400) {
                        lastPct = pct;
                        lastPost = now;
                        postProgress(pct, got, total);
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
            if (expectedSha != null && expectedSha.length() == 64) {
                if (!sha256(part).equalsIgnoreCase(expectedSha)) {
                    throw new IOException("更新包校验失败");
                }
            }
            if (expectedVc != null && expectedVc.length() > 0) {
                String got = apkVersionCode(part);
                // 读不到版本号时放行（主修复是文件名/URI 逐版唯一），只在确定读到不同版本时才拦截
                if (got.length() > 0 && !expectedVc.equals(got)) {
                    throw new IOException("安装包版本不符：下载到的是版本号 " + got + "，期望 " + expectedVc);
                }
            }
            if (apk.exists() && !apk.delete()) {
                throw new IOException("无法覆盖旧更新包");
            }
            if (!part.renameTo(apk)) {
                throw new IOException("无法保存更新包");
            }
            postProgress(100, apk.length(), apk.length());
            cancel(ctx, NOTIF_PROGRESS);
            final File ready = apk;
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onReady(ready);
                }
            });
        } catch (Exception e) {
            if (part.exists()) {
                part.delete();
            }
            String msg = e.getMessage();
            postError(msg == null || msg.length() == 0 ? "下载失败" : msg);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void postProgress(final int pct, final long got, final long total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onProgress(pct, got, total);
                try {
                    Notification.Builder b = builder(ctx)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle("正在下载更新")
                            .setContentText(total > 0 ? (human(got) + " / " + human(total)) : human(got))
                            .setOngoing(true)
                            .setOnlyAlertOnce(true);
                    if (total > 0) {
                        b.setProgress(100, pct, false);
                    } else {
                        b.setProgress(0, 0, true);
                    }
                    notifyId(ctx, NOTIF_PROGRESS, b.build());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void postError(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cancel(ctx, NOTIF_PROGRESS);
                try {
                    Notification.Builder b = builder(ctx)
                            .setSmallIcon(android.R.drawable.stat_sys_warning)
                            .setContentTitle("更新下载失败")
                            .setContentText(message)
                            .setAutoCancel(true);
                    notifyId(ctx, NOTIF_ERROR, b.build());
                } catch (Throwable ignored) {
                }
                listener.onError(message);
            }
        });
    }
}
