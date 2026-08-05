package com.kongda.toapplite.builder;

import java.io.File;

final class BuildSpec {
    final String appName;
    final String packageName;
    final String url;
    final File iconPng;
    final boolean adBlockEnabled;

    BuildSpec(
            String appName,
            String packageName,
            String url,
            File iconPng,
            boolean adBlockEnabled
    ) {
        this.appName = appName;
        this.packageName = packageName;
        this.url = url;
        this.iconPng = iconPng;
        this.adBlockEnabled = adBlockEnabled;
    }
}
