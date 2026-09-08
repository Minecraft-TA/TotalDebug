package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

/** Ordered filesystem declarations, not an index or a claim about post-Mixin loaded definitions. */
public record ServerManifest(List<Source> sources, Map<String, Definition> classes) {
    public static final int MAX_COMPRESSED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DECODED_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CLASSES = 1_000_000;

    public record Source(String name, String archiveHash) {}
    public record Definition(int source, String declarationHash) {}

    public ServerManifest {
        sources = List.copyOf(sources);
        classes = Map.copyOf(classes);
        if (sources.size() > 4096 || classes.size() > MAX_CLASSES) {
            throw new IllegalArgumentException("Server manifest exceeds the source/class limit");
        }
        for (Definition definition : classes.values()) {
            if (definition.source() < 0 || definition.source() >= sources.size()) {
                throw new IllegalArgumentException("Invalid server class source");
            }
        }
    }

    public static ServerManifest scan(List<Path> paths) throws IOException {
        var sources = new ArrayList<Source>();
        var classes = new LinkedHashMap<String, Definition>();
        for (Path path : paths) {
            int source = sources.size();
            sources.add(new Source(path.getFileName().toString(),
                    Files.isDirectory(path) ? "" : ClassDeclarations.archiveFingerprint(path)));
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        String name = path.relativize(file).toString().replace('\\', '/');
                        if (classEntry(name)) add(classes, name, source, Files.readAllBytes(file));
                    }
                }
            } else {
                try (var jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.Version.parse("21"))) {
                    for (var entry : jar.versionedStream().filter(e -> classEntry(e.getName())).toList()) {
                        try (var input = jar.getInputStream(entry)) {
                            add(classes, entry.getName(), source, input.readAllBytes());
                        }
                    }
                }
            }
        }
        return new ServerManifest(sources, classes);
    }

    private static boolean classEntry(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class");
    }

    private static void add(Map<String, Definition> classes, String resource, int source, byte[] bytes) {
        String name = resource.substring(0, resource.length() - 6).replace('/', '.');
        if (!classes.containsKey(name)) classes.put(name, new Definition(source, ClassDeclarations.fingerprint(bytes)));
    }

    public void requireCompatible(String name, String localArchiveHash, byte[] localBytes) throws IOException {
        Definition definition = classes.get(name);
        if (definition == null) throw new IOException("Server compilation unsupported: class " + name + " is absent on the server");
        Source source = sources.get(definition.source());
        if (!localArchiveHash.isEmpty() && localArchiveHash.equals(source.archiveHash())) return;
        if (!definition.declarationHash().equals(ClassDeclarations.fingerprint(localBytes))) {
            throw new IOException("Server compilation unsupported: declarations differ for " + name
                    + " in " + source.name() + ". Compile using matching client/server classes.");
        }
    }

    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(1);
            output.writeInt(sources.size());
            for (Source source : sources) {
                output.writeUTF(source.name());
                output.writeUTF(source.archiveHash());
            }
            output.writeInt(classes.size());
            for (var entry : classes.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().source());
                output.write(HexFormat.of().parseHex(entry.getValue().declarationHash()));
            }
        }
        if (bytes.size() > MAX_COMPRESSED_BYTES) throw new IOException("Server manifest exceeds the transfer limit");
        return bytes.toByteArray();
    }

    public static ServerManifest decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_COMPRESSED_BYTES) throw new IOException("Server manifest exceeds the transfer limit");
        byte[] decoded;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            decoded = gzip.readNBytes(MAX_DECODED_BYTES + 1);
            if (decoded.length > MAX_DECODED_BYTES) throw new IOException("Server manifest exceeds the decoded limit");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(decoded))) {
            if (input.readInt() != 1) throw new IOException("Unsupported server manifest format");
            int sourceCount = input.readInt();
            if (sourceCount < 0 || sourceCount > 4096) throw new IOException("Invalid server source count");
            var sources = new ArrayList<Source>();
            for (int i = 0; i < sourceCount; i++) sources.add(new Source(input.readUTF(), input.readUTF()));
            int count = input.readInt();
            if (count < 0 || count > MAX_CLASSES) throw new IOException("Invalid server class count");
            var classes = new LinkedHashMap<String, Definition>();
            for (int i = 0; i < count; i++) {
                String name = input.readUTF();
                int source = input.readInt();
                byte[] hash = new byte[32];
                input.readFully(hash);
                if (classes.putIfAbsent(name, new Definition(source, HexFormat.of().formatHex(hash))) != null) {
                    throw new IOException("Duplicate server class " + name);
                }
            }
            if (input.read() != -1) throw new IOException("Trailing server manifest data");
            try { return new ServerManifest(sources, classes); }
            catch (IllegalArgumentException exception) { throw new IOException("Invalid server manifest", exception); }
        }
    }
}
