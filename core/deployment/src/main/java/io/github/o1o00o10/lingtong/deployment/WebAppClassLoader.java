/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** 应用类与资源优先从 WAR 加载，平台 API 和容器 API 则由父加载器提供。 */
public final class WebAppClassLoader extends URLClassLoader {
    WebAppClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /** 阻止应用加载容器实现类，避免相同类在应用和容器间出现两份定义。 */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = super.loadClass(name, false);
                } else if (isContainerImplementation(name)) {
                    throw new ClassNotFoundException("container implementation class is hidden: " + name);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missingFromApplication) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /** 单个资源优先查找应用本地副本。 */
    @Override
    public URL getResource(String name) {
        URL local = findResource(name);
        return local == null ? super.getResource(name) : local;
    }

    /** 多资源枚举先列应用，再列父加载器并去重。 */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> resources = new ArrayList<URL>();
        Enumeration<URL> local = findResources(name);
        while (local.hasMoreElements()) {
            resources.add(local.nextElement());
        }
        Enumeration<URL> parent = getParent().getResources(name);
        while (parent.hasMoreElements()) {
            URL value = parent.nextElement();
            if (!resources.contains(value)) {
                resources.add(value);
            }
        }
        return Collections.enumeration(resources);
    }

    /** Java、Servlet API 及容器公开 API 必须保持父加载器中的类型身份。 */
    private static boolean isParentFirst(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("org.w3c.dom.")
                || name.startsWith("org.xml.sax.")
                || name.startsWith("io.github.o1o00o10.lingtong.api.")
                || name.equals(
                        "io.github.o1o00o10.lingtong.websocket.LingTongDefaultServerEndpointConfigurator");
    }

    private static boolean isContainerImplementation(String name) {
        return name.startsWith("io.github.o1o00o10.lingtong.");
    }
}
