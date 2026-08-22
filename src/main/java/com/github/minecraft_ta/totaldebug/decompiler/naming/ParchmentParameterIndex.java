package com.github.minecraft_ta.totaldebug.decompiler.naming;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class ParchmentParameterIndex {
    private static final int MAGIC = 0x5444504E;
    private static final int FORMAT_VERSION = 1;
    private static final String RESOURCE = "/totaldebug/parchment-parameters.bin";

    private final Map<MethodKey, Map<Integer, String>> methods;

    private ParchmentParameterIndex(Map<MethodKey, Map<Integer, String>> methods) {
        this.methods = Map.copyOf(methods);
    }

    static ParchmentParameterIndex load() {
        InputStream resource = ParchmentParameterIndex.class.getResourceAsStream(RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Missing generated Parchment parameter index " + RESOURCE);
        }
        try (resource) {
            return read(resource);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read generated Parchment parameter index " + RESOURCE, failure);
        }
    }

    static ParchmentParameterIndex read(InputStream input) throws IOException {
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(input))) {
            if (stream.readInt() != MAGIC) {
                throw new IOException("Invalid Parchment parameter index magic");
            }
            int formatVersion = stream.readInt();
            if (formatVersion != FORMAT_VERSION) {
                throw new IOException("Unsupported Parchment parameter index version " + formatVersion);
            }

            stream.readUTF();
            stream.readUTF();
            int methodCount = stream.readInt();
            if (methodCount < 0) {
                throw new IOException("Negative Parchment method count " + methodCount);
            }

            Map<String, String> owners = new HashMap<>();
            Map<MethodKey, Map<Integer, String>> methods = new LinkedHashMap<>(methodCount);
            for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
                String owner = owners.computeIfAbsent(stream.readUTF(), value -> value);
                MethodKey key = new MethodKey(owner, stream.readUTF(), stream.readUTF());
                int parameterCount = stream.readInt();
                if (parameterCount <= 0) {
                    throw new IOException("Invalid parameter count " + parameterCount + " for " + key);
                }

                Map<Integer, String> parameters = new LinkedHashMap<>(parameterCount);
                for (int parameterIndex = 0; parameterIndex < parameterCount; parameterIndex++) {
                    int localVariableIndex = stream.readInt();
                    String previous = parameters.put(localVariableIndex, stream.readUTF());
                    if (previous != null) {
                        throw new IOException("Duplicate parameter index " + localVariableIndex + " for " + key);
                    }
                }
                if (methods.put(key, Map.copyOf(parameters)) != null) {
                    throw new IOException("Duplicate method " + key);
                }
            }
            return new ParchmentParameterIndex(methods);
        }
    }

    Map<Integer, String> find(String owner, String methodName, String descriptor) {
        return this.methods.getOrDefault(new MethodKey(owner, methodName, descriptor), Map.of());
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }
}
