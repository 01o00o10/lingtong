/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.api.websocket;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 单个 WebSocket 端点的握手选项与消息回调。 */
public interface WebSocketHandler {
  /** 按端点偏好返回可协商的子协议。 */
  default List<String> subprotocols() {
    return Collections.emptyList();
  }

  /** 是否允许协商 permessage-deflate 扩展。 */
  default boolean perMessageDeflate() {
    return true;
  }

  /** 返回握手响应需要附加的 HTTP Header。 */
  default Map<String, List<String>> handshakeResponseHeaders() {
    return Collections.emptyMap();
  }

  /** 握手成功且 Session 可用后调用。 */
  default void onOpen(WebSocketSession session) throws Exception {}

  /** 默认的完整文本消息回调。 */
  default void onText(WebSocketSession session, String message) throws Exception {}

  /** 选择逐片文本回调时返回 true。 */
  default boolean receivesTextFragments() {
    return false;
  }

  /** 收到一个文本片段时调用，last 表示消息末片。 */
  default void onTextFragment(WebSocketSession session, String fragment, boolean last)
      throws Exception {}

  /** 默认的完整二进制消息回调。 */
  default void onBinary(WebSocketSession session, ByteBuffer message) throws Exception {}

  /** 选择逐片二进制回调时返回 true。 */
  default boolean receivesBinaryFragments() {
    return false;
  }

  /** 收到一个二进制片段时调用，last 表示消息末片。 */
  default void onBinaryFragment(WebSocketSession session, ByteBuffer fragment, boolean last)
      throws Exception {}

  /** 选择以 Reader 消费文本消息时返回 true。 */
  default boolean streamsTextMessages() {
    return false;
  }

  /** 文本流回调；读取期间可能等待后续网络分块。 */
  default void onTextStream(WebSocketSession session, Reader message) throws Exception {}

  /** 选择以 InputStream 消费二进制消息时返回 true。 */
  default boolean streamsBinaryMessages() {
    return false;
  }

  /** 二进制流回调；读取期间可能等待后续网络分块。 */
  default void onBinaryStream(WebSocketSession session, InputStream message) throws Exception {}

  /** 收到 Pong 控制帧时调用。 */
  default void onPong(WebSocketSession session, ByteBuffer payload) throws Exception {}

  /** 连接关闭时收到状态码和原因。 */
  default void onClose(WebSocketSession session, int statusCode, String reason) throws Exception {}

  /** 连接或回调处理失败时通知端点。 */
  default void onError(WebSocketSession session, Throwable failure) {}
}
