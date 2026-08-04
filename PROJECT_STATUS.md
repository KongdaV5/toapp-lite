# Project Status — v0.1 source delivery

Implemented:

- clean two-module Android project;
- offline builder Manifest with explicit permission removals;
- independent minimal WebView shell;
- binary AXML package/name patching;
- URL/config/icon replacement;
- local RSA/X.509 signing identity;
- P12 backup and restore;
- local APK signing through apksig-android;
- GitHub Actions build workflow;
- privacy and security documentation.

Validated in the current environment:

- AXML string-pool patcher compiled and successfully modified a real binary Android Manifest;
- patched Manifest was parsed back successfully;
- local RSA certificate generation, certificate verification, P12 export and P12 import compiled and passed a JVM harness;
- XML/JSON/project structure checks passed.

Not validated in the current environment:

- full Android/Gradle build, because the execution environment does not contain Android SDK/Gradle and binary SDK downloads are unavailable;
- installation and on-device APK generation;
- final merged Manifest permission dump.

The included GitHub Actions workflow is the intended first full build and verification path.
