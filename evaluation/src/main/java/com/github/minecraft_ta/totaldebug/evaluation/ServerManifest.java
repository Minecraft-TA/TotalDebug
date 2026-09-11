package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

/** Ordered archive baseline. Class declarations are read only for requested sources. */
public record ServerManifest(List<Source> sources) {
    public static final int MAX_COMPRESSED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SOURCES = 4096;
    private static final int MAX_DECODED_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CLASSES = 1_000_000;

    public record Source(String name, String archiveHash) {
        public Source {
            if (name.length() > 4096 || !archiveHash.matches("(?:[0-9a-f]{64})?")) {
                throw new IllegalArgumentException("Invalid server source");
            }
        }
    }

    public ServerManifest {
        sources = List.copyOf(sources);
        if (sources.size() > MAX_SOURCES) throw new IllegalArgumentException("Too many server sources");
    }

    public static ServerManifest scan(List<Path> paths) throws IOException {
        var sources = new ArrayList<Source>();
        for (Path path : paths) {
            sources.add(new Source(path.getFileName().toString(),
                    Files.isDirectory(path) ? "" : ClassDeclarations.archiveFingerprint(path)));
        }
        return new ServerManifest(sources);
    }

    /** Shared for one server runtime, accessed on the manifest worker. Retains encoded details once per source. */
    public static final class Catalog {
        private final List<Path> paths;
        private final byte[] baseline;
        private final Map<Integer, byte[]> details = new HashMap<>();

        public Catalog(List<Path> paths) throws IOException {
            this.paths = List.copyOf(paths);
            this.baseline = scan(paths).encode();
        }

        public byte[] baseline() { return baseline; }

        public synchronized byte[] details(int source) throws IOException {
            if (source < 0 || source >= paths.size()) throw new IOException("Unknown server source " + source);
            byte[] bytes = details.get(source);
            if (bytes == null) {
                bytes = encodeDetails(readClasses(paths.get(source), true));
                details.put(source, bytes);
            }
            return bytes;
        }
    }

    /** With declarations=false this reads entry names only, never class bodies. */
    public static Map<String, String> readClasses(Path path, boolean declarations) throws IOException {
        var classes = new LinkedHashMap<String, String>();
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String resource = path.relativize(file).toString().replace('\\', '/');
                    if (classEntry(resource)) classes.put(binaryName(resource),
                            declarations ? ClassDeclarations.fingerprint(Files.readAllBytes(file)) : "");
                }
            }
        } else {
            try (var jar = openArchive(path)) {
                for (var entry : jar.versionedStream().filter(e -> classEntry(e.getName())).toList()) {
                    String hash = "";
                    if (declarations) {
                        try (var input = jar.getInputStream(entry)) {
                            hash = ClassDeclarations.fingerprint(input.readAllBytes());
                        }
                    }
                    classes.put(binaryName(entry.getName()), hash);
                }
            }
        }
        if (classes.size() > MAX_CLASSES) throw new IOException("Too many source classes");
        return classes;
    }

    private static JarFile openArchive(Path path) throws IOException {
        return new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.Version.parse("21"));
    }

    private static boolean classEntry(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class");
    }

    private static String binaryName(String resource) {
        return resource.substring(0, resource.length() - 6).replace('/', '.');
    }

    public byte[] encode() throws IOException {
        return encode(output -> {
            output.writeInt(sources.size());
            for (Source source : sources) {
                output.writeUTF(source.name());
                output.writeUTF(source.archiveHash());
            }
        });
    }

    public static ServerManifest decode(byte[] bytes) throws IOException {
        try (var input = input(bytes)) {
            int count = count(input, MAX_SOURCES);
            var sources = new ArrayList<Source>();
            for (int i = 0; i < count; i++) sources.add(new Source(input.readUTF(), input.readUTF()));
            end(input);
            return new ServerManifest(sources);
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid server baseline", exception); }
    }

    public static byte[] encodeDetails(Map<String, String> classes) throws IOException {
        if (classes.size() > MAX_CLASSES) throw new IOException("Too many source classes");
        return encode(output -> {
            output.writeInt(classes.size());
            for (var entry : classes.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                output.writeUTF(entry.getKey());
                byte[] hash = HexFormat.of().parseHex(entry.getValue());
                if (hash.length != 32) throw new IOException("Invalid declaration fingerprint");
                output.write(hash);
            }
        });
    }

    public static Map<String, String> decodeDetails(byte[] bytes) throws IOException {
        try (var input = input(bytes)) {
            int count = count(input, MAX_CLASSES);
            var classes = new HashMap<String, String>();
            for (int i = 0; i < count; i++) {
                String name = input.readUTF();
                byte[] hash = new byte[32];
                input.readFully(hash);
                if (classes.putIfAbsent(name, HexFormat.of().formatHex(hash)) != null) {
                    throw new IOException("Duplicate server class " + name);
                }
            }
            end(input);
            return classes;
        }
    }

    @FunctionalInterface
    private interface Encoder { void write(DataOutputStream output) throws IOException; }

    private static byte[] encode(Encoder encoder) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(2);
            encoder.write(output);
        }
        if (bytes.size() > MAX_COMPRESSED_BYTES) throw new IOException("Server metadata exceeds the transfer limit");
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] bytes) throws IOException {
        if (bytes.length > MAX_COMPRESSED_BYTES) throw new IOException("Server metadata exceeds the transfer limit");
        byte[] decoded;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            decoded = gzip.readNBytes(MAX_DECODED_BYTES + 1);
            if (decoded.length > MAX_DECODED_BYTES) throw new IOException("Server metadata exceeds the decoded limit");
        }
        var input = new DataInputStream(new ByteArrayInputStream(decoded));
        if (input.readInt() != 2) throw new IOException("Unsupported server metadata format");
        return input;
    }

    private static int count(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid server metadata count");
        return count;
    }

    private static void end(DataInputStream input) throws IOException {
        if (input.read() != -1) throw new IOException("Trailing server metadata");
    }
}
