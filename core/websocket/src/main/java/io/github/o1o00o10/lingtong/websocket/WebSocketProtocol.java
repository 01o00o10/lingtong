/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.ConnectionUpgrade;
import io.github.o1o00o10.lingtong.http.UpgradeChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** 处理 WebSocket 帧、消息分片、心跳、关闭握手及发送完成回调。 */
final class WebSocketProtocol implements ConnectionUpgrade, WebSocketSession {
  /** 操作码续帧。 */
  private static final int OP_CONTINUATION = 0x0;
  /** 操作码文本。 */
  private static final int OP_TEXT = 0x1;
  /** 操作码二进制。 */
  private static final int OP_BINARY = 0x2;
  /** 操作码关闭。 */
  private static final int OP_CLOSE = 0x8;
  /** 操作码心跳请求。 */
  private static final int OP_PING = 0x9;
  /** 操作码心跳响应。 */
  private static final int OP_PONG = 0xa;

  /** 标识符。 */
  private final String id = UUID.randomUUID().toString();
  /** 处理器。 */
  private final WebSocketHandler handler;
  /** 执行器。 */
  private final Executor executor;
  /** 回调集合。 */
  private final SerialExecutor callbacks;
  /** 最大消息字节数，单位为字节。 */
  private final int maxMessageBytes;
  /** 协商后的子协议。 */
  private final String negotiatedSubprotocol;
  /** 逐消息压缩，布尔标志。 */
  private final boolean perMessageDeflate;
  /** 预期已掩码输入，布尔标志。 */
  private final boolean expectMaskedInput;
  /** 掩码输出，布尔标志。 */
  private final boolean maskOutput;
  /** 掩码集合。 */
  private final SecureRandom masks;
  /** 待处理。 */
  private byte[] pending = new byte[0];
  /** 网络流帧。 */
  private NetworkStreamFrame networkStreamFrame;
  /** 分片。 */
  private ByteArrayOutputStream fragmented;
  /** 分片操作码。 */
  private int fragmentedOpcode = -1;
  /** 分片已压缩，布尔标志。 */
  private boolean fragmentedCompressed;
  /** 部分操作码。 */
  private int partialOpcode = -1;
  /** 部分已压缩，布尔标志。 */
  private boolean partialCompressed;
  /** 部分解压器。 */
  private Inflater partialInflater;
  /** 部分文本解码器。 */
  private CharsetDecoder partialTextDecoder;
  /** 部分文本remainder。 */
  private byte[] partialTextRemainder = new byte[0];
  /** 部分线路字节数，单位为字节。 */
  private long partialWireBytes;
  /** 部分已解码的字节数，单位为字节。 */
  private long partialDecodedBytes;
  /** 流式处理输入。 */
  private MessageInputStream streamingInput;
  /** 流式处理已压缩，布尔标志。 */
  private boolean streamingCompressed;
  /** 流式处理解压器。 */
  private Inflater streamingInflater;
  /** 流式处理线路字节数，单位为字节。 */
  private long streamingWireBytes;
  /** 流式处理已解码的字节数，单位为字节。 */
  private long streamingDecodedBytes;
  /** 通道。 */
  private volatile UpgradeChannel channel;
  /** 打开，布尔标志。 */
  private volatile boolean open;
  /** 关闭已发送，布尔标志。 */
  private boolean closeSent;
  /** 关闭已接收，布尔标志。 */
  private boolean closeReceived;
  /** 出站片段操作码。 */
  private int outboundFragmentOpcode = -1;
  /** 出站片段字节数，单位为字节。 */
  private long outboundFragmentBytes;
  /** 出站压缩器。 */
  private Deflater outboundDeflater;
  /** 空闲超时时长毫秒，单位为毫秒。 */
  private volatile long idleTimeoutMillis = -1L;
  /** 末尾activity纳秒。 */
  private volatile long lastActivityNanos = System.nanoTime();
  /** 按顺序保存的待处理sends。 */
  private final List<PendingSend> pendingSends = new ArrayList<PendingSend>();

  WebSocketProtocol(WebSocketHandler handler, Executor executor, int maxMessageBytes) {
    this(handler, executor, maxMessageBytes, "", false);
  }

  WebSocketProtocol(
      WebSocketHandler handler,
      Executor executor,
      int maxMessageBytes,
      String negotiatedSubprotocol,
      boolean perMessageDeflate) {
    this(handler, executor, maxMessageBytes, negotiatedSubprotocol, perMessageDeflate, true, false);
  }

  static WebSocketProtocol client(
      WebSocketHandler handler,
      Executor executor,
      int maxMessageBytes,
      String negotiatedSubprotocol,
      boolean perMessageDeflate) {
    return new WebSocketProtocol(
        handler, executor, maxMessageBytes, negotiatedSubprotocol, perMessageDeflate, false, true);
  }

