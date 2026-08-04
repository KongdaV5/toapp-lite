package com.kongda.toapplite.builder;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.Collections;

final class ApkSignerFacade {
    private ApkSignerFacade() {
    }

    static void sign(
            File unsignedApk,
            File signedApk,
            SigningKeyManager.SigningIdentity identity
    ) throws Exception {
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "ToAppLite",
                identity.privateKey,
                Collections.<X509Certificate>singletonList(identity.certificate)
        ).build();

        new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }
}
