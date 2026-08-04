package com.kongda.toapplite.builder;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Builds and signs an APK entirely on the device. */
final class LocalApkBuilder {
    static final String PACKAGE_PLACEHOLDER =
            "com.toapplite.generated.placeholderaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String LABEL_PLACEHOLDER =
            "TOAPP_LITE_NAME_PLACEHOLDER_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final String TEMPLATE_ASSET = "template.apk";
    private static final String MANIFEST_ENTRY = "AndroidManifest.xml";
    private static final String CONFIG_ENTRY = "assets/app_config.json";
    private static final long REPRODUCIBLE_ZIP_TIME = 315532800000L; // 1980-01-01 UTC

    private final Context context;
    private final SigningKeyManager signingKeyManager;

    LocalApkBuilder(Context context, SigningKeyManager signingKeyManager) {
        this.context = context.getApplicationContext();
        this.signingKeyManager = signingKeyManager;
    }

    File build(BuildSpec spec) throws Exception {
        File workDirectory = new File(context.getCacheDir(), "apk-build");
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            throw new IOException("无法创建临时构建目录");
        }
        File unsignedApk = new File(workDirectory, "unsigned.apk");
        File signedApk = new File(workDirectory, "signed.apk");
        deleteIfPresent(unsignedApk);
        deleteIfPresent(signedApk);

        createUnsignedApk(spec, unsignedApk);
        SigningKeyManager.SigningIdentity identity = signingKeyManager.loadOrCreate();
        ApkSignerFacade.sign(unsignedApk, signedApk, identity);
        deleteIfPresent(unsignedApk);
        return signedApk;
    }

    private void createUnsignedApk(BuildSpec spec, File outputFile) throws Exception {
        boolean manifestReplaced = false;
        boolean configReplaced = false;
        boolean iconReplaced = spec.iconPng == null;

        try (InputStream template = context.getAssets().open(TEMPLATE_ASSET);
             ZipInputStream zipInput = new ZipInputStream(template);
             ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(outputFile))) {

            ZipEntry inputEntry;
            while ((inputEntry = zipInput.getNextEntry()) != null) {
                String name = inputEntry.getName();
                if (isSignatureEntry(name)) {
                    zipInput.closeEntry();
                    continue;
                }

                ZipEntry outputEntry = new ZipEntry(name);
                outputEntry.setTime(REPRODUCIBLE_ZIP_TIME);
                zipOutput.putNextEntry(outputEntry);

                if (inputEntry.isDirectory()) {
                    // Empty directory entry.
                } else if (MANIFEST_ENTRY.equals(name)) {
                    byte[] manifest = readAll(zipInput);
                    Map<String, String> replacements = new HashMap<>();
                    replacements.put(PACKAGE_PLACEHOLDER, spec.packageName);
                    replacements.put(LABEL_PLACEHOLDER, spec.appName);
                    zipOutput.write(BinaryXmlStringPoolPatcher.replaceExactStrings(
                            manifest,
                            replacements
                    ));
                    manifestReplaced = true;
                } else if (CONFIG_ENTRY.equals(name)) {
                    JSONObject config = new JSONObject();
                    config.put("url", spec.url);
                    config.put("appName", spec.appName);
                    zipOutput.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
                    configReplaced = true;
                } else if (spec.iconPng != null && isLauncherIconEntry(name)) {
                    try (FileInputStream iconInput = new FileInputStream(spec.iconPng)) {
                        copy(iconInput, zipOutput);
                    }
                    iconReplaced = true;
                } else {
                    copy(zipInput, zipOutput);
                }

                zipOutput.closeEntry();
                zipInput.closeEntry();
            }
        } catch (Exception error) {
            deleteIfPresent(outputFile);
            throw error;
        }

        if (!manifestReplaced || !configReplaced || !iconReplaced) {
            deleteIfPresent(outputFile);
            throw new IOException("模板结构与当前版本不匹配，已停止生成");
        }
    }

    private static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(Locale.US);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.equals("META-INF/MANIFEST.MF")
                || upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    private static boolean isLauncherIconEntry(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.startsWith("res/")
                && lower.endsWith("/ic_launcher.png")
                && (lower.contains("/drawable") || lower.contains("/mipmap"));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("无法清理旧临时文件：" + file.getName());
        }
    }
}
