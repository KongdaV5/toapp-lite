package com.kongda.toapplite.builder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Offline URL-to-APK builder. */
public final class MainActivity extends Activity {
    private static final int PICK_ICON_REQUEST = 1001;
    private static final int SAVE_APK_REQUEST = 1002;
    private static final int EXPORT_KEY_REQUEST = 1003;
    private static final int IMPORT_KEY_REQUEST = 1004;

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText appNameInput;
    private EditText packageInput;
    private EditText urlInput;
    private CheckBox adBlockInput;
    private ImageView iconPreview;
    private TextView statusText;
    private TextView fingerprintText;
    private Button buildButton;

    private SigningKeyManager signingKeyManager;
    private LocalApkBuilder localApkBuilder;
    private File selectedIconFile;
    private File pendingSignedApk;
    private Uri pendingImportUri;
    private char[] pendingExportPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        signingKeyManager = new SigningKeyManager(this);
        localApkBuilder = new LocalApkBuilder(this, signingKeyManager);
        setContentView(createContentView());
        refreshFingerprint();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("WebtoApp", 27f, Color.BLACK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView description = text(
                "纯本地网页转 APK 工具。生成器自身没有联网、电话、定位、通讯录或全盘存储权限。",
                15f,
                Color.DKGRAY
        );
        description.setPadding(0, dp(7), 0, dp(20));
        content.addView(description);

        appNameInput = addField(content, "应用名称", "我的网页应用");
        packageInput = addField(content, "包名", "com.kongda.webapp");
        urlInput = addField(content, "HTTPS 网页地址", "https://example.com");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        adBlockInput = new CheckBox(this);
        adBlockInput.setText("启用广告过滤（推荐）");
        adBlockInput.setTextSize(15f);
        adBlockInput.setTextColor(Color.BLACK);
        adBlockInput.setChecked(true);
        adBlockInput.setPadding(0, dp(12), 0, 0);
        content.addView(adBlockInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView adBlockHint = text(
                "自动使用内置基础规则，并定期更新国际、中文和移动网页过滤规则。网站功能异常时可取消勾选后重新生成。",
                13f,
                0xff666666
        );
        adBlockHint.setPadding(dp(4), 0, 0, dp(4));
        content.addView(adBlockHint);

        TextView iconLabel = text("应用图标（可选）", 14f, Color.DKGRAY);
        iconLabel.setPadding(0, dp(12), 0, dp(7));
        content.addView(iconLabel);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview = new ImageView(this);
        iconPreview.setImageResource(com.kongda.toapplite.builder.R.drawable.ic_builder);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconRow.addView(iconPreview, new LinearLayout.LayoutParams(dp(72), dp(72)));

        Button chooseIcon = new Button(this);
        chooseIcon.setText("选择图片");
        chooseIcon.setOnClickListener(v -> chooseIcon());
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chooseParams.setMargins(dp(14), 0, 0, 0);
        iconRow.addView(chooseIcon, chooseParams);
        content.addView(iconRow);

        buildButton = new Button(this);
        buildButton.setText("生成并保存 APK");
        buildButton.setOnClickListener(v -> startBuild());
        LinearLayout.LayoutParams buildParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        buildParams.setMargins(0, dp(20), 0, 0);
        content.addView(buildButton, buildParams);

        statusText = text("等待生成", 14f, Color.DKGRAY);
        statusText.setPadding(0, dp(12), 0, dp(18));
        content.addView(statusText);

        View divider = new View(this);
        divider.setBackgroundColor(0xffdddddd);
        content.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        TextView signingTitle = text("本机签名身份", 19f, Color.BLACK);
        signingTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        signingTitle.setPadding(0, dp(18), 0, dp(8));
        content.addView(signingTitle);

        TextView signingInfo = text(
                "首次运行会在应用私有目录生成 RSA 签名密钥。以后用同一密钥生成，APK 才能覆盖更新。请备份 P12；清除应用数据会删除本机密钥。",
                14f,
                Color.DKGRAY
        );
        content.addView(signingInfo);

        fingerprintText = text("证书 SHA-256：正在生成……", 12f, 0xff555555);
        fingerprintText.setTextIsSelectable(true);
        fingerprintText.setPadding(0, dp(10), 0, dp(10));
        content.addView(fingerprintText);

        LinearLayout keyButtons = new LinearLayout(this);
        keyButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button exportKey = new Button(this);
        exportKey.setText("备份 P12");
        exportKey.setOnClickListener(v -> askExportPassword());
        Button importKey = new Button(this);
        importKey.setText("导入 P12");
        importKey.setOnClickListener(v -> chooseKeyFile());

        LinearLayout.LayoutParams keyButtonParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        keyButtons.addView(exportKey, keyButtonParams);
        LinearLayout.LayoutParams secondKeyParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        secondKeyParams.setMargins(dp(10), 0, 0, 0);
        keyButtons.addView(importKey, secondKeyParams);
        content.addView(keyButtons);

        TextView limits = text(
                "当前 WebtoApp 版：仅支持 HTTPS；生成的应用不含统计 SDK、远程控制、广告、JS 桥、相机、麦克风或定位权限。图片和普通文件上传均通过安卓系统选择器完成。启用广告过滤后，生成的应用会在后台更新公开过滤规则。",
                13f,
                0xff666666
        );
        limits.setPadding(0, dp(20), 0, 0);
        content.addView(limits);

        return scrollView;
    }

    private EditText addField(LinearLayout parent, String label, String defaultValue) {
        TextView labelView = text(label, 14f, Color.DKGRAY);
        labelView.setPadding(0, dp(10), 0, dp(5));
        parent.addView(labelView);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultValue);
        input.setTextSize(16f);
        input.setPadding(dp(12), 0, dp(12), 0);
        parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        return input;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private void chooseIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_ICON_REQUEST);
    }

    private void startBuild() {
        final BuildSpec spec;
        try {
            spec = validateInputs();
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }

        setBusy(true, "正在本地修改模板并签名……");
        executor.execute(() -> {
            try {
                File result = localApkBuilder.build(spec);
                pendingSignedApk = result;
                mainHandler.post(() -> {
                    setBusy(false, "APK 已生成，选择保存位置");
                    Intent saveIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    saveIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    saveIntent.setType("application/vnd.android.package-archive");
                    saveIntent.putExtra(
                            Intent.EXTRA_TITLE,
                            sanitizeFileName(spec.appName) + "-1.0.apk"
                    );
                    startActivityForResult(saveIntent, SAVE_APK_REQUEST);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setBusy(false, "生成失败");
                    showError(readableError(error));
                });
            }
        });
    }

    private BuildSpec validateInputs() {
        String appName = appNameInput.getText().toString().trim();
        String packageName = packageInput.getText().toString().trim();
        String url = urlInput.getText().toString().trim();

        if (appName.isEmpty() || appName.length() > 50) {
            throw new IllegalArgumentException("应用名称应为 1—50 个字符");
        }
        if (packageName.length() > 180 || !PACKAGE_PATTERN.matcher(packageName).matches()) {
            throw new IllegalArgumentException("包名格式错误，例如 com.kongda.webapp");
        }
        if (packageName.startsWith("android.") || packageName.equals("android")) {
            throw new IllegalArgumentException("包名不能使用 android 命名空间");
        }
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("当前版本只接受完整的 HTTPS 地址");
        }
        return new BuildSpec(
                appName,
                packageName,
                url,
                selectedIconFile,
                adBlockInput != null && adBlockInput.isChecked()
        );
    }

    private void normalizeAndStoreIcon(Uri uri) {
        setBusy(true, "正在处理图标……");
        executor.execute(() -> {
            try {
                ContentResolver resolver = getContentResolver();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) {
                        throw new IllegalArgumentException("无法读取图片");
                    }
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new IllegalArgumentException("图片格式不受支持");
                }

                int sampleSize = 1;
                int smaller = Math.min(bounds.outWidth, bounds.outHeight);
                while (smaller / sampleSize > 2048) {
                    sampleSize *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;

                Bitmap source;
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) {
                        throw new IllegalArgumentException("无法读取图片");
                    }
                    source = BitmapFactory.decodeStream(input, null, options);
                }
                if (source == null) {
                    throw new IllegalArgumentException("图片解码失败");
                }

                Bitmap output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(output);
                canvas.drawColor(Color.TRANSPARENT);
                int cropSize = Math.min(source.getWidth(), source.getHeight());
                int left = (source.getWidth() - cropSize) / 2;
                int top = (source.getHeight() - cropSize) / 2;
                Rect sourceRect = new Rect(left, top, left + cropSize, top + cropSize);
                Rect targetRect = new Rect(0, 0, 512, 512);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(source, sourceRect, targetRect, paint);

                File iconFile = new File(getCacheDir(), "selected-icon.png");
                try (FileOutputStream fileOutput = new FileOutputStream(iconFile)) {
                    if (!output.compress(Bitmap.CompressFormat.PNG, 100, fileOutput)) {
                        throw new IllegalStateException("图标保存失败");
                    }
                }
                source.recycle();
                output.recycle();
                selectedIconFile = iconFile;

                mainHandler.post(() -> {
                    iconPreview.setImageURI(Uri.fromFile(iconFile));
                    setBusy(false, "图标已选择");
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setBusy(false, "图标处理失败");
                    showError(readableError(error));
                });
            }
        });
    }

    private void saveGeneratedApk(Uri destination) {
        File source = pendingSignedApk;
        pendingSignedApk = null;
        if (source == null || !source.isFile()) {
            showError("临时 APK 已不存在，请重新生成");
            return;
        }
        setBusy(true, "正在保存 APK……");
        executor.execute(() -> {
            try (InputStream input = new java.io.FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IllegalStateException("系统未提供写入通道");
                }
                copy(input, output);
                output.flush();
                mainHandler.post(() -> setBusy(false, "保存完成"));
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setBusy(false, "保存失败");
                    showError(readableError(error));
                });
            }
        });
    }

    private void askExportPassword() {
        EditText passwordInput = passwordInput();
        new AlertDialog.Builder(this)
                .setTitle("设置 P12 备份密码")
                .setMessage("至少 8 个字符。忘记密码后无法导入备份。")
                .setView(passwordInput)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> {
                    char[] password = passwordInput.getText().toString().toCharArray();
                    if (password.length < 8) {
                        Arrays.fill(password, '\0');
                        showError("备份密码至少 8 个字符");
                        return;
                    }
                    clearPendingExportPassword();
                    pendingExportPassword = password;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/x-pkcs12");
                    intent.putExtra(Intent.EXTRA_TITLE, "toapp-lite-signing-backup.p12");
                    startActivityForResult(intent, EXPORT_KEY_REQUEST);
                })
                .show();
    }

    private void exportKey(Uri destination) {
        char[] password = pendingExportPassword;
        pendingExportPassword = null;
        if (password == null) {
            showError("备份密码状态已失效");
            return;
        }
        setBusy(true, "正在导出签名密钥……");
        executor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IllegalStateException("系统未提供写入通道");
                }
                signingKeyManager.exportPkcs12(output, password);
                output.flush();
                mainHandler.post(() -> setBusy(false, "签名密钥已备份"));
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setBusy(false, "密钥备份失败");
                    showError(readableError(error));
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void chooseKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_KEY_REQUEST);
    }

    private void askImportPassword(Uri uri) {
        pendingImportUri = uri;
        EditText passwordInput = passwordInput();
        new AlertDialog.Builder(this)
                .setTitle("输入 P12 密码")
                .setView(passwordInput)
                .setNegativeButton("取消", (dialog, which) -> pendingImportUri = null)
                .setPositiveButton("导入", (dialog, which) -> {
                    char[] password = passwordInput.getText().toString().toCharArray();
                    Uri selected = pendingImportUri;
                    pendingImportUri = null;
                    if (selected != null) {
                        importKey(selected, password);
                    } else {
                        Arrays.fill(password, '\0');
                    }
                })
                .show();
    }

    private void importKey(Uri uri, char[] password) {
        setBusy(true, "正在验证并导入签名密钥……");
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("系统未提供读取通道");
                }
                signingKeyManager.importPkcs12(input, password);
                mainHandler.post(() -> {
                    setBusy(false, "签名密钥导入完成");
                    refreshFingerprint();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setBusy(false, "密钥导入失败");
                    showError("密码错误、文件损坏，或密钥格式不受支持");
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private EditText passwordInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(20), 0, dp(20), 0);
        return input;
    }

    private void refreshFingerprint() {
        executor.execute(() -> {
            try {
                String fingerprint = signingKeyManager.certificateFingerprintSha256();
                mainHandler.post(() -> fingerprintText.setText(
                        "证书 SHA-256：\n" + fingerprint
                ));
            } catch (Exception error) {
                mainHandler.post(() -> fingerprintText.setText("签名身份生成失败"));
            }
        });
    }

    private void setBusy(boolean busy, String status) {
        buildButton.setEnabled(!busy);
        statusText.setText(status);
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("操作未完成")
                .setMessage(message == null ? "未知错误" : message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "web-app" : sanitized;
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == EXPORT_KEY_REQUEST) {
                clearPendingExportPassword();
            }
            return;
        }
        Uri uri = data.getData();
        if (requestCode == PICK_ICON_REQUEST) {
            normalizeAndStoreIcon(uri);
        } else if (requestCode == SAVE_APK_REQUEST) {
            saveGeneratedApk(uri);
        } else if (requestCode == EXPORT_KEY_REQUEST) {
            exportKey(uri);
        } else if (requestCode == IMPORT_KEY_REQUEST) {
            askImportPassword(uri);
        }
    }

    private void clearPendingExportPassword() {
        if (pendingExportPassword != null) {
            Arrays.fill(pendingExportPassword, '\0');
            pendingExportPassword = null;
        }
    }

    @Override
    protected void onDestroy() {
        clearPendingExportPassword();
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
