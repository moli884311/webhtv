package com.moliys.tvbox;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalServer {
    private static final String TAG = "LocalServer";

    private final AssetManager assets;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile boolean running;
    private ServerSocket socket;
    private int port;

    public LocalServer(AssetManager assets) {
        this.assets = assets;
    }

    public int getPort() {
        return port;
    }

    public void start() throws IOException {
        socket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        port = socket.getLocalPort();
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket s = socket.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                serve(s);
                            }
                        });
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.setName("tvbox-local-server");
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    private static void writeHeader(OutputStream out, int code, String reason, String mime, int length)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    private void serve(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        try {
            s.setSoTimeout(20000);
            in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String header;
            while ((header = readLine(in)) != null && header.length() > 0) {
                // drain request headers
            }
            int cut = target.indexOf('?');
            if (cut >= 0) {
                target = target.substring(0, cut);
            }
            cut = target.indexOf('#');
            if (cut >= 0) {
                target = target.substring(0, cut);
            }
            try {
                target = URLDecoder.decode(target, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
            if (target.length() == 0 || target.equals("/")) {
                target = "/index.html";
            }
            if (!target.startsWith("/") || target.contains("..")) {
                byte[] denied = "forbidden".getBytes("UTF-8");
                writeHeader(out, 403, "Forbidden", "text/plain; charset=utf-8", denied.length);
                out.write(denied);
                out.flush();
                return;
            }
            String rel = target.substring(1);
            byte[] body = null;
            SitePak pak = SitePak.getShared();
            if (pak != null) {
                body = pak.read(rel);
            }
            if (body == null) {
                InputStream asset = null;
                try {
                    asset = assets.open("html/" + rel, AssetManager.ACCESS_STREAMING);
                } catch (IOException e) {
                    asset = null;
                }
                if (asset == null) {
                    byte[] miss = ("not found: " + rel).getBytes("UTF-8");
                    writeHeader(out, 404, "Not Found", "text/plain; charset=utf-8", miss.length);
                    out.write(miss);
                    out.flush();
                    return;
                }
                try {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
                    byte[] tmp = new byte[32 * 1024];
                    int n;
                    while ((n = asset.read(tmp)) > 0) {
                        buf.write(tmp, 0, n);
                    }
                    body = buf.toByteArray();
                } finally {
                    asset.close();
                }
            }
            writeHeader(out, 200, "OK", mimeOf(rel), body.length);
            if (!"HEAD".equalsIgnoreCase(method)) {
                out.write(body);
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "serve failed", e);
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
