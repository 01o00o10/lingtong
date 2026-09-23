/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 大小写不敏感、保留同名多值的不可变 HTTP Header 集。 */
public final class HttpHeaders {
  /** 小写字段名到全部字段值的映射。 */
  private final Map<String, List<String>> values;

  private HttpHeaders(Map<String, List<String>> values) {
    this.values = values;
  }

  /** 返回同名字段的首个值，缺失时返回 null。 */
  public String first(String name) {
    List<String> all = values.get(normalize(name));
    return all == null || all.isEmpty() ? null : all.get(0);
  }

  /** 返回同名字段的全部值，缺失时返回空列表。 */
  public List<String> all(String name) {
    List<String> all = values.get(normalize(name));
    return all == null ? Collections.<String>emptyList() : all;
  }

  public boolean contains(String name) {
    return values.containsKey(normalize(name));
  }

  public Set<Map.Entry<String, List<String>>> entries() {
    return values.entrySet();
  }

  public static Builder builder() {
    return new Builder();
  }

  private static String normalize(String name) {
    if (name == null) {
      throw new IllegalArgumentException("header name must not be null");
    }
    return name.toLowerCase(Locale.ROOT);
  }

  /** Header 的可变收集器，build 后生成防御性副本。 */
  public static final class Builder {
    /** 尚未冻结的字段值映射。 */
    private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

    public Builder add(String name, String value) {
      String normalized = normalize(name);
      List<String> all = values.get(normalized);
      if (all == null) {
        all = new ArrayList<String>();
        values.put(normalized, all);
      }
      all.add(value);
      return this;
    }

    public HttpHeaders build() {
      Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
      for (Map.Entry<String, List<String>> entry : values.entrySet()) {
        result.put(
            entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
      }
      return new HttpHeaders(Collections.unmodifiableMap(result));
    }
  }
}
