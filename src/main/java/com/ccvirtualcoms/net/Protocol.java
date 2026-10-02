package com.ccvirtualcoms.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The CC Synaxis Virtual Coms binary framing protocol.
 *
 * <p>Every frame on the socket has the following layout (all integers big-endian):
 *
 * <pre>
 * +--------+-----------+-----------+------------+-----------------+
 * |  magic |  version  |  msg type |  payload   |    payload      |
 * | 4 byte |  2 byte   |  2 byte   |  length    |   (JSON UTF-8)  |
 * | "CCVC" |  uint16   |  uint16   |  uint32    |  N byte         |
 * +--------+-----------+-----------+------------+-----------------+
 * </pre>
 *
 * <p>The payload is always a UTF-8 encoded JSON object (or empty for {@link #MSG_HEARTBEAT}).
 */
public final class Protocol {

    /** Frame magic, ASCII {@code CCVC}. */
    public static final byte[] MAGIC = {'C', 'C', 'V', 'C'};

    /** Protocol revision implemented by this build. */
    public static final int VERSION = 1;

    /** Size in bytes of the fixed frame header. */
    public static final int HEADER_SIZE = 12;

    /** Hard upper bound on a single payload, guarding against a hostile or desynchronised peer. */
    public static final int MAX_PAYLOAD_SIZE = 8 * 1024 * 1024;

    /** Game to external: a port value changed and is being published. */
    public static final int MSG_DATA_PUSH = 0x01;

    /** External to game: write a new value into a port. */
    public static final int MSG_DATA_WRITE = 0x02;

    /** Either direction: liveness probe, payload may be empty. */
    public static final int MSG_HEARTBEAT = 0x03;

    /** Either direction: an error occurred, payload carries {@code {"error": "..."}}. */
    public static final int MSG_ERROR = 0x04;

    private Protocol() {
    }

    /** Human readable name of a message type, for logging. */
    public static String messageTypeName(int type) {
        return switch (type) {
            case MSG_DATA_PUSH -> "DATA_PUSH";
            case MSG_DATA_WRITE -> "DATA_WRITE";
            case MSG_HEARTBEAT -> "HEARTBEAT";
            case MSG_ERROR -> "ERROR";
            default -> "UNKNOWN(0x" + Integer.toHexString(type) + ")";
        };
    }

    /**
     * Builds a complete frame.
     *
     * @param messageType one of the {@code MSG_*} constants
     * @param payload     payload bytes, may be empty but never {@code null}
     * @return the framed message, ready to be written to the socket
     */
    public static byte[] encode(int messageType, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload of " + body.length + " bytes exceeds the protocol limit");
        }
        byte[] frame = new byte[HEADER_SIZE + body.length];
        frame[0] = MAGIC[0];
        frame[1] = MAGIC[1];
        frame[2] = MAGIC[2];
        frame[3] = MAGIC[3];
        writeUInt16(frame, 4, VERSION);
        writeUInt16(frame, 6, messageType);
        writeUInt32(frame, 8, body.length);
        System.arraycopy(body, 0, frame, HEADER_SIZE, body.length);
        return frame;
    }

    /** Builds a complete frame whose payload is {@code json}. */
    public static byte[] encodeJson(int messageType, String json) {
        return encode(messageType, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a frame to a stream and flushes it. */
    public static void write(OutputStream out, int messageType, byte[] payload) throws IOException {
        out.write(encode(messageType, payload));
        out.flush();
    }

    /** Writes a JSON frame to a stream and flushes it. */
    public static void writeJson(OutputStream out, int messageType, String json) throws IOException {
        write(out, messageType, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads exactly one frame from {@code in}, blocking until it is complete.
     *
     * @return the decoded frame
     * @throws EOFException     when the peer closed the connection cleanly at a frame boundary
     * @throws ProtocolException when the magic, version or length fields are invalid
     */
    public static Frame read(InputStream in) throws IOException {
        byte[] header = readFully(in, HEADER_SIZE);

        if (header[0] != MAGIC[0] || header[1] != MAGIC[1] || header[2] != MAGIC[2] || header[3] != MAGIC[3]) {
            throw new ProtocolException("Bad frame magic: expected 'CCVC' but the stream is out of sync");
        }

        int version = readUInt16(header, 4);
        if (version != VERSION) {
            throw new ProtocolException("Unsupported protocol version " + version + ", this build speaks version " + VERSION);
        }

        int messageType = readUInt16(header, 6);

        long length = readUInt32(header, 8);
        if (length > MAX_PAYLOAD_SIZE) {
            throw new ProtocolException("Declared payload of " + length + " bytes exceeds the protocol limit");
        }

        byte[] payload = readFully(in, (int) length);
        return new Frame(messageType, payload);
    }

    /** Reads {@code length} bytes, failing if the stream ends first. */
    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Connection closed after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    /** Convenience overload that reads from a {@link DataInputStream} without consuming more than one frame. */
    public static Frame read(DataInputStream in) throws IOException {
        return read((InputStream) in);
    }

    /** Convenience overload that writes to a {@link DataOutputStream} and flushes it. */
    public static void write(DataOutputStream out, int messageType, byte[] payload) throws IOException {
        write((OutputStream) out, messageType, payload);
    }

    private static void writeUInt16(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeUInt32(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readUInt16(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 8) | (source[offset + 1] & 0xFF);
    }

    private static long readUInt32(byte[] source, int offset) {
        return ((long) (source[offset] & 0xFF) << 24)
                | ((long) (source[offset + 1] & 0xFF) << 16)
                | ((long) (source[offset + 2] & 0xFF) << 8)
                | (source[offset + 3] & 0xFF);
    }

    /** A decoded frame. */
    public record Frame(int messageType, byte[] payload) {

        /** The payload decoded as UTF-8 JSON text, or an empty string when there is no payload. */
        public String json() {
            return this.payload.length == 0 ? "" : new String(this.payload, StandardCharsets.UTF_8);
        }

        public boolean isEmpty() {
            return this.payload.length == 0;
        }
    }

    /** Thrown when a peer sends something that is not a valid frame. */
    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
