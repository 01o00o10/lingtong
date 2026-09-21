/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 合并主描述符、排序后的 fragment、注解与应用 SCI，生成一次性的部署模型。 */
final class WebMetadataCompiler {
    /** 依赖 JAR 内 fragment 描述符的位置。 */
    private static final String FRAGMENT = "META-INF/web-fragment.xml";
    /** ServletContainerInitializer 的服务发现文件。 */
    private static final String SCI_SERVICE =
            "META-INF/services/javax.servlet.ServletContainerInitializer";
    /** 单个 SCI 服务文件的最大读取字节数。 */
    private static final int MAX_SERVICE_BYTES = 1024 * 1024;
    /** 用于先从字节码筛选候选类的 Web 注解描述符。 */
    private static final Set<String> WEB_ANNOTATIONS = webAnnotationDescriptors();

    /** 只在部署准备阶段扫描归档；请求分派时不再重复读取这些元数据。 */
    WebXmlModel compile(Path root, WebAppArchiveIndex index, ClassLoader classLoader)
            throws WebAppDeploymentException {
        WebXmlParser parser = new WebXmlParser();
        WebXmlModel main = parser.parse(root.resolve("WEB-INF").resolve("web.xml"));
        List<LibraryMetadata> libraries = main.metadataComplete
                ? librariesWithoutFragments(index.libraries)
                : readLibraries(index.libraries, parser);
        OrderingResult ordering = main.metadataComplete
                ? new OrderingResult(libraries, false)
                : orderLibraries(libraries, main.absoluteOrdering);

        if (!main.metadataComplete) {
            List<String> mainListeners = new ArrayList<String>(main.listeners);
            mergeFragments(main, ordering.included);
            main.listeners.clear();
            main.listeners.addAll(mainListeners);
            scanAnnotations(main, index.classes, index.classMetadata, classLoader);
            for (LibraryMetadata library : ordering.included) {
                if (library.fragment != null) {
                    addListeners(main.listeners, library.fragment.listeners);
                }
                if (library.fragment == null || !library.fragment.metadataComplete) {
                    scanAnnotations(main, library.library.classes,
                            library.library.classMetadata, classLoader);
                }
            }
        }
        WebXmlParser.validateReferences(main);
        if (ordering.explicit) {
            main.orderedLibraries = new ArrayList<String>();
            for (LibraryMetadata library : ordering.included) {
                main.orderedLibraries.add(library.library.fileName);
            }
        }
        discoverInitializers(main, root, index, ordering.included, classLoader);
        return main;
    }

    private static List<LibraryMetadata> librariesWithoutFragments(
            List<WebAppArchiveIndex.Library> libraries) {
        List<LibraryMetadata> result = new ArrayList<LibraryMetadata>();
        for (WebAppArchiveIndex.Library library : libraries) {
            result.add(new LibraryMetadata(library, null));
        }
        return result;
    }

