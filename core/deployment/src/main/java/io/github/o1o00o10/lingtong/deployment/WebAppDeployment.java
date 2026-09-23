/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import io.github.o1o00o10.lingtong.servlet.ServletApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 持有已准备应用的元数据、类加载器与临时资源；关闭时统一清理。 */
public final class WebAppDeployment implements AutoCloseable {
  /** 归档及展开目录允许的默认最大条目数。 */
  public static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 10_000;
  /** 归档及展开目录允许的默认最大字节数。 */
  public static final long DEFAULT_MAX_EXPANDED_BYTES = 256L * 1024L * 1024L;
  /** 单个类文件的最大读取字节数。 */
  private static final int MAX_CLASSFILE_BYTES = 16 * 1024 * 1024;
  /** JAR 中可暴露为 Web 静态资源的目录前缀。 */
  private static final String LIBRARY_RESOURCES = "META-INF/resources/";

  /** 应用内容根目录；展开 WAR 时位于 temporaryRoot 内。 */
  private final Path root;
  /** 仅 WAR 模式创建的展开目录，由本对象删除。 */
  private final Path temporaryRoot;
  /** 每次部署独立创建的工作目录，由本对象删除。 */
  private final Path workRoot;
  /** 交给 ServletContext 使用的应用临时目录。 */
  private final Path applicationTemp;
  /** 从依赖 JAR 的 META-INF/resources 提取的资源覆盖目录。 */
  private final Path resourceOverlay;
  /** 仅用于该应用的类加载器，关闭时释放 JAR 句柄。 */
  private final WebAppClassLoader classLoader;
  /** 已合并 web.xml、fragment 与注解的部署模型。 */
  private final WebXmlModel model;
  /** 类扫描和初始化器匹配复用的归档索引。 */
  private final WebAppArchiveIndex index;
  /** 防止应用与调用方重复释放部署资源。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  private WebAppDeployment(
      Path root,
      Path temporaryRoot,
      Path workRoot,
      Path applicationTemp,
      Path resourceOverlay,
      WebAppClassLoader classLoader,
      WebXmlModel model,
      WebAppArchiveIndex index) {
    this.root = root;
    this.temporaryRoot = temporaryRoot;
    this.workRoot = workRoot;
    this.applicationTemp = applicationTemp;
    this.resourceOverlay = resourceOverlay;
    this.classLoader = classLoader;
    this.model = model;
    this.index = index;
  }

  /** 使用默认条目与字节限制准备展开目录或 WAR。 */
  public static WebAppDeployment open(Path artifact) throws WebAppDeploymentException {
    return open(artifact, DEFAULT_MAX_ARCHIVE_ENTRIES, DEFAULT_MAX_EXPANDED_BYTES);
  }

