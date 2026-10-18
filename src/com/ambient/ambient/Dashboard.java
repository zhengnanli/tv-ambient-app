package com.ambient.ambient;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds the dashboard WebView and the JS bridge it uses for config and network. */
final class Dashboard {
    private Dashboard() {}

    /**
     * interactive: the launchable app, where the remote can open the settings menu.
     * settings: start with that menu open.
     */
    static WebView create(Context ctx, boolean interactive, boolean settings) {
        WebView web = new WebView(ctx);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);  // photos are cached as files and shown from disk
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("AmbientDashboard", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(ctx, web, interactive), "Android");
        web.loadUrl("file:///android_asset/index.html" + (settings ? "#settings" : ""));
        return web;
    }

    static final class Bridge {
        private final Context ctx;
        private final WebView web;
        private final boolean interactive;
        private final SharedPreferences prefs;
        private final ExecutorService pool = Executors.newFixedThreadPool(3);

        Bridge(Context ctx, WebView web, boolean interactive) {
            this.ctx = ctx;
            this.web = web;
            this.interactive = interactive;
            this.prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        }

        @JavascriptInterface
        public boolean interactive() {
            return interactive;
        }

        /** Choices made in the on-screen menu, as a JSON object. */
        @JavascriptInterface
        public void saveSettings(String json) {
            prefs.edit().putString("json", json).apply();
        }

        /**
         * Contents of a JSON file in the app's external files dir, or "{}". The page reads
         * config.json, then the menu's settings, then debug.json, which adb can push
         * without touching the credentials in config.json.
         */
        @JavascriptInterface
        public String config(String name) {
            if (name.equals("settings")) return prefs.getString("json", "{}");
            if (!name.matches("[a-z]+\\.json")) return "{}";
            File f = new File(ctx.getExternalFilesDir(null), name);
            try (InputStream in = new FileInputStream(f)) {
                return read(in);
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Fetches url off the UI thread, then calls window.__fetched(id, ok, body). */
        @JavascriptInterface
        public void fetch(final int id, final String url) {
            pool.execute(() -> {
                boolean ok = false;
                String body;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "AmbientDashboard/1.0");
                    int code = c.getResponseCode();
                    ok = code >= 200 && code < 300;
                    InputStream in = ok ? c.getInputStream() : c.getErrorStream();
                    body = in == null ? ("HTTP " + code) : read(in);
                } catch (Exception e) {
                    body = String.valueOf(e);
                }
                final String js = "window.__fetched(" + id + "," + ok + "," + JSONObject.quote(body) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            });
        }

        /**
         * Downloads an image into the cache dir (once per URL), then calls
         * window.__fetched(id, ok, fileUrl). The WebView's own image loader stalls on
         * this TV, and a disk cache means the screensaver has a photo the instant it starts.
         */
        @JavascriptInterface
        public void cacheImage(final int id, final String url) {
            pool.execute(() -> {
                boolean ok = false;
                String body;
                try {
                    File dir = new File(ctx.getCacheDir(), "photos");
                    dir.mkdirs();
                    File f = new File(dir, sha1(url) + ".jpg");
                    if (!f.exists()) {
                        File tmp = new File(dir, f.getName() + ".part");
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        c.setRequestProperty("User-Agent", "AmbientDashboard/1.0");
                        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        }
                        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
                        prune(dir, 80);
                    }
                    f.setLastModified(System.currentTimeMillis());
                    body = "file://" + f.getAbsolutePath();
                    ok = true;
                } catch (Exception e) {
                    body = String.valueOf(e);
                }
                final String js = "window.__fetched(" + id + "," + ok + "," + JSONObject.quote(body) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            });
        }

        /** Keeps the newest `keep` files in dir. */
        private static void prune(File dir, int keep) {
            File[] files = dir.listFiles();
            if (files == null || files.length <= keep) return;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < files.length; i++) files[i].delete();
        }

        private static String sha1(String s) throws Exception {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)))
                hex.append(String.format("%02x", b));
            return hex.toString();
        }

        private static String read(InputStream in) throws java.io.IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
