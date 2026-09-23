/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次归档扫描的不可变结果，供类加载和 Servlet 元数据编译复用。 */
final class WebAppArchiveIndex {
  /** WEB-INF/classes 路径；目录不存在时为空。 */
  final Path classesRoot;
  /** 应用目录中可发现的类名，已按名称排序。 */
  final List<String> classes;
  /** 上述类对应的字节码元数据。 */
  final List<ClassFileMetadata> classMetadata;
  /** 按文件名排序的 WEB-INF/lib 归档。 */
  final List<Library> libraries;

  WebAppArchiveIndex(Path classesRoot, List<String> classes, List<Library> libraries) {
    this(classesRoot, classes, Collections.<ClassFileMetadata>emptyList(), libraries);
  }

  WebAppArchiveIndex(
      Path classesRoot,
      List<String> classes,
      List<ClassFileMetadata> classMetadata,
      List<Library> libraries) {
    this.classesRoot = classesRoot;
    this.classes = Collections.unmodifiableList(new ArrayList<String>(classes));
    this.classMetadata =
        Collections.unmodifiableList(new ArrayList<ClassFileMetadata>(classMetadata));
    this.libraries = Collections.unmodifiableList(new ArrayList<Library>(libraries));
  }

  /** 按类目录、库归档的顺序生成应用类加载器搜索路径。 */
  List<URL> urls() throws java.net.MalformedURLException {
    List<URL> urls = new ArrayList<URL>();
    if (classesRoot != null) urls.add(classesRoot.toUri().toURL());
    for (Library library : libraries) urls.add(library.path.toUri().toURL());
    return urls;
  }

  /** 单个应用 JAR 的类清单及元数据。 */
  static final class Library {
    /** JAR 的真实路径。 */
    final Path path;
    /** 用于 web-fragment 排序和匹配的文件名。 */
    final String fileName;
    /** 本 JAR 中可发现的类名。 */
    final List<String> classes;
    /** 本 JAR 中可发现类的字节码元数据。 */
    final List<ClassFileMetadata> classMetadata;

    Library(Path path, List<String> classes) {
      this(path, classes, Collections.<ClassFileMetadata>emptyList());
    }

    Library(Path path, List<String> classes, List<ClassFileMetadata> classMetadata) {
      this.path = path;
      this.fileName = path.getFileName().toString();
      this.classes = Collections.unmodifiableList(new ArrayList<String>(classes));
      this.classMetadata =
          Collections.unmodifiableList(new ArrayList<ClassFileMetadata>(classMetadata));
    }
  }
}
