package com.moliys.tvbox;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("NewApi")
public final class CrashLogger {

    private static final String DIR = "crashlog";
    private static final String FILE_JAVA = "last_java_crash.txt";
    private static final String FILE_EXIT = "last_process_exit.txt";
    private static final String PREF = "moliys_crash";
    private static final String KEY_EXIT_TS = "exit_ts";

    private static volatile Context appContext;

    private CrashLogger() {
    }

    public static void install(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        appContext = app;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, FILE_JAVA, buildJava(thread, error));
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public static String read(Context context) {
        Context app = app(context);
        if (app == null) return null;
        captureExit(app);
        String exit = readFile(new File(dir(app), FILE_EXIT));
        String java = readFile(new File(dir(app), FILE_JAVA));
        StringBuilder builder = new StringBuilder();
        if (exit != null) builder.append(exit);
        if (java != null) {
            if (builder.length() > 0) builder.append("\n\n");
            builder.append(java);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    public static void clear(Context context) {
        Context app = app(context);
        if (app == null) return;
        new File(dir(app), FILE_JAVA).delete();
        new File(dir(app), FILE_EXIT).delete();
    }

    private static void captureExit(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (exits.isEmpty()) return;
            SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long lastSeen = pref.getLong(KEY_EXIT_TS, 0L);
            long newest = lastSeen;
            StringBuilder builder = new StringBuilder();
            for (ApplicationExitInfo exit : exits) {
                long timestamp = exit.getTimestamp();
                if (timestamp <= lastSeen) continue;
                if (timestamp > newest) newest = timestamp;
                builder.append(buildExit(exit)).append('\n');
            }
            if (builder.length() == 0) return;
            pref.edit().putLong(KEY_EXIT_TS, newest).apply();
            write(context, FILE_EXIT, builder.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String buildExit(ApplicationExitInfo exit) {
        StringBuilder builder = new StringBuilder();
        builder.append("=== 上次进程退出 ===\n");
        builder.append("原因: ").append(reasonName(exit.getReason())).append('(').append(exit.getReason()).append(")\n");
        builder.append("状态: ").append(exit.getStatus()).append('\n');
        builder.append("时间: ").append(format(exit.getTimestamp())).append('\n');
        builder.append("描述: ").append(safe(exit.getDescription())).append('\n');
        builder.append("PSS/RSS: ").append(exit.getPss()).append(" / ").append(exit.getRss()).append('\n');
        String trace = readTrace(exit);
        if (trace != null) builder.append("--- 系统 trace ---\n").append(trace).append('\n');
        builder.append("对照: CRASH=Java崩溃 CRASH_NATIVE=native崩溃 SIGNALED=被信号杀死 ANR=无响应 EXIT_SELF=主动退出 LOW_MEMORY=内存不足\n");
        return builder.toString();
    }

    private static String readTrace(ApplicationExitInfo exit) {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while (out.size() < 16384 && (read = input.read(buffer, 0, Math.min(buffer.length, 16384 - out.size()))) > 0) {
                out.write(buffer, 0, read);
            }
            if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return "系统 trace 为二进制，读取 " + out.size() + " 字节（未导出内容）";
        } catch (Throwable e) {
            return null;
        }
    }

    private static String buildJava(Thread thread, Throwable error) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        printer.println("=== Java 未捕获异常 ===");
        printer.println("时间: " + format(System.currentTimeMillis()));
        printer.println("线程: " + thread.getName());
        printer.println("异常: " + error);
        printer.println();
        error.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    private static Context app(Context context) {
        if (context == null) return appContext;
        return appContext != null ? appContext : context;
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void write(Context context, String name, String text) {
        try {
            File file = new File(dir(context), name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readFile(File file) {
        try {
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

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String format(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestamp));
    }
}
