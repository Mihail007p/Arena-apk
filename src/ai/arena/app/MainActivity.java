package ai.arena.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.UUID;
import java.io.OutputStream;
import org.json.JSONObject;

/**
 * Arena 1.2 — full-screen dark WebView launcher for https://arena.ai/
 * No toolbar, no progress strip: the site owns the whole screen (like 1.0).
 * Plain Android framework only: no AndroidX, no Gradle, no third-party libs.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://arena.ai/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int REQ_SAVE_DOWNLOAD = 1003;
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    // Dark palette
    private static final int BG = 0xFF0B0D14;
    private static final int SURFACE = 0xFF11141F;
    private static final int CARD = 0xFF171B28;
    private static final int BORDER = 0xFF262C3E;
    private static final int TEXT = 0xFFF2F3FA;
    private static final int MUTED = 0xFF8B90A6;
    private static final int ACCENT = 0xFF7C6CFF;
    private static final int ACCENT_SOFT = 0xFFA99BFF;

    /**
     * Enables arena.ai's own (standard) dark theme: next-themes reads
     * localStorage.theme and toggles the `dark` class on <html>.
     * A MutationObserver keeps the class if the site re-renders on SPA navigation.
     */
    private static final String DARK_JS =
            "(function(){" +
            "function apply(){" +
            "try{" +
            "var d=document.documentElement;" +
            "d.classList.add('dark');" +
            "d.style.setProperty('color-scheme','dark','important');" +
            "localStorage.setItem('theme','dark');" +
            "var st=document.getElementById('arena-app-dark-style');" +
            "if(!st){st=document.createElement('style');st.id='arena-app-dark-style';" +
            "st.appendChild(document.createTextNode('html{color-scheme:dark !important}'));" +
            "(document.head||d).appendChild(st);}" +
            "}catch(e){}" +
            "}" +
            "apply();" +
            "try{" +
            "if(!window.__arenaDarkGuard){" +
            "window.__arenaDarkGuard=new MutationObserver(function(){" +
            "var d=document.documentElement;" +
            "if(!d.classList.contains('dark')){d.classList.add('dark');}" +
            "});" +
            "window.__arenaDarkGuard.observe(document.documentElement," +
            "{attributes:true,attributeFilter:['class']});" +
            "}" +
            "}catch(e){}" +
            "})()";

    // Placeholders are replaced with JSON-quoted values before execution.
    private static final String DOWNLOAD_JS =
            "(function(){" +
            "var url=__URL__,token=__TOKEN__;" +
            "fetch(url).then(function(r){if(!r.ok)throw new Error();return r.blob();})" +
            ".then(function(b){if(b.size>16777216)throw new Error();" +
            "var reader=new FileReader();" +
            "reader.onload=function(){window.ArenaDownload.complete(token,reader.result);};" +
            "reader.onerror=function(){window.ArenaDownload.fail(token);};" +
            "reader.readAsDataURL(b);})" +
            ".catch(function(){window.ArenaDownload.fail(token);});" +
            "})()";

    private String downloadToken;
    private String downloadName;
    private String downloadMime;
    private byte[] downloadBytes;

    private FrameLayout root;
    private WebView web;
    private View offlineView;
    private long lastBackAt;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermission;
    private boolean pageFailed;
    private boolean darkAppliedForPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBarColors();

        // Full screen: one FrameLayout, WebView occupies everything.
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        offlineView = buildOfflineView();
        buildWebView();

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            pageFailed = false;
            hideOffline();
            applyDarkTheme();
        } else {
            retry();
        }
    }

    // ------------------------------------------------------------------ shell

    private void applySystemBarColors() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(SURFACE);
            getWindow().setNavigationBarColor(BG);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private View buildOfflineView() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(BG);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), BORDER);
        card.setBackground(cardBg);
        card.setPadding(dp(24), dp(28), dp(24), dp(20));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardLp.setMargins(dp(28), 0, dp(28), 0);
        card.setLayoutParams(cardLp);

        TextView glyph = new TextView(this);
        glyph.setText("☾");
        glyph.setTextColor(ACCENT_SOFT);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        glyph.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("Нет соединения");
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, 0);

        TextView sub = new TextView(this);
        sub.setText("Проверьте Wi‑Fi или мобильные данные и попробуйте снова.");
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(8), dp(8), dp(8), dp(22));

        TextView retry = makeActionButton("Повторить", true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retry();
            }
        });
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));

        TextView network = makeActionButton("Настройки сети", false);
        network.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNetworkSettings();
            }
        });
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        netLp.setMargins(0, dp(8), 0, 0);

        card.addView(glyph);
        card.addView(title);
        card.addView(sub);
        card.addView(retry, retryLp);
        card.addView(network, netLp);
        overlay.addView(card);
        overlay.setVisibility(View.GONE);
        return overlay;
    }

    private TextView makeActionButton(String label, boolean primary) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(false);
        if (primary) {
            t.setTextColor(0xFFFFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ACCENT);
            bg.setCornerRadius(dp(12));
            t.setBackground(bg);
        } else {
            t.setTextColor(ACCENT_SOFT);
            t.setPadding(dp(12), dp(10), dp(12), dp(6));
        }
        return t;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // --------------------------------------------------------------- WebView

    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 11) {
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        WebSettings s = web.getSettings();
        // Strip the "; wv" WebView marker so anti-bot checks / OAuth see mobile Chrome.
        String ua = WebSettings.getDefaultUserAgent(this);
        if (ua != null) {
            s.setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));
        }
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSaveFormData(true);
        s.setDefaultTextEncodingName("utf-8");
        s.setLoadsImagesAutomatically(true);
        s.setNeedInitialFocus(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 17) {
            s.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // Rasterise off-screen tiles while idle -> smoother scrolling.
            s.setOffscreenPreRaster(true);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // The site has its own proper dark theme; avoid Chromium's auto-inversion.
            try {
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {
            }
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cookies.setAcceptThirdPartyCookies(web, true);
        }

        web.addJavascriptInterface(new DownloadBridge(), "ArenaDownload");
        web.setWebViewClient(new ArenaWebViewClient());
        web.setWebChromeClient(new ArenaChromeClient());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        root.removeAllViews();
        root.addView(web);
        root.addView(offlineView);
        offlineView.setVisibility(View.GONE);
    }

    private void startDownload(String url, String userAgent, String disposition, String mime) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String filename = URLUtil.guessFileName(url, disposition, mime);
        if ("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)) {
            Uri page = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
            String host = page.getHost();
            if (!"https".equalsIgnoreCase(page.getScheme()) || host == null ||
                    !(host.equalsIgnoreCase("arena.ai") || host.toLowerCase(java.util.Locale.US).endsWith(".arena.ai"))) {
                Toast.makeText(this, "Скачивание с этой страницы запрещено", Toast.LENGTH_LONG).show();
                return;
            }
            if (downloadToken != null || downloadBytes != null) {
                Toast.makeText(this, "Дождитесь завершения предыдущей загрузки", Toast.LENGTH_SHORT).show();
                return;
            }
            downloadToken = UUID.randomUUID().toString();
            downloadName = filename;
            downloadMime = mime;
            String script = DOWNLOAD_JS.replace("__URL__", JSONObject.quote(url))
                    .replace("__TOKEN__", JSONObject.quote(downloadToken));
            web.evaluateJavascript(script, null);
        } else if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            try {
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(filename);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
                if (mime != null && !mime.isEmpty()) request.setMimeType(mime);
                if (Build.VERSION.SDK_INT >= 29) {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                }
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Этот тип ссылки не поддерживается", Toast.LENGTH_LONG).show();
        }
    }

    private class DownloadBridge {
        @JavascriptInterface
        public void complete(String token, String dataUrl) {
            runOnUiThread(new Runnable() { public void run() {
                if (downloadToken == null || !downloadToken.equals(token)) return;
                downloadToken = null;
                try {
                    int comma = dataUrl.indexOf(',');
                    if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64") ||
                            dataUrl.length() - comma - 1 > ((MAX_DOWNLOAD_BYTES + 2L) / 3L) * 4L + 4L)
                        throw new IllegalArgumentException();
                    downloadBytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                    if (downloadBytes.length > MAX_DOWNLOAD_BYTES) throw new IllegalArgumentException();
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(downloadMime == null || downloadMime.isEmpty() ? "application/octet-stream" : downloadMime);
                    save.putExtra(Intent.EXTRA_TITLE, downloadName);
                    startActivityForResult(save, REQ_SAVE_DOWNLOAD);
                } catch (Exception e) {
                    downloadBytes = null;
                    Toast.makeText(MainActivity.this, "Не удалось подготовить файл к сохранению", Toast.LENGTH_LONG).show();
                }
            }});
        }
        @JavascriptInterface
        public void fail(String token) {
            runOnUiThread(new Runnable() { public void run() {
                if (downloadToken == null || !downloadToken.equals(token)) return;
                downloadToken = null;
                Toast.makeText(MainActivity.this, "Не удалось скачать файл (максимум 16 МБ)", Toast.LENGTH_LONG).show();
            }});
        }
    }

    private void applyDarkTheme() {
        if (web == null || darkAppliedForPage) return;
        darkAppliedForPage = true;
        try {
            web.evaluateJavascript(DARK_JS, null);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- navigation

    private void retry() {
        if (!isOnline()) {
            showOffline();
            Toast.makeText(this, "Нет сети", Toast.LENGTH_SHORT).show();
            return;
        }
        hideOffline();
        pageFailed = false;
        darkAppliedForPage = false;
        String current = web.getUrl();
        if (current != null && (current.startsWith("https://") || current.startsWith("http://"))) {
            web.reload();
        } else {
            web.loadUrl(START_URL);
        }
    }

    private void showOffline() {
        pageFailed = true;
        if (offlineView != null) {
            offlineView.setVisibility(View.VISIBLE);
            offlineView.bringToFront();
        }
    }

    private void hideOffline() {
        if (offlineView != null) offlineView.setVisibility(View.GONE);
    }

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        if (Build.VERSION.SDK_INT >= 29) {
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void openExternally(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void openNetworkSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void showMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Arena")
                .setItems(new CharSequence[]{
                                "Обновить",
                                "Открыть в браузере",
                                "Настройки сети",
                                "О приложении",
                                "Выйти"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                switch (which) {
                                    case 0:
                                        retry();
                                        break;
                                    case 1:
                                        openExternally(START_URL);
                                        break;
                                    case 2:
                                        openNetworkSettings();
                                        break;
                                    case 3:
                                        showAbout();
                                        break;
                                    case 4:
                                        finish();
                                        break;
                                }
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Arena")
                .setMessage("Версия 2.1\nПакет: ai.arena.app\nСайт: arena.ai\n\n"
                        + "Полноэкранная обёртка WebView с тёмной темой сайта. "
                        + "Сборка подписана своим ключом и не распространяется через Play Market.")
                .setPositiveButton("ОК", null)
                .show();
    }

    // -------------------------------------------------------------- clients

    private class ArenaWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= 24) return handleUri(request.getUrl());
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        private boolean handleUri(Uri uri) {
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
                return false;
            }
            openExternally(uri.toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageFailed = false;
            darkAppliedForPage = false;
            downloadToken = null;
            hideOffline();
        }

        /** Right after the document commits, BEFORE the first paint — no white flash. */
        @Override
        public void onPageCommitVisible(WebView view, String url) {
            applyDarkTheme();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!pageFailed) hideOffline();
            applyDarkTheme(); // safety net if onPageCommitVisible did not fire
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (Build.VERSION.SDK_INT >= 23 && request.isForMainFrame()) showOffline();
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                                    String failingUrl) {
            String current = view.getUrl();
            if (failingUrl != null && failingUrl.equals(current)) {
                showOffline();
            } else if (!isOnline()) {
                showOffline();
            }
        }
    }

    private class ArenaChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            // Hide the offline card as soon as real content starts arriving.
            if (newProgress >= 25 && !pageFailed) hideOffline();
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(
                        Intent.createChooser(params.createIntent(), "Выберите файл"),
                        REQ_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, "Нет приложения для выбора файла",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            String[] resources = request.getResources();
            ArrayList<String> missing = new ArrayList<String>();
            for (String r : resources) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                        && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    missing.add(Manifest.permission.RECORD_AUDIO);
                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                        && !hasPermission(Manifest.permission.CAMERA)) {
                    missing.add(Manifest.permission.CAMERA);
                }
            }
            if (missing.isEmpty()) {
                request.grant(resources);
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                pendingPermission = request;
                requestPermissions(missing.toArray(new String[missing.size()]), REQ_PERMISSIONS);
            } else {
                request.deny();
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    // ------------------------------------------------------- activity results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SAVE_DOWNLOAD) {
            final byte[] bytes = downloadBytes;
            downloadBytes = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && bytes != null) {
                final Uri destination = data.getData();
                new Thread(new Runnable() { public void run() {
                    boolean success = false;
                    try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                        if (out != null) { out.write(bytes); success = true; }
                    } catch (Exception ignored) { }
                    final boolean saved = success;
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(MainActivity.this, saved ? "Файл сохранён" : "Не удалось сохранить файл", Toast.LENGTH_LONG).show();
                    }});
                }}).start();
            }
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getDataString() != null) {
                    results = new Uri[]{Uri.parse(data.getDataString())};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            ValueCallback<Uri[]> cb = filePathCallback;
            filePathCallback = null;
            cb.onReceiveValue(results);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_PERMISSIONS && pendingPermission != null) {
            PermissionRequest request = pendingPermission;
            pendingPermission = null;
            ArrayList<String> allowed = new ArrayList<String>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                    if (hasPermission(Manifest.permission.RECORD_AUDIO)) allowed.add(r);
                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                    if (hasPermission(Manifest.permission.CAMERA)) allowed.add(r);
                } else {
                    allowed.add(r);
                }
            }
            if (allowed.isEmpty()) request.deny();
            else request.grant(allowed.toArray(new String[allowed.size()]));
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // -------------------------------------------------------- lifecycle / back

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 1300) {
            lastBackAt = 0;
            showMenu();
            return;
        }
        lastBackAt = now;
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        showMenu();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (web != null) web.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers(); // save battery while in background
        }
        try {
            CookieManager.getInstance().flush(); // persist login before process death
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
