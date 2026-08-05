package com.kongda.toapplite.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal generated web shell.
 *
 * Security properties:
 * - no analytics or remote-control SDK;
 * - no JavaScript bridge;
 * - clear-text HTTP disabled by the manifest;
 * - direct local-file access disabled;
 * - content URI access is limited to files explicitly selected with Android's picker;
 * - SSL errors are never bypassed;
 * - non-HTTP(S) links are delegated to Android.
 */
public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 901;
    private static final int SAVE_DOWNLOAD_REQUEST = 902;
    private static final int MAX_DOWNLOAD_REDIRECTS = 5;
    private static final long LEGACY_BAR_ANIMATION_MS = 180L;

    private FrameLayout root;
    private FrameLayout contentContainer;
    private View statusBarScrim;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final ExecutorService legacyDownloadExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());
    private PendingDownload pendingLegacyDownload;
    private float lastTouchViewX;
    private float lastTouchViewY;
    private boolean forwardingDefaultLongClick;

    private int statusBarOverlayHeight;
    private boolean requestedStatusBarHidden;
    private boolean actualStatusBarHidden;
    private boolean statusBarTransitionPending;
    private boolean statusBarAnimationRunning;
    private Object activeStatusBarAnimation;
    private float statusBarAnimationStartTranslation;
    private float statusBarAnimationTargetTranslation;

    private int touchSlop;
    private int gestureThreshold;
    private int edgeGestureGuard;
    private int longPressTimeout;
    private float touchStartX;
    private float touchStartY;
    private long touchDownTime;
    private boolean touchGestureEligible;
    private boolean touchGestureHandled;
    private boolean touchMovedBeyondSlop;

    private Object backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);

        configureWindow();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        contentContainer = new FrameLayout(this);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);

        progressBar = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        progressBar.setMax(100);

        contentContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        );
        progressParams.gravity = android.view.Gravity.TOP;
        contentContainer.addView(progressBar, progressParams);

        root.addView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        statusBarScrim = new View(this);
        statusBarScrim.setBackgroundColor(Color.WHITE);
        statusBarScrim.setClickable(false);
        statusBarScrim.setFocusable(false);
        statusBarScrim.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );

        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        );
        scrimParams.gravity = android.view.Gravity.TOP;
        root.addView(statusBarScrim, scrimParams);

        setContentView(root);

        configureSystemBars();
        configureWebView();
        configureAutoStatusBar();
        registerBackHandler();

        String url = readConfiguredUrl();
        if (url == null || !url.startsWith("https://")) {
            showConfigurationError();
            return;
        }
        webView.loadUrl(url);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.WHITE);

        View decorView = window.getDecorView();
        int visibility = decorView.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(visibility);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }

    /**
     * The WebView remains the same height whether the status bar is shown or hidden.
     * A native overlay follows the status bar animation instead of resizing the page.
     */
    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30SystemBars.install(this);
        } else {
            configureLegacySystemBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void configureLegacySystemBars() {
        root.setFitsSystemWindows(false);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int leftInset = 0;
            int rightInset = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    topInset = Math.max(topInset, cutout.getSafeInsetTop());
                    leftInset = cutout.getSafeInsetLeft();
                    rightInset = cutout.getSafeInsetRight();
                }
            }

            updateHorizontalCutoutPadding(leftInset, rightInset);
            updateStatusBarOverlayHeight(topInset);

            int visibility = getWindow().getDecorView()
                    .getSystemUiVisibility();
            boolean hidden =
                    (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
            actualStatusBarHidden = hidden;

            if (!statusBarAnimationRunning
                    && !statusBarTransitionPending) {
                setScrimToFinalState(hidden);
            }

            /*
             * Remove only top/side native insets before WebView receives them.
             * Keep the bottom inset so WebView still receives navigation bar and
             * keyboard overlap information.
             */
            return insets.replaceSystemWindowInsets(
                    0,
                    0,
                    0,
                    insets.getSystemWindowInsetBottom()
            );
        });

        getWindow().getDecorView()
                .setOnSystemUiVisibilityChangeListener(visibility -> {
                    boolean hidden =
                            (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
                    boolean alreadyAnimatingTarget =
                            statusBarAnimationRunning
                                    && requestedStatusBarHidden == hidden;

                    actualStatusBarHidden = hidden;
                    requestedStatusBarHidden = hidden;
                    statusBarTransitionPending = false;

                    if (!alreadyAnimatingTarget) {
                        animateLegacyScrim(hidden);
                    }

                    root.requestApplyInsets();
                });

        root.post(root::requestApplyInsets);
    }

    private void updateHorizontalCutoutPadding(
            int leftInset,
            int rightInset
    ) {
        if (contentContainer.getPaddingLeft() == leftInset
                && contentContainer.getPaddingRight() == rightInset) {
            return;
        }

        contentContainer.setPadding(
                leftInset,
                0,
                rightInset,
                0
        );
    }

    private void updateStatusBarOverlayHeight(int height) {
        int safeHeight = Math.max(0, height);
        if (statusBarOverlayHeight == safeHeight) {
            return;
        }

        statusBarOverlayHeight = safeHeight;

        ViewGroup.LayoutParams rawParams =
                statusBarScrim.getLayoutParams();
        if (rawParams.height != safeHeight) {
            rawParams.height = safeHeight;
            statusBarScrim.setLayoutParams(rawParams);
        }

        if (!statusBarAnimationRunning
                && !statusBarTransitionPending) {
            setScrimToFinalState(actualStatusBarHidden);
        }
    }

    private void setScrimToFinalState(boolean hidden) {
        statusBarScrim.animate().cancel();
        statusBarScrim.setTranslationY(
                hidden ? -statusBarOverlayHeight : 0f
        );
    }

    private void animateLegacyScrim(boolean hidden) {
        statusBarAnimationRunning = true;
        statusBarScrim.animate().cancel();
        statusBarScrim.animate()
                .translationY(
                        hidden ? -statusBarOverlayHeight : 0f
                )
                .setDuration(LEGACY_BAR_ANIMATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    statusBarAnimationRunning = false;
                    statusBarTransitionPending = false;
                    setScrimToFinalState(actualStatusBarHidden);
                })
                .start();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
        );
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        String userAgent = settings.getUserAgentString();
        if (userAgent != null) {
            settings.setUserAgentString(
                    userAgent
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")
            );
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error
            ) {
                handler.cancel();
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                progressBar.setProgress(100);
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(
                    WebView view,
                    int newProgress
            ) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(
                        newProgress >= 100
                                ? View.GONE
                                : View.VISIBLE
                );
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = filePathCallback;
                return launchSystemFilePicker(fileChooserParams);
            }
        });

        webView.setDownloadListener((
                url,
                downloadUserAgent,
                contentDisposition,
                mimeType,
                contentLength
        ) -> startDownload(
                url,
                downloadUserAgent,
                contentDisposition,
                mimeType
        ));

        webView.setOnLongClickListener(
                this::handleWebViewLongClick
        );
    }

    private boolean handleWebViewLongClick(View ignored) {
        if (forwardingDefaultLongClick
                || webView == null) {
            return false;
        }

        WebView.HitTestResult hit =
                webView.getHitTestResult();

        if (hit.getType()
                == WebView.HitTestResult.IMAGE_TYPE
                && isHttpsUrl(hit.getExtra())) {
            showDownloadDialog(
                    "image",
                    hit.getExtra(),
                    null
            );
            return true;
        }

        inspectLongPressedElement();
        return true;
    }

    private void inspectLongPressedElement() {
        if (webView == null) {
            return;
        }

        String script = String.format(
                Locale.US,
                "(function(x,y){"
                        + "try{"
                        + "var r=window.devicePixelRatio||1;"
                        + "var e=document.elementFromPoint(x/r,y/r);"
                        + "while(e&&e!==document.documentElement){"
                        + "var t=(e.tagName||'').toLowerCase();"
                        + "if(t==='img'){"
                        + "return ['image',e.currentSrc||e.src||'',''];"
                        + "}"
                        + "if(t==='video'){"
                        + "var s=e.currentSrc||e.src||'';"
                        + "var m=e.getAttribute('type')||'';"
                        + "if(!s){"
                        + "var q=e.querySelector('source[src]');"
                        + "if(q){s=q.src||'';m=q.type||m;}"
                        + "}"
                        + "return ['video',s,m];"
                        + "}"
                        + "if(t==='source'&&e.parentElement"
                        + "&&(e.parentElement.tagName||'')"
                        + ".toLowerCase()==='video'){"
                        + "return ['video',e.src||'',e.type||''];"
                        + "}"
                        + "if(t==='a'&&e.href){"
                        + "var h=e.href||'';"
                        + "var p=h.split('#')[0].split('?')[0]"
                        + ".toLowerCase();"
                        + "var d=e.hasAttribute('download');"
                        + "var media=/\\.(png|jpe?g|gif|webp|bmp|svg|avif"
                        + "|mp4|webm|mov|m4v|mkv|avi|m3u8)$/i.test(p);"
                        + "if(d||media){return ['link',h,''];}"
                        + "}"
                        + "e=e.parentElement;"
                        + "}"
                        + "return null;"
                        + "}catch(error){return null;}"
                        + "})(%.2f,%.2f);",
                lastTouchViewX,
                lastTouchViewY
        );

        webView.evaluateJavascript(script, value -> {
            if (value == null
                    || "null".equals(value)) {
                performDefaultWebViewLongClick();
                return;
            }

            try {
                JSONArray result = new JSONArray(value);
                String kind = result.optString(0, "");
                String url = result.optString(1, "");
                String mimeType = result.optString(2, "");

                if (url.isEmpty()) {
                    performDefaultWebViewLongClick();
                    return;
                }

                showDownloadDialog(
                        kind,
                        url,
                        mimeType
                );
            } catch (Exception error) {
                performDefaultWebViewLongClick();
            }
        });
    }

    private void performDefaultWebViewLongClick() {
        if (webView == null
                || forwardingDefaultLongClick) {
            return;
        }

        forwardingDefaultLongClick = true;
        webView.setOnLongClickListener(null);
        webView.performLongClick(
                lastTouchViewX,
                lastTouchViewY
        );

        webView.post(() -> {
            if (webView != null) {
                webView.setOnLongClickListener(
                        this::handleWebViewLongClick
                );
            }
            forwardingDefaultLongClick = false;
        });
    }

    private void showDownloadDialog(
            String kind,
            String url,
            String mimeType
    ) {
        String normalizedKind = classifyKind(
                kind,
                mimeType,
                url
        );

        String title;
        if ("image".equals(normalizedKind)) {
            title = "图片";
        } else if ("video".equals(normalizedKind)) {
            title = "视频";
        } else {
            title = "文件";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(
                        new String[]{
                                "保存到手机",
                                "用外部应用打开"
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                startDownload(
                                        url,
                                        webView == null
                                                ? null
                                                : webView.getSettings()
                                                .getUserAgentString(),
                                        null,
                                        mimeType,
                                        normalizedKind
                                );
                            } else {
                                openInExternalApp(url);
                            }
                        }
                )
                .show();
    }

    private void openInExternalApp(String url) {
        try {
            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
            );
            startActivity(intent);
        } catch (Exception error) {
            toast("没有可打开该资源的应用");
        }
    }

    private void startDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType
    ) {
        startDownload(
                url,
                userAgent,
                contentDisposition,
                mimeType,
                ""
        );
    }

    private void startDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            String suggestedKind
    ) {
        PendingDownload download = createPendingDownload(
                url,
                userAgent,
                contentDisposition,
                mimeType,
                suggestedKind
        );

        if (download == null) {
            return;
        }

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.Q) {
            enqueueSystemDownload(download);
        } else {
            chooseLegacyDownloadDestination(download);
        }
    }

    private PendingDownload createPendingDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            String suggestedKind
    ) {
        if (!isHttpsUrl(url)) {
            if (url != null
                    && url.toLowerCase(Locale.US)
                    .startsWith("blob:")) {
                toast("该资源是 Blob 临时地址，当前不能直接下载");
            } else {
                toast("只支持 HTTPS 直链下载");
            }
            return null;
        }

        String normalizedMime =
                normalizeDownloadMimeType(mimeType, url);
        String normalizedKind = classifyKind(
                suggestedKind,
                normalizedMime,
                url
        );

        if (isStreamingPlaylist(url, normalizedMime)) {
            toast("暂不支持 M3U8、分片流或 DRM 视频");
            return null;
        }

        String fileName = URLUtil.guessFileName(
                url,
                contentDisposition,
                normalizedMime
        );
        fileName = sanitizeDownloadFileName(
                fileName,
                normalizedMime
        );

        String referer =
                webView == null ? null : webView.getUrl();
        String cookie = CookieManager.getInstance()
                .getCookie(url);

        return new PendingDownload(
                url,
                fileName,
                normalizedMime,
                normalizedKind,
                userAgent,
                cookie,
                referer
        );
    }

    private void enqueueSystemDownload(
            PendingDownload download
    ) {
        try {
            DownloadManager.Request request =
                    new DownloadManager.Request(
                            Uri.parse(download.url)
                    );

            request.setTitle(download.fileName);
            request.setDescription("WebtoApp 下载");
            request.setNotificationVisibility(
                    DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            if (download.mimeType != null) {
                request.setMimeType(download.mimeType);
            }
            addDownloadHeaders(request, download);

            String directory = directoryForDownload(
                    download.kind,
                    download.mimeType,
                    download.url
            );

            request.setDestinationInExternalPublicDir(
                    directory,
                    "WebtoApp/" + download.fileName
            );

            DownloadManager manager =
                    (DownloadManager) getSystemService(
                            DOWNLOAD_SERVICE
                    );

            if (manager == null) {
                toast("系统下载服务不可用");
                return;
            }

            manager.enqueue(request);
            toast("已加入系统下载任务");
        } catch (Exception error) {
            toast("无法开始下载：" + readableMessage(error));
        }
    }

    private static void addDownloadHeaders(
            DownloadManager.Request request,
            PendingDownload download
    ) {
        if (download.userAgent != null
                && !download.userAgent.isEmpty()) {
            request.addRequestHeader(
                    "User-Agent",
                    download.userAgent
            );
        }
        if (download.cookie != null
                && !download.cookie.isEmpty()) {
            request.addRequestHeader(
                    "Cookie",
                    download.cookie
            );
        }
        if (download.referer != null
                && isHttpsUrl(download.referer)) {
            request.addRequestHeader(
                    "Referer",
                    download.referer
            );
        }
    }

    private void chooseLegacyDownloadDestination(
            PendingDownload download
    ) {
        if (pendingLegacyDownload != null) {
            toast("请先完成当前文件的保存");
            return;
        }

        pendingLegacyDownload = download;

        Intent intent = new Intent(
                Intent.ACTION_CREATE_DOCUMENT
        );
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(
                download.mimeType == null
                        ? "application/octet-stream"
                        : download.mimeType
        );
        intent.putExtra(
                Intent.EXTRA_TITLE,
                download.fileName
        );

        try {
            startActivityForResult(
                    intent,
                    SAVE_DOWNLOAD_REQUEST
            );
        } catch (ActivityNotFoundException error) {
            pendingLegacyDownload = null;
            toast("系统没有可用的文件保存器");
        }
    }

    private void downloadToDocument(
            Uri destination,
            PendingDownload download
    ) {
        toast("开始下载，请保持网络连接");

        legacyDownloadExecutor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                connection = openDownloadConnection(download);

                try (InputStream input =
                             new BufferedInputStream(
                                     connection.getInputStream()
                             );
                     OutputStream output =
                             getContentResolver()
                                     .openOutputStream(
                                             destination,
                                             "w"
                                     )) {
                    if (output == null) {
                        throw new IllegalStateException(
                                "无法打开保存位置"
                        );
                    }

                    byte[] buffer = new byte[64 * 1024];
                    int read;

                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }

                    output.flush();
                }

                mainHandler.post(
                        () -> toast("下载完成")
                );
            } catch (Exception error) {
                try {
                    getContentResolver().delete(
                            destination,
                            null,
                            null
                    );
                } catch (Exception ignored) {
                    // Some document providers do not support delete.
                }

                mainHandler.post(
                        () -> toast(
                                "下载失败："
                                        + readableMessage(error)
                        )
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private HttpURLConnection openDownloadConnection(
            PendingDownload download
    ) throws Exception {
        URL originalUrl = new URL(download.url);
        URL currentUrl = originalUrl;

        for (int redirect = 0;
                redirect <= MAX_DOWNLOAD_REDIRECTS;
                redirect++) {
            HttpURLConnection connection =
                    (HttpURLConnection)
                            currentUrl.openConnection();

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty(
                    "Accept",
                    "*/*"
            );

            if (download.userAgent != null
                    && !download.userAgent.isEmpty()) {
                connection.setRequestProperty(
                        "User-Agent",
                        download.userAgent
                );
            }

            if (download.cookie != null
                    && !download.cookie.isEmpty()
                    && sameHost(originalUrl, currentUrl)) {
                connection.setRequestProperty(
                        "Cookie",
                        download.cookie
                );
            }

            if (download.referer != null
                    && isHttpsUrl(download.referer)) {
                connection.setRequestProperty(
                        "Referer",
                        download.referer
                );
            }

            int responseCode =
                    connection.getResponseCode();

            if (responseCode >= 300
                    && responseCode < 400) {
                String location =
                        connection.getHeaderField(
                                "Location"
                        );
                connection.disconnect();

                if (location == null
                        || location.isEmpty()) {
                    throw new IllegalStateException(
                            "下载重定向地址无效"
                    );
                }

                currentUrl = new URL(
                        currentUrl,
                        location
                );
                continue;
            }

            if (responseCode < 200
                    || responseCode >= 300) {
                connection.disconnect();
                throw new IllegalStateException(
                        "服务器返回 " + responseCode
                );
            }

            return connection;
        }

        throw new IllegalStateException(
                "下载重定向次数过多"
        );
    }

    private static boolean sameHost(
            URL first,
            URL second
    ) {
        return first.getProtocol()
                .equalsIgnoreCase(second.getProtocol())
                && first.getHost()
                .equalsIgnoreCase(second.getHost())
                && effectivePort(first)
                == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        int port = url.getPort();
        return port >= 0
                ? port
                : url.getDefaultPort();
    }

    private static String directoryForDownload(
            String suggestedKind,
            String mimeType,
            String url
    ) {
        String kind = classifyKind(
                suggestedKind,
                mimeType,
                url
        );

        if ("image".equals(kind)) {
            return Environment.DIRECTORY_PICTURES;
        }
        if ("video".equals(kind)) {
            return Environment.DIRECTORY_MOVIES;
        }
        return Environment.DIRECTORY_DOWNLOADS;
    }

    private static String classifyKind(
            String suggestedKind,
            String mimeType,
            String url
    ) {
        if ("image".equalsIgnoreCase(suggestedKind)
                || "video".equalsIgnoreCase(
                        suggestedKind
                )) {
            return suggestedKind.toLowerCase(Locale.US);
        }

        String normalizedMime =
                normalizeDownloadMimeType(
                        mimeType,
                        url
                );

        if (normalizedMime != null) {
            if (normalizedMime.startsWith("image/")) {
                return "image";
            }
            if (normalizedMime.startsWith("video/")) {
                return "video";
            }
        }

        return "file";
    }

    private static String normalizeDownloadMimeType(
            String mimeType,
            String url
    ) {
        String normalized = null;

        if (mimeType != null) {
            normalized = mimeType.trim()
                    .toLowerCase(Locale.US);
            int semicolon = normalized.indexOf(';');
            if (semicolon >= 0) {
                normalized = normalized
                        .substring(0, semicolon)
                        .trim();
            }
            if (normalized.isEmpty()
                    || "application/octet-stream"
                    .equals(normalized)) {
                normalized = null;
            }
        }

        if (normalized == null
                && url != null) {
            String extension =
                    MimeTypeMap.getFileExtensionFromUrl(
                            url
                    );
            if (extension != null
                    && !extension.isEmpty()) {
                normalized = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(
                                extension.toLowerCase(
                                        Locale.US
                                )
                        );
            }
        }

        return normalized;
    }

    private static String sanitizeDownloadFileName(
            String fileName,
            String mimeType
    ) {
        String safe = fileName == null
                ? "download"
                : fileName.trim();

        safe = safe.replaceAll(
                "[\\\\/:*?\"<>|\\p{Cntrl}]",
                "_"
        );

        if (safe.isEmpty()
                || ".".equals(safe)
                || "..".equals(safe)) {
            safe = "download";
        }

        if (!safe.contains(".")
                && mimeType != null) {
            String extension =
                    MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(
                                    mimeType
                            );
            if (extension != null
                    && !extension.isEmpty()) {
                safe += "." + extension;
            }
        }

        if (safe.length() > 120) {
            int extensionIndex = safe.lastIndexOf('.');
            String extension =
                    extensionIndex > 0
                            ? safe.substring(extensionIndex)
                            : "";
            int baseLimit = Math.max(
                    1,
                    120 - extension.length()
            );
            safe = safe.substring(0, baseLimit)
                    + extension;
        }

        return safe;
    }

    private static boolean isStreamingPlaylist(
            String url,
            String mimeType
    ) {
        if (mimeType != null) {
            String normalized =
                    mimeType.toLowerCase(Locale.US);
            if (normalized.contains("mpegurl")
                    || normalized.contains(
                            "dash+xml"
                    )) {
                return true;
            }
        }

        String lowerUrl = url.toLowerCase(Locale.US);
        int queryIndex = lowerUrl.indexOf('?');
        if (queryIndex >= 0) {
            lowerUrl = lowerUrl.substring(
                    0,
                    queryIndex
            );
        }

        return lowerUrl.endsWith(".m3u8")
                || lowerUrl.endsWith(".mpd");
    }

    private static boolean isHttpsUrl(String value) {
        if (value == null) {
            return false;
        }

        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();

        return "https".equalsIgnoreCase(scheme);
    }

    private static String readableMessage(
            Exception error
    ) {
        String message = error.getMessage();
        if (message == null
                || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void configureAutoStatusBar() {
        ViewConfiguration configuration =
                ViewConfiguration.get(this);

        touchSlop = configuration.getScaledTouchSlop();
        gestureThreshold = dp(28);
        edgeGestureGuard = dp(28);
        longPressTimeout = ViewConfiguration.getLongPressTimeout();

        webView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    beginStatusBarGesture(event);
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelStatusBarGesture();
                    break;

                case MotionEvent.ACTION_MOVE:
                    updateStatusBarGesture(event);
                    break;

                case MotionEvent.ACTION_UP:
                    finishStatusBarGesture(view, event);
                    break;

                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                    cancelStatusBarGesture();
                    break;

                default:
                    break;
            }

            return false;
        });
    }

    private void beginStatusBarGesture(MotionEvent event) {
        touchStartX = event.getRawX();
        touchStartY = event.getRawY();
        lastTouchViewX = event.getX();
        lastTouchViewY = event.getY();
        touchDownTime = SystemClock.uptimeMillis();

        float windowWidth = root.getWidth();
        boolean startsNearSystemBackEdge =
                touchStartX <= edgeGestureGuard
                        || touchStartX
                        >= windowWidth - edgeGestureGuard;

        touchGestureEligible =
                event.getPointerCount() == 1
                        && !startsNearSystemBackEdge;
        touchGestureHandled = false;
        touchMovedBeyondSlop = false;
    }

    private void updateStatusBarGesture(MotionEvent event) {
        if (!touchGestureEligible
                || touchGestureHandled
                || event.getPointerCount() != 1) {
            return;
        }

        float movedX = event.getRawX() - touchStartX;
        float movedY = event.getRawY() - touchStartY;

        if (Math.abs(movedX) > touchSlop
                || Math.abs(movedY) > touchSlop) {
            touchMovedBeyondSlop = true;
        }

        boolean clearlyVertical =
                Math.abs(movedY)
                        > Math.abs(movedX) * 1.25f;

        if (!clearlyVertical
                || Math.abs(movedY) < gestureThreshold) {
            return;
        }

        if (movedY < 0f) {
            hideStatusBar();
        } else {
            showStatusBar();
        }

        touchGestureHandled = true;
    }

    private void finishStatusBarGesture(
            View view,
            MotionEvent event
    ) {
        long pressDuration =
                SystemClock.uptimeMillis() - touchDownTime;

        float movedX = Math.abs(
                event.getRawX() - touchStartX
        );
        float movedY = Math.abs(
                event.getRawY() - touchStartY
        );

        boolean simpleTap =
                touchGestureEligible
                        && !touchGestureHandled
                        && !touchMovedBeyondSlop
                        && movedX <= touchSlop
                        && movedY <= touchSlop
                        && pressDuration < longPressTimeout;

        if (simpleTap) {
            /*
             * The page keeps a constant viewport, so showing the bar after
             * the click does not resize the WebView or move the clicked target.
             */
            view.postOnAnimation(this::showStatusBar);
        }

        cancelStatusBarGesture();
    }

    private void cancelStatusBarGesture() {
        touchGestureEligible = false;
        touchGestureHandled = false;
        touchMovedBeyondSlop = false;
    }

    private void hideStatusBar() {
        requestStatusBarVisibility(true);
    }

    private void showStatusBar() {
        requestStatusBarVisibility(false);
    }

    private void requestStatusBarVisibility(boolean hidden) {
        if (requestedStatusBarHidden == hidden
                && actualStatusBarHidden == hidden
                && !statusBarTransitionPending
                && !statusBarAnimationRunning) {
            return;
        }

        requestedStatusBarHidden = hidden;
        statusBarTransitionPending = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30SystemBars.setHidden(this, hidden);
        } else {
            setLegacyStatusBarHidden(hidden);
        }
    }

    @SuppressWarnings("deprecation")
    private void setLegacyStatusBarHidden(boolean hidden) {
        View decorView = getWindow().getDecorView();
        int currentVisibility = decorView.getSystemUiVisibility();
        boolean currentlyHidden =
                (currentVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;

        int visibility = currentVisibility
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }

        if (hidden) {
            visibility |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        } else {
            visibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
        }

        if (currentlyHidden == hidden) {
            actualStatusBarHidden = hidden;
            statusBarTransitionPending = false;
            setScrimToFinalState(hidden);
            return;
        }

        animateLegacyScrim(hidden);
        decorView.setSystemUiVisibility(visibility);
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = Api33BackHandler.register(
                    this,
                    this::handleBackNavigation
            );
        }
    }

    private void unregisterBackHandler() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU
                && backCallback != null) {
            Api33BackHandler.unregister(this, backCallback);
            backCallback = null;
        }
    }

    private void handleBackNavigation() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private boolean launchSystemFilePicker(
            WebChromeClient.FileChooserParams params
    ) {
        Intent intent = new Intent(
                Intent.ACTION_OPEN_DOCUMENT
        );
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        String[] mimeTypes = normalizeMimeTypes(
                params.getAcceptTypes()
        );

        if (mimeTypes.length == 1) {
            intent.setType(mimeTypes[0]);
        } else {
            intent.setType("*/*");
            if (mimeTypes.length > 1) {
                intent.putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        mimeTypes
                );
            }
        }

        intent.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode()
                        == WebChromeClient.FileChooserParams
                        .MODE_OPEN_MULTIPLE
        );

        try {
            startActivityForResult(
                    intent,
                    FILE_CHOOSER_REQUEST
            );
            return true;
        } catch (ActivityNotFoundException firstError) {
            Intent fallback = new Intent(
                    Intent.ACTION_GET_CONTENT
            );
            fallback.addCategory(
                    Intent.CATEGORY_OPENABLE
            );
            fallback.setType(
                    mimeTypes.length == 1
                            ? mimeTypes[0]
                            : "*/*"
            );

            if (mimeTypes.length > 1) {
                fallback.putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        mimeTypes
                );
            }

            fallback.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode()
                            == WebChromeClient.FileChooserParams
                            .MODE_OPEN_MULTIPLE
            );

            try {
                startActivityForResult(
                        fallback,
                        FILE_CHOOSER_REQUEST
                );
                return true;
            } catch (ActivityNotFoundException secondError) {
                if (pendingFileCallback != null) {
                    pendingFileCallback
                            .onReceiveValue(null);
                    pendingFileCallback = null;
                }
                return false;
            }
        }
    }

    private static String[] normalizeMimeTypes(
            String[] acceptTypes
    ) {
        Set<String> normalized =
                new LinkedHashSet<>();

        if (acceptTypes != null) {
            for (String acceptType : acceptTypes) {
                if (acceptType == null) {
                    continue;
                }

                for (String token
                        : acceptType.split(",")) {
                    String value = token
                            .trim()
                            .toLowerCase(Locale.US);

                    if (value.isEmpty()) {
                        continue;
                    }

                    if (value.startsWith(".")) {
                        String extension =
                                value.substring(1);
                        String mime =
                                MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(
                                                extension
                                        );
                        if (mime != null) {
                            normalized.add(mime);
                        }
                    } else if (value.contains("/")) {
                        normalized.add(value);
                    }
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add("*/*");
        }

        return normalized.toArray(new String[0]);
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();

        if ("https".equalsIgnoreCase(scheme)) {
            return false;
        }

        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    uri
            ));
        } catch (ActivityNotFoundException ignored) {
            // Ignore unsupported custom schemes.
        }

        return true;
    }

    private String readConfiguredUrl() {
        try (InputStream input = getAssets()
                .open("app_config.json")) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            JSONObject json = new JSONObject(
                    output.toString(
                            StandardCharsets.UTF_8.name()
                    )
            );
            return json.optString("url", null);
        } catch (Exception error) {
            return null;
        }
    }

    private void showConfigurationError() {
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        TextView error = new TextView(this);
        error.setText(
                "应用配置无效：仅支持 HTTPS 地址。"
        );
        error.setTextColor(Color.DKGRAY);
        error.setTextSize(17f);
        error.setGravity(
                android.view.Gravity.CENTER
        );

        contentContainer.addView(
                error,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == SAVE_DOWNLOAD_REQUEST) {
            PendingDownload download =
                    pendingLegacyDownload;
            pendingLegacyDownload = null;

            if (resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null
                    && download != null) {
                downloadToDocument(
                        data.getData(),
                        download
                );
            }
            return;
        }

        if (requestCode != FILE_CHOOSER_REQUEST
                || pendingFileCallback == null) {
            return;
        }

        Uri[] result = null;

        if (resultCode == RESULT_OK
                && data != null) {
            ClipData clipData = data.getClipData();

            if (clipData != null
                    && clipData.getItemCount() > 0) {
                ArrayList<Uri> selected =
                        new ArrayList<>();

                for (int index = 0;
                        index < clipData.getItemCount();
                        index++) {
                    Uri uri = clipData
                            .getItemAt(index)
                            .getUri();

                    if (uri != null) {
                        selected.add(uri);
                    }
                }

                result = selected.toArray(
                        new Uri[0]
                );
            } else if (data.getData() != null) {
                result = new Uri[]{
                        data.getData()
                };
            }
        }

        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onPause() {
        cancelStatusBarGesture();
        statusBarScrim.animate().cancel();
        statusBarAnimationRunning = false;
        activeStatusBarAnimation = null;
        statusBarTransitionPending = false;
        setScrimToFinalState(actualStatusBarHidden);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (root != null) {
            root.post(() -> {
                root.requestApplyInsets();
                reapplyRequestedStatusBarState();
            });
        }
    }

    @Override
    public void onWindowFocusChanged(
            boolean hasFocus
    ) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && root != null) {
            root.post(() -> {
                root.requestApplyInsets();
                reapplyRequestedStatusBarState();
            });
        }
    }

    @Override
    public void onConfigurationChanged(
            Configuration newConfig
    ) {
        super.onConfigurationChanged(newConfig);
        cancelStatusBarGesture();
        statusBarScrim.animate().cancel();
        statusBarAnimationRunning = false;
        activeStatusBarAnimation = null;
        statusBarTransitionPending = false;

        if (root != null) {
            root.post(() -> {
                root.requestApplyInsets();
                reapplyRequestedStatusBarState();
            });
        }
    }

    private void reapplyRequestedStatusBarState() {
        statusBarTransitionPending = true;

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.R) {
            Api30SystemBars.setHidden(
                    this,
                    requestedStatusBarHidden
            );
        } else {
            setLegacyStatusBarHidden(
                    requestedStatusBarHidden
            );
        }
    }

    @Override
    protected void onDestroy() {
        unregisterBackHandler();
        mainHandler.removeCallbacksAndMessages(null);
        legacyDownloadExecutor.shutdownNow();
        pendingLegacyDownload = null;

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.R) {
            Api30SystemBars.remove(this);
        } else if (root != null) {
            root.setOnApplyWindowInsetsListener(null);
            getWindow().getDecorView()
                    .setOnSystemUiVisibilityChangeListener(
                            null
                    );
        }

        if (statusBarScrim != null) {
            statusBarScrim.animate().cancel();
        }

        if (pendingFileCallback != null) {
            pendingFileCallback
                    .onReceiveValue(null);
            pendingFileCallback = null;
        }

        if (webView != null) {
            ViewGroup parent =
                    (ViewGroup) webView.getParent();

            if (parent != null) {
                parent.removeView(webView);
            }

            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private static final class PendingDownload {
        final String url;
        final String fileName;
        final String mimeType;
        final String kind;
        final String userAgent;
        final String cookie;
        final String referer;

        PendingDownload(
                String url,
                String fileName,
                String mimeType,
                String kind,
                String userAgent,
                String cookie,
                String referer
        ) {
            this.url = url;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.kind = kind;
            this.userAgent = userAgent;
            this.cookie = cookie;
            this.referer = referer;
        }
    }

    /**
     * API 30+ implementation is isolated so older Android versions do not need
     * to resolve WindowInsetsAnimation and WindowInsetsController classes.
     */
    private static final class Api30SystemBars {
        private Api30SystemBars() {
        }

        static void install(MainActivity activity) {
            Window window = activity.getWindow();
            window.setDecorFitsSystemWindows(false);

            WindowInsetsController controller =
                    window.getInsetsController();

            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController
                                .BEHAVIOR_DEFAULT
                );
                controller.setSystemBarsAppearance(
                        WindowInsetsController
                                .APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController
                                .APPEARANCE_LIGHT_STATUS_BARS
                );
            }

            activity.root.setOnApplyWindowInsetsListener(
                    (view, insets) -> {
                        android.graphics.Insets
                                stableStatus =
                                insets.getInsetsIgnoringVisibility(
                                        WindowInsets.Type
                                                .statusBars()
                                );

                        android.graphics.Insets
                                stableCutout =
                                insets.getInsetsIgnoringVisibility(
                                        WindowInsets.Type
                                                .displayCutout()
                                );

                        int overlayHeight = Math.max(
                                stableStatus.top,
                                stableCutout.top
                        );

                        activity.updateHorizontalCutoutPadding(
                                stableCutout.left,
                                stableCutout.right
                        );
                        activity.updateStatusBarOverlayHeight(
                                overlayHeight
                        );

                        boolean hidden =
                                !insets.isVisible(
                                        WindowInsets.Type
                                                .statusBars()
                                );

                        activity.actualStatusBarHidden =
                                hidden;

                        if (!activity.statusBarAnimationRunning
                                && !activity
                                .statusBarTransitionPending) {
                            activity.setScrimToFinalState(
                                    hidden
                            );
                        }

                        /*
                         * WebView keeps receiving navigation-bar and IME insets.
                         * Only the top status-bar/cutout insets are handled by
                         * the native overlay, so the page viewport does not resize
                         * when the status bar toggles.
                         */
                        return new WindowInsets.Builder(
                                insets
                        )
                                .setInsets(
                                        WindowInsets.Type
                                                .statusBars()
                                                | WindowInsets.Type
                                                .displayCutout(),
                                        android.graphics.Insets
                                                .NONE
                                )
                                .setInsetsIgnoringVisibility(
                                        WindowInsets.Type
                                                .statusBars()
                                                | WindowInsets.Type
                                                .displayCutout(),
                                        android.graphics.Insets
                                                .NONE
                                )
                                .setVisible(
                                        WindowInsets.Type
                                                .statusBars(),
                                        false
                                )
                                .setDisplayCutout(null)
                                .build();
                    }
            );

            activity.root
                    .setWindowInsetsAnimationCallback(
                            new WindowInsetsAnimation.Callback(
                                    WindowInsetsAnimation
                                            .Callback
                                            .DISPATCH_MODE_CONTINUE_ON_SUBTREE
                            ) {
                                @Override
                                public void onPrepare(
                                        WindowInsetsAnimation animation
                                ) {
                                    if (!isStatusBarAnimation(
                                            animation
                                    )) {
                                        return;
                                    }

                                    activity.statusBarScrim
                                            .animate()
                                            .cancel();

                                    boolean requestedTransition =
                                            activity
                                                    .statusBarTransitionPending;

                                    activity
                                            .statusBarAnimationRunning =
                                            true;
                                    activity
                                            .activeStatusBarAnimation =
                                            animation;
                                    activity
                                            .statusBarTransitionPending =
                                            false;

                                    activity
                                            .statusBarAnimationStartTranslation =
                                            activity.statusBarScrim
                                                    .getTranslationY();

                                    boolean targetHidden =
                                            requestedTransition
                                                    ? activity
                                                    .requestedStatusBarHidden
                                                    : !activity
                                                    .actualStatusBarHidden;

                                    activity
                                            .requestedStatusBarHidden =
                                            targetHidden;
                                    activity
                                            .statusBarAnimationTargetTranslation =
                                            targetHidden
                                                    ? -activity
                                                    .statusBarOverlayHeight
                                                    : 0f;
                                }

                                @Override
                                public WindowInsets onProgress(
                                        WindowInsets insets,
                                        List<WindowInsetsAnimation>
                                                runningAnimations
                                ) {
                                    if (!activity
                                            .statusBarAnimationRunning) {
                                        return insets;
                                    }

                                    for (WindowInsetsAnimation
                                            animation
                                            : runningAnimations) {
                                        if (!isStatusBarAnimation(
                                                animation
                                        )
                                                || activity
                                                .activeStatusBarAnimation
                                                != animation) {
                                            continue;
                                        }

                                        float fraction =
                                                animation
                                                        .getInterpolatedFraction();

                                        float start =
                                                activity
                                                        .statusBarAnimationStartTranslation;
                                        float end =
                                                activity
                                                        .statusBarAnimationTargetTranslation;

                                        activity.statusBarScrim
                                                .setTranslationY(
                                                        start
                                                                + (
                                                                end
                                                                        - start
                                                        )
                                                                * fraction
                                                );
                                        break;
                                    }

                                    return insets;
                                }

                                @Override
                                public void onEnd(
                                        WindowInsetsAnimation animation
                                ) {
                                    if (!isStatusBarAnimation(
                                            animation
                                    )
                                            || activity
                                            .activeStatusBarAnimation
                                            != animation) {
                                        return;
                                    }

                                    activity
                                            .activeStatusBarAnimation =
                                            null;
                                    activity
                                            .statusBarAnimationRunning =
                                            false;
                                    activity
                                            .statusBarTransitionPending =
                                            false;

                                    WindowInsets currentInsets =
                                            activity.root
                                                    .getRootWindowInsets();

                                    if (currentInsets != null) {
                                        boolean hidden =
                                                !currentInsets
                                                        .isVisible(
                                                                WindowInsets.Type
                                                                        .statusBars()
                                                        );
                                        activity
                                            .actualStatusBarHidden =
                                            hidden;
                                        activity
                                            .requestedStatusBarHidden =
                                            hidden;
                                        activity
                                            .setScrimToFinalState(
                                                    hidden
                                            );
                                    } else {
                                        activity
                                            .setScrimToFinalState(
                                                    activity
                                                            .requestedStatusBarHidden
                                            );
                                    }
                                }
                            }
                    );

            activity.root.post(
                    activity.root::requestApplyInsets
            );
        }

        static void setHidden(
                MainActivity activity,
                boolean hidden
        ) {
            WindowInsetsController controller =
                    activity.getWindow()
                            .getInsetsController();

            if (controller == null) {
                activity.statusBarTransitionPending =
                        false;
                activity.actualStatusBarHidden = hidden;
                activity.requestedStatusBarHidden = hidden;
                activity.setScrimToFinalState(hidden);
                return;
            }

            if (hidden) {
                controller.hide(
                        WindowInsets.Type.statusBars()
                );
            } else {
                controller.show(
                        WindowInsets.Type.statusBars()
                );
            }

            /*
             * Fallback for devices that don't dispatch a status-bar animation
             * callback reliably. It moves only the overlay, never the WebView.
             */
            activity.root.postDelayed(() -> {
                if (!activity
                        .statusBarTransitionPending
                        || activity
                        .statusBarAnimationRunning) {
                    return;
                }

                activity.statusBarTransitionPending =
                        false;
                activity.statusBarAnimationRunning =
                        true;
                activity.activeStatusBarAnimation =
                        null;
                activity.statusBarScrim
                        .animate()
                        .cancel();
                activity.statusBarScrim
                        .animate()
                        .translationY(
                                hidden
                                        ? -activity
                                        .statusBarOverlayHeight
                                        : 0f
                        )
                        .setDuration(
                                LEGACY_BAR_ANIMATION_MS
                        )
                        .setInterpolator(
                                new DecelerateInterpolator()
                        )
                        .withEndAction(() -> {
                            activity
                                    .statusBarAnimationRunning =
                                    false;

                            WindowInsets currentInsets =
                                    activity.root
                                            .getRootWindowInsets();

                            boolean finalHidden = hidden;
                            if (currentInsets != null) {
                                finalHidden =
                                        !currentInsets.isVisible(
                                                WindowInsets.Type
                                                        .statusBars()
                                        );
                            }

                            activity
                                    .actualStatusBarHidden =
                                    finalHidden;
                            activity
                                    .requestedStatusBarHidden =
                                    finalHidden;
                            activity
                                    .setScrimToFinalState(
                                            finalHidden
                                    );
                        })
                        .start();
            }, 48L);
        }

        static void remove(MainActivity activity) {
            activity.root
                    .setWindowInsetsAnimationCallback(null);
            activity.root
                    .setOnApplyWindowInsetsListener(null);
        }

        private static boolean isStatusBarAnimation(
                WindowInsetsAnimation animation
        ) {
            return (
                    animation.getTypeMask()
                            & WindowInsets.Type
                            .statusBars()
            ) != 0;
        }
    }

    /**
     * API 33+ predictive-back registration is isolated for minSdk 26 safety.
     */
    private static final class Api33BackHandler {
        private Api33BackHandler() {
        }

        static Object register(
                Activity activity,
                Runnable action
        ) {
            android.window.OnBackInvokedCallback callback =
                    action::run;

            activity.getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(
                            android.window
                                    .OnBackInvokedDispatcher
                                    .PRIORITY_DEFAULT,
                            callback
                    );

            return callback;
        }

        static void unregister(
                Activity activity,
                Object callback
        ) {
            activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(
                            (android.window
                                    .OnBackInvokedCallback)
                                    callback
                    );
        }
    }
}
