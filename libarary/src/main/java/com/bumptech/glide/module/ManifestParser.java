package com.bumptech.glide.module;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@link com.bumptech.glide.module.GlideModule} references out of the AndroidManifest file.
 * GlideModule可以通过在AndroidManifest中设置指定的meta-data来指定
 * 注意因为使用了反射，所以说在混淆的时候需要保留指定的GlideModule的原文
 */
public final class ManifestParser {
    private static final String GLIDE_MODULE_VALUE = "GlideModule";

    private final Context context;

    public ManifestParser(Context context) {
        this.context = context;
    }

    /**
     * 解析AndroidManifest中指定的GlideModule模块
     * 可以看出可以通过指定
     * <meta-data android:name="AModule" android:value="GlideModule" />
     * <meta-data android:name="BModule" android:value="GlideModule" />
     * 来指定AModule.java和BModule.java作为GlideModule
     * @return 满足条件的GlideModule列表
     */
    public List<GlideModule> parse() {
        List<GlideModule> modules = new ArrayList<GlideModule>();
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            if (appInfo.metaData != null) {
                for (String key : appInfo.metaData.keySet()) {
                    if (GLIDE_MODULE_VALUE.equals(appInfo.metaData.get(key))) {
                        modules.add(parseModule(key));
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Unable to find metadata to parse GlideModules", e);
        }

        return modules;
    }

    /**
     * 通过反射调用空构造函数生成GlideModule实例
     * 注意该类型Module调用的是空构造函数
     * @param className Module的类名，用于反射
     */
    private static GlideModule parseModule(String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unable to find GlideModule implementation", e);
        }

        Object module;
        try {
            module = clazz.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException("Unable to instantiate GlideModule implementation for " + clazz, e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Unable to instantiate GlideModule implementation for " + clazz, e);
        }

        if (!(module instanceof GlideModule)) {
            throw new RuntimeException("Expected instanceof GlideModule, but found: " + module);
        }
        return (GlideModule) module;
    }
}
