/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/** 基于 DataSource 的共享 Session 存储，通过版本列做乐观并发控制。 */
public final class JdbcSessionStore implements SessionStore {
  /** 由调用方提供的连接来源，本类不负责关闭连接池。 */
  private final DataSource dataSource;
  /** 经标识符校验的 Session 表名。 */
  private final String table;

  public JdbcSessionStore(DataSource dataSource) {
    this(dataSource, "lingtong_http_session");
  }

  public JdbcSessionStore(DataSource dataSource, String table) {
    if (dataSource == null || table == null || !table.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("invalid JDBC session store configuration");
    }
    this.dataSource = dataSource;
    this.table = table;
  }

  /** 读取记录，过期判断留给 SessionManager。 */
  @Override
  public SessionRecord load(String id) throws IOException {
    String sql = "SELECT version, expires_at, payload FROM " + table + " WHERE session_id = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new SessionRecord(id, result.getLong(1), result.getLong(2), result.getBytes(3))
            : null;
      }
    } catch (SQLException e) {
      throw new IOException("failed to load HTTP session", e);
    }
  }

  /** 首次写入使用 INSERT，后续写入在 WHERE 中核对版本。 */
  @Override
  public long save(SessionRecord record, long expectedVersion) throws IOException {
    try (Connection connection = dataSource.getConnection()) {
      if (expectedVersion < 0L) {
        String insert =
            "INSERT INTO "
                + table
                + " (session_id, version, expires_at, payload) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
          statement.setString(1, record.id());
          statement.setLong(2, 0L);
          statement.setLong(3, record.expiresAtMillis());
          statement.setBytes(4, record.payload());
          return statement.executeUpdate() == 1 ? 0L : -1L;
        } catch (SQLException conflict) {
          if (conflict.getSQLState() != null && conflict.getSQLState().startsWith("23")) return -1L;
          throw conflict;
        }
      }
      String update =
          "UPDATE "
              + table
              + " SET version = ?, expires_at = ?, payload = ?"
              + " WHERE session_id = ? AND version = ?";
      try (PreparedStatement statement = connection.prepareStatement(update)) {
        long next = expectedVersion + 1L;
        statement.setLong(1, next);
        statement.setLong(2, record.expiresAtMillis());
        statement.setBytes(3, record.payload());
        statement.setString(4, record.id());
        statement.setLong(5, expectedVersion);
        return statement.executeUpdate() == 1 ? next : -1L;
      }
    } catch (SQLException e) {
      throw new IOException("failed to save HTTP session", e);
    }
  }

  @Override
  public boolean delete(String id, long expectedVersion) throws IOException {
    String sql =
        "DELETE FROM "
            + table
            + " WHERE session_id = ?"
            + (expectedVersion >= 0L ? " AND version = ?" : "");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      if (expectedVersion >= 0L) statement.setLong(2, expectedVersion);
      return statement.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new IOException("failed to delete HTTP session", e);
    }
  }

  @Override
  public long size() throws IOException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    } catch (SQLException e) {
      throw new IOException("failed to count HTTP sessions", e);
    }
  }

  @Override
  public long purgeExpired(long nowMillis) throws IOException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM " + table + " WHERE expires_at <= ?")) {
      statement.setLong(1, nowMillis);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IOException("failed to purge expired HTTP sessions", e);
    }
  }

  @Override
  public void close() {}
}