  private WebSocketProtocol(
      WebSocketHandler handler,
      Executor executor,
      int maxMessageBytes,
      String negotiatedSubprotocol,
      boolean perMessageDeflate,
      boolean expectMaskedInput,
      boolean maskOutput) {
    if (handler == null || executor == null || maxMessageBytes <= 0) {
      throw new IllegalArgumentException("WebSocket handler, executor and limit are required");
    }
    this.handler = handler;
    this.executor = executor;
    this.callbacks = new SerialExecutor(executor);
    this.maxMessageBytes = maxMessageBytes;
    this.negotiatedSubprotocol = negotiatedSubprotocol == null ? "" : negotiatedSubprotocol;
    this.perMessageDeflate = perMessageDeflate;
    this.expectMaskedInput = expectMaskedInput;
    this.maskOutput = maskOutput;
    this.masks = maskOutput ? new SecureRandom() : null;
  }

  /** 连接升级完成后才暴露会话并回调业务端点。 */
  @Override
  public void onOpen(UpgradeChannel value) {
    channel = value;
    open = true;
    touch();
    callback(() -> handler.onOpen(this));
  }

  /** 累积不完整帧，逐帧校验并交付消息；超限时按协议关闭。 */
  @Override
  public synchronized void onInput(ByteBuffer input) throws IOException {
    if (input.hasRemaining()) touch();
    byte[] next = new byte[pending.length + input.remaining()];
    System.arraycopy(pending, 0, next, 0, pending.length);
    input.get(next, pending.length, next.length - pending.length);
    int offset = 0;
    while (offset < next.length) {
      int consumed = decodeFrame(next, offset);
      if (consumed == 0) break;
      offset += consumed;
    }
    pending = Arrays.copyOfRange(next, offset, next.length);
    if (pending.length > maxMessageBytes + 14) {
      protocolClose(1009, "message too large");
    }
  }

  /** 校验掩码、保留位、长度与控制帧规则，再决定整帧或增量交付。 */
  private int decodeFrame(byte[] bytes, int offset) throws IOException {
    if (networkStreamFrame != null) {
      return consumeNetworkStreamFrame(bytes, offset);
    }
    int remaining = bytes.length - offset;
    if (remaining < 2) return 0;
    int first = bytes[offset] & 0xff;
    int second = bytes[offset + 1] & 0xff;
    boolean fin = (first & 0x80) != 0;
    int opcode = first & 0x0f;
    if (!validOpcode(opcode)) return failProtocol("unknown opcode", remaining);
    boolean control = opcode >= 0x8;
    boolean compressed = (first & 0x40) != 0;
    if ((first & 0x30) != 0
        || compressed && (!perMessageDeflate || control || opcode == OP_CONTINUATION)) {
      return failProtocol("reserved bits are set", remaining);
    }
    if (control && !fin) return failProtocol("fragmented control frame", remaining);
    boolean masked = (second & 0x80) != 0;
    if (masked != expectMaskedInput) {
      return failProtocol(
          expectMaskedInput ? "client frame is not masked" : "server frame is masked", remaining);
    }
    long length = second & 0x7f;
    int cursor = offset + 2;
    if (length == 126) {
      if (bytes.length - cursor < 2) return 0;
      length = ((bytes[cursor] & 0xffL) << 8) | (bytes[cursor + 1] & 0xffL);
      cursor += 2;
      if (length < 126) return failProtocol("non-minimal payload length", remaining);
    } else if (length == 127) {
      if (bytes.length - cursor < 8) return 0;
      if ((bytes[cursor] & 0x80) != 0) return failProtocol("invalid 64-bit length", remaining);
      length = 0;
      for (int i = 0; i < 8; i++) length = (length << 8) | (bytes[cursor + i] & 0xffL);
      cursor += 8;
      if (length <= 0xffff) return failProtocol("non-minimal payload length", remaining);
    }
    if (control && length > 125) return failProtocol("control payload is too large", remaining);
    if (length > maxMessageBytes || length > Integer.MAX_VALUE) {
      protocolClose(1009, "message too large");
      return bytes.length - offset;
    }
    int maskBytes = masked ? 4 : 0;
    if (bytes.length - cursor < maskBytes) return 0;
    byte[] mask = masked ? Arrays.copyOfRange(bytes, cursor, cursor + 4) : null;
    cursor += maskBytes;
    if (bytes.length - cursor < length) {
      if (!canStreamNetworkFrame(opcode)) return 0;
      MessageInputStream dispatchInput = null;
      if (opcode != OP_CONTINUATION) {
        dispatchInput = beginStreaming(opcode, compressed);
      }
      networkStreamFrame = new NetworkStreamFrame(opcode, fin, mask, length, dispatchInput);
      int consumed = consumeNetworkStreamFrame(bytes, cursor);
      return cursor - offset + consumed;
    }
    byte[] payload = Arrays.copyOfRange(bytes, cursor, cursor + (int) length);
    if (masked) {
      for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    }
    handleFrame(opcode, fin, compressed, payload);
    return cursor + (int) length - offset;
  }

  private boolean canStreamNetworkFrame(int opcode) {
    if (opcode == OP_CONTINUATION) return streamingInput != null;
    return streams(opcode) && fragmented == null && streamingInput == null && partialOpcode == -1;
  }

