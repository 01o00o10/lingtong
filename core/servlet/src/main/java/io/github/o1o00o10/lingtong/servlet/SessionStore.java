/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.IOException;

/** 可重启及多节点 Session 共用的持久化边界。 */
public interface SessionStore extends AutoCloseable {
    /** 按 ID 读取记录；不存在时返回空值，过期判断由调用方负责。 */
    SessionRecord load(String id) throws IOException;

    /** 按预期版本保存；版本冲突时返回 -1，否则返回新版本。 */
    long save(SessionRecord record, long expectedVersion) throws IOException;

    /** 仅在版本匹配时删除；实现可约定负版本表示无条件删除。 */
    boolean delete(String id, long expectedVersion) throws IOException;

    /** 返回存储层当前记录数。 */
    long size() throws IOException;

    /** 清理不晚于 nowMillis 过期的记录，返回删除数。 */
    long purgeExpired(long nowMillis) throws IOException;

    @Override
    void close() throws IOException;
}
