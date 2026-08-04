package com.kongda.toapplite.builder;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;

/** Creates and stores a local RSA signing identity without contacting a server. */
final class SigningKeyManager {
    private static final String KEY_DIRECTORY = "signing";
    private static final String PRIVATE_KEY_FILE = "private.pk8";
    private static final String CERTIFICATE_FILE = "certificate.der";
    private static final String PKCS12_ALIAS = "toapp-lite";

    private final File directory;
    private final File privateKeyFile;
    private final File certificateFile;

    SigningKeyManager(Context context) {
        directory = new File(context.getFilesDir(), KEY_DIRECTORY);
        privateKeyFile = new File(directory, PRIVATE_KEY_FILE);
        certificateFile = new File(directory, CERTIFICATE_FILE);
    }

    synchronized SigningIdentity loadOrCreate() throws Exception {
        if (privateKeyFile.isFile() && certificateFile.isFile()) {
            return load();
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建签名目录");
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate certificate = createSelfSignedCertificate(keyPair);

        writeAtomically(privateKeyFile, keyPair.getPrivate().getEncoded());
        writeAtomically(certificateFile, certificate.getEncoded());
        return new SigningIdentity(keyPair.getPrivate(), certificate);
    }

    synchronized SigningIdentity load() throws Exception {
        byte[] keyBytes = readAll(privateKeyFile);
        byte[] certificateBytes = readAll(certificateFile);

        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        X509Certificate certificate = (X509Certificate) CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateBytes));
        return new SigningIdentity(privateKey, certificate);
    }

    synchronized void exportPkcs12(OutputStream output, char[] password) throws Exception {
        SigningIdentity identity = loadOrCreate();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(
                PKCS12_ALIAS,
                identity.privateKey,
                password,
                new Certificate[]{identity.certificate}
        );
        keyStore.store(output, password);
    }

    synchronized SigningIdentity importPkcs12(InputStream input, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, password);

        Enumeration<String> aliases = keyStore.aliases();
        String selectedAlias = null;
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                selectedAlias = alias;
                break;
            }
        }
        if (selectedAlias == null) {
            throw new IllegalArgumentException("P12 文件中没有私钥");
        }

        Key key = keyStore.getKey(selectedAlias, password);
        Certificate certificate = keyStore.getCertificate(selectedAlias);
        if (!(key instanceof PrivateKey) || !(certificate instanceof X509Certificate)) {
            throw new IllegalArgumentException("P12 签名材料格式不受支持");
        }

        X509Certificate x509 = (X509Certificate) certificate;
        String algorithm = key.getAlgorithm();
        if (!"RSA".equalsIgnoreCase(algorithm)) {
            throw new IllegalArgumentException("当前版本只支持 RSA 签名密钥");
        }

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建签名目录");
        }
        writeAtomically(privateKeyFile, key.getEncoded());
        writeAtomically(certificateFile, x509.getEncoded());
        return new SigningIdentity((PrivateKey) key, x509);
    }

    String certificateFingerprintSha256() throws Exception {
        SigningIdentity identity = loadOrCreate();
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.certificate.getEncoded());
        StringBuilder builder = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
        }
        return builder.toString();
    }

    private static X509Certificate createSelfSignedCertificate(KeyPair keyPair) throws Exception {
        SecureRandom random = new SecureRandom();
        BigInteger serial = new BigInteger(128, random).abs();
        if (serial.signum() == 0) {
            serial = BigInteger.ONE;
        }

        Date notBefore = new Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(notBefore);
        calendar.add(Calendar.YEAR, 30);
        Date notAfter = calendar.getTime();

        byte[] signatureAlgorithm = Der.sequence(
                Der.oid("1.2.840.113549.1.1.11"),
                Der.nullValue()
        );
        byte[] name = Der.sequence(
                Der.set(
                        Der.sequence(
                                Der.oid("2.5.4.3"),
                                Der.utf8String("ToApp Lite Local Signing")
                        )
                )
        );
        byte[] validity = Der.sequence(Der.time(notBefore), Der.time(notAfter));

        byte[] tbsCertificate = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(serial),
                signatureAlgorithm,
                name,
                validity,
                name,
                keyPair.getPublic().getEncoded()
        );

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate(), random);
        signer.update(tbsCertificate);
        byte[] signature = signer.sign();

        byte[] encodedCertificate = Der.sequence(
                tbsCertificate,
                signatureAlgorithm,
                Der.bitString(signature)
        );

        X509Certificate certificate = (X509Certificate) CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encodedCertificate));
        certificate.verify(keyPair.getPublic());
        return certificate;
    }

    private static void writeAtomically(File target, byte[] data) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(data);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("无法替换旧签名文件");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("无法保存签名文件");
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static final class SigningIdentity {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        SigningIdentity(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    private static final class Der {
        private Der() {
        }

        static byte[] sequence(byte[]... elements) {
            return tagged(0x30, concatenate(elements));
        }

        static byte[] set(byte[]... elements) {
            return tagged(0x31, concatenate(elements));
        }

        static byte[] explicit(int tagNumber, byte[] value) {
            return tagged(0xA0 + tagNumber, value);
        }

        static byte[] integer(BigInteger value) {
            return tagged(0x02, value.toByteArray());
        }

        static byte[] nullValue() {
            return tagged(0x05, new byte[0]);
        }

        static byte[] utf8String(String value) {
            return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8));
        }

        static byte[] bitString(byte[] value) {
            byte[] content = new byte[value.length + 1];
            content[0] = 0;
            System.arraycopy(value, 0, content, 1, value.length);
            return tagged(0x03, content);
        }

        static byte[] oid(String value) {
            String[] parts = value.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("OID 格式错误");
            }
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(first * 40 + second);
            for (int i = 2; i < parts.length; i++) {
                writeBase128(output, new BigInteger(parts[i]));
            }
            return tagged(0x06, output.toByteArray());
        }

        static byte[] time(Date date) {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.setTime(date);
            int year = calendar.get(Calendar.YEAR);
            String pattern = year >= 1950 && year < 2050
                    ? "yyMMddHHmmss'Z'"
                    : "yyyyMMddHHmmss'Z'";
            int tag = year >= 1950 && year < 2050 ? 0x17 : 0x18;
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return tagged(tag, format.format(date).getBytes(StandardCharsets.US_ASCII));
        }

        private static byte[] tagged(int tag, byte[] content) {
            byte[] length = encodedLength(content.length);
            byte[] result = new byte[1 + length.length + content.length];
            result[0] = (byte) tag;
            System.arraycopy(length, 0, result, 1, length.length);
            System.arraycopy(content, 0, result, 1 + length.length, content.length);
            return result;
        }

        private static byte[] encodedLength(int length) {
            if (length < 0x80) {
                return new byte[]{(byte) length};
            }
            int value = length;
            int count = 0;
            while (value > 0) {
                count++;
                value >>>= 8;
            }
            byte[] result = new byte[count + 1];
            result[0] = (byte) (0x80 | count);
            for (int i = count; i > 0; i--) {
                result[i] = (byte) (length & 0xff);
                length >>>= 8;
            }
            return result;
        }

        private static byte[] concatenate(byte[]... arrays) {
            int length = 0;
            for (byte[] array : arrays) {
                length = Math.addExact(length, array.length);
            }
            byte[] result = new byte[length];
            int offset = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, result, offset, array.length);
                offset += array.length;
            }
            return result;
        }

        private static void writeBase128(ByteArrayOutputStream output, BigInteger value) {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("OID 节点不能为负数");
            }
            if (value.signum() == 0) {
                output.write(0);
                return;
            }
            BigInteger base = BigInteger.valueOf(128);
            byte[] reversed = new byte[(value.bitLength() + 6) / 7];
            int count = 0;
            while (value.signum() > 0) {
                BigInteger[] division = value.divideAndRemainder(base);
                reversed[count++] = division[1].byteValue();
                value = division[0];
            }
            for (int i = count - 1; i >= 0; i--) {
                int current = reversed[i] & 0x7f;
                if (i != 0) {
                    current |= 0x80;
                }
                output.write(current);
            }
        }
    }
}