  private int consumeNetworkStreamFrame(byte[] bytes, int offset) throws IOException {
    NetworkStreamFrame frame = networkStreamFrame;
    int count = (int) Math.min(frame.remaining, bytes.length - offset);
    if (count <= 0) return 0;
    byte[] payload = Arrays.copyOfRange(bytes, offset, offset + count);
    if (frame.mask != null) {
      for (int i = 0; i < payload.length; i++) {
        payload[i] ^= frame.mask[(int) ((frame.consumed + i) & 3L)];
      }
    }
    frame.consumed += count;
    frame.remaining -= count;
    boolean complete = frame.remaining == 0L;
    boolean accepted = feedStreaming(payload, complete && frame.finalMessageFragment);
    if (accepted && frame.dispatchInput != null) {
      dispatchStreaming(frame.opcode, frame.dispatchInput);
      frame.dispatchInput = null;
    }
    if (!accepted || complete) networkStreamFrame = null;
    return count;
  }

  private void handleFrame(int opcode, boolean fin, boolean compressed, byte[] payload)
      throws IOException {
    if (opcode == OP_PING) {
      writeFrame(OP_PONG, payload);
      return;
    }
    if (opcode == OP_PONG) {
      final ByteBuffer pong = ByteBuffer.wrap(payload).asReadOnlyBuffer();
      callback(() -> handler.onPong(this, pong));
      return;
    }
    if (opcode == OP_CLOSE) {
      handleClose(payload);
      return;
    }
    if (opcode == OP_CONTINUATION) {
      if (streamingInput != null) {
        feedStreaming(payload, fin);
        return;
      }
      if (partialOpcode != -1) {
        deliverPartial(payload, fin);
        return;
      }
      if (fragmented == null) {
        protocolClose(1002, "unexpected continuation");
        return;
      }
      appendFragment(payload);
      if (fin && fragmented != null) {
        byte[] message = fragmented.toByteArray();
        if (fragmentedCompressed && (message = inflate(message)) == null) return;
        finishMessage(fragmentedOpcode, message);
      }
      return;
    }
    if (fragmented != null || streamingInput != null || partialOpcode != -1) {
      protocolClose(1002, "unfinished fragmented message");
      return;
    }
    if (streams(opcode)) {
      MessageInputStream input = beginStreaming(opcode, compressed);
      if (feedStreaming(payload, fin)) dispatchStreaming(opcode, input);
      return;
    }
    if (receivesFragments(opcode)) {
      beginPartial(opcode, compressed);
      deliverPartial(payload, fin);
      return;
    }
    if (fin) {
      if (compressed && (payload = inflate(payload)) == null) return;
      finishMessage(opcode, payload);
    } else {
      fragmentedOpcode = opcode;
      fragmentedCompressed = compressed;
      fragmented = new ByteArrayOutputStream(Math.min(payload.length + 256, maxMessageBytes));
      appendFragment(payload);
    }
  }

  private void appendFragment(byte[] payload) throws IOException {
    if ((long) fragmented.size() + payload.length > maxMessageBytes) {
      protocolClose(1009, "message too large");
      fragmented = null;
      fragmentedOpcode = -1;
      return;
    }
    fragmented.write(payload);
  }

