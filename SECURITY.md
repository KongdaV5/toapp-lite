# Security Model

## Threat model

ToApp Lite protects against accidental or hidden telemetry inside the APK builder itself. It does not claim to make an untrusted website safe.

## Controls

- Builder Manifest removes network, phone-state and storage permissions.
- No analytics, ads, remote config, update service or embedded promotional WebView.
- Signing key is generated on-device and stored under app-private storage.
- P12 export requires a user-supplied password.
- Template patching is fail-closed: missing placeholders or resources abort the build.
- Generated WebView has no JavaScript interface and rejects SSL errors.
- Only HTTPS is accepted; clear-text traffic and mixed content are disabled.

## Release verification checklist

1. Review the merged builder manifest.
2. Search source and APK strings for unexpected domains and SDK namespaces.
3. Verify the generated APK signature and certificate fingerprint.
4. Capture network traffic while exercising every builder action; expected builder traffic is zero.
5. Build from a clean commit and compare source tag, workflow run and artifact hash.

## Reporting

Do not include private keys, P12 files, passwords, account cookies or production URLs in a public issue.
