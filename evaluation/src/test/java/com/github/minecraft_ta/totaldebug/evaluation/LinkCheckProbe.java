package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a script against client classes, checks it against server classes, and runs the same bytecode there, so a
 * test compares the link check with the JVM.
 */
final class LinkCheckProbe {
    /** What the check named, and what running the script returned or threw. */
    record Outcome(List<String> unresolved, Object value, Throwable failure) {
        /** Whether running failed as linking fails, possibly wrapped. */
        boolean failedToLink() {
            for (Throwable current = this.failure; current != null; current = current.getCause()) {
                if (current instanceof LinkageError || current instanceof ReflectiveOperationException) return true;
            }
            return false;
        }
    }

    private LinkCheckProbe() {
    }

    /**
     * {@code client} and {@code server} are the sources of {@code fixture.Api}, empty for none, and the server's classes
     * named in {@code removed} are left out. {@code script} is compiled against the client's; its class {@code entry} has
     * a static {@code run()}.
     */
    static Outcome run(Path directory, String client, String server, List<String> removed, String script, String entry)
            throws Exception {
        Map<String, byte[]> bytecode;
        Map<String, byte[]> serverClasses = new HashMap<>();
        try (var compiler = new InMemoryJavaCompiler()) {
            String classpath = "";
            if (!client.isEmpty()) {
                Path classes = Files.createTempDirectory(directory, "client-");
                write(classes, compiler.compile(client, "fixture.Api", ""));
                classpath = classes.toString();
            }
            bytecode = compiler.compile(script, entry, classpath);
            if (!server.isEmpty()) serverClasses.putAll(compiler.compile(server, "fixture.Api", ""));
        }
        removed.forEach(serverClasses::remove);
        ClassLoader runtime = new ScriptClassLoader(LinkCheckProbe.class.getClassLoader(), serverClasses);
        List<String> unresolved = ScriptReferences.read(bytecode).unresolved(runtime);
        try {
            Object value = new ScriptClassLoader(runtime, bytecode).loadClass(entry).getMethod("run").invoke(null);
            return new Outcome(unresolved, value, null);
        } catch (InvocationTargetException failure) {
            return new Outcome(unresolved, null, failure.getCause());
        } catch (LinkageError failure) {
            return new Outcome(unresolved, null, failure);
        }
    }

    private static void write(Path directory, Map<String, byte[]> classes) throws IOException {
        for (var definition : classes.entrySet()) {
            Path target = directory.resolve(definition.getKey().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, definition.getValue());
        }
    }
}
