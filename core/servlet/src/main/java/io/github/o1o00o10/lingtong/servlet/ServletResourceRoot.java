/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 统一访问应用根目录和依赖 JAR 资源覆盖层，并隔离受保护路径。 */
final class ServletResourceRoot {
    /** 应用原始资源根目录的真实路径。 */
    private final Path root;
    /** 依赖 JAR 中 META-INF/资源集合 提取后的真实目录，查找优先级低于 根目录。 */
    private final List<Path> overlays;
    /** 以小写扩展名索引的描述符 MIME 映射。 */
    private final Map<String, String> mimeMappings;

    ServletResourceRoot(Path root) {
        this(root, Collections.<Path>emptyList(), Collections.<String, String>emptyMap());
    }

    ServletResourceRoot(Path root, Map<String, String> mimeMappings) {
        this(root, Collections.<Path>emptyList(), mimeMappings);
    }

    ServletResourceRoot(Path root, List<Path> overlays, Map<String, String> mimeMappings) {
        if (root == null) {
            throw new IllegalArgumentException("resource root must not be null");
        }
        try {
            Path real = root.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("resource root is not a directory: " + root);
            }
            this.root = real;
            List<Path> resolvedOverlays = new ArrayList<Path>();
            for (Path overlay : overlays) {
                Path overlayReal = overlay.toRealPath();
                if (!Files.isDirectory(overlayReal)) {
                    throw new IllegalArgumentException(
                            "resource overlay is not a directory: " + overlay);
                }
                resolvedOverlays.add(overlayReal);
            }
            this.overlays = Collections.unmodifiableList(resolvedOverlays);
            Map<String, String> mappings = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> mapping : mimeMappings.entrySet()) {
                mappings.put(mapping.getKey().toLowerCase(Locale.ROOT), mapping.getValue());
            }
            this.mimeMappings = Collections.unmodifiableMap(mappings);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot access resource root: " + root, e);
        }
    }

    /** ServletContext 可见资源：先查应用根目录，再查各覆盖层。 */
    Path file(String webPath) {
        Path value = file(root, webPath);
        if (value != null) return value;
        for (Path overlay : overlays) {
            value = file(overlay, webPath);
            if (value != null) return value;
        }
        return null;
    }

    /** 对外可直接服务的资源，根目录中的 WEB-INF/META-INF 不得暴露。 */
    Path publicFile(String webPath) {
        if (!isProtectedPath(webPath)) {
            return file(webPath);
        }
        for (Path overlay : overlays) {
            Path value = file(overlay, webPath);
            if (value != null) return value;
        }
        return null;
    }

    /** 解析真实路径后再次约束在当前资源根目录，阻止符号链接逃逸。 */
    private Path file(Path sourceRoot, String webPath) {
        Path candidate = candidate(sourceRoot, webPath);
        if (candidate == null || !Files.exists(candidate)) {
            return null;
        }
        try {
            Path real = candidate.toRealPath();
            return real.startsWith(sourceRoot) ? real : null;
        } catch (IOException e) {
            return null;
        }
    }

    boolean isDirectory(String webPath) {
        Path path = file(webPath);
        return path != null && Files.isDirectory(path);
    }

    boolean isRegularFile(String webPath) {
        Path path = file(webPath);
        return path != null && Files.isRegularFile(path);
    }

    URL resource(String webPath) {
        Path path = file(webPath);
        if (path == null) {
            return null;
        }
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            return null;
        }
    }

    InputStream open(String webPath) {
        Path path = file(webPath);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            return null;
        }
    }

    String realPath(String webPath) {
        Path path = file(webPath);
        return path == null ? null : path.toString();
    }

    /** 合并各层目录成员，结果按路径排序并去重。 */
    Set<String> resourcePaths(String webPath) {
        String prefix = normalizedWebDirectory(webPath);
        Set<String> merged = new LinkedHashSet<String>();
        addResourcePaths(root, webPath, prefix, merged);
        for (Path overlay : overlays) addResourcePaths(overlay, webPath, prefix, merged);
        if (merged.isEmpty()) return null;
        List<String> values = new ArrayList<String>(merged);
        Collections.sort(values);
        return Collections.unmodifiableSet(new LinkedHashSet<String>(values));
    }

    private void addResourcePaths(
            Path sourceRoot, String webPath, String prefix, Set<String> target) {
        Path directory = file(sourceRoot, webPath);
        if (directory == null || !Files.isDirectory(directory)) return;
        try (Stream<Path> children = Files.list(directory)) {
            children.forEach(child -> {
                Path allowed = file(sourceRoot, prefix + child.getFileName().toString());
                if (allowed != null) {
                    target.add(prefix + child.getFileName().toString()
                            + (Files.isDirectory(allowed) ? "/" : ""));
                }
            });
        } catch (IOException ignored) {
            // 不可访问的覆盖层不贡献资源路径。
        }
    }

    /** 优先使用描述符映射，其次使用内置的常见静态资源类型。 */
    String mimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        int dot = lower.lastIndexOf('.');
        if (dot > slash && dot + 1 < lower.length()) {
            String configured = mimeMappings.get(lower.substring(dot + 1));
            if (configured != null) return configured;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".wasm")) return "application/wasm";
        return null;
    }

    /** 将 Web 路径标准化到指定根目录内，拒绝非法或越界路径。 */
    private Path candidate(Path sourceRoot, String webPath) {
        if (webPath == null || !webPath.startsWith("/")) {
            return null;
        }
        try {
            String decoded = URI.create(webPath).getPath();
            if (decoded == null || decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0) {
                return null;
            }
            Path relative = sourceRoot.getFileSystem().getPath(decoded.substring(1));
            Path candidate = sourceRoot.resolve(relative).normalize();
            return candidate.startsWith(sourceRoot) ? candidate : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** WEB-INF 与 META-INF 只可供应用内部读取，不从主资源根对外提供。 */
    private static boolean isProtectedPath(String path) {
        if (path == null) return false;
        String upper = path.toUpperCase(Locale.ROOT);
        return upper.equals("/WEB-INF") || upper.startsWith("/WEB-INF/")
                || upper.equals("/META-INF") || upper.startsWith("/META-INF/");
    }

    private static String normalizedWebDirectory(String webPath) {
        String value = webPath == null || webPath.isEmpty() ? "/" : webPath;
        return value.endsWith("/") ? value : value + "/";
    }
}
