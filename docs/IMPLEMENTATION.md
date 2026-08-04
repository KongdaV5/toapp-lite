# Implementation Notes

## Why this is a rewrite

The project intentionally does not patch or redistribute the original ToApp application. The only shared concept is the general workflow of using a prebuilt template APK and modifying it locally.

## AXML patching

Android manifests inside APK files are binary XML. `BinaryXmlStringPoolPatcher` locates the `RES_STRING_POOL_TYPE` chunk, decodes its UTF-8 or UTF-16 entries, replaces two exact placeholders and rebuilds the chunk while preserving string indices. The root and chunk sizes are updated.

This deliberately does not perform arbitrary Manifest editing. Narrow exact replacement is easier to audit and fails when the template changes unexpectedly.

## APK rewrite

`LocalApkBuilder` streams `template.apk` through `ZipInputStream`/`ZipOutputStream` and changes only:

- `AndroidManifest.xml`
- `assets/app_config.json`
- launcher icon PNG

Legacy signature records under `META-INF` are removed before re-signing. All other entries are copied.

## Signing identity

`SigningKeyManager` uses platform Java crypto APIs to generate an RSA key pair. A minimal self-signed X.509 certificate is DER-encoded locally, parsed by `CertificateFactory`, and verified against the generated public key.

Private key and certificate are stored separately under the builder's private files directory. Export/import uses standard PKCS#12.

## Dependency boundary

The only non-platform runtime dependency is `apksig-android`, used after APK rewriting. No dependency is given network permission in the final builder Manifest.
