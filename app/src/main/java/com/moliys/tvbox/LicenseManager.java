package com.moliys.tvbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * 「影视」功能授权校验。
 *
 * 混合方案：
 *  - 设备码由系统设备标识（ANDROID_ID）稳定派生，清除数据/卸载重装后不变；用户在 App 内查看后发到群，管理员用机器人指令授权。
 *  - App 联网向 /auth_check.php 查询该设备码的授权状态；响应带 RSA-SHA256 签名，App 内嵌公钥验签。
 *  - 验签通过的 {auth, exp, srv} 缓存到本地；以「服务器时间 + 单调时钟增量」估算当前时间，
 *    防止用户改系统时间续命，也允许短暂断网继续使用直到 exp。
 *  - 到期或未授权即视为无效，不注入「影视」入口。
 */
public final class LicenseManager {
    private static final String TAG = "MoliysLicense";
    private static final String PREFS = "moliys_license";
    private static final String CHECK_URL = "https://tvbox.moliys.icu/auth_check.php";
    private static final int TIMEOUT_MS = 12000;

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 10;
    private static final String CODE_SALT = "moliys-tvbox-device-v1";
    private static volatile String cachedCode;

    private static final String PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0hYx6BjDpBNp8NBxosKx\n"
            + "PuF+Q18oxX7Y4IE8O7ViVwvrLjxM4H5wtKsSA7QVT1NmgGNZ2wyFvmm9AWSqssBH\n"
            + "+m/Xl8dE2P2xzMC4bkCutnanTitWY445yZobELV7WSWqYZk5TDaoX4PuRoC/KKTJ\n"
            + "+6uGKDh2/VZLlnny6rgtESJlDa6Jdi0cxAwdQN3dHzLG8YGeX5lWauvWmmj2SKDb\n"
            + "n4eL9tUs3yLow62HiPE+VAkSwMdg5kSVKq9MfpFGThj54WfhTmPcfeCqyRe8+BKz\n"
            + "Dhvpj4wcQNyCTptfX4tiu6CCaocMdNblIBNEhCUpjnCsVZ74lgDdtbbjlmys3G3j\n"
            + "SQIDAQAB\n"
            + "-----END PUBLIC KEY-----";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void done(boolean changed);
    }

    private LicenseManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 设备码：由系统设备标识（ANDROID_ID）稳定派生，同一设备在同一签名下
     * 清除数据、卸载重装后均保持不变；仅在无法取得标识时退回持久化随机码。
     */
    public static String deviceCode(Context ctx) {
        String code = cachedCode;
        if (code != null) {
            return code;
        }
        synchronized (LicenseManager.class) {
            if (cachedCode != null) {
                return cachedCode;
            }
            SharedPreferences p = prefs(ctx);
            String derived = null;
            try {
                String androidId = Settings.Secure.getString(
                        ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (androidId != null && androidId.length() > 0
                        && !"9774d56d682e549c".equalsIgnoreCase(androidId)
                        && !"0000000000000000".equals(androidId)) {
                    derived = derive(androidId);
                }
            } catch (Throwable e) {
                Log.w(TAG, "androidId unavailable: " + e.getMessage());
            }
            if (derived == null || derived.length() != CODE_LEN) {
                derived = p.getString("dcode", null);
                if (derived == null || derived.length() != CODE_LEN) {
                    SecureRandom rnd = new SecureRandom();
                    StringBuilder sb = new StringBuilder(CODE_LEN);
                    for (int i = 0; i < CODE_LEN; i++) {
                        sb.append(CODE_ALPHABET.charAt(rnd.nextInt(CODE_ALPHABET.length())));
                    }
                    derived = sb.toString();
                }
                p.edit().putString("dcode", derived).apply();
            }
            cachedCode = derived;
            return derived;
        }
    }

    /** 把设备标识映射为 10 位设备码；32 整除 256，取模无偏。 */
    private static String derive(String seed) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest((CODE_SALT + ":" + seed).getBytes(Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(CODE_ALPHABET.charAt((h[i] & 0xFF) % CODE_ALPHABET.length()));
        }
        return sb.toString();
    }

    /** 估算当前时间：以最近一次校验得到的服务器时间为锚，叠加单调时钟增量。 */
    private static long estimatedNow(SharedPreferences p) {
        long srv = p.getLong("srv", 0L);
        long recvElapsed = p.getLong("recv_elapsed", 0L);
        long delta = SystemClock.elapsedRealtime() - recvElapsed;
        if (delta < 0L) {
            delta = 0L;
        }
        return srv + delta / 1000L;
    }

    private static boolean verified(SharedPreferences p) {
        return p.getBoolean("verified", false);
    }

    /** 是否处于有效授权期内。 */
    public static boolean isAuthorized(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!verified(p) || p.getInt("auth", 0) != 1) {
            return false;
        }
        long exp = p.getLong("exp", 0L);
        return exp > 0L && estimatedNow(p) < exp;
    }

    /** 是否曾拿到过有效授权记录（用于区分「已过期」与「从未授权」）。 */
    public static boolean hasRecord(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return verified(p) && p.getLong("exp", 0L) > 0L;
    }

    /** 剩余秒数；未授权或已过期返回 0。 */
    public static long remainingSeconds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!isAuthorized(ctx)) {
            return 0L;
        }
        return p.getLong("exp", 0L) - estimatedNow(p);
    }

    /** 供 JS 读取的状态 JSON。 */
    public static String statusJson(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", deviceCode(ctx));
            o.put("auth", isAuthorized(ctx) ? 1 : 0);
            o.put("exp", prefs(ctx).getLong("exp", 0L));
            o.put("remain", remainingSeconds(ctx));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 联网校验。回调在主线程；changed 表示授权态发生变化。 */
    public static void refresh(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean changed = false;
                try {
                    changed = doCheck(app);
                } catch (Exception e) {
                    Log.w(TAG, "license check failed: " + e.getMessage());
                }
                final boolean c = changed;
                if (cb != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(c);
                        }
                    });
                }
            }
        }, "moliys-license").start();
    }

    private static boolean doCheck(Context app) throws Exception {
        SharedPreferences p = prefs(app);
        String code = deviceCode(app);
        String pkg = app.getPackageName();
        StringBuilder url = new StringBuilder(CHECK_URL)
                .append("?d=").append(URLEncoder.encode(code, "UTF-8"))
                .append("&p=").append(URLEncoder.encode(pkg, "UTF-8"))
                .append("&v=").append(Version.CODE);

        HttpURLConnection conn = null;
        StringBuilder body = new StringBuilder();
        try {
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "moliys/" + Version.NAME);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return false;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        JSONObject o = new JSONObject(body.toString());
        if (o.optInt("ok", 0) != 1) {
            return false;
        }
        String d = o.optString("d", "");
        String rp = o.optString("p", "");
        int auth = o.optInt("auth", 0);
        long exp = o.optLong("exp", 0L);
        long srv = o.optLong("srv", 0L);
        String sign = o.optString("sign", "");
        if (srv <= 0L || sign.length() == 0) {
            return false;
        }

        String canon = d + "|" + rp + "|" + auth + "|" + exp + "|" + srv;
        if (!verify(canon, sign)) {
            Log.w(TAG, "license signature invalid");
            return false;
        }

        int oldAuth = isAuthorized(app) ? 1 : 0;
        long oldExp = p.getLong("exp", 0L);

        p.edit()
                .putInt("auth", auth)
                .putLong("exp", exp)
                .putLong("srv", srv)
                .putLong("recv_elapsed", SystemClock.elapsedRealtime())
                .putBoolean("verified", true)
                .apply();

        int newAuth = isAuthorized(app) ? 1 : 0;
        return newAuth != oldAuth || exp != oldExp;
    }

    private static boolean verify(String canon, String signB64) {
        try {
            PublicKey pub = loadPublicKey();
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pub);
            sig.update(canon.getBytes(Charset.forName("UTF-8")));
            return sig.verify(Base64.decode(signB64, Base64.DEFAULT));
        } catch (Exception e) {
            Log.w(TAG, "verify error: " + e.getMessage());
            return false;
        }
    }

    private static PublicKey loadPublicKey() throws Exception {
        String pem = PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}
