package com.kongda.toapplite.shell;

import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsetsController;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileCallback;
    private int touchSlop;
    private float touchStartX;
    private float touchStartY;
    private float lastTouchY;
    private float verticalAccumulator;
    private boolean statusBarHidden;

    private android.window.OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        configureSystemBars(root);

        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        );
        progressParams.gravity = android.view.Gravity.TOP;
        root.addView(progressBar, progressParams);
        setContentView(root);

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

    private void configureSystemBars(FrameLayout root) {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        View decorView = getWindow().getDecorView();
        int visibility = decorView.getSystemUiVisibility();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }

        decorView.setSystemUiVisibility(visibility);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);

            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets status = insets.getInsets(
                        WindowInsets.Type.statusBars()
                );
                android.graphics.Insets navigation = insets.getInsets(
                        WindowInsets.Type.navigationBars()
                );
                android.graphics.Insets cutout = insets.getInsets(
                        WindowInsets.Type.displayCutout()
                );
                android.graphics.Insets ime = insets.getInsets(
                        WindowInsets.Type.ime()
                );

                boolean statusVisible = insets.isVisible(
                        WindowInsets.Type.statusBars()
                );
                statusBarHidden = !statusVisible;

                int leftPadding = Math.max(
                        Math.max(status.left, navigation.left),
                        cutout.left
                );
                int rightPadding = Math.max(
                        Math.max(status.right, navigation.right),
                        cutout.right
                );

                int topPadding = statusVisible
                        ? Math.max(status.top, cutout.top)
                        : 0;

                int bottomPadding = Math.max(
                        navigation.bottom,
                        ime.bottom
                );

                view.setPadding(
                        leftPadding,
                        topPadding,
                        rightPadding,
                        bottomPadding
                );

                return insets;
            });

            root.post(root::requestApplyInsets);
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        // Required for content:// URIs returned by Android's system file picker.
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Some sites hide upload controls when they detect the generic Android WebView UA.
        // Keep the real Chrome/WebView version while removing only the embedded-view markers.
        String userAgent = settings.getUserAgentString();
        if (userAgent != null) {
            settings.setUserAgentString(
                    userAgent.replace("; wv", "").replace("Version/4.0 ", "")
            );
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setProgress(100);
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
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

        webView.setDownloadListener((url, downloadUserAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // No compatible external application. Do not silently download inside the shell.
            }
        });
    }
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::handleBackNavigation;

            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback
            );
        }
    }

    private void handleBackNavigation() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }
    private void configureAutoStatusBar() {
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final int gestureThreshold = dp(24);

        webView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    lastTouchY = event.getY();
                    verticalAccumulator = 0f;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float totalX = event.getX() - touchStartX;
                    float totalY = event.getY() - touchStartY;
                    float deltaY = event.getY() - lastTouchY;
                    lastTouchY = event.getY();

                    // 只识别明显的纵向滑动，避免与侧边返回冲突。
                    if (Math.abs(totalY) > Math.abs(totalX)) {
                        if (deltaY < 0f) {
                            // 手指向上滑：隐藏状态栏。
                            if (verticalAccumulator > 0f) {
                                verticalAccumulator = 0f;
                            }

                            verticalAccumulator += deltaY;

                            if (-verticalAccumulator >= gestureThreshold) {
                                hideStatusBar();
                                verticalAccumulator = 0f;
                            }
                        } else if (deltaY > 0f) {
                            // 手指向下滑：恢复状态栏。
                            if (verticalAccumulator < 0f) {
                                verticalAccumulator = 0f;
                            }

                            verticalAccumulator += deltaY;

                            if (verticalAccumulator >= gestureThreshold) {
                                showStatusBar();
                                verticalAccumulator = 0f;
                            }
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    float movedX = Math.abs(event.getX() - touchStartX);
                    float movedY = Math.abs(event.getY() - touchStartY);

                    // 轻点网页时恢复状态栏。
                    // 使用 post，避免状态栏出现导致当前点击位置变化。
                    if (movedX <= touchSlop && movedY <= touchSlop) {
                        view.post(this::showStatusBar);
                    }

                    verticalAccumulator = 0f;
                    break;

                case MotionEvent.ACTION_CANCEL:
                    verticalAccumulator = 0f;
                    break;

                default:
                    break;
            }

            // 不拦截触摸，网页点击、滚动和文件上传继续执行。
            return false;
        });
    }

    private void hideStatusBar() {
        if (statusBarHidden) {
            return;
        }

        statusBarHidden = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller =
                    getWindow().getInsetsController();

            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_DEFAULT
                );
                controller.hide(WindowInsets.Type.statusBars());
            }

            getWindow().getDecorView().requestApplyInsets();
        } else {
            View decorView = getWindow().getDecorView();

            decorView.setSystemUiVisibility(
                    decorView.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void showStatusBar() {
        if (!statusBarHidden) {
            return;
        }

        statusBarHidden = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller =
                    getWindow().getInsetsController();

            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars());
            }

            getWindow().getDecorView().requestApplyInsets();
        } else {
            View decorView = getWindow().getDecorView();
            int visibility = decorView.getSystemUiVisibility();

            visibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
            visibility &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

            decorView.setSystemUiVisibility(visibility);
        }
    }
    private boolean launchSystemFilePicker(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String[] mimeTypes = normalizeMimeTypes(params.getAcceptTypes());
        if (mimeTypes.length == 1) {
            intent.setType(mimeTypes[0]);
        } else {
            intent.setType("*/*");
            if (mimeTypes.length > 1) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
        }
        intent.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        );

        try {
            startActivityForResult(intent, FILE_CHOOSER_REQUEST);
            return true;
        } catch (ActivityNotFoundException firstError) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType(mimeTypes.length == 1 ? mimeTypes[0] : "*/*");
            if (mimeTypes.length > 1) {
                fallback.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
            fallback.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            );
            try {
                startActivityForResult(fallback, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException secondError) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                    pendingFileCallback = null;
                }
                return false;
            }
        }
    }

    private static String[] normalizeMimeTypes(String[] acceptTypes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (acceptTypes != null) {
            for (String acceptType : acceptTypes) {
                if (acceptType == null) {
                    continue;
                }
                for (String token : acceptType.split(",")) {
                    String value = token.trim().toLowerCase(Locale.US);
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (value.startsWith(".")) {
                        String extension = value.substring(1);
                        String mime = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(extension);
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
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // Ignore unsupported custom schemes.
        }
        return true;
    }

    private String readConfiguredUrl() {
        try (InputStream input = getAssets().open("app_config.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            return json.optString("url", null);
        } catch (Exception e) {
            return null;
        }
    }

    private void showConfigurationError() {
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        TextView error = new TextView(this);
        error.setText("应用配置无效：仅支持 HTTPS 地址。");
        error.setTextColor(Color.DKGRAY);
        error.setTextSize(17f);
        error.setGravity(android.view.Gravity.CENTER);
        ((ViewGroup) webView.getParent()).addView(error, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileCallback == null) {
            return;
        }

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                ArrayList<Uri> selected = new ArrayList<>();
                for (int index = 0; index < clipData.getItemCount(); index++) {
                    Uri uri = clipData.getItemAt(index).getUri();
                    if (uri != null) {
                        selected.add(uri);
                    }
                }
                result = selected.toArray(new Uri[0]);
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
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
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    backCallback
            );
            backCallback = null;
        }
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
