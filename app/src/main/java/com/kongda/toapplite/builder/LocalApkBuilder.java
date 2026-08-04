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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        List<String> resourceEntries = new ArrayList<>();

        try (InputStream template = context.getAssets().open(TEMPLATE_ASSET);
             ZipInputStream zipInput = new ZipInputStream(template);
             ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(outputFile))) {

            ZipEntry inputEntry;
            while ((inputEntry = zipInput.getNextEntry()) != null) {
                String originalName = inputEntry.getName();
                String name = normalizeEntryName(originalName);
                if (isSignatureEntry(name)) {
                    zipInput.closeEntry();
                    continue;
                }
                if (name.startsWith("res/") && resourceEntries.size() < 20) {
                    resourceEntries.add(name);
                }

                ZipEntry outputEntry = new ZipEntry(name);
                outputEntry.setTime(REPRODUCIBLE_ZIP_TIME);
                zipOutput.putNextEntry(outputEntry);

                if (inputEntry.isDirectory()) {
                    // Empty directory entry.
                } else if (MANIFEST_ENTRY.equalsIgnoreCase(name)) {
                    byte[] manifest = readAll(zipInput);
                    Map<String, String> replacements = new HashMap<>();
                    replacements.put(PACKAGE_PLACEHOLDER, spec.packageName);
                    replacements.put(LABEL_PLACEHOLDER, spec.appName);
                    zipOutput.write(BinaryXmlStringPoolPatcher.replaceExactStrings(
                            manifest,
                            replacements
                    ));
                    manifestReplaced = true;
                } else if (CONFIG_ENTRY.equalsIgnoreCase(name)) {
                    writeConfig(zipOutput, spec);
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

            // Assets do not need an entry in resources.arsc, so a missing config can be
            // safely recreated instead of rejecting an otherwise valid template.
            if (!configReplaced) {
                ZipEntry configEntry = new ZipEntry(CONFIG_ENTRY);
                configEntry.setTime(REPRODUCIBLE_ZIP_TIME);
                zipOutput.putNextEntry(configEntry);
                writeConfig(zipOutput, spec);
                zipOutput.closeEntry();
                configReplaced = true;
            }
        } catch (Exception error) {
            deleteIfPresent(outputFile);
            throw error;
        }

        List<String> missing = new ArrayList<>();
        if (!manifestReplaced) {
            missing.add("AndroidManifest.xml");
        }
        if (!configReplaced) {
            missing.add("网页配置");
        }
        if (!iconReplaced) {
            missing.add("可替换的启动图标 PNG");
        }
        if (!missing.isEmpty()) {
            deleteIfPresent(outputFile);
            String details = resourceEntries.isEmpty()
                    ? ""
                    : "；模板资源示例：" + String.join(", ", resourceEntries);
            throw new IOException("模板缺少：" + String.join("、", missing) + details);
        }
    }

    private static void writeConfig(ZipOutputStream output, BuildSpec spec) throws Exception {
        JSONObject config = new JSONObject();
        config.put("url", spec.url);
        config.put("appName", spec.appName);
        output.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeEntryName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
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
        String lower = normalizeEntryName(name).toLowerCase(Locale.US);
        int lastSlash = lower.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? lower.substring(lastSlash + 1) : lower;
        return "ic_launcher.png".equals(fileName)
                && (lower.startsWith("res/drawable") || lower.startsWith("res/mipmap"));
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
