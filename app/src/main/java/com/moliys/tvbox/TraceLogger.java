package com.moliys.tvbox;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TraceLogger {

    private static final String DIR = "crashlog";
    private static final String FILE = "trace.txt";
    private static final long MAX = 256 * 1024;
    private static final Object LOCK = new Object();

    private static volatile Context appContext;
    private static volatile boolean enabled = true;

    private TraceLogger() {
    }

    public static void setContext(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void log(String tag, String message) {
        if (!enabled) return;
        Context context = appContext;
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), DIR);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, FILE);
                if (file.exists() && file.length() > MAX) file.delete();
                String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " [" + tag + "] " + message + "\n";
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.getFD().sync();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void log(String tag, String format, Object... args) {
        try {
            log(tag, args == null || args.length == 0 ? format : String.format(Locale.US, format, args));
        } catch (Throwable e) {
            log(tag, format);
        }
    }

    public static String read(Context context) {
        try {
            File file = new File(new File(context.getFilesDir(), DIR), FILE);
            if (!file.exists()) return null;
            byte[] data = new byte[(int) file.length()];
            try (InputStream in = new FileInputStream(file)) {
                int offset = 0;
                int read;
                while (offset < data.length && (read = in.read(data, offset, data.length - offset)) > 0) {
                    offset += read;
                }
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    public static void clear(Context context) {
        try {
            new File(new File(context.getFilesDir(), DIR), FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
