# ToApp Lite

一个**完全在安卓手机本地运行**的网页转 APK 工具。它不修改原 ToApp 成品包，也不复用原作者的统计、官网、更新或推广代码。

## v0.1 功能

- 输入应用名称、包名和 HTTPS 网页地址；
- 可选择自定义 PNG/JPG 图标；
- 在手机本地修改自有 WebView 模板；
- 首次运行在应用私有目录生成 3072-bit RSA 签名身份；
- 使用同一签名持续生成可覆盖更新的 APK；
- 通过系统文件选择器保存 APK，不申请全盘存储权限；
- 可把签名身份导出为带密码的 `.p12`，也可重新导入。

## 权限设计

### ToApp Lite 生成器

最终 Manifest 应为 **0 个运行/敏感权限**，并且不包含：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `READ_PHONE_STATE`
- 存储读写或全盘文件权限
- 相机、麦克风、定位、通讯录、短信权限

生成器通过 Android Storage Access Framework 选择图片、保存 APK 和导入/导出 P12，不直接扫描用户文件。

### 生成的网页应用

仅包含：

- `INTERNET`
- `ACCESS_NETWORK_STATE`

WebView 默认：

- 只接受 `https://` 入口；
- 禁止明文 HTTP 和混合内容；
- 禁止文件/内容 URI 访问；
- 禁止第三方 Cookie；
- 不注入 JavaScript Bridge；
- SSL 错误直接终止，不提供“继续访问”；
- 不声明相机、麦克风、定位等权限。

## 工程结构

```text
app/       ToApp Lite 生成器
shell/     自有的极简 WebView 模板
```

构建 `app` 时，会先构建未签名的 `shell`，再自动复制为生成器的 `assets/template.apk`。手机端只是替换模板中的包名、应用名、配置和图标，然后使用本机密钥签名。

## 构建

要求：

- JDK 17
- Gradle 9.5.0
- Android SDK Platform 37
- Android Build Tools 36.0.0

命令：

```bash
gradle :app:assembleDebug
```

安装包输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

仓库自带 `.github/workflows/build.yml`。推送到 GitHub 后，也可在 Actions 的构建产物中下载 APK。

## 使用

1. 安装 `app-debug.apk`；
2. 填写应用名称、唯一包名和 HTTPS 地址；
3. 选择图标（可选）；
4. 点击“生成并保存 APK”；
5. 第一次生成后立即备份 P12，并妥善保存密码。

同一应用后续升级必须同时保持：

- 包名不变；
- 签名密钥不变；
- 新版本号高于旧版本。

v0.1 模板版本固定为 `versionCode=1`，适合首次验证。下一版本应增加版本号输入和模板 Manifest 的整数值修改。

## 已知限制

- 当前只支持在线 HTTPS 网页，不支持把一整个本地 HTML 目录嵌入 APK；
- 不支持 HTTP；
- 不支持相机、录音、定位、推送通知等原生能力；
- 不支持直接安装生成结果，避免申请“安装未知应用”权限；
- APK 模板的包名和应用名通过二进制 AXML 字符串池精确替换，模板结构改变时会拒绝生成，而不是盲目输出；
- 当前项目未包含 Gradle Wrapper JAR，可用 Android Studio 自带 Gradle 或 GitHub Actions 构建。

## 安全验收

构建后建议执行：

```bash
aapt2 dump permissions app-debug.apk
apkanalyzer manifest permissions app-debug.apk
apkanalyzer manifest permissions generated.apk
apksigner verify --verbose --print-certs generated.apk
```

预期：

- `app-debug.apk` 不含联网和敏感权限；
- `generated.apk` 只有网络相关两项权限；
- 源码及 APK 中不存在 `baidu.mobstat`、`seegood.top`、广告 SDK 或远程更新地址；
- 生成器断网时仍可完成生成、签名和保存。

## 第三方依赖

生成器仅引入 Android 端 APK 签名库：

```text
com.github.MuntashirAkon:apksig-android:4.4.0
```

该依赖只在本地签名流程中使用。项目没有统计、广告、崩溃上报或远程配置依赖。
