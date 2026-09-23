/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.util.Locale;

/** 管理接口共享的 JSON 数值、字符串和 Prometheus 文本格式化函数。 */
final class ManagementText {
  private ManagementText() {}

  static String decimal(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  static String jsonNumber(double value) {
    return Double.isNaN(value) || Double.isInfinite(value) ? "null" : decimal(value);
  }

  static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      appendJsonCharacter(escaped, value.charAt(i));
    }
    return escaped.toString();
  }

  private static void appendJsonCharacter(StringBuilder escaped, char character) {
    switch (character) {
      case '\\':
        escaped.append("\\\\");
        break;
      case '"':
        escaped.append("\\\"");
        break;
      case '\n':
        escaped.append("\\n");
        break;
      case '\r':
        escaped.append("\\r");
        break;
      case '\t':
        escaped.append("\\t");
        break;
      default:
        if (character < 0x20) {
          escaped.append(String.format("\\u%04x", (int) character));
        } else {
          escaped.append(character);
        }
    }
  }

  static void metric(StringBuilder output, String name, String type, Number value) {
    output
        .append("# TYPE ")
        .append(name)
        .append(' ')
        .append(type)
        .append('\n')
        .append(name)
        .append(' ')
        .append(value)
        .append('\n');
  }
}
