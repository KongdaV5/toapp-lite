package com.kongda.toapplite.builder;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
    private static final String RESOURCE_TABLE_ENTRY = "resources.arsc";
    private static final long REPRODUCIBLE_ZIP_TIME = 315532800000L; // 1980-01-01 UTC
    private static final int APK_ALIGNMENT = 4;

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
        ApkLayoutVerifier.verify(signedApk);
        deleteIfPresent(unsignedApk);
        return signedApk;
    }

    private void createUnsignedApk(BuildSpec spec, File outputFile) throws Exception {
        List<TemplateEntry> entries = readTemplateEntries();
        String iconEntryName = selectIconEntry(entries, spec.iconPng != null);

        boolean manifestReplaced = false;
        boolean configReplaced = false;
        boolean iconReplaced = spec.iconPng == null;
        List<String> resourceEntries = new ArrayList<>();

        try (CountingOutputStream countingOutput = new CountingOutputStream(
                new FileOutputStream(outputFile)
        ); ZipOutputStream zipOutput = new ZipOutputStream(countingOutput)) {
            for (TemplateEntry templateEntry : entries) {
                String name = templateEntry.name;
                if (name.startsWith("res/") && resourceEntries.size() < 20) {
                    resourceEntries.add(name);
                }

                byte[] data = templateEntry.data;
                if (MANIFEST_ENTRY.equalsIgnoreCase(name)) {
                    Map<String, String> replacements = new HashMap<>();
                    replacements.put(PACKAGE_PLACEHOLDER, spec.packageName);
                    replacements.put(LABEL_PLACEHOLDER, spec.appName);
                    data = BinaryXmlStringPoolPatcher.replaceExactStrings(data, replacements);
                    manifestReplaced = true;
                } else if (CONFIG_ENTRY.equalsIgnoreCase(name)) {
                    data = createConfig(spec);
                    configReplaced = true;
                } else if (spec.iconPng != null && name.equals(iconEntryName)) {
                    data = readFile(spec.iconPng);
                    iconReplaced = true;
                }

                writeEntry(zipOutput, countingOutput, templateEntry, data);
            }

            // Assets do not need an entry in resources.arsc, so a missing config can be
            // safely recreated instead of rejecting an otherwise valid template.
            if (!configReplaced) {
                TemplateEntry configEntry = new TemplateEntry(
                        CONFIG_ENTRY,
                        false,
                        ZipEntry.DEFLATED,
                        createConfig(spec)
                );
                writeEntry(zipOutput, countingOutput, configEntry, configEntry.data);
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

    private List<TemplateEntry> readTemplateEntries() throws IOException {
        List<TemplateEntry> entries = new ArrayList<>();
        try (InputStream template = context.getAssets().open(TEMPLATE_ASSET);
             ZipInputStream zipInput = new ZipInputStream(template)) {
            ZipEntry inputEntry;
            while ((inputEntry = zipInput.getNextEntry()) != null) {
                String name = normalizeEntryName(inputEntry.getName());
                if (isSignatureEntry(name)) {
                    zipInput.closeEntry();
                    continue;
                }
                boolean directory = inputEntry.isDirectory();
                byte[] data = directory ? new byte[0] : readAll(zipInput);
                int method = inputEntry.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    method = ZipEntry.DEFLATED;
                }
                entries.add(new TemplateEntry(name, directory, method, data));
                zipInput.closeEntry();
            }
        }
        return entries;
    }

    private static String selectIconEntry(
            List<TemplateEntry> entries,
            boolean customIconRequested
    ) throws IOException {
        if (!customIconRequested) {
            return null;
        }

        List<String> exactCandidates = new ArrayList<>();
        List<String> pngCandidates = new ArrayList<>();
        for (TemplateEntry entry : entries) {
            String lower = entry.name.toLowerCase(Locale.US);
            if (entry.directory || !lower.startsWith("res/") || !lower.endsWith(".png")) {
                continue;
            }
            pngCandidates.add(entry.name);
            String fileName = lower.substring(lower.lastIndexOf('/') + 1);
            if ("ic_launcher.png".equals(fileName)
                    || fileName.contains("launcher")
                    || fileName.contains("icon")) {
                exactCandidates.add(entry.name);
            }
        }

        if (exactCandidates.size() == 1) {
            return exactCandidates.get(0);
        }
        // AAPT2 may shorten compiled resource paths (for example res/Cx.png). The
        // Lite shell intentionally contains only one PNG resource, so that single
        // compiled PNG is the launcher icon even when its original name is gone.
        if (pngCandidates.size() == 1) {
            return pngCandidates.get(0);
        }
        if (pngCandidates.isEmpty()) {
            throw new IOException("模板中没有可替换的 PNG 图标资源");
        }
        throw new IOException(
                "模板中存在多个 PNG 资源，无法安全判断启动图标："
                        + String.join(", ", pngCandidates)
        );
    }

    private static void writeEntry(
            ZipOutputStream zipOutput,
            CountingOutputStream countingOutput,
            TemplateEntry templateEntry,
            byte[] data
    ) throws IOException {
        ZipEntry outputEntry = new ZipEntry(templateEntry.name);
        outputEntry.setTime(REPRODUCIBLE_ZIP_TIME);

        int method = templateEntry.directory ? ZipEntry.STORED : templateEntry.method;
        outputEntry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc32 = new CRC32();
            crc32.update(data);
            outputEntry.setSize(data.length);
            outputEntry.setCompressedSize(data.length);
            outputEntry.setCrc(crc32.getValue());
            setAlignmentExtra(outputEntry, countingOutput.getCount(), APK_ALIGNMENT);
        }

        zipOutput.putNextEntry(outputEntry);
        if (!templateEntry.directory && data.length > 0) {
            zipOutput.write(data);
        }
        zipOutput.closeEntry();
    }

    /**
     * Android 11+ requires the uncompressed resources.arsc payload to begin on a
     * 4-byte boundary. Rebuilding an APK with a normal ZipOutputStream otherwise
     * turns the template into an install-time -124 / BAD_MANIFEST failure.
     */
    private static void setAlignmentExtra(
            ZipEntry entry,
            long localHeaderOffset,
            int alignment
    ) {
        byte[] nameBytes = entry.getName().getBytes(StandardCharsets.UTF_8);
        long withoutExtra = localHeaderOffset + 30L + nameBytes.length;
        if ((withoutExtra % alignment) == 0) {
            return;
        }

        // A ZIP extra field needs a 4-byte id/length header. The payload length is
        // selected so the following file data begins at the requested boundary.
        int payloadLength = (int) ((alignment - ((withoutExtra + 4L) % alignment)) % alignment);
        byte[] extra = new byte[4 + payloadLength];
        extra[0] = (byte) 0x1e; // private field id 0xA11E, little endian
        extra[1] = (byte) 0xa1;
        extra[2] = (byte) (payloadLength & 0xff);
        extra[3] = (byte) ((payloadLength >>> 8) & 0xff);
        entry.setExtra(extra);
    }

    private static byte[] createConfig(BuildSpec spec) throws Exception {
        JSONObject config = new JSONObject();
        config.put("url", spec.url);
        config.put("appName", spec.appName);
        config.put("adBlockEnabled", spec.adBlockEnabled);
        return config.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
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

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
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

    private static final class TemplateEntry {
        final String name;
        final boolean directory;
        final int method;
        final byte[] data;

        TemplateEntry(String name, boolean directory, int method, byte[] data) {
            this.name = name;
            this.directory = directory;
            this.method = method;
            this.data = data;
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream output) {
            super(output);
        }

        long getCount() {
            return count;
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }
    }

    private static final class ApkLayoutVerifier {
        private static final int EOCD_SIGNATURE = 0x06054b50;
        private static final int CENTRAL_SIGNATURE = 0x02014b50;
        private static final int LOCAL_SIGNATURE = 0x04034b50;

        private ApkLayoutVerifier() {
        }

        static void verify(File apk) throws IOException {
            // First ensure the signed output is a readable ZIP and contains the
            // mandatory Android payloads.
            try (ZipFile zipFile = new ZipFile(apk)) {
                if (zipFile.getEntry(MANIFEST_ENTRY) == null
                        || zipFile.getEntry("classes.dex") == null
                        || zipFile.getEntry(RESOURCE_TABLE_ENTRY) == null) {
                    throw new IOException("生成的 APK 缺少必要文件");
                }
            }

            try (RandomAccessFile file = new RandomAccessFile(apk, "r")) {
                long eocdOffset = findEocd(file);
                int entryCount = readU16(file, eocdOffset + 10);
                long centralOffset = readU32(file, eocdOffset + 16);
                long cursor = centralOffset;
                boolean foundResourceTable = false;

                for (int index = 0; index < entryCount; index++) {
                    if (readU32(file, cursor) != CENTRAL_SIGNATURE) {
                        throw new IOException("APK 中央目录损坏");
                    }
                    int method = readU16(file, cursor + 10);
                    int nameLength = readU16(file, cursor + 28);
                    int extraLength = readU16(file, cursor + 30);
                    int commentLength = readU16(file, cursor + 32);
                    long localOffset = readU32(file, cursor + 42);
                    byte[] nameBytes = new byte[nameLength];
                    file.seek(cursor + 46);
                    file.readFully(nameBytes);
                    String name = new String(nameBytes, StandardCharsets.UTF_8);

                    if (RESOURCE_TABLE_ENTRY.equals(name)) {
                        foundResourceTable = true;
                        if (method != ZipEntry.STORED) {
                            throw new IOException("resources.arsc 被压缩，安卓 11 及以上无法安装");
                        }
                        if (readU32(file, localOffset) != LOCAL_SIGNATURE) {
                            throw new IOException("resources.arsc 本地头损坏");
                        }
                        int localNameLength = readU16(file, localOffset + 26);
                        int localExtraLength = readU16(file, localOffset + 28);
                        long dataOffset = localOffset + 30L + localNameLength + localExtraLength;
                        if ((dataOffset % APK_ALIGNMENT) != 0) {
                            throw new IOException("resources.arsc 未按 4 字节对齐");
                        }
                    }
                    cursor += 46L + nameLength + extraLength + commentLength;
                }

                if (!foundResourceTable) {
                    throw new IOException("生成的 APK 缺少 resources.arsc");
                }
            }
        }

        private static long findEocd(RandomAccessFile file) throws IOException {
            long length = file.length();
            long lowerBound = Math.max(0, length - 65557L);
            for (long offset = length - 22L; offset >= lowerBound; offset--) {
                if (readU32(file, offset) == EOCD_SIGNATURE) {
                    int commentLength = readU16(file, offset + 20);
                    if (offset + 22L + commentLength == length) {
                        return offset;
                    }
                }
            }
            throw new IOException("APK 缺少 ZIP 结束目录");
        }

        private static int readU16(RandomAccessFile file, long offset) throws IOException {
            file.seek(offset);
            int b0 = file.read();
            int b1 = file.read();
            if ((b0 | b1) < 0) {
                throw new IOException("APK 数据截断");
            }
            return b0 | (b1 << 8);
        }

        private static long readU32(RandomAccessFile file, long offset) throws IOException {
            file.seek(offset);
            long b0 = file.read();
            long b1 = file.read();
            long b2 = file.read();
            long b3 = file.read();
            if ((b0 | b1 | b2 | b3) < 0) {
                throw new IOException("APK 数据截断");
            }
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }
    }
}
