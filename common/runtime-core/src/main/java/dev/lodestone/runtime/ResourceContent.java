// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Caller-facing resource payload that can safely represent trusted text or isolated binary data. */
public final class ResourceContent {
    private final String mimeType;
    private final String text;
    private final byte[] bytes;
    private final boolean binary;

    private ResourceContent(String mimeType, String text, byte[] bytes, boolean binary) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        this.mimeType = mimeType;
        this.text = text;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.binary = binary;
    }

    public static ResourceContent text(String mimeType, String text) {
        var required = Objects.requireNonNull(text, "text");
        return new ResourceContent(mimeType, required, required.getBytes(StandardCharsets.UTF_8), false);
    }

    public static ResourceContent binary(String mimeType, byte[] bytes) {
        return new ResourceContent(mimeType, null, bytes, true);
    }

    public String mimeType() {
        return mimeType;
    }

    public boolean binary() {
        return binary;
    }

    public String text() {
        if (binary) {
            throw new IllegalStateException("binary resource does not have text content");
        }
        return text;
    }

    public byte[] bytes() {
        return bytes.clone();
    }
}