  /** 先校验归档并编译元数据，再允许挂接到 Servlet 应用；失败时回收已创建资源。 */
  public static WebAppDeployment open(Path artifact, int maxEntries, long maxExpandedBytes)
      throws WebAppDeploymentException {
    if (artifact == null) {
      throw new IllegalArgumentException("web application artifact must not be null");
    }
    if (maxEntries <= 0 || maxExpandedBytes <= 0L) {
      throw new IllegalArgumentException("deployment archive limits must be positive");
    }
    Path temporary = null;
    Path work = null;
    Path applicationTemp = null;
    Path resourceOverlay = null;
    WebAppClassLoader loader = null;
    try {
      Path source = artifact.toRealPath();
      Path root;
      if (Files.isDirectory(source)) {
        root = source;
      } else if (Files.isRegularFile(source)
          && source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".war")) {
        temporary = Files.createTempDirectory("lingtong-war-");
        extractWar(source, temporary, maxEntries, maxExpandedBytes);
        root = temporary.toRealPath();
      } else {
        throw new WebAppDeploymentException(
            "web application must be an expanded directory or .war file: " + artifact);
      }
      work = Files.createTempDirectory("lingtong-webapp-").toRealPath();
      applicationTemp = Files.createDirectory(work.resolve("temp"));
      resourceOverlay = Files.createDirectory(work.resolve("resources"));
      WebAppArchiveIndex inspection = inspect(root, resourceOverlay, maxEntries, maxExpandedBytes);
      List<URL> urls = inspection.urls();
      loader =
          new WebAppClassLoader(
              urls.toArray(new URL[urls.size()]), WebAppDeployment.class.getClassLoader());
      WebXmlModel model = new WebMetadataCompiler().compile(root, inspection, loader);
      return new WebAppDeployment(
          root, temporary, work, applicationTemp, resourceOverlay, loader, model, inspection);
    } catch (WebAppDeploymentException e) {
      closeQuietly(loader);
      deleteQuietly(work);
      deleteQuietly(temporary);
      throw e;
    } catch (IOException | RuntimeException e) {
      closeQuietly(loader);
      deleteQuietly(work);
      deleteQuietly(temporary);
      throw new WebAppDeploymentException("cannot prepare web application " + artifact, e);
    }
  }

  /** 返回已准备应用的内容根目录。 */
  public Path root() {
    return root;
  }

  /** 返回应用专属类加载器；其生命周期仍由本部署对象管理。 */
  public ClassLoader classLoader() {
    return classLoader;
  }

  /** 按 Servlet HandlesTypes 规则筛选应用类，部署关闭后不可调用。 */
  public Set<Class<?>> matchingClasses(Class<?>... interests) throws WebAppDeploymentException {
    if (closed.get()) {
      throw new IllegalStateException("web application deployment is closed");
    }
    if (interests == null || interests.length == 0) return Collections.emptySet();
    return WebMetadataCompiler.matchingClasses(model, index, classLoader, interests);
  }

  /** 将本对象注册为应用托管资源，由应用停止流程触发清理。 */
  public void applyTo(ServletApplication.Builder builder) throws WebAppDeploymentException {
    if (builder == null) {
      throw new IllegalArgumentException("Servlet application builder must not be null");
    }
    if (closed.get()) {
      throw new IllegalStateException("web application deployment is closed");
    }
    builder.resourceRoot(root);
    builder.resourceOverlay(resourceOverlay);
    builder.temporaryDirectory(applicationTemp);
    builder.applicationClassLoader(classLoader);
    builder.managedResource(this);
    model.apply(builder, classLoader);
  }

  /** 幂等关闭类加载器并清理本次部署创建的目录，不删除原始展开目录。 */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    closeQuietly(classLoader);
    deleteQuietly(workRoot);
    deleteQuietly(temporaryRoot);
  }

  /** 展开 WAR 时限制条目数与累计字节数，并拒绝重名和越界路径。 */
  private static void extractWar(Path archive, Path target, int maxEntries, long maxBytes)
      throws IOException, WebAppDeploymentException {
    int entries = 0;
    long expanded = 0L;
    Set<Path> destinations = new HashSet<Path>();
    byte[] buffer = new byte[16 * 1024];
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        entries++;
        if (entries > maxEntries) {
          throw new WebAppDeploymentException("WAR exceeds configured entry count");
        }
        Path destination = safeDestination(target, entry.getName());
        if (!destinations.add(destination)) {
          throw new WebAppDeploymentException("WAR contains duplicate entry: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(destination);
        } else {
          Path parent = destination.getParent();
          if (parent != null) Files.createDirectories(parent);
          try (OutputStream output =
              Files.newOutputStream(
                  destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
              if (expanded > maxBytes - count) {
                throw new WebAppDeploymentException("WAR exceeds configured expanded size");
              }
              expanded += count;
              output.write(buffer, 0, count);
            }
          }
        }
        input.closeEntry();
      }
    }
  }

  /** 将归档内路径约束在目标目录内，防止路径穿越。 */
  private static Path safeDestination(Path target, String entryName)
      throws WebAppDeploymentException {
    if (entryName == null
        || entryName.isEmpty()
        || entryName.indexOf('\0') >= 0
        || entryName.indexOf('\\') >= 0
        || entryName.startsWith("/")) {
      throw new WebAppDeploymentException("WAR contains invalid entry name: " + entryName);
    }
    Path destination = target.resolve(entryName).normalize();
    if (!destination.startsWith(target)) {
      throw new WebAppDeploymentException("WAR entry escapes deployment root: " + entryName);
    }
    return destination;
  }

  /** 检查目录及 JAR，建立类索引和静态资源覆盖层。 */
  private static WebAppArchiveIndex inspect(
      Path root, Path resourceOverlay, int maxEntries, long maxBytes)
      throws IOException, WebAppDeploymentException {
    Path realRoot = root.toRealPath();
    InspectionBudget budget = new InspectionBudget(maxEntries, maxBytes);
    try (Stream<Path> paths = Files.walk(realRoot)) {
      Iterator<Path> iterator = paths.iterator();
      while (iterator.hasNext()) {
        Path path = iterator.next();
        budget.addEntry();
        if (Files.isSymbolicLink(path)) {
          throw new WebAppDeploymentException(
              "symbolic links are not allowed in web applications: " + path);
        }
        Path real = path.toRealPath();
        if (!real.startsWith(realRoot)) {
          throw new WebAppDeploymentException(
              "web application path escapes deployment root: " + path);
        }
        if (Files.isRegularFile(real)) {
          budget.addBytes(Files.size(real));
        }
      }
    }

    Path classes = realRoot.resolve("WEB-INF").resolve("classes");
    Path libraries = realRoot.resolve("WEB-INF").resolve("lib");
    List<Path> jars = new ArrayList<Path>();
    if (Files.isDirectory(libraries)) {
      try (Stream<Path> paths = Files.list(libraries)) {
        Iterator<Path> iterator = paths.iterator();
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (Files.isRegularFile(path)
              && path.getFileName()
                  .toString()
                  .toLowerCase(java.util.Locale.ROOT)
                  .endsWith(".jar")) {
            jars.add(path.toRealPath());
          }
        }
      }
      Collections.sort(jars, Comparator.comparing(path -> path.getFileName().toString()));
    }

    Map<String, String> classOwners = new HashMap<String, String>();
    List<String> applicationClasses = new ArrayList<String>();
    List<ClassFileMetadata> applicationMetadata = new ArrayList<ClassFileMetadata>();
    ClassFileParser classFileParser = new ClassFileParser();
    if (Files.isDirectory(classes)) {
      try (Stream<Path> paths = Files.walk(classes)) {
        Iterator<Path> iterator = paths.iterator();
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".class")) {
            String classFile = classes.relativize(path).toString().replace('\\', '/');
            registerClass(classOwners, classFile, "WEB-INF/classes");
            String className = discoverableClassName(classFile);
            if (className != null) {
              ClassFileMetadata metadata =
                  classFileParser.parse(
                      readClassFile(path, "WEB-INF/classes/" + classFile),
                      "WEB-INF/classes/" + classFile);
              requireMatchingClassName(className, metadata, "WEB-INF/classes/" + classFile);
              applicationClasses.add(className);
              applicationMetadata.add(metadata);
            }
          }
        }
      }
    }
    Collections.sort(applicationClasses);
    Collections.sort(applicationMetadata, Comparator.comparing(value -> value.className));
    List<WebAppArchiveIndex.Library> indexedLibraries = new ArrayList<WebAppArchiveIndex.Library>();
    Set<String> libraryResources = new HashSet<String>();
    for (Path jar : jars) {
      JarInspection inspection =
          inspectJar(jar, classOwners, budget, classFileParser, resourceOverlay, libraryResources);
      indexedLibraries.add(
          new WebAppArchiveIndex.Library(jar, inspection.classes, inspection.metadata));
    }
    return new WebAppArchiveIndex(
        Files.isDirectory(classes) ? classes : null,
        applicationClasses,
        applicationMetadata,
        indexedLibraries);
  }

  /** 扫描单个依赖 JAR，同时计入部署配额并提取可公开资源。 */
  private static JarInspection inspectJar(
      Path jar,
      Map<String, String> classOwners,
      InspectionBudget budget,
      ClassFileParser classFileParser,
      Path resourceOverlay,
      Set<String> libraryResources)
      throws IOException, WebAppDeploymentException {
    List<String> classes = new ArrayList<String>();
    List<ClassFileMetadata> metadata = new ArrayList<ClassFileMetadata>();
    try (JarFile file = new JarFile(jar.toFile(), true)) {
      java.util.Enumeration<JarEntry> entries = file.entries();
      byte[] buffer = new byte[16 * 1024];
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        budget.addEntry();
        if (name.startsWith("/") || name.contains("../") || name.indexOf('\\') >= 0) {
          throw new WebAppDeploymentException(
              "invalid JAR entry in " + jar.getFileName() + ": " + name);
        }
        if (!entry.isDirectory()) {
          ByteArrayOutputStream classBytes =
              name.endsWith(".class") && discoverableClassName(name) != null
                  ? new ByteArrayOutputStream()
                  : null;
          Path resourceDestination = null;
          if (name.startsWith(LIBRARY_RESOURCES) && name.length() > LIBRARY_RESOURCES.length()) {
            String resourceName = name.substring(LIBRARY_RESOURCES.length());
            if (libraryResources.add(resourceName)) {
              resourceDestination = safeDestination(resourceOverlay, resourceName);
              Path parent = resourceDestination.getParent();
              if (parent != null) Files.createDirectories(parent);
            }
          }
          OutputStream resourceOutput =
              resourceDestination == null
                  ? null
                  : Files.newOutputStream(
                      resourceDestination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          try (InputStream input = file.getInputStream(entry)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
              budget.addBytes(count);
              if (resourceOutput != null) resourceOutput.write(buffer, 0, count);
              if (classBytes != null) {
                if (classBytes.size() > MAX_CLASSFILE_BYTES - count) {
                  throw new WebAppDeploymentException(
                      "class file exceeds 16 MiB: " + jar.getFileName() + "!/" + name);
                }
                classBytes.write(buffer, 0, count);
              }
            }
          } finally {
            if (resourceOutput != null) resourceOutput.close();
          }
          if (name.endsWith(".class")) {
            registerClass(classOwners, name, jar.getFileName().toString());
            String className = discoverableClassName(name);
            if (className != null) {
              ClassFileMetadata value =
                  classFileParser.parse(classBytes.toByteArray(), jar.getFileName() + "!/" + name);
              requireMatchingClassName(className, value, jar.getFileName() + "!/" + name);
              classes.add(className);
              metadata.add(value);
            }
          }
        }
      }
    }
    Collections.sort(classes);
    Collections.sort(metadata, Comparator.comparing(value -> value.className));
    return new JarInspection(classes, metadata);
  }

  private static byte[] readClassFile(Path path, String source)
      throws IOException, WebAppDeploymentException {
    if (Files.size(path) > MAX_CLASSFILE_BYTES) {
      throw new WebAppDeploymentException("class file exceeds 16 MiB: " + source);
    }
    return Files.readAllBytes(path);
  }

  private static void requireMatchingClassName(
      String expected, ClassFileMetadata metadata, String source) throws WebAppDeploymentException {
    if (!expected.equals(metadata.className)) {
      throw new WebAppDeploymentException(
          "class file name does not match declared class in " + source);
    }
  }

  private static String discoverableClassName(String classFile) {
    if (classFile.startsWith("META-INF/")
        || classFile.endsWith("/module-info.class")
        || "module-info.class".equals(classFile)
        || classFile.endsWith("/package-info.class")
        || "package-info.class".equals(classFile)) {
      return null;
    }
    return classFile.substring(0, classFile.length() - ".class".length()).replace('/', '.');
  }

  /** 拒绝受保护命名空间和跨目录/JAR 的重复应用类。 */
  private static void registerClass(Map<String, String> owners, String classFile, String owner)
      throws WebAppDeploymentException {
    if (discoverableClassName(classFile) == null) {
      return;
    }
    if (classFile.startsWith("javax/servlet/")
        || classFile.startsWith("io/github/o1o00o10/lingtong/")) {
      throw new WebAppDeploymentException(
          "web application contains protected class " + classFile + " in " + owner);
    }
    String previous = owners.put(classFile, owner);
    if (previous != null) {
      throw new WebAppDeploymentException(
          "duplicate web application class " + classFile + " in " + previous + " and " + owner);
    }
  }

  private static void closeQuietly(WebAppClassLoader loader) {
    if (loader == null) return;
    try {
      loader.close();
    } catch (IOException ignored) {
      // 已发生部署失败时，继续清理其他资源。
    }
  }

  private static void deleteQuietly(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 单个文件删除失败不妨碍继续清理其余文件。
                }
              });
    } catch (IOException ignored) {
      // 部署已不可用，此处尽力清理即可。
    }
  }

  /** 同时约束目录与嵌套 JAR 的扫描总量。 */
  private static final class InspectionBudget {
    /** 可接受的最大条目数。 */
    private final int maxEntries;
    /** 可接受的最大累计字节数。 */
    private final long maxBytes;
    /** 已扫描条目数。 */
    private int entries;
    /** 已扫描累计字节数。 */
    private long bytes;

    private InspectionBudget(int maxEntries, long maxBytes) {
      this.maxEntries = maxEntries;
      this.maxBytes = maxBytes;
    }

    private void addEntry() throws WebAppDeploymentException {
      entries++;
      if (entries > maxEntries) {
        throw new WebAppDeploymentException("expanded application exceeds configured entry count");
      }
    }

    private void addBytes(long value) throws WebAppDeploymentException {
      if (value < 0L || bytes > maxBytes - value) {
        throw new WebAppDeploymentException("expanded application exceeds configured size");
      }
      bytes += value;
    }
  }

  /** 单个 JAR 扫描阶段的临时结果。 */
  private static final class JarInspection {
    /** 可发现的类名。 */
    private final List<String> classes;
    /** 与类名对应的字节码元数据。 */
    private final List<ClassFileMetadata> metadata;

    private JarInspection(List<String> classes, List<ClassFileMetadata> metadata) {
      this.classes = classes;
      this.metadata = metadata;
    }
  }
}
