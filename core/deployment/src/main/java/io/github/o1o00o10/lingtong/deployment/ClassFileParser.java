/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 仅读取类名、继承关系及注解，不执行应用类的加载或初始化。 */
final class ClassFileParser {
    /** JVM class 文件的魔数。 */
    private static final int CLASS_MAGIC = 0xCAFEBABE;

    /** 解析单个类文件；source 仅用于定位损坏归档中的具体条目。 */
    ClassFileMetadata parse(byte[] content, String source) throws WebAppDeploymentException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(content));
            if (input.readInt() != CLASS_MAGIC) {
                throw invalid(source, "invalid magic");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            ConstantPool constants = readConstantPool(input, source);
            input.readUnsignedShort();
            String className = constants.className(input.readUnsignedShort(), source);
            int superClass = input.readUnsignedShort();
            String superClassName = superClass == 0 ? null : constants.className(superClass, source);
            int interfaceCount = input.readUnsignedShort();
            List<String> interfaces = new ArrayList<String>(interfaceCount);
            for (int i = 0; i < interfaceCount; i++) {
                interfaces.add(constants.className(input.readUnsignedShort(), source));
            }
            Set<String> memberAnnotations = new LinkedHashSet<String>();
            readMembers(input, constants, memberAnnotations, source);
            readMembers(input, constants, memberAnnotations, source);
            Set<String> classAnnotations = new LinkedHashSet<String>();
            readAttributes(input, constants, classAnnotations, source);
            if (input.read() != -1) {
                throw invalid(source, "trailing class data");
            }
            return new ClassFileMetadata(
                    className, superClassName, interfaces, classAnnotations, memberAnnotations);
        } catch (WebAppDeploymentException e) {
            throw e;
        } catch (EOFException e) {
            throw invalid(source, "truncated class file", e);
        } catch (IOException | RuntimeException e) {
            throw invalid(source, "cannot parse class file", e);
        }
    }

    /** 保留后续解析需要的 UTF-8 和类引用，其余常量按格式跳过。 */
    private static ConstantPool readConstantPool(DataInputStream input, String source)
            throws IOException, WebAppDeploymentException {
        int count = input.readUnsignedShort();
        Object[] values = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    values[i] = input.readUTF();
                    break;
                case 3:
                case 4:
                    skipFully(input, 4);
                    break;
                case 5:
                case 6:
                    skipFully(input, 8);
                    i++;
                    break;
                case 7:
                    values[i] = new ClassReference(input.readUnsignedShort());
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    skipFully(input, 2);
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    skipFully(input, 4);
                    break;
                case 15:
                    skipFully(input, 3);
                    break;
                default:
                    throw invalid(source, "unknown constant-pool tag " + tag);
            }
        }
        return new ConstantPool(values);
    }

    /** 读取字段或方法表，并归并成员级注解。 */
    private static void readMembers(
            DataInputStream input,
            ConstantPool constants,
            Set<String> annotations,
            String source) throws IOException, WebAppDeploymentException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            skipFully(input, 6);
            readAttributes(input, constants, annotations, source);
        }
    }

    /** 从属性表中提取可见和不可见注解。 */
    private static void readAttributes(
            DataInputStream input,
            ConstantPool constants,
            Set<String> annotations,
            String source) throws IOException, WebAppDeploymentException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = constants.utf8(input.readUnsignedShort(), source);
            long length = Integer.toUnsignedLong(input.readInt());
            if ("RuntimeVisibleAnnotations".equals(name)
                    || "RuntimeInvisibleAnnotations".equals(name)) {
                if (length > Integer.MAX_VALUE) {
                    throw invalid(source, "annotation attribute is too large");
                }
                byte[] attribute = new byte[(int) length];
                input.readFully(attribute);
                readAnnotations(new DataInputStream(new ByteArrayInputStream(attribute)),
                        constants, annotations, source);
            } else {
                skipFully(input, length);
            }
        }
    }

    /** 按属性中声明的数量遍历注解。 */
    private static void readAnnotations(
            DataInputStream input,
            ConstantPool constants,
            Set<String> annotations,
            String source) throws IOException, WebAppDeploymentException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            readAnnotation(input, constants, annotations, source, true);
        }
        if (input.read() != -1) {
            throw invalid(source, "invalid annotation attribute length");
        }
    }

    /** 记录注解类型，并跳过各元素值。 */
    private static void readAnnotation(
            DataInputStream input,
            ConstantPool constants,
            Set<String> annotations,
            String source,
            boolean collect) throws IOException, WebAppDeploymentException {
        String descriptor = constants.utf8(input.readUnsignedShort(), source);
        if (collect) annotations.add(descriptor);
        int pairs = input.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            input.readUnsignedShort();
            readElementValue(input, constants, annotations, source);
        }
    }

    /** 递归跳过嵌套注解及数组元素，保持输入流位置正确。 */
    private static void readElementValue(
            DataInputStream input,
            ConstantPool constants,
            Set<String> annotations,
            String source) throws IOException, WebAppDeploymentException {
        int tag = input.readUnsignedByte();
        switch (tag) {
            case 'B': case 'C': case 'D': case 'F': case 'I': case 'J':
            case 'S': case 'Z': case 's': case 'c':
                input.readUnsignedShort();
                return;
            case 'e':
                skipFully(input, 4);
                return;
            case '@':
                readAnnotation(input, constants, annotations, source, false);
                return;
            case '[':
                int count = input.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    readElementValue(input, constants, annotations, source);
                }
                return;
            default:
                throw invalid(source, "unknown annotation element tag " + tag);
        }
    }

    /** 确保恰好跳过指定字节数，截断输入不能静默成功。 */
    private static void skipFully(DataInputStream input, long count) throws IOException {
        while (count > 0L) {
            int skipped = input.skipBytes((int) Math.min(Integer.MAX_VALUE, count));
            if (skipped == 0) {
                if (input.read() < 0) throw new EOFException();
                skipped = 1;
            }
            count -= skipped;
        }
    }

    private static WebAppDeploymentException invalid(String source, String message) {
        return new WebAppDeploymentException("invalid class file " + source + ": " + message);
    }

    private static WebAppDeploymentException invalid(
            String source, String message, Throwable cause) {
        return new WebAppDeploymentException("invalid class file " + source + ": " + message, cause);
    }

    /** 常量池中指向 UTF-8 类名的索引。 */
    private static final class ClassReference {
        /** 类内部名所在常量池槽位。 */
        private final int nameIndex;

        private ClassReference(int nameIndex) {
            this.nameIndex = nameIndex;
        }
    }

    /** 保留解析所需条目的常量池视图。 */
    private static final class ConstantPool {
        /** 按 JVM 常量池索引存储的条目，索引零无效。 */
        private final Object[] values;

        private ConstantPool(Object[] values) {
            this.values = values;
        }

        private String utf8(int index, String source) throws WebAppDeploymentException {
            if (index <= 0 || index >= values.length || !(values[index] instanceof String)) {
                throw invalid(source, "invalid UTF-8 constant index " + index);
            }
            return (String) values[index];
        }

        private String className(int index, String source) throws WebAppDeploymentException {
            if (index <= 0 || index >= values.length || !(values[index] instanceof ClassReference)) {
                throw invalid(source, "invalid class constant index " + index);
            }
            String value = utf8(((ClassReference) values[index]).nameIndex, source);
            return value.replace('/', '.');
        }
    }
}
