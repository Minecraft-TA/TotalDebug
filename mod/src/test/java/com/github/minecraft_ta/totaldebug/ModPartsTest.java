package com.github.minecraft_ta.totaldebug;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule of docs/MOD_SIDES.md: outside the client part, no class of the mod refers to the client. */
class ModPartsTest {
    private static final String ROOT = "com/github/minecraft_ta/totaldebug/";
    private static final String CLIENT_PART = ROOT + "client/";
    private static final List<String> CLIENT = List.of("net/minecraft/client/", CLIENT_PART);

    @Test
    void noClassOutsideTheClientPartRefersToTheClient() throws Exception {
        Map<String, byte[]> classes = classes();
        assertTrue(classes.containsKey(ROOT + "TotalDebug.class"), "the mod's classes are found");
        List<String> found = new ArrayList<>();
        classes.forEach((name, bytes) -> {
            if (name.startsWith(CLIENT_PART)) return;
            // Class and member names sit in the constant pool as they are written, so a reference shows in the bytes.
            String pool = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String client : CLIENT) {
                if (pool.contains(client)) found.add(name + " refers to " + client);
            }
        });
        Collections.sort(found);
        assertEquals(List.of(), found);
    }

    @Test
    void theClientLeavesTheIntegratedServerToItsServerPart() throws Exception {
        List<String> found = new ArrayList<>();
        classes().forEach((name, bytes) -> {
            // Naming the world the game plays by its folder is the one thing the client reads there.
            if (!name.startsWith(CLIENT_PART) || name.equals(CLIENT_PART + "world/Playing.class")) return;
            if (new String(bytes, StandardCharsets.ISO_8859_1).contains("getSingleplayerServer")) found.add(name);
        });
        assertEquals(List.of(), found, "the world's data, datapacks and rules are its server's, reached through the relay");
    }

    /** The mod's compiled classes by path, from the folder or jar that holds {@link TotalDebug}. */
    private static Map<String, byte[]> classes() throws IOException, URISyntaxException {
        URL self = TotalDebug.class.getResource("TotalDebug.class");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (self != null && self.getProtocol().equals("jar")) {
            try (JarFile jar = ((JarURLConnection) self.openConnection()).getJarFile()) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (!entry.getName().startsWith(ROOT) || !entry.getName().endsWith(".class")) continue;
                    try (InputStream input = jar.getInputStream(entry)) {
                        classes.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
            return classes;
        }
        Path root = Path.of(self.toURI()).getParent();
        for (int depth = 0; depth < ROOT.split("/").length; depth++) root = root.getParent();
        Path top = root;
        try (Stream<Path> files = Files.walk(top.resolve(ROOT))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                classes.put(top.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
