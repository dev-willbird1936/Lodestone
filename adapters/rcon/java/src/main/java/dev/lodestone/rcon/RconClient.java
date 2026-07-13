// SPDX-License-Identifier: MIT
package dev.lodestone.rcon;

import dev.lodestone.adapter.CancellationToken;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal Source RCON client with bounded packets and no command-output interpretation. */
public final class RconClient implements AutoCloseable {
    private static final int AUTH_TYPE = 3;
    private static final int EXEC_TYPE = 2;
    private static final int MIN_PACKET_BYTES = 10;
    private static final int MAX_PACKET_BYTES = 4 * 1024 * 1024;
    private static final int INITIAL_READ_MILLIS = 1_000;
    private static final int RESPONSE_IDLE_MILLIS = 150;

    private final String host;
    private final int port;
    private final String password;
    private final int maxOutputBytes;
    private final AtomicInteger ids = new AtomicInteger(1);
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public RconClient(String host, int port, String password, int maxOutputBytes) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("RCON host and port are invalid");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("RCON password is required");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("RCON output bound is invalid");
        }
        this.host = host;
        this.port = port;
        this.password = password;
        this.maxOutputBytes = maxOutputBytes;
    }

    public synchronized void connect(CancellationToken cancellation, long deadlineEpochMs) throws IOException {
        if (isConnected()) {
            return;
        }
        closeSocket();
        cancellation.throwIfCancelled();
        var candidate = new Socket();
        try {
            candidate.setTcpNoDelay(true);
            candidate.connect(new InetSocketAddress(host, port), remainingMillis(deadlineEpochMs));
            var candidateInput = new DataInputStream(candidate.getInputStream());
            var candidateOutput = new DataOutputStream(candidate.getOutputStream());
            var requestId = nextId();
            writePacket(candidateOutput, requestId, AUTH_TYPE, password);
            var response = readUntil(candidateInput, candidate, deadlineEpochMs, cancellation,
                    packet -> packet.id() == requestId || packet.id() == -1);
            if (response.id() == -1 || response.type() != EXEC_TYPE) {
                throw new AuthenticationException();
            }
            socket = candidate;
            input = candidateInput;
            output = candidateOutput;
        } catch (Throwable failure) {
            try {
                candidate.close();
            } catch (IOException ignored) {
                // Preserve original connection/auth failure.
            }
            if (failure instanceof CancellationToken.CancellationException cancellationFailure) {
                throw cancellationFailure;
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("RCON authentication failed", failure);
        }
    }

    public synchronized Response execute(String command, CancellationToken cancellation, long deadlineEpochMs)
            throws IOException {
        if (command == null || command.isBlank() || command.length() > 32_768) {
            throw new IllegalArgumentException("RCON command must be 1-32768 characters");
        }
        // RCON servers commonly close idle clients. Fresh connection per command avoids stale
        // sockets without retrying a command whose execution status is unknown.
        closeSocket();
        connect(cancellation, deadlineEpochMs);
        var requestId = nextId();
        try {
            cancellation.throwIfCancelled();
            // Authentication is reversible setup. Cross the irreversible boundary only when the
            // command packet is about to be dispatched so connect/auth failures remain retryable.
            cancellation.commitMutation();
            writePacket(output, requestId, EXEC_TYPE, command);
            var responseBytes = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
            var truncated = false;
            var received = false;
            var idleDeadline = deadlineEpochMs;
            while (true) {
                cancellation.throwIfCancelled();
                var readDeadline = received ? Math.min(deadlineEpochMs,
                        Math.min(idleDeadline, System.currentTimeMillis() + RESPONSE_IDLE_MILLIS)) : deadlineEpochMs;
                try {
                    var packet = readPacket(input, socket, readDeadline, cancellation,
                            received ? RESPONSE_IDLE_MILLIS : INITIAL_READ_MILLIS);
                    if (packet.id() != requestId) {
                        continue;
                    }
                    received = true;
                    var bytes = packet.payload().getBytes(StandardCharsets.UTF_8);
                    var remaining = maxOutputBytes - responseBytes.size();
                    if (remaining > 0) {
                        responseBytes.write(bytes, 0, Math.min(remaining, bytes.length));
                    }
                    truncated |= bytes.length > Math.max(remaining, 0);
                    idleDeadline = System.currentTimeMillis() + RESPONSE_IDLE_MILLIS;
                } catch (SocketTimeoutException timeout) {
                    if (received) {
                        break;
                    }
                    if (System.currentTimeMillis() >= deadlineEpochMs) {
                        throw timeout;
                    }
                } catch (EOFException endOfStream) {
                    if (received) {
                        break;
                    }
                    throw endOfStream;
                }
            }
            if (!received) {
                throw new IOException("RCON command returned no response");
            }
            return new Response(new String(responseBytes.toByteArray(), StandardCharsets.UTF_8), truncated);
        } catch (Throwable failure) {
            if (failure instanceof CancellationToken.CancellationException cancellationFailure) {
                throw cancellationFailure;
            }
            if (failure instanceof IOException ioFailure) {
                closeSocket();
                throw ioFailure;
            }
            closeSocket();
            throw new IOException("RCON command failed", failure);
        } finally {
            closeSocket();
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed()
                && input != null && output != null;
    }

    @Override
    public synchronized void close() {
        closeSocket();
    }

    private Packet readUntil(DataInputStream stream, Socket target, long deadlineEpochMs,
                             CancellationToken cancellation, java.util.function.Predicate<Packet> predicate)
            throws IOException {
        while (true) {
            cancellation.throwIfCancelled();
            var packet = readPacket(stream, target, deadlineEpochMs, cancellation, INITIAL_READ_MILLIS);
            if (predicate.test(packet)) {
                return packet;
            }
        }
    }

    private static Packet readPacket(DataInputStream stream, Socket target, long deadlineEpochMs,
                                     CancellationToken cancellation, int pollMillis) throws IOException {
        var remaining = remainingMillis(deadlineEpochMs);
        target.setSoTimeout(Math.min(pollMillis, remaining));
        cancellation.throwIfCancelled();
        var size = intLittleEndian(readFully(stream, target, 4, deadlineEpochMs, cancellation, pollMillis), 0);
        if (size < MIN_PACKET_BYTES || size > MAX_PACKET_BYTES) {
            throw new IOException("RCON packet size is outside bounds");
        }
        var body = readFully(stream, target, size, deadlineEpochMs, cancellation, pollMillis);
        var id = intLittleEndian(body, 0);
        var type = intLittleEndian(body, 4);
        var payloadLength = size - MIN_PACKET_BYTES;
        if (body[size - 1] != 0 || body[size - 2] != 0) {
            throw new IOException("RCON packet terminators are invalid");
        }
        var payload = new String(body, 8, payloadLength, StandardCharsets.UTF_8);
        return new Packet(id, type, payload);
    }

    private static byte[] readFully(DataInputStream stream, Socket target, int length, long deadlineEpochMs,
                                    CancellationToken cancellation, int pollMillis) throws IOException {
        var bytes = new byte[length];
        var offset = 0;
        while (offset < length) {
            cancellation.throwIfCancelled();
            var remaining = remainingMillis(deadlineEpochMs);
            target.setSoTimeout(Math.min(pollMillis, remaining));
            try {
                var read = stream.read(bytes, offset, length - offset);
                if (read < 0) throw new EOFException("RCON packet ended early");
                if (read > 0) offset += read;
            } catch (SocketTimeoutException timeout) {
                if (System.currentTimeMillis() >= deadlineEpochMs) throw timeout;
            }
        }
        return bytes;
    }

    private static void writePacket(DataOutputStream stream, int id, int type, String payload) throws IOException {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        var size = 4 + 4 + bytes.length + 2;
        var packet = new byte[size + 4];
        putIntLittleEndian(packet, 0, size);
        putIntLittleEndian(packet, 4, id);
        putIntLittleEndian(packet, 8, type);
        System.arraycopy(bytes, 0, packet, 12, bytes.length);
        packet[packet.length - 2] = 0;
        packet[packet.length - 1] = 0;
        stream.write(packet);
        stream.flush();
    }

    private int nextId() {
        var id = ids.getAndIncrement();
        return id == -1 ? ids.getAndIncrement() : id;
    }

    private static int remainingMillis(long deadlineEpochMs) throws SocketTimeoutException {
        var remaining = deadlineEpochMs - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new SocketTimeoutException("RCON deadline exceeded");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static int intLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void putIntLittleEndian(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xff);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private void closeSocket() {
        var current = socket;
        socket = null;
        input = null;
        output = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
    }

    public record Response(String text, boolean truncated) {
    }

    private record Packet(int id, int type, String payload) {
    }

    public static final class AuthenticationException extends IOException {
        public AuthenticationException() {
            super("RCON authentication rejected");
        }
    }
}