  private void finishMessage(int opcode, byte[] payload) throws IOException {
    fragmented = null;
    fragmentedOpcode = -1;
    fragmentedCompressed = false;
    if (opcode == OP_TEXT) {
      final String text;
      try {
        CharBuffer decoded =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload));
        text = decoded.toString();
      } catch (CharacterCodingException e) {
        protocolClose(1007, "invalid UTF-8");
        return;
      }
      callback(() -> handler.onText(this, text));
    } else {
      final ByteBuffer binary = ByteBuffer.wrap(payload).asReadOnlyBuffer();
      callback(() -> handler.onBinary(this, binary));
    }
  }

  private boolean streams(int opcode) {
    return opcode == OP_TEXT && handler.streamsTextMessages()
        || opcode == OP_BINARY && handler.streamsBinaryMessages();
  }

  private boolean receivesFragments(int opcode) {
    return opcode == OP_TEXT && handler.receivesTextFragments()
        || opcode == OP_BINARY && handler.receivesBinaryFragments();
  }

  private void beginPartial(int opcode, boolean compressed) {
    partialOpcode = opcode;
    partialCompressed = compressed;
    partialInflater = compressed ? new Inflater(true) : null;
    partialTextDecoder =
        opcode == OP_TEXT
            ? StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            : null;
    partialTextRemainder = new byte[0];
    partialWireBytes = 0L;
    partialDecodedBytes = 0L;
  }

  private void deliverPartial(byte[] payload, boolean last) throws IOException {
    final int opcode = partialOpcode;
    byte[] decoded = decodePartialPayload(payload, last);
    if (decoded == null || partialOpcode == -1) return;
    if (opcode == OP_TEXT) {
      final String text = decodePartialText(decoded, last);
      if (text == null || partialOpcode == -1) return;
      if (last) clearPartial();
      callback(() -> handler.onTextFragment(this, text, last));
    } else {
      final ByteBuffer binary = ByteBuffer.wrap(decoded).asReadOnlyBuffer();
      if (last) clearPartial();
      callback(() -> handler.onBinaryFragment(this, binary, last));
    }
  }

  private byte[] decodePartialPayload(byte[] payload, boolean last) throws IOException {
    partialWireBytes += payload.length;
    if (partialWireBytes > maxMessageBytes) {
      protocolClose(1009, "message too large");
      return null;
    }
    if (!partialCompressed) {
      partialDecodedBytes += payload.length;
      if (partialDecodedBytes > maxMessageBytes) {
        protocolClose(1009, "message too large");
        return null;
      }
      return payload;
    }
    ByteArrayOutputStream output =
        new ByteArrayOutputStream(Math.min(Math.max(payload.length, 32), maxMessageBytes));
    if (!inflatePartial(payload, output)) return null;
    if (last && !inflatePartial(new byte[] {0, 0, (byte) 0xff, (byte) 0xff}, output)) return null;
    return output.toByteArray();
  }

  private boolean inflatePartial(byte[] payload, ByteArrayOutputStream output) throws IOException {
    Inflater inflater = partialInflater;
    inflater.setInput(payload);
    byte[] decoded = new byte[8192];
    try {
      while (!inflater.needsInput()) {
        int count = inflater.inflate(decoded);
        if (count > 0) {
          partialDecodedBytes += count;
          if (partialDecodedBytes > maxMessageBytes) {
            protocolClose(1009, "message too large");
            return false;
          }
          output.write(decoded, 0, count);
        } else if (inflater.needsDictionary()) {
          throw new DataFormatException("compression dictionary is not supported");
        } else if (inflater.needsInput() || inflater.finished()) {
          break;
        } else {
          throw new DataFormatException("compressed message made no progress");
        }
      }
      return true;
    } catch (DataFormatException e) {
      protocolClose(1002, "invalid compressed message");
      return false;
    }
  }

  private String decodePartialText(byte[] payload, boolean last) throws IOException {
    ByteBuffer input = ByteBuffer.allocate(partialTextRemainder.length + payload.length);
    input.put(partialTextRemainder).put(payload).flip();
    CharBuffer output = CharBuffer.allocate(Math.max(1, input.remaining() + 1));
    try {
      CoderResult result = partialTextDecoder.decode(input, output, last);
      if (result.isError()) result.throwException();
      if (result.isOverflow()) throw new IllegalStateException("UTF-8 decoder output overflow");
      if (last) {
        result = partialTextDecoder.flush(output);
        if (result.isError()) result.throwException();
        if (result.isOverflow() || input.hasRemaining()) {
          throw new CharacterCodingException();
        }
        partialTextRemainder = new byte[0];
      } else {
        partialTextRemainder = new byte[input.remaining()];
        input.get(partialTextRemainder);
      }
    } catch (CharacterCodingException e) {
      protocolClose(1007, "invalid UTF-8");
      return null;
    }
    output.flip();
    return output.toString();
  }

  private synchronized void clearPartial() {
    if (partialInflater != null) partialInflater.end();
    partialOpcode = -1;
    partialCompressed = false;
    partialInflater = null;
    partialTextDecoder = null;
    partialTextRemainder = new byte[0];
    partialWireBytes = 0L;
    partialDecodedBytes = 0L;
  }

  private MessageInputStream beginStreaming(int opcode, boolean compressed) {
    MessageInputStream input = new MessageInputStream();
    streamingInput = input;
    streamingCompressed = compressed;
    streamingInflater = compressed ? new Inflater(true) : null;
    streamingWireBytes = 0L;
    streamingDecodedBytes = 0L;
    return input;
  }

  private boolean feedStreaming(byte[] payload, boolean fin) throws IOException {
    MessageInputStream input = streamingInput;
    if (input == null) return false;
    streamingWireBytes += payload.length;
    if (streamingWireBytes > maxMessageBytes) {
      failStreaming(new IOException("WebSocket message exceeds configured limit"));
      protocolClose(1009, "message too large");
      return false;
    }
    if (streamingCompressed) {
      if (!inflateStreaming(payload)) return false;
      if (fin && !inflateStreaming(new byte[] {0, 0, (byte) 0xff, (byte) 0xff})) {
        return false;
      }
    } else if (!offerStreaming(payload, payload.length)) {
      return false;
    }
    if (fin) completeStreaming();
    return true;
  }

  private boolean inflateStreaming(byte[] payload) throws IOException {
    Inflater inflater = streamingInflater;
    inflater.setInput(payload);
    byte[] decoded = new byte[8192];
    try {
      while (!inflater.needsInput()) {
        int count = inflater.inflate(decoded);
        if (count > 0) {
          if (!offerStreaming(decoded, count)) return false;
        } else if (inflater.needsDictionary()) {
          throw new DataFormatException("compression dictionary is not supported");
        } else {
          break;
        }
      }
      return true;
    } catch (DataFormatException e) {
      failStreaming(new IOException("invalid compressed WebSocket message", e));
      protocolClose(1002, "invalid compressed message");
      return false;
    }
  }

  private boolean offerStreaming(byte[] value, int length) throws IOException {
    streamingDecodedBytes += length;
    if (streamingDecodedBytes > maxMessageBytes) {
      failStreaming(new IOException("WebSocket message exceeds configured limit"));
      protocolClose(1009, "message too large");
      return false;
    }
    streamingInput.offer(value, length);
    return true;
  }

  private void completeStreaming() {
    MessageInputStream input = streamingInput;
    if (input != null) input.finish();
    clearStreaming();
  }

  private synchronized void failStreaming(IOException failure) {
    MessageInputStream input = streamingInput;
    if (input != null) input.fail(failure);
    clearStreaming();
  }

  private void clearStreaming() {
    if (streamingInflater != null) streamingInflater.end();
    streamingInput = null;
    streamingCompressed = false;
    streamingInflater = null;
    streamingWireBytes = 0L;
    streamingDecodedBytes = 0L;
  }

  private void dispatchStreaming(int opcode, MessageInputStream input) {
    if (opcode == OP_TEXT) {
      final Reader reader =
          new InputStreamReader(
              input,
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT));
      callback(
          () -> {
            try {
              handler.onTextStream(this, reader);
            } catch (CharacterCodingException invalidUtf8) {
              protocolClose(1007, "invalid UTF-8");
            }
          });
    } else {
      callback(() -> handler.onBinaryStream(this, input));
    }
  }

  private void handleClose(byte[] payload) throws IOException {
    if (payload.length == 1) {
      protocolClose(1002, "invalid close payload");
      return;
    }
    int code = 1005;
    String reason = "";
    if (payload.length >= 2) {
      code = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
      if (!validCloseCode(code)) {
        protocolClose(1002, "invalid close code");
        return;
      }
      try {
        reason =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload, 2, payload.length - 2))
                .toString();
      } catch (CharacterCodingException e) {
        protocolClose(1007, "invalid close reason");
        return;
      }
    }
    closeReceived = true;
    if (!closeSent) writeFrame(OP_CLOSE, payload);
    open = false;
    final int completedCode = code;
    final String completedReason = reason;
    callback(() -> handler.onClose(this, completedCode, completedReason));
    UpgradeChannel current = channel;
    if (current != null) current.close();
  }

  private int failProtocol(String reason, int remaining) throws IOException {
    protocolClose(1002, reason);
    return remaining;
  }

  private void protocolClose(int status, String reason) throws IOException {
    networkStreamFrame = null;
    failStreaming(new IOException("WebSocket message aborted: " + reason));
    clearPartial();
    close(status, reason);
  }

  @Override
  public void onClosed() {
    networkStreamFrame = null;
    failStreaming(new IOException("WebSocket connection closed while reading a message"));
    clearPartial();
    boolean notify = open && !closeReceived;
    open = false;
    if (notify) callback(() -> handler.onClose(this, 1006, "connection closed"));
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean isOpen() {
    UpgradeChannel current = channel;
    return open && current != null && current.isOpen();
  }

  @Override
  public String negotiatedSubprotocol() {
    return negotiatedSubprotocol;
  }

  @Override
  public List<String> negotiatedExtensions() {
    return perMessageDeflate
        ? Collections.singletonList("permessage-deflate")
        : Collections.<String>emptyList();
  }

  @Override
  public void sendText(String message) throws IOException {
    if (message == null) throw new IllegalArgumentException("message must not be null");
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    if (payload.length > maxMessageBytes)
      throw new IllegalArgumentException("message exceeds configured limit");
    writeMessageFrame(OP_TEXT, payload);
  }

  @Override
  public CompletionStage<Void> sendTextAsync(String message) {
    if (message == null) return failed(new IllegalArgumentException("message must not be null"));
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    if (payload.length > maxMessageBytes) {
      return failed(new IllegalArgumentException("message exceeds configured limit"));
    }
    return writeMessageFrameAsync(OP_TEXT, payload);
  }

  @Override
  public CompletionStage<Void> sendTextFragmentAsync(String fragment, boolean last) {
    if (fragment == null) return failed(new IllegalArgumentException("fragment must not be null"));
    return writeFragmentAsync(OP_TEXT, fragment.getBytes(StandardCharsets.UTF_8), last);
  }

  @Override
  public void sendBinary(ByteBuffer message) throws IOException {
    if (message == null) throw new IllegalArgumentException("message must not be null");
    ByteBuffer copy = message.slice();
    if (copy.remaining() > maxMessageBytes)
      throw new IllegalArgumentException("message exceeds configured limit");
    byte[] payload = new byte[copy.remaining()];
    copy.get(payload);
    writeMessageFrame(OP_BINARY, payload);
  }

  @Override
  public CompletionStage<Void> sendBinaryAsync(ByteBuffer message) {
    if (message == null) return failed(new IllegalArgumentException("message must not be null"));
    ByteBuffer copy = message.slice();
    if (copy.remaining() > maxMessageBytes) {
      return failed(new IllegalArgumentException("message exceeds configured limit"));
    }
    byte[] payload = new byte[copy.remaining()];
    copy.get(payload);
    return writeMessageFrameAsync(OP_BINARY, payload);
  }

  @Override
  public CompletionStage<Void> sendBinaryFragmentAsync(ByteBuffer fragment, boolean last) {
    if (fragment == null) return failed(new IllegalArgumentException("fragment must not be null"));
    ByteBuffer source = fragment.slice();
    byte[] payload = new byte[source.remaining()];
    source.get(payload);
    return writeFragmentAsync(OP_BINARY, payload, last);
  }

  @Override
  public void sendPing(ByteBuffer payload) throws IOException {
    ByteBuffer copy = payload == null ? ByteBuffer.allocate(0) : payload.slice();
    if (copy.remaining() > 125)
      throw new IllegalArgumentException("ping payload exceeds 125 bytes");
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    writeFrame(OP_PING, bytes);
  }

  @Override
  public CompletionStage<Void> sendPingAsync(ByteBuffer payload) {
    return controlFrameAsync(OP_PING, payload);
  }

  @Override
  public void sendPong(ByteBuffer payload) throws IOException {
    ByteBuffer copy = payload == null ? ByteBuffer.allocate(0) : payload.slice();
    if (copy.remaining() > 125)
      throw new IllegalArgumentException("pong payload exceeds 125 bytes");
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    writeFrame(OP_PONG, bytes);
  }

  @Override
  public CompletionStage<Void> sendPongAsync(ByteBuffer payload) {
    return controlFrameAsync(OP_PONG, payload);
  }

  @Override
  public void setIdleTimeoutMillis(long timeoutMillis) {
    idleTimeoutMillis = timeoutMillis;
    touch();
  }

  @Override
  public boolean managesIdleTimeout() {
    return idleTimeoutMillis != -1L;
  }

  @Override
  /** 按空闲超时和待完成发送的截止时间推进连接状态。 */
  public void onTimer(long nowNanos) {
    expireSends(nowNanos);
    long timeout = idleTimeoutMillis;
    if (!open || timeout <= 0L || (nowNanos - lastActivityNanos) / 1_000_000L < timeout) return;
    try {
      close(1001, "idle timeout");
    } catch (IOException ignored) {
      // The transport close below terminates a failed close handshake.
    }
    UpgradeChannel current = channel;
    if (current != null) current.close();
  }

  @Override
  public CompletionStage<Void> withSendTimeout(CompletionStage<Void> send, long timeoutMillis) {
    if (timeoutMillis <= 0L) return send;
    final CompletableFuture<Void> timed = new CompletableFuture<Void>();
    final PendingSend pending = new PendingSend(timed, deadline(timeoutMillis));
    synchronized (pendingSends) {
      pendingSends.add(pending);
    }
    send.whenComplete(
        (ignored, failure) -> {
          synchronized (pendingSends) {
            pendingSends.remove(pending);
          }
          if (failure == null) timed.complete(null);
          else timed.completeExceptionally(failure);
        });
    return timed;
  }

  @Override
  /** 等待当前批次的异步发送达到可观察的完成边界。 */
  public CompletionStage<Void> asyncCheckpoint() {
    return isOpen()
        ? CompletableFuture.completedFuture(null)
        : failed(new IOException("WebSocket channel is closed"));
  }

  @Override
  /** 将传输层发送结果转为端点回调应看到的完成结果。 */
  public CompletionStage<Void> dispatchCompletion(CompletionStage<Void> send) {
    final CompletableFuture<Void> dispatched = new CompletableFuture<Void>();
    send.whenComplete(
        (ignored, failure) -> {
          try {
            executor.execute(
                () -> {
                  if (failure == null) dispatched.complete(null);
                  else dispatched.completeExceptionally(failure);
                });
          } catch (Throwable rejected) {
            dispatched.completeExceptionally(rejected);
          }
        });
    return dispatched;
  }

  @Override
  public void close() throws IOException {
    close(1000, "");
  }

  @Override
  public synchronized void close(int statusCode, String reason) throws IOException {
    if (closeSent) return;
    if (!validCloseCode(statusCode))
      throw new IllegalArgumentException("invalid close status code");
    byte[] reasonBytes = (reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8);
    if (reasonBytes.length > 123)
      throw new IllegalArgumentException("close reason exceeds 123 bytes");
    ByteBuffer payload = ByteBuffer.allocate(reasonBytes.length + 2);
    payload.putShort((short) statusCode).put(reasonBytes);
    writeFrame(OP_CLOSE, payload.array());
    closeSent = true;
    if (closeReceived) {
      open = false;
      UpgradeChannel current = channel;
      if (current != null) current.close();
    }
  }

  private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
    writeFrame(opcode, payload, false);
  }

  private synchronized void writeMessageFrame(int opcode, byte[] payload) throws IOException {
    if (outboundFragmentOpcode != -1) {
      throw new IllegalStateException("a fragmented WebSocket message is in progress");
    }
    if (perMessageDeflate && payload.length > 0) {
      writeFrame(opcode, deflate(payload), true);
    } else {
      writeFrame(opcode, payload, false);
    }
  }

  private synchronized CompletionStage<Void> writeMessageFrameAsync(int opcode, byte[] payload) {
    if (outboundFragmentOpcode != -1) {
      return failed(new IllegalStateException("a fragmented WebSocket message is in progress"));
    }
    try {
      return perMessageDeflate && payload.length > 0
          ? writeFrameAsync(opcode, deflate(payload), true)
          : writeFrameAsync(opcode, payload, false);
    } catch (Throwable failure) {
      return failed(failure);
    }
  }

  private synchronized CompletionStage<Void> writeFragmentAsync(
      int opcode, byte[] payload, boolean last) {
    boolean first = outboundFragmentOpcode == -1;
    if (!first && outboundFragmentOpcode != opcode) {
      return failed(
          new IllegalStateException("a different fragmented WebSocket message is in progress"));
    }
    long total = (first ? 0L : outboundFragmentBytes) + payload.length;
    if (total > maxMessageBytes) {
      if (!first) abortOutboundFragment();
      return failed(new IllegalArgumentException("message exceeds configured limit"));
    }
    try {
      byte[] encoded = payload;
      boolean compressed = perMessageDeflate;
      if (compressed) {
        if (first) outboundDeflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        encoded = deflateFragment(payload, last);
      }
      outboundFragmentOpcode = last ? -1 : opcode;
      outboundFragmentBytes = last ? 0L : total;
      int frameOpcode = first ? opcode : OP_CONTINUATION;
      CompletionStage<Void> result =
          writeFrameAsync(frameOpcode, encoded, first && compressed, last);
      if (last) clearOutboundDeflater();
      result.whenComplete(
          (ignored, failure) -> {
            if (failure != null) abortOutboundFragment();
          });
      return result;
    } catch (Throwable failure) {
      abortOutboundFragment();
      return failed(failure);
    }
  }

  private synchronized void writeFrame(int opcode, byte[] payload, boolean compressed)
      throws IOException {
    if (!open && opcode != OP_CLOSE) throw new IOException("WebSocket session is closed");
    UpgradeChannel current = channel;
    if (current == null || !current.isOpen()) throw new IOException("WebSocket channel is closed");
    current.write(encodedFrame(opcode, payload, compressed, true));
    touch();
  }

  private synchronized CompletionStage<Void> writeFrameAsync(
      int opcode, byte[] payload, boolean compressed) {
    return writeFrameAsync(opcode, payload, compressed, true);
  }

  private synchronized CompletionStage<Void> writeFrameAsync(
      int opcode, byte[] payload, boolean compressed, boolean fin) {
    if (!open && opcode != OP_CLOSE) return failed(new IOException("WebSocket session is closed"));
    UpgradeChannel current = channel;
    if (current == null || !current.isOpen()) {
      return failed(new IOException("WebSocket channel is closed"));
    }
    CompletionStage<Void> result =
        current.writeAsync(encodedFrame(opcode, payload, compressed, fin));
    result.whenComplete(
        (ignored, failure) -> {
          if (failure == null) touch();
        });
    return result;
  }

  private ByteBuffer encodedFrame(int opcode, byte[] payload, boolean compressed, boolean fin) {
    int maskBytes = maskOutput ? 4 : 0;
    int header = (payload.length <= 125 ? 2 : payload.length <= 0xffff ? 4 : 10) + maskBytes;
    ByteBuffer frame = ByteBuffer.allocate(header + payload.length);
    frame.put((byte) ((fin ? 0x80 : 0) | (compressed ? 0x40 : 0) | opcode));
    if (payload.length <= 125) {
      frame.put((byte) ((maskOutput ? 0x80 : 0) | payload.length));
    } else if (payload.length <= 0xffff) {
      frame.put((byte) ((maskOutput ? 0x80 : 0) | 126)).putShort((short) payload.length);
    } else {
      frame.put((byte) ((maskOutput ? 0x80 : 0) | 127)).putLong(payload.length);
    }
    if (maskOutput) {
      byte[] mask = new byte[4];
      masks.nextBytes(mask);
      frame.put(mask);
      for (int i = 0; i < payload.length; i++) {
        frame.put((byte) (payload[i] ^ mask[i & 3]));
      }
    } else {
      frame.put(payload);
    }
    frame.flip();
    return frame;
  }

  private byte[] deflateFragment(byte[] payload, boolean last) throws IOException {
    outboundDeflater.setInput(payload);
    ByteArrayOutputStream result = new ByteArrayOutputStream(Math.max(32, payload.length));
    byte[] buffer = new byte[8192];
    while (!outboundDeflater.needsInput()) {
      int count = outboundDeflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
      if (count == 0) break;
      result.write(buffer, 0, count);
    }
    byte[] encoded = result.toByteArray();
    if (!last) return encoded;
    if (encoded.length < 4) {
      throw new IOException("failed to finish compressed WebSocket message");
    }
    return Arrays.copyOf(encoded, encoded.length - 4);
  }

  private synchronized void abortOutboundFragment() {
    outboundFragmentOpcode = -1;
    outboundFragmentBytes = 0L;
    clearOutboundDeflater();
  }

  private void clearOutboundDeflater() {
    if (outboundDeflater != null) outboundDeflater.end();
    outboundDeflater = null;
  }

  private CompletionStage<Void> controlFrameAsync(int opcode, ByteBuffer payload) {
    ByteBuffer copy = payload == null ? ByteBuffer.allocate(0) : payload.slice();
    if (copy.remaining() > 125) {
      return failed(new IllegalArgumentException("control payload exceeds 125 bytes"));
    }
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return writeFrameAsync(opcode, bytes, false);
  }

  private void touch() {
    lastActivityNanos = System.nanoTime();
  }

  private void expireSends(long nowNanos) {
    List<PendingSend> expired = new ArrayList<PendingSend>();
    synchronized (pendingSends) {
      for (PendingSend pending : pendingSends) {
        if (nowNanos >= pending.deadlineNanos) expired.add(pending);
      }
      pendingSends.removeAll(expired);
    }
    for (PendingSend pending : expired) {
      pending.completion.completeExceptionally(
          new SocketTimeoutException("WebSocket asynchronous send timed out"));
    }
  }

  private static long deadline(long timeoutMillis) {
    long now = System.nanoTime();
    long nanos =
        timeoutMillis >= Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : timeoutMillis * 1_000_000L;
    return nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
  }

  private static <T> CompletionStage<T> failed(Throwable failure) {
    CompletableFuture<T> result = new CompletableFuture<T>();
    result.completeExceptionally(failure);
    return result;
  }

  private byte[] inflate(byte[] payload) throws IOException {
    if (payload.length > maxMessageBytes) throw new IOException("compressed message is too large");
    byte[] source = Arrays.copyOf(payload, payload.length + 4);
    source[source.length - 4] = 0;
    source[source.length - 3] = 0;
    source[source.length - 2] = (byte) 0xff;
    source[source.length - 1] = (byte) 0xff;
    Inflater inflater = new Inflater(true);
    inflater.setInput(source);
    ByteArrayOutputStream result =
        new ByteArrayOutputStream(Math.min(Math.max(256, payload.length * 2), maxMessageBytes));
    byte[] buffer = new byte[8192];
    try {
      while (!inflater.needsInput()) {
        int count = inflater.inflate(buffer);
        if (count == 0) break;
        if ((long) result.size() + count > maxMessageBytes) {
          protocolClose(1009, "message too large");
          return null;
        }
        result.write(buffer, 0, count);
      }
    } catch (DataFormatException e) {
      protocolClose(1002, "invalid compressed message");
      return null;
    } finally {
      inflater.end();
    }
    return result.toByteArray();
  }

  private static byte[] deflate(byte[] payload) throws IOException {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(payload);
    ByteArrayOutputStream result = new ByteArrayOutputStream(payload.length);
    byte[] buffer = new byte[8192];
    try {
      while (!deflater.needsInput()) {
        int count = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
        if (count == 0) break;
        result.write(buffer, 0, count);
      }
    } finally {
      deflater.end();
    }
    byte[] encoded = result.toByteArray();
    if (encoded.length < 4) throw new IOException("failed to encode permessage-deflate payload");
    return Arrays.copyOf(encoded, encoded.length - 4);
  }

  private void callback(ThrowingRunnable action) {
    callbacks.execute(
        () -> {
          try {
            action.run();
          } catch (Throwable failure) {
            handler.onError(this, failure);
            try {
              close(1011, "endpoint failure");
            } catch (IOException ignored) {
              UpgradeChannel current = channel;
              if (current != null) current.close();
            }
          }
        });
  }

  private static boolean validOpcode(int opcode) {
    return opcode == OP_CONTINUATION
        || opcode == OP_TEXT
        || opcode == OP_BINARY
        || opcode == OP_CLOSE
        || opcode == OP_PING
        || opcode == OP_PONG;
  }

  private static boolean validCloseCode(int code) {
    if (code < 1000 || code >= 5000) return false;
    if (code >= 3000) return true;
    return code <= 1014 && code != 1004 && code != 1005 && code != 1006;
  }

  /** 封装throwingrunnable的状态与处理边界。 */
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** 封装待处理发送的状态与处理边界。 */
  private static final class PendingSend {
    /** 完成。 */
    private final CompletableFuture<Void> completion;
    /** 截止时间纳秒。 */
    private final long deadlineNanos;

    private PendingSend(CompletableFuture<Void> completion, long deadlineNanos) {
      this.completion = completion;
      this.deadlineNanos = deadlineNanos;
    }
  }

  /** 封装网络流帧的状态与处理边界。 */
  private static final class NetworkStreamFrame {
    /** 操作码。 */
    private final int opcode;
    /** final消息片段，布尔标志。 */
    private final boolean finalMessageFragment;
    /** 掩码。 */
    private final byte[] mask;
    /** 剩余。 */
    private long remaining;
    /** consumed。 */
    /** 已消费的（consumed）。 */
    private long consumed;
    /** 分派输入。 */
    private MessageInputStream dispatchInput;

    private NetworkStreamFrame(
        int opcode,
        boolean finalMessageFragment,
        byte[] mask,
        long remaining,
        MessageInputStream dispatchInput) {
      this.opcode = opcode;
      this.finalMessageFragment = finalMessageFragment;
      this.mask = mask;
      this.remaining = remaining;
      this.dispatchInput = dispatchInput;
    }
  }

  /** 封装序列执行器的状态与处理边界。 */
  private static final class SerialExecutor implements Executor {
    /** 委托对象。 */
    private final Executor delegate;
    /** tasks。 */
    /** 任务集合（tasks）。 */
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
    /** 活动。 */
    private Runnable active;

    private SerialExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public synchronized void execute(final Runnable command) {
      tasks.offer(
          () -> {
            try {
              command.run();
            } finally {
              scheduleNext();
            }
          });
      if (active == null) scheduleNext();
    }

    private synchronized void scheduleNext() {
      active = tasks.poll();
      if (active != null) delegate.execute(active);
    }
  }
}
