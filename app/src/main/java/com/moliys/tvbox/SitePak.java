package com.moliys.tvbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 站点资源包读取器：解析 assets/site.pak（AES-256-CBC，每文件独立 IV），
 * 按需解密单个文件，供 LocalServer 与页面桥使用。
 *
 * 不依赖任何 Android API，便于在 JVM 上单测。
 */
public final class SitePak {
    private static final String MAGIC = "MSITEPK2";

    private static volatile SitePak shared;

    private final byte[] pak;
    private final byte[] key;
    private final Map<String, int[]> index = new HashMap<String, int[]>();
    private final Map<String, byte[]> cache = new HashMap<String, byte[]>();
    private int payloadStart;

    private SitePak(byte[] pak, byte[] key) throws Exception {
        this.pak = pak;
        this.key = key;
        parse();
    }

    /** 构建并返回实例；解析失败返回 null。 */
    public static SitePak create(byte[] pak, byte[] key) {
        if (pak == null || key == null || key.length != 32) {
            return null;
        }
        try {
            return new SitePak(pak, key);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setShared(SitePak p) {
        shared = p;
    }

    public static SitePak getShared() {
        return shared;
    }

    /** 从 assets 读取 site.pak 并初始化；keyHex 为空或文件缺失时返回 null。 */
    public static SitePak loadFromAssets(InputStream pakStream, String keyHex) {
        if (pakStream == null || keyHex == null || keyHex.length() < 64) {
            return null;
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = pakStream.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return create(buf.toByteArray(), hexToBytes(keyHex));
        } catch (Exception e) {
            return null;
        } finally {
            try {
                pakStream.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return null;
        }
        int len = hex.length();
        if (len % 2 != 0) {
            return null;
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private void parse() throws Exception {
        byte[] m = MAGIC.getBytes("UTF-8");
        for (int i = 0; i < m.length; i++) {
            if (pak[i] != m[i]) {
                throw new Exception("bad magic");
            }
        }
        int pos = m.length;
        int count = readInt(pos);
        pos += 4;
        for (int i = 0; i < count; i++) {
            int nameLen = readShort(pos);
            pos += 2;
            String name = new String(pak, pos, nameLen, "UTF-8");
            pos += nameLen;
            int off = readInt(pos);
            pos += 4;
            int len = readInt(pos);
            pos += 4;
            index.put(name, new int[] { off, len, pos });
            pos += 16;
        }
        payloadStart = pos;
    }

    private int readInt(int at) {
        return ((pak[at] & 0xff) << 24) | ((pak[at + 1] & 0xff) << 16)
                | ((pak[at + 2] & 0xff) << 8) | (pak[at + 3] & 0xff);
    }

    private int readShort(int at) {
        return ((pak[at] & 0xff) << 8) | (pak[at + 1] & 0xff);
    }

    public boolean has(String rel) {
        return index.containsKey(rel);
    }

    public int count() {
        return index.size();
    }

    /** 返回文件明文；不存在或解密失败返回 null。结果会缓存。 */
    public byte[] read(String rel) {
        if (rel == null) {
            return null;
        }
        byte[] hit = cache.get(rel);
        if (hit != null) {
            return hit;
        }
        int[] e = index.get(rel);
        if (e == null) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(pak, e[2], 16));
            byte[] pt = c.doFinal(pak, payloadStart + e[0], e[1]);
            pt = inflate(pt);
            cache.put(rel, pt);
            return pt;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 载荷为 zlib 压缩后再加密，解密后需解压。 */
    private static byte[] inflate(byte[] src) throws Exception {
        java.util.zip.InflaterInputStream in =
                new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(src));
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length * 3);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
