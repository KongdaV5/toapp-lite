package com.kongda.toapplite.shell;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
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
import java.util.List;
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
    private static final long STATUS_BAR_TOGGLE_COOLDOWN_MS = 220L;
    private static final int STATUS_ACTION_NONE = 0;
    private static final int STATUS_ACTION_HIDE = -1;
    private static final int STATUS_ACTION_SHOW = 1;

    private FrameLayout root;
    private FrameLayout contentContainer;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileCallback;

    private int touchSlop;
    private float touchStartX;
    private float touchStartY;
    private boolean statusBarGestureHandled;
    private int pendingStatusAction = STATUS_ACTION_NONE;
    private boolean statusBarHidden;
    private long lastStatusBarToggleAt;

    private android.window.OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        contentContainer = new FrameLayout(this);
        webView = new WebView(this);
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

    /**
     * Applies final safe-area padding only once per inset state, then uses a compositor
     * translation during the system status-bar animation. This avoids repeatedly resizing
     * and relaying out the WebView on every animation frame.
     */
    private void configureSystemBars() {
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

            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_DEFAULT
                );
            }

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

                if (view.getPaddingLeft() != leftPadding
                        || view.getPaddingTop() != topPadding
                        || view.getPaddingRight() != rightPadding
                        || view.getPaddingBottom() != bottomPadding) {
                    view.setPadding(
                            leftPadding,
                            topPadding,
                            rightPadding,
                            bottomPadding
                    );
                }

                /*
                 * The native root has already consumed these dimensions as padding.
                 * Zero them before dispatching to WebView so WebView does not apply the
                 * same system-bar/IME adjustment a second time.
                 */
                return new WindowInsets.Builder(insets)
                        .setInsets(
                                WindowInsets.Type.statusBars()
                                        | WindowInsets.Type.navigationBars()
                                        | WindowInsets.Type.displayCutout()
                                        | WindowInsets.Type.ime(),
                                android.graphics.Insets.NONE
                        )
                        .build();
            });

            root.setWindowInsetsAnimationCallback(
                    new WindowInsetsAnimation.Callback(
                            WindowInsetsAnimation.Callback
                                    .DISPATCH_MODE_CONTINUE_ON_SUBTREE
                    ) {
                        private int startContentY;
                        private int translationDeltaY;
                        private boolean statusAnimationRunning;

                        @Override
                        public void onPrepare(WindowInsetsAnimation animation) {
                            if (!isStatusBarAnimation(animation)) {
                                return;
                            }

                            contentContainer.animate().cancel();
                            contentContainer.setTranslationY(0f);

                            int[] location = new int[2];
                            contentContainer.getLocationOnScreen(location);
                            startContentY = location[1];
                            translationDeltaY = 0;
                            statusAnimationRunning = true;
                        }

                        @Override
                        public WindowInsetsAnimation.Bounds onStart(
                                WindowInsetsAnimation animation,
                                WindowInsetsAnimation.Bounds bounds
                        ) {
                            if (!isStatusBarAnimation(animation)) {
                                return bounds;
                            }

                            int[] location = new int[2];
                            contentContainer.getLocationOnScreen(location);
                            translationDeltaY = startContentY - location[1];

                            /*
                             * The layout has already jumped to its final inset state.
                             * Apply the inverse translation immediately so the user sees
                             * no jump, then let onProgress remove it smoothly.
                             */
                            contentContainer.setTranslationY(translationDeltaY);
                            return bounds;
                        }

                        @Override
                        public WindowInsets onProgress(
                                WindowInsets insets,
                                List<WindowInsetsAnimation> runningAnimations
                        ) {
                            if (!statusAnimationRunning) {
                                return insets;
                            }

                            for (WindowInsetsAnimation animation : runningAnimations) {
                                if (!isStatusBarAnimation(animation)) {
                                    continue;
                                }

                                float fraction = animation.getInterpolatedFraction();
                                contentContainer.setTranslationY(
                                        translationDeltaY * (1f - fraction)
                                );
                                break;
                            }
                            return insets;
                        }

                        @Override
                        public void onEnd(WindowInsetsAnimation animation) {
                            if (!isStatusBarAnimation(animation)) {
                                return;
                            }

                            contentContainer.setTranslationY(0f);
                            translationDeltaY = 0;
                            statusAnimationRunning = false;
                        }
                    }
            );

            root.post(root::requestApplyInsets);
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    private static boolean isStatusBarAnimation(
            WindowInsetsAnimation animation
    ) {
        return (animation.getTypeMask() & WindowInsets.Type.statusBars()) != 0;
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
        // Keep the real Chrome/WebView version while removing only embedded-view markers.
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
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
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
            public void onPageFinished(WebView view, String url) {
                progressBar.setProgress(100);
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(
                        newProgress >= 100 ? View.GONE : View.VISIBLE
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
        ) -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // No compatible external application.
            }
        });
    }

    private void configureAutoStatusBar() {
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final int gestureThreshold = dp(28);

        webView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    statusBarGestureHandled = false;
                    pendingStatusAction = STATUS_ACTION_NONE;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (statusBarGestureHandled) {
                        break;
                    }

                    float movedX = event.getRawX() - touchStartX;
                    float movedY = event.getRawY() - touchStartY;

                    if (Math.abs(movedY) > Math.abs(movedX)
                            && Math.abs(movedY) >= gestureThreshold) {
                        pendingStatusAction = movedY < 0f
                                ? STATUS_ACTION_HIDE
                                : STATUS_ACTION_SHOW;
                        statusBarGestureHandled = true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    float totalX = Math.abs(event.getRawX() - touchStartX);
                    float totalY = Math.abs(event.getRawY() - touchStartY);

                    if (statusBarGestureHandled) {
                        int action = pendingStatusAction;

                        /*
                         * Do not resize the WebView while the finger is still driving
                         * an active scroll. Start the bar transition on the next frame
                         * after ACTION_UP.
                         */
                        view.postOnAnimation(() -> applyStatusAction(action));
                    } else if (totalX <= touchSlop && totalY <= touchSlop) {
                        view.postOnAnimation(this::showStatusBar);
                    }

                    statusBarGestureHandled = false;
                    pendingStatusAction = STATUS_ACTION_NONE;
                    break;

                case MotionEvent.ACTION_CANCEL:
                    statusBarGestureHandled = false;
                    pendingStatusAction = STATUS_ACTION_NONE;
                    break;

                default:
                    break;
            }

            // Keep WebView clicks, scrolling, selection and uploads untouched.
            return false;
        });
    }

    private void applyStatusAction(int action) {
        if (action == STATUS_ACTION_HIDE) {
            hideStatusBar();
        } else if (action == STATUS_ACTION_SHOW) {
            showStatusBar();
        }
    }

    private boolean canToggleStatusBar() {
        long now = SystemClock.uptimeMillis();
        if (now - lastStatusBarToggleAt < STATUS_BAR_TOGGLE_COOLDOWN_MS) {
            return false;
        }
        lastStatusBarToggleAt = now;
        return true;
    }

    private void hideStatusBar() {
        if (statusBarHidden || !canToggleStatusBar()) {
            return;
        }

        statusBarHidden = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
            }
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
        if (!statusBarHidden || !canToggleStatusBar()) {
            return;
        }

        statusBarHidden = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars());
            }
        } else {
            View decorView = getWindow().getDecorView();
            int visibility = decorView.getSystemUiVisibility();

            visibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
            visibility &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

            decorView.setSystemUiVisibility(visibility);
        }
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

    private boolean launchSystemFilePicker(
            WebChromeClient.FileChooserParams params
    ) {
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
                params.getMode()
                        == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        );

        try {
            startActivityForResult(intent, FILE_CHOOSER_REQUEST);
            return true;
        } catch (ActivityNotFoundException firstError) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType(
                    mimeTypes.length == 1 ? mimeTypes[0] : "*/*"
            );
            if (mimeTypes.length > 1) {
                fallback.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
            fallback.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode()
                            == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
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
            JSONObject json = new JSONObject(
                    output.toString(StandardCharsets.UTF_8.name())
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
        error.setText("应用配置无效：仅支持 HTTPS 地址。");
        error.setTextColor(Color.DKGRAY);
        error.setTextSize(17f);
        error.setGravity(android.view.Gravity.CENTER);

        contentContainer.addView(error, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST
                || pendingFileCallback == null) {
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

        if (root != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setWindowInsetsAnimationCallback(null);
            root.setOnApplyWindowInsetsListener(null);
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
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }
}
