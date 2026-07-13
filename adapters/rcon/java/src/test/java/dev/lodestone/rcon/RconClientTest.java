// SPDX-License-Identifier: MIT
package dev.lodestone.rcon;

import dev.lodestone.adapter.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RconClientTest {
    @Test
    void authenticatesExecutesAndBoundsUnstructuredOutput() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    var auth = read(input);
                    write(output, auth.id(), 2, "");
                    var command = read(input);
                    assertEquals("list", command.payload());
                    write(output, command.id(), 0, "players: 2\n");
                }
                return null;
            });
            try (var client = new RconClient("127.0.0.1", server.getLocalPort(), "secret", 4)) {
                var cancellation = new RecordingCancellationToken();
                var response = client.execute("list", cancellation, System.currentTimeMillis() + 3_000);
                assertEquals("play", response.text());
                assertTrue(response.truncated());
                assertTrue(cancellation.committed);
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsAuthenticationWithoutExposingPassword() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    var auth = read(input);
                    write(output, -1, 2, "");
                }
                return null;
            });
            try (var client = new RconClient("127.0.0.1", server.getLocalPort(), "secret", 100)) {
                var cancellation = new RecordingCancellationToken();
                var failure = assertThrows(RconClient.AuthenticationException.class,
                        () -> client.execute("list", cancellation, System.currentTimeMillis() + 3_000));
                assertTrue(!failure.getMessage().contains("secret"));
                assertFalse(cancellation.committed);
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void preservesFramingAcrossFragmentedPackets() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    var auth = read(input);
                    writeFragmented(output, auth.id(), 2, "");
                    var command = read(input);
                    assertEquals("list", command.payload());
                    writeFragmented(output, command.id(), 0, "players: 2\n");
                }
                return null;
            });
            try (var client = new RconClient("127.0.0.1", server.getLocalPort(), "secret", 100)) {
                var response = client.execute("list", CancellationToken.none(), System.currentTimeMillis() + 3_000);
                assertEquals("players: 2\n", response.text());
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    private static Packet read(DataInputStream input) throws Exception {
        var size = readLittleEndian(input);
        var body = input.readNBytes(size);
        return new Packet(intLittleEndian(body, 0), intLittleEndian(body, 4),
                new String(body, 8, size - 10, StandardCharsets.UTF_8));
    }

    private static void write(DataOutputStream output, int id, int type, String payload) throws Exception {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(output, 10 + bytes.length);
        writeLittleEndian(output, id);
        writeLittleEndian(output, type);
        output.write(bytes);
        output.writeByte(0);
        output.writeByte(0);
        output.flush();
    }

    private static void writeFragmented(DataOutputStream output, int id, int type, String payload) throws Exception {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        var packet = new byte[14 + bytes.length];
        putLittleEndian(packet, 0, 10 + bytes.length);
        putLittleEndian(packet, 4, id);
        putLittleEndian(packet, 8, type);
        System.arraycopy(bytes, 0, packet, 12, bytes.length);
        for (var index = 0; index < packet.length; index++) {
            output.writeByte(packet[index]);
            output.flush();
            Thread.sleep(5);
        }
    }

    private static void putLittleEndian(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static int readLittleEndian(DataInputStream input) throws Exception {
        return input.readUnsignedByte()
                | (input.readUnsignedByte() << 8)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 24);
    }

    private static int intLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeLittleEndian(DataOutputStream output, int value) throws Exception {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
        output.writeByte((value >>> 16) & 0xff);
        output.writeByte((value >>> 24) & 0xff);
    }

    private record Packet(int id, int type, String payload) {
    }

    private static final class RecordingCancellationToken implements CancellationToken {
        private boolean committed;

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void commitMutation() {
            committed = true;
        }
    }
}