    private static List<LibraryMetadata> readLibraries(
            List<WebAppArchiveIndex.Library> libraries,
            WebXmlParser parser) throws WebAppDeploymentException {
        List<LibraryMetadata> result = new ArrayList<LibraryMetadata>();
        for (WebAppArchiveIndex.Library library : libraries) {
            try (JarFile jar = new JarFile(library.path.toFile(), true)) {
                JarEntry entry = jar.getJarEntry(FRAGMENT);
                WebXmlModel fragment = null;
                if (entry != null && !entry.isDirectory()) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        fragment = parser.parseFragment(input, library.fileName + "!/" + FRAGMENT);
                    }
                }
                result.add(new LibraryMetadata(library, fragment));
            } catch (IOException e) {
                throw new WebAppDeploymentException(
                        "cannot inspect metadata in " + library.fileName, e);
            }
        }
        return result;
    }

    /** 主描述符绝对排序优先，否则根据 fragment 的相对约束决定顺序。 */
    private static OrderingResult orderLibraries(
            List<LibraryMetadata> libraries,
            WebXmlModel.Ordering absolute) throws WebAppDeploymentException {
        if (absolute != null) {
            return absoluteOrder(libraries, absolute);
        }
        namedLibraries(libraries);
        boolean relative = false;
        for (LibraryMetadata library : libraries) {
            if (library.fragment != null && library.fragment.relativeOrdering != null) {
                relative = true;
                break;
            }
        }
        return relative
                ? new OrderingResult(relativeOrder(libraries), true)
                : new OrderingResult(new ArrayList<LibraryMetadata>(libraries), false);
    }

    /** 仅纳入绝对排序点名的库及 others 覆盖的库。 */
    private static OrderingResult absoluteOrder(
            List<LibraryMetadata> libraries,
            WebXmlModel.Ordering ordering) throws WebAppDeploymentException {
        Map<String, List<LibraryMetadata>> named =
                new LinkedHashMap<String, List<LibraryMetadata>>();
        for (LibraryMetadata library : libraries) {
            if (library.name() == null) continue;
            List<LibraryMetadata> values = named.get(library.name());
            if (values == null) {
                values = new ArrayList<LibraryMetadata>();
                named.put(library.name(), values);
            }
            values.add(library);
        }
        Set<LibraryMetadata> selected = new LinkedHashSet<LibraryMetadata>();
        List<LibraryMetadata> result = new ArrayList<LibraryMetadata>();
        int othersPosition = ordering.othersPosition == null
                ? -1 : ordering.othersPosition.intValue();
        for (int i = 0; i <= ordering.before.size(); i++) {
            if (i == othersPosition) {
                for (LibraryMetadata library : libraries) {
                    if (!ordering.before.contains(library.name()) && selected.add(library)) {
                        result.add(library);
                    }
                }
            }
            if (i < ordering.before.size()) {
                List<LibraryMetadata> matches = named.get(ordering.before.get(i));
                if (matches != null) {
                    for (LibraryMetadata library : matches) {
                        if (selected.add(library)) result.add(library);
                    }
                }
            }
        }
        return new OrderingResult(result, true);
    }

    /** 将 before/after 关系转为有向图，稳定拓扑排序并拒绝环。 */
    private static List<LibraryMetadata> relativeOrder(List<LibraryMetadata> libraries)
            throws WebAppDeploymentException {
        Map<String, LibraryMetadata> named = namedLibraries(libraries);
        Map<LibraryMetadata, Set<LibraryMetadata>> edges =
                new LinkedHashMap<LibraryMetadata, Set<LibraryMetadata>>();
        Map<LibraryMetadata, Integer> incoming = new LinkedHashMap<LibraryMetadata, Integer>();
        for (LibraryMetadata library : libraries) {
            edges.put(library, new LinkedHashSet<LibraryMetadata>());
            incoming.put(library, 0);
        }
        for (LibraryMetadata library : libraries) {
            WebXmlModel.Ordering order = library.ordering();
            if (order == null) continue;
            for (String before : order.before) addEdge(library, named.get(before), edges, incoming);
            for (String after : order.after) addEdge(named.get(after), library, edges, incoming);
            if (order.beforeOthers) {
                for (LibraryMetadata other : libraries) {
                    if (other != library && !isBeforeOthers(other)) {
                        addEdge(library, other, edges, incoming);
                    }
                }
            }
            if (order.afterOthers) {
                for (LibraryMetadata other : libraries) {
                    if (other != library && !isAfterOthers(other)) {
                        addEdge(other, library, edges, incoming);
                    }
                }
            }
        }

        PriorityQueue<LibraryMetadata> ready = new PriorityQueue<LibraryMetadata>(
                Comparator.comparing(value -> value.library.fileName));
        for (LibraryMetadata library : libraries) {
            if (incoming.get(library) == 0) ready.add(library);
        }
        List<LibraryMetadata> result = new ArrayList<LibraryMetadata>();
        while (!ready.isEmpty()) {
            LibraryMetadata library = ready.remove();
            result.add(library);
            for (LibraryMetadata target : edges.get(library)) {
                int remaining = incoming.get(target) - 1;
                incoming.put(target, remaining);
                if (remaining == 0) ready.add(target);
            }
        }
        if (result.size() != libraries.size()) {
            throw new WebAppDeploymentException("cyclic web-fragment ordering");
        }
        return result;
    }

    /** 建立 fragment 名称索引，并拒绝重复名称。 */
    private static Map<String, LibraryMetadata> namedLibraries(
            List<LibraryMetadata> libraries) throws WebAppDeploymentException {
        Map<String, LibraryMetadata> named = new HashMap<String, LibraryMetadata>();
        for (LibraryMetadata library : libraries) {
            String name = library.name();
            if (name == null) continue;
            LibraryMetadata previous = named.put(name, library);
            if (previous != null) {
                throw new WebAppDeploymentException(
                        "duplicate web-fragment name " + name + " in "
                                + previous.library.fileName + " and " + library.library.fileName);
            }
        }
        return named;
    }

    private static void addEdge(
            LibraryMetadata source,
            LibraryMetadata target,
            Map<LibraryMetadata, Set<LibraryMetadata>> edges,
            Map<LibraryMetadata, Integer> incoming) {
        if (source == null || target == null || source == target) return;
        if (edges.get(source).add(target)) incoming.put(target, incoming.get(target) + 1);
    }

    private static boolean isBeforeOthers(LibraryMetadata library) {
        WebXmlModel.Ordering ordering = library.ordering();
        return ordering != null && ordering.beforeOthers;
    }

    private static boolean isAfterOthers(LibraryMetadata library) {
        WebXmlModel.Ordering ordering = library.ordering();
        return ordering != null && ordering.afterOthers;
    }

    /** 主 web.xml 优先于 fragment；fragment 之间不兼容的同名配置报冲突。 */
    private static void mergeFragments(WebXmlModel target, List<LibraryMetadata> libraries)
            throws WebAppDeploymentException {
        Set<String> mainServlets = new HashSet<String>(target.servlets.keySet());
        Set<String> mainFilters = new HashSet<String>(target.filters.keySet());
        Set<String> mainParameters = new HashSet<String>(target.contextParameters.keySet());
        Set<String> fragmentParameters = new HashSet<String>();
        Set<String> mainErrorKeys = errorKeys(target.errorPages);
        Set<String> fragmentErrorKeys = new HashSet<String>();
        Set<String> mainMimeMappings = new HashSet<String>(target.mimeMappings.keySet());
        Map<String, String> fragmentMimeMappings = new HashMap<String, String>();
        Set<String> mainLocaleEncodings =
                new HashSet<String>(target.localeEncodingMappings.keySet());
        Map<String, String> fragmentLocaleEncodings = new HashMap<String, String>();
        boolean mainSession = target.session != null;
        boolean mainRequestEncoding = target.requestCharacterEncoding != null;
        boolean mainResponseEncoding = target.responseCharacterEncoding != null;
        boolean mainLogin = target.login != null;
        for (LibraryMetadata library : libraries) {
            WebXmlModel fragment = library.fragment;
            if (fragment == null) continue;
            for (Map.Entry<String, String> parameter : fragment.contextParameters.entrySet()) {
                if (mainParameters.contains(parameter.getKey())) continue;
                if (!fragmentParameters.add(parameter.getKey())) {
                    throw conflict("context parameter", parameter.getKey(), library);
                }
                target.contextParameters.put(parameter.getKey(), parameter.getValue());
            }
            for (Map.Entry<String, WebXmlModel.ServletModel> servlet : fragment.servlets.entrySet()) {
                if (mainServlets.contains(servlet.getKey())) continue;
                if (target.servlets.put(servlet.getKey(), servlet.getValue()) != null) {
                    throw conflict("Servlet", servlet.getKey(), library);
                }
            }
            for (Map.Entry<String, WebXmlModel.FilterModel> filter : fragment.filters.entrySet()) {
                if (mainFilters.contains(filter.getKey())) continue;
                if (target.filters.put(filter.getKey(), filter.getValue()) != null) {
                    throw conflict("Filter", filter.getKey(), library);
                }
            }
            target.servletMappings.addAll(fragment.servletMappings);
            target.filterMappings.addAll(fragment.filterMappings);
            target.securityRoles.addAll(fragment.securityRoles);
            target.securityConstraints.addAll(fragment.securityConstraints);
            target.denyUncoveredHttpMethods |= fragment.denyUncoveredHttpMethods;
            if (fragment.login != null && !mainLogin) {
                if (target.login != null && !target.login.sameAs(fragment.login)) {
                    throw conflict("login-config", "login-config", library);
                }
                target.login = fragment.login;
            }
            for (WebXmlModel.ErrorPageModel page : fragment.errorPages) {
                String key = errorKey(page);
                if (mainErrorKeys.contains(key)) continue;
                if (!fragmentErrorKeys.add(key)) throw conflict("error page", key, library);
                target.errorPages.add(page);
            }
            for (Map.Entry<String, String> mapping : fragment.mimeMappings.entrySet()) {
                if (mainMimeMappings.contains(mapping.getKey())) continue;
                String previous = fragmentMimeMappings.put(mapping.getKey(), mapping.getValue());
                if (previous != null && !previous.equals(mapping.getValue())) {
                    throw conflict("MIME mapping", mapping.getKey(), library);
                }
                target.mimeMappings.put(mapping.getKey(), mapping.getValue());
            }
            for (Map.Entry<String, String> mapping
                    : fragment.localeEncodingMappings.entrySet()) {
                if (mainLocaleEncodings.contains(mapping.getKey())) continue;
                String previous = fragmentLocaleEncodings.put(
                        mapping.getKey(), mapping.getValue());
                if (previous != null && !previous.equals(mapping.getValue())) {
                    throw conflict("locale encoding mapping", mapping.getKey(), library);
                }
                target.localeEncodingMappings.put(mapping.getKey(), mapping.getValue());
            }
            target.welcomeFiles.addAll(fragment.welcomeFiles);
            if (fragment.session != null && !mainSession) {
                if (target.session != null) {
                    throw conflict("session-config", "session-config", library);
                }
                target.session = fragment.session;
            }
            if (fragment.requestCharacterEncoding != null && !mainRequestEncoding) {
                if (target.requestCharacterEncoding != null
                        && !target.requestCharacterEncoding.equals(fragment.requestCharacterEncoding)) {
                    throw conflict("request character encoding",
                            fragment.requestCharacterEncoding, library);
                }
                target.requestCharacterEncoding = fragment.requestCharacterEncoding;
            }
            if (fragment.responseCharacterEncoding != null && !mainResponseEncoding) {
                if (target.responseCharacterEncoding != null
                        && !target.responseCharacterEncoding.equals(fragment.responseCharacterEncoding)) {
                    throw conflict("response character encoding",
                            fragment.responseCharacterEncoding, library);
                }
                target.responseCharacterEncoding = fragment.responseCharacterEncoding;
            }
        }
    }

    private static WebAppDeploymentException conflict(
            String type, String name, LibraryMetadata library) {
        return new WebAppDeploymentException(
                "conflicting " + type + " " + name + " in " + library.library.fileName);
    }

    private static Set<String> errorKeys(List<WebXmlModel.ErrorPageModel> pages) {
        Set<String> result = new HashSet<String>();
        for (WebXmlModel.ErrorPageModel page : pages) result.add(errorKey(page));
        return result;
    }

    private static String errorKey(WebXmlModel.ErrorPageModel page) {
        if (page.errorCode != null) return "status:" + page.errorCode;
        if (page.exceptionType != null) return "exception:" + page.exceptionType;
        return "default";
    }

    /** 优先用 class 文件元数据筛选，再加载真正需要检查 Web 注解的类。 */
    private static void scanAnnotations(
            WebXmlModel model,
            List<String> sourceClasses,
            List<ClassFileMetadata> sourceMetadata,
            ClassLoader classLoader)
            throws WebAppDeploymentException {
        Set<String> classNames = new LinkedHashSet<String>();
        if (sourceMetadata.isEmpty()) {
            classNames.addAll(sourceClasses);
        } else {
            for (ClassFileMetadata metadata : sourceMetadata) {
                if (!Collections.disjoint(metadata.classAnnotations, WEB_ANNOTATIONS)) {
                    classNames.add(metadata.className);
                }
            }
        }
        for (String className : classNames) {
            Class<?> type = loadClass(className, classLoader, "annotation scanning");
            addWebServlet(model, type);
            applyServletSecurity(model, type);
            applyMultipartConfig(model, type);
            addWebFilter(model, type);
            addWebListener(model, type);
        }
    }

    private static void addWebServlet(WebXmlModel model, Class<?> type)
            throws WebAppDeploymentException {
        WebServlet annotation = type.getAnnotation(WebServlet.class);
        if (annotation == null) return;
        if (!Servlet.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) {
            throw new WebAppDeploymentException("@WebServlet class is not an instantiable Servlet: "
                    + type.getName());
        }
        String name = annotation.name().isEmpty() ? type.getName() : annotation.name();
        List<String> patterns = annotationPatterns(
                annotation.value(), annotation.urlPatterns(), "@WebServlet " + type.getName());
        if (patterns.isEmpty()) {
            throw new WebAppDeploymentException("@WebServlet requires a URL pattern: " + type.getName());
        }
        WebXmlModel.ServletModel servlet = model.servlets.get(name);
        if (servlet != null) {
            if (servlet.annotationDeclared && !type.getName().equals(servlet.className)) {
                throw new WebAppDeploymentException("duplicate @WebServlet name " + name);
            }
            if (!type.getName().equals(servlet.className)) return;
            mergeInitParameters(servlet.initParameters, annotation.initParams());
            if (!servlet.asyncSupportedSpecified) {
                servlet.asyncSupported = annotation.asyncSupported();
                servlet.asyncSupportedSpecified = true;
            }
            if (servlet.loadOnStartup == null && annotation.loadOnStartup() >= 0) {
                servlet.loadOnStartup = annotation.loadOnStartup();
            }
            if (!hasServletMapping(model, name)) {
                addServletMapping(model, name, patterns);
            }
            return;
        }
        servlet = new WebXmlModel.ServletModel();
        servlet.name = name;
        servlet.className = type.getName();
        servlet.asyncSupported = annotation.asyncSupported();
        servlet.asyncSupportedSpecified = true;
        servlet.annotationDeclared = true;
        if (annotation.loadOnStartup() >= 0) servlet.loadOnStartup = annotation.loadOnStartup();
        putInitParameters(servlet.initParameters, annotation.initParams(), "@WebServlet " + type.getName());
        model.servlets.put(name, servlet);
        addServletMapping(model, name, patterns);
    }

    private static void addWebFilter(WebXmlModel model, Class<?> type)
            throws WebAppDeploymentException {
        WebFilter annotation = type.getAnnotation(WebFilter.class);
        if (annotation == null) return;
        if (!Filter.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) {
            throw new WebAppDeploymentException("@WebFilter class is not an instantiable Filter: "
                    + type.getName());
        }
        String name = annotation.filterName().isEmpty() ? type.getName() : annotation.filterName();
        List<String> patterns = annotationPatterns(
                annotation.value(), annotation.urlPatterns(), "@WebFilter " + type.getName());
        if (patterns.isEmpty() && annotation.servletNames().length == 0) {
            throw new WebAppDeploymentException("@WebFilter requires a mapping: " + type.getName());
        }
        WebXmlModel.FilterModel filter = model.filters.get(name);
        if (filter != null) {
            if (filter.annotationDeclared && !type.getName().equals(filter.className)) {
                throw new WebAppDeploymentException("duplicate @WebFilter name " + name);
            }
            if (!type.getName().equals(filter.className)) return;
            mergeInitParameters(filter.initParameters, annotation.initParams());
            if (!filter.asyncSupportedSpecified) {
                filter.asyncSupported = annotation.asyncSupported();
                filter.asyncSupportedSpecified = true;
            }
            if (!hasFilterMapping(model, name)) addFilterMapping(model, name, patterns, annotation);
            return;
        }
        filter = new WebXmlModel.FilterModel();
        filter.name = name;
        filter.className = type.getName();
        filter.asyncSupported = annotation.asyncSupported();
        filter.asyncSupportedSpecified = true;
        filter.annotationDeclared = true;
        putInitParameters(filter.initParameters, annotation.initParams(), "@WebFilter " + type.getName());
        model.filters.put(name, filter);
        addFilterMapping(model, name, patterns, annotation);
    }

    private static void addFilterMapping(
            WebXmlModel model, String name, List<String> patterns, WebFilter annotation) {
        WebXmlModel.FilterMappingModel mapping = new WebXmlModel.FilterMappingModel();
        mapping.filterName = name;
        mapping.urlPatterns.addAll(patterns);
        Collections.addAll(mapping.servletNames, annotation.servletNames());
        DispatcherType[] dispatchers = annotation.dispatcherTypes();
        if (dispatchers.length == 0) mapping.dispatchers.add(DispatcherType.REQUEST);
        else Collections.addAll(mapping.dispatchers, dispatchers);
        model.filterMappings.add(mapping);
    }

    private static void addWebListener(WebXmlModel model, Class<?> type) {
        if (type.getAnnotation(WebListener.class) != null && !model.listeners.contains(type.getName())) {
            model.listeners.add(type.getName());
        }
    }

    private static void applyMultipartConfig(WebXmlModel model, Class<?> type) {
        MultipartConfig multipart = type.getAnnotation(MultipartConfig.class);
        if (multipart == null) return;
        for (WebXmlModel.ServletModel servlet : model.servlets.values()) {
            if (type.getName().equals(servlet.className) && servlet.multipartConfig == null) {
                servlet.multipartConfig = new MultipartConfigElement(
                        multipart.location(), multipart.maxFileSize(), multipart.maxRequestSize(),
                        multipart.fileSizeThreshold());
            }
        }
    }

    private static void applyServletSecurity(WebXmlModel model, Class<?> type) {
        ServletSecurity annotation = type.getAnnotation(ServletSecurity.class);
        if (annotation == null) return;
        ServletSecurityElement element = new ServletSecurityElement(annotation);
        Collections.addAll(model.securityRoles, element.getRolesAllowed());
        for (javax.servlet.HttpMethodConstraintElement method
                : element.getHttpMethodConstraints()) {
            Collections.addAll(model.securityRoles, method.getRolesAllowed());
        }
        for (WebXmlModel.ServletModel servlet : model.servlets.values()) {
            if (type.getName().equals(servlet.className)) {
                servlet.servletSecurity = element;
            }
        }
    }

    private static void addServletMapping(
            WebXmlModel model, String name, List<String> patterns) {
        WebXmlModel.ServletMappingModel mapping = new WebXmlModel.ServletMappingModel();
        mapping.servletName = name;
        mapping.urlPatterns.addAll(patterns);
        model.servletMappings.add(mapping);
    }

    private static boolean hasServletMapping(WebXmlModel model, String name) {
        for (WebXmlModel.ServletMappingModel mapping : model.servletMappings) {
            if (name.equals(mapping.servletName)) return true;
        }
        return false;
    }

    private static boolean hasFilterMapping(WebXmlModel model, String name) {
        for (WebXmlModel.FilterMappingModel mapping : model.filterMappings) {
            if (name.equals(mapping.filterName)) return true;
        }
        return false;
    }

    private static void mergeInitParameters(
            Map<String, String> target, WebInitParam[] parameters) {
        for (WebInitParam parameter : parameters) {
            if (!target.containsKey(parameter.name())) {
                target.put(parameter.name(), parameter.value());
            }
        }
    }

    private static void addListeners(List<String> target, List<String> listeners) {
        for (String listener : listeners) {
            if (!target.contains(listener)) target.add(listener);
        }
    }

    private static List<String> annotationPatterns(
            String[] value, String[] urlPatterns, String source) throws WebAppDeploymentException {
        if (value.length > 0 && urlPatterns.length > 0) {
            throw new WebAppDeploymentException(source + " cannot define both value and urlPatterns");
        }
        String[] selected = value.length > 0 ? value : urlPatterns;
        List<String> result = new ArrayList<String>();
        for (String pattern : selected) {
            if (pattern == null || pattern.trim().isEmpty()) {
                throw new WebAppDeploymentException(source + " contains an empty URL pattern");
            }
            result.add(pattern.trim());
        }
        return result;
    }

    private static void putInitParameters(
            Map<String, String> target,
            WebInitParam[] parameters,
            String source) throws WebAppDeploymentException {
        for (WebInitParam parameter : parameters) {
            if (target.put(parameter.name(), parameter.value()) != null) {
                throw new WebAppDeploymentException(
                        source + " contains duplicate init parameter " + parameter.name());
            }
        }
    }

    /** 按已纳入库发现 SCI 服务，并预计算各自 HandlesTypes 匹配集合。 */
    private static void discoverInitializers(
            WebXmlModel model,
            Path root,
            WebAppArchiveIndex index,
            List<LibraryMetadata> included,
            ClassLoader classLoader) throws WebAppDeploymentException {
        LinkedHashSet<String> providers = new LinkedHashSet<String>();
        Path classesService = root.resolve("WEB-INF").resolve("classes").resolve(SCI_SERVICE);
        if (Files.isRegularFile(classesService)) {
            try (InputStream input = Files.newInputStream(classesService)) {
                readProviders(input, "WEB-INF/classes/" + SCI_SERVICE, providers);
            } catch (IOException e) {
                throw new WebAppDeploymentException("cannot read ServletContainerInitializer service", e);
            }
        }
        for (LibraryMetadata library : included) {
            try (JarFile jar = new JarFile(library.library.path.toFile(), true)) {
                JarEntry entry = jar.getJarEntry(SCI_SERVICE);
                if (entry != null && !entry.isDirectory()) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        readProviders(input, library.library.fileName + "!/" + SCI_SERVICE, providers);
                    }
                }
            } catch (IOException e) {
                throw new WebAppDeploymentException(
                        "cannot read ServletContainerInitializer service from "
                                + library.library.fileName, e);
            }
        }

        List<String> candidates = new ArrayList<String>(index.classes);
        List<ClassFileMetadata> candidateMetadata = new ArrayList<ClassFileMetadata>(index.classMetadata);
        Set<String> includedClasses = new HashSet<String>(index.classes);
        Set<String> excludedClasses = new HashSet<String>();
        Set<WebAppArchiveIndex.Library> includedLibraries =
                new HashSet<WebAppArchiveIndex.Library>();
        for (LibraryMetadata library : included) {
            includedLibraries.add(library.library);
            includedClasses.addAll(library.library.classes);
            candidates.addAll(library.library.classes);
            candidateMetadata.addAll(library.library.classMetadata);
        }
        for (WebAppArchiveIndex.Library library : index.libraries) {
            if (!includedLibraries.contains(library)) excludedClasses.addAll(library.classes);
        }
        Map<String, Class<?>> loadedCandidates = new LinkedHashMap<String, Class<?>>();
        Map<String, ClassFileMetadata> metadataByName = new LinkedHashMap<String, ClassFileMetadata>();
        for (ClassFileMetadata metadata : candidateMetadata) {
            metadataByName.put(metadata.className, metadata);
        }
        for (String provider : providers) {
            if (excludedClasses.contains(provider) && !includedClasses.contains(provider)) {
                continue;
            }
            Class<?> providerClass = loadClass(provider, classLoader, "SCI discovery");
            if (!ServletContainerInitializer.class.isAssignableFrom(providerClass)
                    || Modifier.isAbstract(providerClass.getModifiers())) {
                throw new WebAppDeploymentException(
                        "SCI provider is not instantiable: " + provider);
            }
            ServletContainerInitializer initializer;
            try {
                initializer = (ServletContainerInitializer) providerClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new WebAppDeploymentException("cannot instantiate SCI provider " + provider, e);
            }
            HandlesTypes handles = providerClass.getAnnotation(HandlesTypes.class);
            Set<Class<?>> matches = null;
            if (handles != null) {
                LinkedHashSet<Class<?>> found = new LinkedHashSet<Class<?>>();
                for (String candidateName : candidates) {
                    ClassFileMetadata metadata = metadataByName.get(candidateName);
                    if (metadata != null
                            && !metadataMayMatch(metadata, handles.value(), metadataByName, classLoader)) {
                        continue;
                    }
                    Class<?> candidate = loadedCandidates.get(candidateName);
                    if (candidate == null) {
                        try {
                            candidate = Class.forName(candidateName, false, classLoader);
                            loadedCandidates.put(candidateName, candidate);
                        } catch (ClassNotFoundException | LinkageError ignored) {
                            continue;
                        }
                    }
                    if (matchesAny(candidate, handles.value())) found.add(candidate);
                }
                if (!found.isEmpty()) matches = Collections.unmodifiableSet(found);
            }
            model.initializers.add(new WebXmlModel.InitializerModel(initializer, matches));
        }
    }

    /** 最终用反射确认候选类的注解或继承关系。 */
    private static boolean matchesAny(Class<?> candidate, Class<?>[] interests) {
        for (Class<?> interest : interests) {
            try {
                if (interest.isAnnotation()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> annotation = (Class<? extends Annotation>) interest;
                    if (hasAnnotation(candidate, annotation)) return true;
                } else if (candidate != interest && interest.isAssignableFrom(candidate)) {
                    return true;
                }
            } catch (LinkageError | RuntimeException ignored) {
                // 应用的可选依赖可能缺失，Servlet 允许跳过这样的候选类。
            }
        }
        return false;
    }

    /** 复用部署索引，按已纳入库的范围查找 HandlesTypes 匹配类。 */
    static Set<Class<?>> matchingClasses(
            WebXmlModel model,
            WebAppArchiveIndex index,
            ClassLoader classLoader,
            Class<?>... interests) throws WebAppDeploymentException {
        List<String> candidates = new ArrayList<String>(index.classes);
        List<ClassFileMetadata> metadata = new ArrayList<ClassFileMetadata>(index.classMetadata);
        Set<String> orderedLibraries = model.orderedLibraries == null
                ? null : new LinkedHashSet<String>(model.orderedLibraries);
        for (WebAppArchiveIndex.Library library : index.libraries) {
            if (orderedLibraries == null || orderedLibraries.contains(library.fileName)) {
                candidates.addAll(library.classes);
                metadata.addAll(library.classMetadata);
            }
        }
        Map<String, ClassFileMetadata> metadataByName = new LinkedHashMap<String, ClassFileMetadata>();
        for (ClassFileMetadata value : metadata) metadataByName.put(value.className, value);
        LinkedHashSet<Class<?>> matches = new LinkedHashSet<Class<?>>();
        for (String candidateName : candidates) {
            ClassFileMetadata value = metadataByName.get(candidateName);
            if (value != null && !metadataMayMatch(value, interests, metadataByName, classLoader)) {
                continue;
            }
            Class<?> candidate;
            try {
                candidate = Class.forName(candidateName, false, classLoader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                continue;
            }
            if (matchesAny(candidate, interests)) matches.add(candidate);
        }
        return Collections.unmodifiableSet(matches);
    }

    private static boolean metadataMayMatch(
            ClassFileMetadata candidate,
            Class<?>[] interests,
            Map<String, ClassFileMetadata> applicationClasses,
            ClassLoader classLoader) {
        for (Class<?> interest : interests) {
            if (interest.isAnnotation()) {
                String descriptor = ClassFileMetadata.descriptor(interest);
                if (candidate.classAnnotations.contains(descriptor)
                        || candidate.memberAnnotations.contains(descriptor)) {
                    return true;
                }
                if (interest.isAnnotationPresent(Inherited.class)
                        && inheritedAnnotationMayMatch(
                                candidate.superClassName, descriptor, applicationClasses,
                                classLoader, interest, new HashSet<String>())) {
                    return true;
                }
            } else if (!candidate.className.equals(interest.getName())
                    && hierarchyMayMatch(candidate, interest, applicationClasses,
                            classLoader, new HashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hierarchyMayMatch(
            ClassFileMetadata candidate,
            Class<?> interest,
            Map<String, ClassFileMetadata> applicationClasses,
            ClassLoader classLoader,
            Set<String> visited) {
        if (!visited.add(candidate.className)) return false;
        if (parentMayMatch(candidate.superClassName, interest, applicationClasses,
                classLoader, visited)) return true;
        for (String interfaceName : candidate.interfaceNames) {
            if (parentMayMatch(interfaceName, interest, applicationClasses,
                    classLoader, visited)) return true;
        }
        return false;
    }

    private static boolean parentMayMatch(
            String parentName,
            Class<?> interest,
            Map<String, ClassFileMetadata> applicationClasses,
            ClassLoader classLoader,
            Set<String> visited) {
        if (parentName == null) return false;
        if (parentName.equals(interest.getName())) return true;
        ClassFileMetadata parent = applicationClasses.get(parentName);
        if (parent != null) {
            return hierarchyMayMatch(parent, interest, applicationClasses, classLoader, visited);
        }
        try {
            Class<?> parentClass = Class.forName(parentName, false, classLoader);
            return interest.isAssignableFrom(parentClass);
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean inheritedAnnotationMayMatch(
            String parentName,
            String descriptor,
            Map<String, ClassFileMetadata> applicationClasses,
            ClassLoader classLoader,
            Class<?> interest,
            Set<String> visited) {
        if (parentName == null || !visited.add(parentName)) return false;
        ClassFileMetadata parent = applicationClasses.get(parentName);
        if (parent != null) {
            return parent.classAnnotations.contains(descriptor)
                    || inheritedAnnotationMayMatch(parent.superClassName, descriptor,
                    applicationClasses, classLoader, interest, visited);
        }
        try {
            return Class.forName(parentName, false, classLoader)
                    .isAnnotationPresent(interest.asSubclass(Annotation.class));
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasAnnotation(
            Class<?> candidate, Class<? extends Annotation> annotation) {
        if (candidate.isAnnotationPresent(annotation)) return true;
        for (Field field : candidate.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotation)) return true;
        }
        for (Method method : candidate.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) return true;
        }
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(annotation)) return true;
        }
        return false;
    }

    private static void readProviders(
            InputStream input, String source, Set<String> providers)
            throws IOException, WebAppDeploymentException {
        int bytes = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.length() + 1;
                if (bytes > MAX_SERVICE_BYTES) {
                    throw new WebAppDeploymentException("SCI service file exceeds 1 MiB: " + source);
                }
                int comment = line.indexOf('#');
                String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (!provider.isEmpty()) providers.add(provider);
            }
        }
    }

    private static Class<?> loadClass(String name, ClassLoader loader, String phase)
            throws WebAppDeploymentException {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new WebAppDeploymentException(
                    "cannot load application class " + name + " during " + phase, e);
        }
    }

    private static Set<String> webAnnotationDescriptors() {
        Set<String> descriptors = new HashSet<String>();
        descriptors.add(ClassFileMetadata.descriptor(WebServlet.class));
        descriptors.add(ClassFileMetadata.descriptor(WebFilter.class));
        descriptors.add(ClassFileMetadata.descriptor(WebListener.class));
        descriptors.add(ClassFileMetadata.descriptor(MultipartConfig.class));
        descriptors.add(ClassFileMetadata.descriptor(ServletSecurity.class));
        return Collections.unmodifiableSet(descriptors);
    }

    /** 索引中的 JAR 与其可选 fragment 的配对。 */
    private static final class LibraryMetadata {
        /** 已扫描的 JAR 索引。 */
        private final WebAppArchiveIndex.Library library;
        /** JAR 中的 web-fragment.xml；不存在时为空。 */
        private final WebXmlModel fragment;

        private LibraryMetadata(WebAppArchiveIndex.Library library, WebXmlModel fragment) {
            this.library = library;
            this.fragment = fragment;
        }

        private String name() {
            return fragment == null ? null : fragment.fragmentName;
        }

        private WebXmlModel.Ordering ordering() {
            return fragment == null ? null : fragment.relativeOrdering;
        }
    }

    /** 排序后实际纳入的库及是否显式指定了顺序。 */
    private static final class OrderingResult {
        /** 后续合并、扫描与 SCI 发现使用的库集合。 */
        private final List<LibraryMetadata> included;
        /** true 时向 ServletContext 暴露 ORDERED_LIBS。 */
        private final boolean explicit;

        private OrderingResult(List<LibraryMetadata> included, boolean explicit) {
            this.included = included;
            this.explicit = explicit;
        }
    }
}
