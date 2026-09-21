/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 不加载目标类，仅从字节码提取初始化器发现所需的元数据。 */
final class ClassFileMetadata {
    /** 类的二进制名称。 */
    final String className;
    /** 父类的二进制名称；没有父类时为空。 */
    final String superClassName;
    /** 直接实现的接口名称，不包含继承所得接口。 */
    final List<String> interfaceNames;
    /** 类级注解的 JVM 描述符集合。 */
    final Set<String> classAnnotations;
    /** 字段和方法上的注解描述符集合。 */
    final Set<String> memberAnnotations;

    ClassFileMetadata(
            String className,
            String superClassName,
            List<String> interfaceNames,
            Set<String> classAnnotations,
            Set<String> memberAnnotations) {
        this.className = className;
        this.superClassName = superClassName;
        this.interfaceNames = Collections.unmodifiableList(new ArrayList<String>(interfaceNames));
        this.classAnnotations = Collections.unmodifiableSet(
                new LinkedHashSet<String>(classAnnotations));
        this.memberAnnotations = Collections.unmodifiableSet(
                new LinkedHashSet<String>(memberAnnotations));
    }

    /** 将运行时类型转换为与 class 文件常量池一致的注解描述符。 */
    static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
