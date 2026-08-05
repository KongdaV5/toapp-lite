package com.kongda.toapplite.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, fail-open WebView ad blocker.
 *
 * <p>The engine intentionally supports a safe, useful subset of Adblock Plus syntax:
 * domain anchors, URL patterns, exceptions, common resource options, domain constraints,
 * third-party constraints and standard cosmetic selectors. Scriptlets, HTML filtering,
 * redirects and other executable/transforming rules are ignored.</p>
 */
final class AdBlockEngine implements AutoCloseable {
    private static final String PREFS_NAME = "webtoapp_adblock";
    private static final String BOOTSTRAP_ASSET = "adblock/bootstrap.txt";
    private static final long UPDATE_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MIN_VALID_SOURCE_LINES = 100;
    private static final int MAX_NETWORK_RULES = 72_000;
    private static final int MAX_EXCEPTION_RULES = 12_000;
    private static final int MAX_COSMETIC_RULES = 8_000;
    private static final int MAX_COSMETIC_EXCEPTIONS = 2_000;
    private static final int MAX_INJECTED_SELECTORS = 1_500;
    private static final int MAX_INJECTED_CSS_CHARS = 180_000;

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] TRANSPARENT_GIF = new byte[]{
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            0x21, (byte) 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
    };

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9%_]{4,}");
    private static final Pattern HOSTS_LINE_PATTERN = Pattern.compile(
            "^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+([^\\s#]+)"
    );

    private static final Set<String> UNSUPPORTED_OPTIONS;
    private static final Set<String> HARD_ALLOWED_HOSTS;
    private static final Set<String> MULTI_LEVEL_PUBLIC_SUFFIXES;

    static {
        Set<String> unsupported = new HashSet<>();
        Collections.addAll(
                unsupported,
                "redirect", "redirect-rule", "rewrite", "csp", "removeparam",
                "header", "removeheader", "permissions", "replace", "urltransform",
                "uritransform", "method", "cookie", "ipaddress", "jsonprune",
                "xmlprune", "hls", "stealth", "webrtc", "urlskip"
        );
        UNSUPPORTED_OPTIONS = Collections.unmodifiableSet(unsupported);

        Set<String> allowed = new HashSet<>();
        Collections.addAll(
                allowed,
                "accounts.google.com",
                "appleid.apple.com",
                "login.microsoftonline.com",
                "login.live.com",
                "challenges.cloudflare.com",
                "hcaptcha.com",
                "newassets.hcaptcha.com",
                "recaptcha.net",
                "www.recaptcha.net",
                "js.stripe.com",
                "checkout.stripe.com",
                "paypal.com",
                "www.paypal.com",
                "pay.google.com"
        );
        HARD_ALLOWED_HOSTS = Collections.unmodifiableSet(allowed);

        Set<String> suffixes = new HashSet<>();
        Collections.addAll(
                suffixes,
                "co.uk", "org.uk", "ac.uk", "gov.uk",
                "com.cn", "net.cn", "org.cn", "gov.cn",
                "com.au", "net.au", "org.au",
                "co.jp", "ne.jp", "or.jp",
                "co.kr", "ne.kr", "or.kr",
                "com.br", "com.mx", "com.tr", "co.in",
                "com.sg", "com.hk", "com.tw", "com.my", "co.nz"
        );
        MULTI_LEVEL_PUBLIC_SUFFIXES = Collections.unmodifiableSet(suffixes);
    }

    private static final FilterSource[] SOURCES = new FilterSource[]{
            // Parse mobile/Chinese supplements first, then let EasyList fill the broad base.
            new FilterSource(
                    "easylistchina",
                    "https://easylist-downloads.adblockplus.org/easylistchina.txt"
            ),
            new FilterSource(
                    "adguard-mobile",
                    "https://filters.adtidy.org/android/filters/11_optimized.txt"
            ),
            new FilterSource(
                    "cjx-annoyance",
                    "https://fastly.jsdelivr.net/gh/cjx82630/cjxlist/cjx-annoyance.txt"
            ),
            new FilterSource(
                    "easylist",
                    "https://easylist-downloads.adblockplus.org/easylist.txt"
            )
    };

    private final Context context;
    private final SharedPreferences preferences;
    private final File cacheDirectory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService cosmeticExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    AdBlockEngine(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
        this.cacheDirectory = new File(this.context.getFilesDir(), "adblock");
        if (!cacheDirectory.exists()) {
            // Failure is harmless. The bundled rules remain available.
            cacheDirectory.mkdirs();
        }
    }

    void start() {
        snapshot = buildSnapshot(Collections.singletonList(
                RuleText.fromAsset(BOOTSTRAP_ASSET, readAssetQuietly(BOOTSTRAP_ASSET))
        ));

        executor.execute(() -> {
            if (closed.get()) {
                return;
            }

            List<RuleText> cachedTexts = readAvailableRuleTexts();
            if (!cachedTexts.isEmpty()) {
                cachedTexts.add(0, RuleText.fromAsset(
                        BOOTSTRAP_ASSET,
                        readAssetQuietly(BOOTSTRAP_ASSET)
                ));
                snapshot = buildSnapshot(cachedTexts);
            }

            boolean updated = updateStaleSources();
            if (updated && !closed.get()) {
                List<RuleText> refreshed = readAvailableRuleTexts();
                refreshed.add(0, RuleText.fromAsset(
                        BOOTSTRAP_ASSET,
                        readAssetQuietly(BOOTSTRAP_ASSET)
                ));
                snapshot = buildSnapshot(refreshed);
            }
        });
    }

    WebResourceResponse shouldIntercept(
            WebResourceRequest request,
            String pageUrl
    ) {
        if (request == null
                || request.isForMainFrame()
                || !"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        Uri uri = request.getUrl();
        if (uri == null) {
            return null;
        }

        String requestUrl = uri.toString();
        String requestHost = normalizeHost(uri.getHost());
        if (!isHttpUrl(requestUrl)
                || requestHost == null
                || isHardAllowed(requestUrl, requestHost)) {
            return null;
        }

        RequestType type = inferType(
                requestUrl,
                request.getRequestHeaders()
        );

        if (!snapshot.shouldBlock(
                requestUrl,
                requestHost,
                pageUrl,
                type
        )) {
            return null;
        }

        return emptyResponse(type);
    }

    boolean shouldBlockNavigation(
            Uri target,
            String pageUrl,
            boolean hasUserGesture
    ) {
        if (target == null || hasUserGesture) {
            return false;
        }

        String targetUrl = target.toString();
        String targetHost = normalizeHost(target.getHost());
        String pageHost = normalizeHost(Uri.parse(
                pageUrl == null ? "" : pageUrl
        ).getHost());

        if (!isHttpUrl(targetUrl)
                || targetHost == null
                || pageHost == null
                || !isThirdParty(targetHost, pageHost)
                || isHardAllowed(targetUrl, targetHost)) {
            return false;
        }

        return snapshot.shouldBlockNavigation(
                targetUrl,
                targetHost,
                pageUrl
        );
    }

    @SuppressWarnings("deprecation")
    WebResourceResponse shouldIntercept(
            String requestUrl,
            String pageUrl
    ) {
        if (!isHttpUrl(requestUrl)
                || requestUrl.equals(pageUrl)) {
            return null;
        }

        Uri uri = Uri.parse(requestUrl);
        String requestHost = normalizeHost(uri.getHost());
        if (requestHost == null || isHardAllowed(requestUrl, requestHost)) {
            return null;
        }

        RequestType type = inferType(requestUrl, Collections.emptyMap());
        return snapshot.shouldBlock(
                requestUrl,
                requestHost,
                pageUrl,
                type
        ) ? emptyResponse(type) : null;
    }

    void injectCosmeticFilters(WebView webView, String pageUrl) {
        if (webView == null
                || pageUrl == null
                || pageUrl.isEmpty()
                || closed.get()) {
            return;
        }

        String host = normalizeHost(Uri.parse(pageUrl).getHost());
        if (host == null) {
            return;
        }

        Snapshot currentSnapshot = snapshot;
        cosmeticExecutor.execute(() -> {
            if (closed.get()) {
                return;
            }

            List<String> selectors = currentSnapshot.selectorsFor(host);
            String css = buildCss(selectors);
            if (css.isEmpty()) {
                return;
            }

            String escapedCss = quoteForJavascript(css);
            String script = "(function(){try{"
                    + "var id='__webtoapp_adblock_css__';"
                    + "var s=document.getElementById(id);"
                    + "if(!s){s=document.createElement('style');s.id=id;"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + "if(s.textContent!==" + escapedCss + "){s.textContent=" + escapedCss + ";}"
                    + "}catch(e){}})();";

            mainHandler.post(() -> {
                if (closed.get()) {
                    return;
                }
                String visibleHost = normalizeHost(Uri.parse(
                        webView.getUrl() == null ? "" : webView.getUrl()
                ).getHost());
                if (host.equals(visibleHost)) {
                    webView.evaluateJavascript(script, null);
                }
            });
        });
    }

    private List<RuleText> readAvailableRuleTexts() {
        List<RuleText> texts = new ArrayList<>();
        for (FilterSource source : SOURCES) {
            File file = sourceFile(source);
            String text = readFileQuietly(file, MAX_SOURCE_BYTES);
            if (!text.isEmpty()) {
                texts.add(new RuleText(source.id, text));
            }
        }
        return texts;
    }

    private boolean updateStaleSources() {
        boolean anyUpdated = false;
        long now = System.currentTimeMillis();

        for (FilterSource source : SOURCES) {
            if (closed.get()) {
                break;
            }

            long updatedAt = preferences.getLong(
                    "updated_" + source.id,
                    0L
            );
            File destination = sourceFile(source);
            boolean stale = !destination.isFile()
                    || now - updatedAt >= UPDATE_INTERVAL_MS;

            if (!stale) {
                continue;
            }

            try {
                String text = downloadSource(source.url);
                if (!isValidFilterText(text)) {
                    continue;
                }
                writeAtomically(destination, text);
                preferences.edit()
                        .putLong("updated_" + source.id, now)
                        .apply();
                anyUpdated = true;
            } catch (Exception ignored) {
                // Fail open and continue using the last successful copy.
            }
        }

        return anyUpdated;
    }

    private File sourceFile(FilterSource source) {
        return new File(cacheDirectory, source.id + ".txt");
    }

    private String downloadSource(String sourceUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "User-Agent",
                    "WebtoApp-AdBlock/1.0 Android"
            );
            connection.setRequestProperty("Accept", "text/plain,*/*;q=0.5");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }

            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream()
            )) {
                return readLimited(input, MAX_SOURCE_BYTES);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isValidFilterText(String text) {
        if (text == null || text.length() < 1_000) {
            return false;
        }

        String prefix = text.substring(0, Math.min(2_000, text.length()))
                .toLowerCase(Locale.US);
        if (prefix.contains("<html") || prefix.contains("<!doctype html")) {
            return false;
        }

        int lines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
                if (lines >= MIN_VALID_SOURCE_LINES) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeAtomically(File destination, String text) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create ad-block cache directory");
        }

        File temporary = new File(
                destination.getParentFile(),
                destination.getName() + ".tmp"
        );

        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace old filter list");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to install new filter list");
        }
    }

    private Snapshot buildSnapshot(List<RuleText> texts) {
        RuleCollector collector = new RuleCollector();
        for (RuleText text : texts) {
            if (closed.get()) {
                return snapshot;
            }
            parseRules(text.content, collector);
        }
        return collector.freeze();
    }

    private void parseRules(String text, RuleCollector collector) {
        if (text == null || text.isEmpty()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                )
        )) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                parseLine(rawLine, collector);
            }
        } catch (IOException ignored) {
            // ByteArrayInputStream does not throw in normal operation.
        }
    }

    private void parseLine(String rawLine, RuleCollector collector) {
        String line = rawLine.trim();
        if (line.isEmpty()
                || line.startsWith("!")
                || line.startsWith("[")) {
            return;
        }

        if (parseCosmeticRule(line, collector)) {
            return;
        }

        boolean exception = line.startsWith("@@");
        if (exception) {
            line = line.substring(2);
            if (collector.exceptionRuleCount >= MAX_EXCEPTION_RULES) {
                return;
            }
        } else if (collector.networkRuleCount >= MAX_NETWORK_RULES) {
            return;
        }

        Matcher hostsMatcher = HOSTS_LINE_PATTERN.matcher(line);
        if (hostsMatcher.find()) {
            String host = normalizeHost(hostsMatcher.group(1));
            if (host != null && !"localhost".equals(host)) {
                collector.addNetworkRule(NetworkRule.domainOnly(
                        host,
                        exception,
                        RuleOptions.ANY
                ));
            }
            return;
        }

        RuleOptions options = RuleOptions.ANY;
        int optionIndex = findOptionSeparator(line);
        if (optionIndex >= 0) {
            String rawOptions = line.substring(optionIndex + 1);
            String networkPattern = line.substring(0, optionIndex);

            if (exception && handleCosmeticDisableException(
                    networkPattern,
                    rawOptions,
                    collector
            )) {
                return;
            }

            options = parseOptions(rawOptions);
            if (options == null) {
                return;
            }
            line = networkPattern;
        }

        line = line.trim();
        if (line.isEmpty()
                || (line.startsWith("/")
                && line.length() > 1
                && line.endsWith("/"))
                || line.contains("##")
                || line.contains("#?#")
                || line.contains("#$#")
                || line.contains("#%#")) {
            return;
        }

        if (line.startsWith("||")) {
            NetworkRule domainRule = parseDomainAnchor(
                    line.substring(2),
                    exception,
                    options
            );
            if (domainRule != null) {
                collector.addNetworkRule(domainRule);
                return;
            }
        }

        String candidateDomain = normalizeHost(
                stripTrailingDotAndSeparator(line)
        );
        if (candidateDomain != null
                && HOST_PATTERN.matcher(candidateDomain).matches()
                && candidateDomain.contains(".")) {
            collector.addNetworkRule(NetworkRule.domainOnly(
                    candidateDomain,
                    exception,
                    options
            ));
            return;
        }

        NetworkRule patternRule = NetworkRule.fromPattern(
                line,
                exception,
                options
        );
        if (patternRule != null) {
            collector.addNetworkRule(patternRule);
        }
    }

    private boolean parseCosmeticRule(String line, RuleCollector collector) {
        int exceptionIndex = line.indexOf("#@#");
        int hideIndex = line.indexOf("##");

        boolean exception;
        int markerIndex;
        int markerLength;

        if (exceptionIndex >= 0) {
            exception = true;
            markerIndex = exceptionIndex;
            markerLength = 3;
        } else if (hideIndex >= 0) {
            exception = false;
            markerIndex = hideIndex;
            markerLength = 2;
        } else {
            return false;
        }

        if (exception) {
            if (collector.cosmeticExceptionRuleCount
                    >= MAX_COSMETIC_EXCEPTIONS) {
                return true;
            }
        } else if (collector.cosmeticRuleCount >= MAX_COSMETIC_RULES) {
            return true;
        }

        String selector = line.substring(markerIndex + markerLength).trim();
        if (!isSafeSelector(selector)) {
            return true;
        }

        String domainPart = line.substring(0, markerIndex).trim();
        DomainScope scope = DomainScope.parse(domainPart);
        collector.addCosmeticRule(new CosmeticRule(
                selector,
                exception,
                scope
        ));
        return true;
    }

    private static int findOptionSeparator(String line) {
        int index = line.lastIndexOf('$');
        if (index <= 0) {
            return -1;
        }
        if (line.startsWith("/") && index == line.length() - 1) {
            return -1;
        }
        return index;
    }

    private static boolean handleCosmeticDisableException(
            String networkPattern,
            String rawOptions,
            RuleCollector collector
    ) {
        boolean genericHide = false;
        boolean elementHide = false;

        for (String rawOption : rawOptions.split(",")) {
            String option = rawOption.trim().toLowerCase(Locale.US);
            if ("generichide".equals(option)) {
                genericHide = true;
            } else if ("elemhide".equals(option)) {
                elementHide = true;
            }
        }

        if (!genericHide && !elementHide) {
            return false;
        }

        String host = extractAnchoredHost(networkPattern);
        if (host == null) {
            return true;
        }

        collector.addCosmeticDisable(host, elementHide);
        return true;
    }

    private static String extractAnchoredHost(String pattern) {
        if (pattern == null || !pattern.startsWith("||")) {
            return null;
        }
        String body = pattern.substring(2);
        int end = 0;
        while (end < body.length()) {
            char value = body.charAt(end);
            if (value == '^' || value == '/' || value == '*' || value == '|') {
                break;
            }
            end++;
        }
        return end == 0 ? null : normalizeHost(body.substring(0, end));
    }

    private static RuleOptions parseOptions(String rawOptions) {
        Set<RequestType> includedTypes = new HashSet<>();
        Set<RequestType> excludedTypes = new HashSet<>();
        DomainScope domainScope = DomainScope.ANY;
        Boolean thirdParty = null;
        boolean matchCase = false;

        for (String rawOption : rawOptions.split(",")) {
            String option = rawOption.trim().toLowerCase(Locale.US);
            if (option.isEmpty()) {
                continue;
            }

            boolean negated = option.startsWith("~");
            String value = negated ? option.substring(1) : option;
            int equalsIndex = value.indexOf('=');
            String name = equalsIndex >= 0
                    ? value.substring(0, equalsIndex)
                    : value;

            if ("badfilter".equals(name)) {
                return null;
            }
            if (UNSUPPORTED_OPTIONS.contains(name)) {
                return null;
            }
            if ("domain".equals(name) && equalsIndex >= 0) {
                domainScope = DomainScope.parseOptions(
                        value.substring(equalsIndex + 1)
                );
                continue;
            }
            if ("third-party".equals(name) || "3p".equals(name)) {
                thirdParty = !negated;
                continue;
            }
            if ("match-case".equals(name)) {
                matchCase = !negated;
                continue;
            }

            RequestType requestType = RequestType.fromOption(name);
            if (requestType != null) {
                if (negated) {
                    excludedTypes.add(requestType);
                } else {
                    includedTypes.add(requestType);
                }
                continue;
            }

            if ("important".equals(name)
                    || "all".equals(name)) {
                continue;
            }

            // Do not broaden rules carrying a modifier this lightweight engine cannot honor.
            return null;
        }

        return new RuleOptions(
                includedTypes,
                excludedTypes,
                domainScope,
                thirdParty,
                matchCase
        );
    }

    private static NetworkRule parseDomainAnchor(
            String body,
            boolean exception,
            RuleOptions options
    ) {
        int end = 0;
        while (end < body.length()) {
            char value = body.charAt(end);
            if (value == '^' || value == '/' || value == '*' || value == '|') {
                break;
            }
            end++;
        }

        if (end == 0) {
            return null;
        }

        String host = normalizeHost(body.substring(0, end));
        if (host == null || !host.contains(".")) {
            return null;
        }

        String remainder = body.substring(end);
        if (remainder.isEmpty() || "^".equals(remainder) || "|".equals(remainder)) {
            return NetworkRule.domainOnly(host, exception, options);
        }

        return NetworkRule.domainWithPattern(
                host,
                body,
                exception,
                options
        );
    }

    private static String stripTrailingDotAndSeparator(String value) {
        String result = value;
        while (result.endsWith("^") || result.endsWith("|")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isSafeSelector(String selector) {
        if (selector == null
                || selector.isEmpty()
                || selector.length() > 420) {
            return false;
        }

        String lower = selector.toLowerCase(Locale.US);
        if (lower.contains("+js(")
                || lower.contains(":-abp-")
                || lower.contains(":xpath(")
                || lower.contains(":remove(")
                || lower.contains(":style(")
                || lower.contains(":matches-css")
                || lower.contains("{")
                || lower.contains("}")) {
            return false;
        }

        for (int index = 0; index < selector.length(); index++) {
            char value = selector.charAt(index);
            if (value < 0x20 && value != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String buildCss(List<String> selectors) {
        StringBuilder css = new StringBuilder();
        int count = 0;

        for (String selector : selectors) {
            if (count >= MAX_INJECTED_SELECTORS) {
                break;
            }

            String rule = selector
                    + "{display:none!important;visibility:hidden!important;"
                    + "min-height:0!important;max-height:0!important;"
                    + "margin:0!important;padding:0!important;border:0!important;}";

            if (css.length() + rule.length() > MAX_INJECTED_CSS_CHARS) {
                break;
            }

            css.append(rule);
            count++;
        }

        return css.toString();
    }

    private static String quoteForJavascript(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 32);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '\u2028':
                    escaped.append("\\u2028");
                    break;
                case '\u2029':
                    escaped.append("\\u2029");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                    break;
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static WebResourceResponse emptyResponse(RequestType type) {
        if (type == RequestType.IMAGE) {
            return new WebResourceResponse(
                    "image/gif",
                    null,
                    new ByteArrayInputStream(TRANSPARENT_GIF)
            );
        }

        String mimeType;
        switch (type) {
            case SCRIPT:
                mimeType = "application/javascript";
                break;
            case STYLESHEET:
                mimeType = "text/css";
                break;
            case FONT:
                mimeType = "font/woff2";
                break;
            case MEDIA:
                mimeType = "application/octet-stream";
                break;
            default:
                mimeType = "text/plain";
                break;
        }

        return new WebResourceResponse(
                mimeType,
                "utf-8",
                new ByteArrayInputStream(EMPTY_BYTES)
        );
    }

    private static RequestType inferType(
            String requestUrl,
            Map<String, String> headers
    ) {
        String accept = headerIgnoreCase(headers, "Accept");
        String destination = headerIgnoreCase(headers, "Sec-Fetch-Dest");
        String lowerUrl = requestUrl.toLowerCase(Locale.US);

        if (containsAny(destination, "script")
                || containsAny(accept, "javascript", "ecmascript")
                || endsWithAny(lowerUrl, ".js", ".mjs")) {
            return RequestType.SCRIPT;
        }
        if (containsAny(destination, "style")
                || containsAny(accept, "text/css")
                || endsWithAny(lowerUrl, ".css")) {
            return RequestType.STYLESHEET;
        }
        if (containsAny(destination, "image")
                || containsAny(accept, "image/")
                || endsWithAny(
                lowerUrl,
                ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif", ".bmp"
        )) {
            return RequestType.IMAGE;
        }
        if (containsAny(destination, "font")
                || containsAny(accept, "font/", "application/font")
                || endsWithAny(lowerUrl, ".woff", ".woff2", ".ttf", ".otf", ".eot")) {
            return RequestType.FONT;
        }
        if (containsAny(destination, "video", "audio")
                || containsAny(accept, "video/", "audio/")
                || endsWithAny(
                lowerUrl,
                ".mp4", ".webm", ".m4v", ".mov", ".mp3", ".m4a", ".aac", ".ogg"
        )) {
            return RequestType.MEDIA;
        }
        if (containsAny(destination, "iframe", "frame")) {
            return RequestType.SUBDOCUMENT;
        }
        if (containsAny(accept, "application/json", "text/event-stream")) {
            return RequestType.XHR;
        }
        return RequestType.OTHER;
    }

    private static String headerIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? "" : entry.getValue().toLowerCase(Locale.US);
            }
        }
        return "";
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        String path = value;
        int question = path.indexOf('?');
        if (question >= 0) {
            path = path.substring(0, question);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static boolean isHardAllowed(String requestUrl, String host) {
        if ("www.google.com".equals(host) || "www.gstatic.com".equals(host)) {
            String lower = requestUrl.toLowerCase(Locale.US);
            return lower.contains("/recaptcha/") || lower.contains("/recaptcha/api");
        }
        for (String allowed : HARD_ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.US);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()
                || normalized.length() > 253
                || !HOST_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private static String siteKey(String host) {
        if (host == null) {
            return "";
        }
        String[] labels = host.split("\\.");
        if (labels.length <= 2) {
            return host;
        }
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (MULTI_LEVEL_PUBLIC_SUFFIXES.contains(lastTwo) && labels.length >= 3) {
            return labels[labels.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    private static boolean isThirdParty(String requestHost, String pageHost) {
        if (requestHost == null || pageHost == null) {
            return true;
        }
        return !siteKey(requestHost).equals(siteKey(pageHost));
    }

    private String readAssetQuietly(String assetName) {
        try (InputStream input = context.getAssets().open(assetName)) {
            return readLimited(input, 2 * 1024 * 1024);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFileQuietly(File file, int maxBytes) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (InputStream input = new FileInputStream(file)) {
            return readLimited(input, maxBytes);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Filter list exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        mainHandler.removeCallbacksAndMessages(null);
        cosmeticExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private static final class FilterSource {
        final String id;
        final String url;

        FilterSource(String id, String url) {
            this.id = id;
            this.url = url;
        }
    }

    private static final class RuleText {
        final String source;
        final String content;

        RuleText(String source, String content) {
            this.source = source;
            this.content = content == null ? "" : content;
        }

        static RuleText fromAsset(String source, String content) {
            return new RuleText(source, content);
        }
    }

    private enum RequestType {
        SCRIPT,
        STYLESHEET,
        IMAGE,
        FONT,
        MEDIA,
        SUBDOCUMENT,
        XHR,
        PING,
        OTHER;

        static RequestType fromOption(String option) {
            switch (option) {
                case "script":
                    return SCRIPT;
                case "stylesheet":
                case "css":
                    return STYLESHEET;
                case "image":
                    return IMAGE;
                case "font":
                    return FONT;
                case "media":
                    return MEDIA;
                case "subdocument":
                case "sub_frame":
                    return SUBDOCUMENT;
                case "xmlhttprequest":
                case "xhr":
                    return XHR;
                case "ping":
                case "beacon":
                    return PING;
                case "other":
                    return OTHER;
                default:
                    return null;
            }
        }
    }

    private static final class RuleOptions {
        static final RuleOptions ANY = new RuleOptions(
                Collections.emptySet(),
                Collections.emptySet(),
                DomainScope.ANY,
                null,
                false
        );

        final Set<RequestType> includedTypes;
        final Set<RequestType> excludedTypes;
        final DomainScope domainScope;
        final Boolean thirdParty;
        final boolean matchCase;

        RuleOptions(
                Set<RequestType> includedTypes,
                Set<RequestType> excludedTypes,
                DomainScope domainScope,
                Boolean thirdParty,
                boolean matchCase
        ) {
            this.includedTypes = Collections.unmodifiableSet(new HashSet<>(includedTypes));
            this.excludedTypes = Collections.unmodifiableSet(new HashSet<>(excludedTypes));
            this.domainScope = domainScope;
            this.thirdParty = thirdParty;
            this.matchCase = matchCase;
        }

        String dedupeKey() {
            return includedTypes.toString()
                    + "/" + excludedTypes
                    + "/" + domainScope.dedupeKey()
                    + "/" + thirdParty
                    + "/" + matchCase;
        }

        boolean matches(
                RequestType type,
                String pageHost,
                boolean isThirdParty
        ) {
            if (!includedTypes.isEmpty() && !includedTypes.contains(type)) {
                return false;
            }
            if (excludedTypes.contains(type)) {
                return false;
            }
            if (thirdParty != null && thirdParty != isThirdParty) {
                return false;
            }
            return domainScope.matches(pageHost);
        }
    }

    private static final class DomainScope {
        static final DomainScope ANY = new DomainScope(
                Collections.emptySet(),
                Collections.emptySet()
        );

        final Set<String> included;
        final Set<String> excluded;

        DomainScope(Set<String> included, Set<String> excluded) {
            this.included = Collections.unmodifiableSet(new LinkedHashSet<>(included));
            this.excluded = Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
        }

        static DomainScope parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return ANY;
            }
            return parseTokens(value.split(","));
        }

        static DomainScope parseOptions(String value) {
            if (value == null || value.trim().isEmpty()) {
                return ANY;
            }
            return parseTokens(value.split("\\|"));
        }

        private static DomainScope parseTokens(String[] tokens) {
            Set<String> included = new LinkedHashSet<>();
            Set<String> excluded = new LinkedHashSet<>();

            for (String rawToken : tokens) {
                String token = rawToken.trim().toLowerCase(Locale.US);
                if (token.isEmpty()) {
                    continue;
                }
                boolean negated = token.startsWith("~");
                if (negated) {
                    token = token.substring(1);
                }
                String host = normalizeHost(token);
                if (host == null) {
                    continue;
                }
                (negated ? excluded : included).add(host);
            }

            if (included.isEmpty() && excluded.isEmpty()) {
                return ANY;
            }
            return new DomainScope(included, excluded);
        }

        String dedupeKey() {
            return included.toString() + "/" + excluded;
        }

        boolean matches(String pageHost) {
            if (pageHost == null) {
                return included.isEmpty();
            }
            for (String blocked : excluded) {
                if (domainMatches(pageHost, blocked)) {
                    return false;
                }
            }
            if (included.isEmpty()) {
                return true;
            }
            for (String allowed : included) {
                if (domainMatches(pageHost, allowed)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean domainMatches(String host, String ruleDomain) {
            return host.equals(ruleDomain) || host.endsWith("." + ruleDomain);
        }
    }

    private static final class NetworkRule {
        final String domainAnchor;
        final String substring;
        final Pattern regex;
        final String keywordSource;
        final boolean exception;
        final RuleOptions options;

        private NetworkRule(
                String domainAnchor,
                String substring,
                Pattern regex,
                String keywordSource,
                boolean exception,
                RuleOptions options
        ) {
            this.domainAnchor = domainAnchor;
            this.substring = substring;
            this.regex = regex;
            this.keywordSource = keywordSource;
            this.exception = exception;
            this.options = options;
        }

        static NetworkRule domainOnly(
                String host,
                boolean exception,
                RuleOptions options
        ) {
            return new NetworkRule(host, null, null, null, exception, options);
        }

        static NetworkRule domainWithPattern(
                String host,
                String pattern,
                boolean exception,
                RuleOptions options
        ) {
            Pattern regex = compileAbpPattern(pattern, options.matchCase);
            if (regex == null) {
                return null;
            }
            return new NetworkRule(host, null, regex, pattern, exception, options);
        }

        static NetworkRule fromPattern(
                String pattern,
                boolean exception,
                RuleOptions options
        ) {
            if (pattern.length() < 3) {
                return null;
            }

            boolean hasSpecial = pattern.indexOf('*') >= 0
                    || pattern.indexOf('^') >= 0
                    || pattern.startsWith("|")
                    || pattern.endsWith("|");

            if (!hasSpecial) {
                String substring = options.matchCase
                        ? pattern
                        : pattern.toLowerCase(Locale.US);
                return new NetworkRule(
                        null,
                        substring,
                        null,
                        pattern,
                        exception,
                        options
                );
            }

            Pattern regex = compileAbpPattern(pattern, options.matchCase);
            if (regex == null) {
                return null;
            }
            return new NetworkRule(null, null, regex, pattern, exception, options);
        }

        boolean matches(
                String requestUrl,
                String requestHost,
                String pageHost,
                RequestType type
        ) {
            boolean thirdParty = isThirdParty(requestHost, pageHost);
            if (!options.matches(type, pageHost, thirdParty)) {
                return false;
            }

            if (domainAnchor != null
                    && !(requestHost.equals(domainAnchor)
                    || requestHost.endsWith("." + domainAnchor))) {
                return false;
            }

            String value = options.matchCase
                    ? requestUrl
                    : requestUrl.toLowerCase(Locale.US);

            if (substring != null) {
                return value.contains(substring);
            }
            return regex == null || regex.matcher(value).find();
        }

        String keyword() {
            String source = keywordSource;
            if (source == null) {
                return null;
            }

            Matcher matcher = TOKEN_PATTERN.matcher(source.toLowerCase(Locale.US));
            String best = null;
            while (matcher.find()) {
                String token = matcher.group();
                if (best == null || token.length() > best.length()) {
                    best = token;
                }
            }
            if (best == null || best.length() < 4) {
                return null;
            }
            return best.substring(0, 4);
        }

        String dedupeKey() {
            String regexValue = regex == null ? "" : regex.pattern();
            return (exception ? "A|" : "B|")
                    + (domainAnchor == null ? "" : domainAnchor)
                    + "|" + (substring == null ? "" : substring)
                    + "|" + regexValue
                    + "|" + options.dedupeKey();
        }

        private static Pattern compileAbpPattern(
                String pattern,
                boolean matchCase
        ) {
            StringBuilder regex = new StringBuilder();
            int index = 0;

            if (pattern.startsWith("||")) {
                regex.append("^(?:[^:/?#]+:)?(?://)?(?:[^/?#]*\\.)?");
                index = 2;
            } else if (pattern.startsWith("|")) {
                regex.append('^');
                index = 1;
            }

            boolean endAnchored = pattern.endsWith("|")
                    && pattern.length() > index;
            int end = endAnchored ? pattern.length() - 1 : pattern.length();

            for (; index < end; index++) {
                char character = pattern.charAt(index);
                switch (character) {
                    case '*':
                        regex.append(".*");
                        break;
                    case '^':
                        regex.append("(?:[^A-Za-z0-9_\\-.%]|$)");
                        break;
                    case '.':
                    case '\\':
                    case '+':
                    case '?':
                    case '(': case ')':
                    case '[': case ']':
                    case '{': case '}':
                    case '$': case '|':
                        regex.append('\\').append(character);
                        break;
                    default:
                        regex.append(character);
                        break;
                }
            }

            if (endAnchored) {
                regex.append('$');
            }

            try {
                return Pattern.compile(
                        regex.toString(),
                        matchCase ? 0 : Pattern.CASE_INSENSITIVE
                );
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class CosmeticRule {
        final String selector;
        final boolean exception;
        final DomainScope scope;

        CosmeticRule(
                String selector,
                boolean exception,
                DomainScope scope
        ) {
            this.selector = selector;
            this.exception = exception;
            this.scope = scope;
        }

        String dedupeKey() {
            return (exception ? "A|" : "B|")
                    + scope.dedupeKey()
                    + "|" + selector;
        }
    }

    private static final class RuleCollector {
        final Map<String, List<NetworkRule>> blockedDomainRules = new HashMap<>();
        final Map<String, List<NetworkRule>> allowedDomainRules = new HashMap<>();
        final Map<String, List<NetworkRule>> blockedKeywordRules = new HashMap<>();
        final Map<String, List<NetworkRule>> allowedKeywordRules = new HashMap<>();
        final List<NetworkRule> blockedFallbackRules = new ArrayList<>();
        final List<NetworkRule> allowedFallbackRules = new ArrayList<>();
        final List<CosmeticRule> cosmeticRules = new ArrayList<>();
        final Set<String> networkKeys = new HashSet<>();
        final Set<String> cosmeticKeys = new HashSet<>();
        final Set<String> genericHideDisabledDomains = new LinkedHashSet<>();
        final Set<String> elementHideDisabledDomains = new LinkedHashSet<>();
        int networkRuleCount;
        int exceptionRuleCount;
        int cosmeticRuleCount;
        int cosmeticExceptionRuleCount;

        void addCosmeticDisable(String host, boolean allElementHiding) {
            if (host == null) {
                return;
            }
            if (allElementHiding) {
                elementHideDisabledDomains.add(host);
            } else {
                genericHideDisabledDomains.add(host);
            }
        }

        void addNetworkRule(NetworkRule rule) {
            if (rule == null) {
                return;
            }
            if (rule.exception) {
                if (exceptionRuleCount >= MAX_EXCEPTION_RULES) {
                    return;
                }
            } else if (networkRuleCount >= MAX_NETWORK_RULES) {
                return;
            }
            if (!networkKeys.add(rule.dedupeKey())) {
                return;
            }

            Map<String, List<NetworkRule>> domainMap = rule.exception
                    ? allowedDomainRules
                    : blockedDomainRules;
            Map<String, List<NetworkRule>> keywordMap = rule.exception
                    ? allowedKeywordRules
                    : blockedKeywordRules;
            List<NetworkRule> fallback = rule.exception
                    ? allowedFallbackRules
                    : blockedFallbackRules;

            if (rule.domainAnchor != null) {
                domainMap.computeIfAbsent(
                        rule.domainAnchor,
                        ignored -> new ArrayList<>()
                ).add(rule);
            } else {
                String keyword = rule.keyword();
                if (keyword == null) {
                    if (fallback.size() < 2_000) {
                        fallback.add(rule);
                    } else {
                        return;
                    }
                } else {
                    keywordMap.computeIfAbsent(
                            keyword,
                            ignored -> new ArrayList<>()
                    ).add(rule);
                }
            }
            if (rule.exception) {
                exceptionRuleCount++;
            } else {
                networkRuleCount++;
            }
        }

        void addCosmeticRule(CosmeticRule rule) {
            if (rule == null) {
                return;
            }
            if (rule.exception) {
                if (cosmeticExceptionRuleCount
                        >= MAX_COSMETIC_EXCEPTIONS) {
                    return;
                }
            } else if (cosmeticRuleCount >= MAX_COSMETIC_RULES) {
                return;
            }
            if (!cosmeticKeys.add(rule.dedupeKey())) {
                return;
            }
            cosmeticRules.add(rule);
            if (rule.exception) {
                cosmeticExceptionRuleCount++;
            } else {
                cosmeticRuleCount++;
            }
        }

        Snapshot freeze() {
            return new Snapshot(
                    freezeMap(blockedDomainRules),
                    freezeMap(allowedDomainRules),
                    freezeMap(blockedKeywordRules),
                    freezeMap(allowedKeywordRules),
                    Collections.unmodifiableList(new ArrayList<>(blockedFallbackRules)),
                    Collections.unmodifiableList(new ArrayList<>(allowedFallbackRules)),
                    Collections.unmodifiableList(new ArrayList<>(cosmeticRules)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(
                            genericHideDisabledDomains
                    )),
                    Collections.unmodifiableSet(new LinkedHashSet<>(
                            elementHideDisabledDomains
                    ))
            );
        }

        private static Map<String, List<NetworkRule>> freezeMap(
                Map<String, List<NetworkRule>> source
        ) {
            Map<String, List<NetworkRule>> result = new HashMap<>();
            for (Map.Entry<String, List<NetworkRule>> entry : source.entrySet()) {
                result.put(
                        entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<>(entry.getValue()))
                );
            }
            return Collections.unmodifiableMap(result);
        }
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet()
        );

        final Map<String, List<NetworkRule>> blockedDomainRules;
        final Map<String, List<NetworkRule>> allowedDomainRules;
        final Map<String, List<NetworkRule>> blockedKeywordRules;
        final Map<String, List<NetworkRule>> allowedKeywordRules;
        final List<NetworkRule> blockedFallbackRules;
        final List<NetworkRule> allowedFallbackRules;
        final List<CosmeticRule> cosmeticRules;
        final Set<String> genericHideDisabledDomains;
        final Set<String> elementHideDisabledDomains;
        final Map<String, List<String>> cosmeticCache =
                new ConcurrentHashMap<>();

        Snapshot(
                Map<String, List<NetworkRule>> blockedDomainRules,
                Map<String, List<NetworkRule>> allowedDomainRules,
                Map<String, List<NetworkRule>> blockedKeywordRules,
                Map<String, List<NetworkRule>> allowedKeywordRules,
                List<NetworkRule> blockedFallbackRules,
                List<NetworkRule> allowedFallbackRules,
                List<CosmeticRule> cosmeticRules,
                Set<String> genericHideDisabledDomains,
                Set<String> elementHideDisabledDomains
        ) {
            this.blockedDomainRules = blockedDomainRules;
            this.allowedDomainRules = allowedDomainRules;
            this.blockedKeywordRules = blockedKeywordRules;
            this.allowedKeywordRules = allowedKeywordRules;
            this.blockedFallbackRules = blockedFallbackRules;
            this.allowedFallbackRules = allowedFallbackRules;
            this.cosmeticRules = cosmeticRules;
            this.genericHideDisabledDomains = genericHideDisabledDomains;
            this.elementHideDisabledDomains = elementHideDisabledDomains;
        }

        boolean shouldBlock(
                String requestUrl,
                String requestHost,
                String pageUrl,
                RequestType type
        ) {
            String pageHost = normalizeHost(Uri.parse(
                    pageUrl == null ? "" : pageUrl
            ).getHost());

            List<NetworkRule> allowCandidates = candidates(
                    allowedDomainRules,
                    allowedKeywordRules,
                    allowedFallbackRules,
                    requestHost,
                    requestUrl
            );
            for (NetworkRule rule : allowCandidates) {
                if (rule.matches(
                        requestUrl,
                        requestHost,
                        pageHost,
                        type
                )) {
                    return false;
                }
            }

            List<NetworkRule> blockCandidates = candidates(
                    blockedDomainRules,
                    blockedKeywordRules,
                    blockedFallbackRules,
                    requestHost,
                    requestUrl
            );
            for (NetworkRule rule : blockCandidates) {
                if (rule.matches(
                        requestUrl,
                        requestHost,
                        pageHost,
                        type
                )) {
                    return true;
                }
            }
            return false;
        }

        boolean shouldBlockNavigation(
                String requestUrl,
                String requestHost,
                String pageUrl
        ) {
            String pageHost = normalizeHost(Uri.parse(
                    pageUrl == null ? "" : pageUrl
            ).getHost());

            for (NetworkRule rule : domainCandidates(
                    allowedDomainRules,
                    requestHost
            )) {
                if (rule.matches(
                        requestUrl,
                        requestHost,
                        pageHost,
                        RequestType.OTHER
                )) {
                    return false;
                }
            }

            for (NetworkRule rule : domainCandidates(
                    blockedDomainRules,
                    requestHost
            )) {
                if (rule.matches(
                        requestUrl,
                        requestHost,
                        pageHost,
                        RequestType.OTHER
                )) {
                    return true;
                }
            }
            return false;
        }

        List<String> selectorsFor(String host) {
            List<String> cached = cosmeticCache.get(host);
            if (cached != null) {
                return cached;
            }

            if (matchesDomainSet(host, elementHideDisabledDomains)) {
                cosmeticCache.put(host, Collections.emptyList());
                return Collections.emptyList();
            }

            boolean genericDisabled = matchesDomainSet(
                    host,
                    genericHideDisabledDomains
            );
            LinkedHashSet<String> specificSelectors = new LinkedHashSet<>();
            LinkedHashSet<String> genericSelectors = new LinkedHashSet<>();
            Set<String> exceptions = new HashSet<>();

            for (CosmeticRule rule : cosmeticRules) {
                if (!rule.scope.matches(host)) {
                    continue;
                }
                boolean generic = rule.scope == DomainScope.ANY;
                if (genericDisabled && generic) {
                    continue;
                }
                if (rule.exception) {
                    exceptions.add(rule.selector);
                } else if (generic) {
                    genericSelectors.add(rule.selector);
                } else {
                    specificSelectors.add(rule.selector);
                }
            }

            specificSelectors.removeAll(exceptions);
            genericSelectors.removeAll(exceptions);

            LinkedHashSet<String> selectors = new LinkedHashSet<>();
            selectors.addAll(specificSelectors);
            selectors.addAll(genericSelectors);
            List<String> result;
            if (selectors.size() <= MAX_INJECTED_SELECTORS) {
                result = Collections.unmodifiableList(
                        new ArrayList<>(selectors)
                );
            } else {
                List<String> limited = new ArrayList<>(
                        MAX_INJECTED_SELECTORS
                );
                int count = 0;
                for (String selector : selectors) {
                    limited.add(selector);
                    count++;
                    if (count >= MAX_INJECTED_SELECTORS) {
                        break;
                    }
                }
                result = Collections.unmodifiableList(limited);
            }

            cosmeticCache.put(host, result);
            return result;
        }

        private static boolean matchesDomainSet(
                String host,
                Set<String> domains
        ) {
            String current = host;
            while (current != null && current.contains(".")) {
                if (domains.contains(current)) {
                    return true;
                }
                int dot = current.indexOf('.');
                current = dot < 0 ? null : current.substring(dot + 1);
            }
            return false;
        }

        private static List<NetworkRule> domainCandidates(
                Map<String, List<NetworkRule>> domainRules,
                String requestHost
        ) {
            LinkedHashSet<NetworkRule> result = new LinkedHashSet<>();
            String current = requestHost;
            while (current != null && current.contains(".")) {
                List<NetworkRule> rules = domainRules.get(current);
                if (rules != null) {
                    result.addAll(rules);
                }
                int dot = current.indexOf('.');
                current = dot < 0 ? null : current.substring(dot + 1);
            }
            return new ArrayList<>(result);
        }

        private static List<NetworkRule> candidates(
                Map<String, List<NetworkRule>> domainRules,
                Map<String, List<NetworkRule>> keywordRules,
                List<NetworkRule> fallbackRules,
                String requestHost,
                String requestUrl
        ) {
            LinkedHashSet<NetworkRule> result = new LinkedHashSet<>();

            String current = requestHost;
            while (current != null && current.contains(".")) {
                List<NetworkRule> rules = domainRules.get(current);
                if (rules != null) {
                    result.addAll(rules);
                }
                int dot = current.indexOf('.');
                current = dot < 0 ? null : current.substring(dot + 1);
            }

            String lowerUrl = requestUrl.toLowerCase(Locale.US);
            if (lowerUrl.length() >= 4) {
                Set<String> seenKeys = new HashSet<>();
                for (int index = 0; index <= lowerUrl.length() - 4; index++) {
                    String key = lowerUrl.substring(index, index + 4);
                    if (!seenKeys.add(key)) {
                        continue;
                    }
                    List<NetworkRule> rules = keywordRules.get(key);
                    if (rules != null) {
                        result.addAll(rules);
                    }
                }
            }

            result.addAll(fallbackRules);
            return new ArrayList<>(result);
        }
    }
}
