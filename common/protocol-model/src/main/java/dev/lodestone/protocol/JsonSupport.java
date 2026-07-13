// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

public final class JsonSupport {
    public static final Gson MAPPER = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(Instant.class,
                    (JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class,
                    (JsonDeserializer<Instant>) (json, type, context) -> Instant.parse(json.getAsString()))
            .registerTypeHierarchyAdapter(Enum.class,
                    (JsonSerializer<Enum>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeHierarchyAdapter(Enum.class,
                    (com.google.gson.JsonDeserializer<Enum>) (json, type, context) -> {
                        var normalized = json.getAsString().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
                        return Enum.valueOf((Class) type, normalized);
                    })
            .registerTypeAdapterFactory(new CapabilityDescriptorAdapterFactory())
            .create();

    private JsonSupport() {
    }

    /** Capability reason is a required nullable wire field; global null serialization would break other schemas. */
    private static final class CapabilityDescriptorAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != CapabilityDescriptor.class) return null;
            var delegate = gson.getDelegateAdapter(this, type);
            return new TypeAdapter<>() {
                @Override
                public void write(JsonWriter output, T value) throws IOException {
                    var previous = output.getSerializeNulls();
                    output.setSerializeNulls(true);
                    try {
                        delegate.write(output, value);
                    } finally {
                        output.setSerializeNulls(previous);
                    }
                }

                @Override
                public T read(JsonReader input) throws IOException {
                    return delegate.read(input);
                }
            };
        }
    }
}
