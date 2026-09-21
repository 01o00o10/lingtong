/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 将任意网络分块拼接成完整 HTTP/2 帧的增量解码器。 */
public final class Http2FrameDecoder {
    /** 尚不足以组成完整帧的尾部字节。 */
    private byte[] pending = new byte[0];
    /** 当前允许的单帧负载长度上限。 */
    private int maxFrameSize = 16_384;

    /** 消费输入并返回所有完整帧，不足一帧的字节留待下次。 */
    public List<Http2Frame> decode(ByteBuffer input) throws Http2Exception {
        byte[] bytes = new byte[pending.length + input.remaining()];
        System.arraycopy(pending, 0, bytes, 0, pending.length);
        input.get(bytes, pending.length, bytes.length - pending.length);
        List<Http2Frame> result = new ArrayList<Http2Frame>();
        int offset = 0;
        while (bytes.length - offset >= 9) {
            int length = ((bytes[offset] & 0xff) << 16)
                    | ((bytes[offset + 1] & 0xff) << 8) | (bytes[offset + 2] & 0xff);
            if (length > maxFrameSize) throw new Http2Exception(6, 0, "frame exceeds maximum size");
            if (bytes.length - offset - 9 < length) break;
            int streamId = ((bytes[offset + 5] & 0x7f) << 24)
                    | ((bytes[offset + 6] & 0xff) << 16)
                    | ((bytes[offset + 7] & 0xff) << 8) | (bytes[offset + 8] & 0xff);
            result.add(new Http2Frame(
                    bytes[offset + 3] & 0xff,
                    bytes[offset + 4] & 0xff,
                    streamId,
                    Arrays.copyOfRange(bytes, offset + 9, offset + 9 + length)));
            offset += 9 + length;
        }
        pending = Arrays.copyOfRange(bytes, offset, bytes.length);
        return result;
    }

    public void maxFrameSize(int value) {
        if (value < 16_384 || value > 16_777_215) throw new IllegalArgumentException("invalid frame size");
        maxFrameSize = value;
    }

    public int pendingBytes() { return pending.length; }
}
