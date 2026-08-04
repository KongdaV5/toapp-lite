package com.kongda.toapplite.builder;

import java.io.File;

final class BuildSpec {
    final String appName;
    final String packageName;
    final String url;
    final File iconPng;

    BuildSpec(String appName, String packageName, String url, File iconPng) {
        this.appName = appName;
        this.packageName = packageName;
        this.url = url;
        this.iconPng = iconPng;
    }
}
