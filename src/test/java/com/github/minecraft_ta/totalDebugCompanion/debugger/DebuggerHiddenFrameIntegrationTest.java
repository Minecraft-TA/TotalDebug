package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.MethodHandleDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.bytecodeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerHiddenFrameIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(30)
    void hiddenMethodHandleFramesPreserveThePausedStackAndLocals() throws Exception {
        Class<?> fixture = MethodHandleDebuggeeMain.class;
        Path sourcePath = Path.of("src/test/java", fixture.getName().replace('.', '/') + ".java").toAbsolutePath();
        String contents = Files.readString(sourcePath);
        int breakpointLine = contents.substring(0, contents.indexOf("// HIDDEN_FRAME_BREAK"))
                .split("\\R", -1).length;
        DebugEngine.Source source = new DebugEngine.Source(sourcePath.toUri(), fixture.getName(), contents);
        Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
        byte[] bytes = Files.readAllBytes(classes.resolve(fixture.getName().replace('.', '/') + ".class"));
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process child = new ProcessBuilder(java.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", classes.toString(), fixture.getName()).redirectErrorStream(true).start();
        try (ClassIndex index = ClassIndex.fromBytes(List.of(bytes));
             CompanionDecompilationService sources = new CompanionDecompilationService(
                     "hidden-frame-test", temporaryDirectory, bytecodeSource(List.of(classes), index));
             BufferedReader output = new BufferedReader(new InputStreamReader(
                     child.getInputStream(), StandardCharsets.UTF_8));
             DebuggerSessionController controller = new DebuggerSessionController(sources::loadDebugSource)) {
            CompletableFuture.runAsync(() -> {
                try {
                    String line;
                    while ((line = output.readLine()) != null) {
                        if (line.equals("ready")) return;
                    }
                    throw new AssertionError("Debuggee exited before ready");
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).get(10, TimeUnit.SECONDS);
            controller.acceptTarget(new DebugTargetDescriptor("fixture", "Method-handle debuggee", child.pid()));
            controller.toggleBreakpoint(source, breakpointLine).get(10, TimeUnit.SECONDS);
            controller.attach().get(10, TimeUnit.SECONDS);
            child.getOutputStream().write(1);
            child.getOutputStream().flush();

            DebuggerSessionController.Snapshot stopped = controller.waitUntilStopped(10_000);
            assertEquals(DebuggerSessionController.Phase.PAUSED, stopped.status().phase());
            assertFalse(stopped.pause().frames().isEmpty(), stopped.status().detail());
            assertNull(stopped.status().failure(), stopped.status().detail());
            assertTrue(stopped.pause().frames().stream().anyMatch(frame ->
                    frame.name().contains("LambdaForm$") && frame.name().contains("/0x")
                            && frame.sourceUri() == null), stopped.pause().frames().toString());
            DebugEngine.StackFrame top = stopped.pause().frames().getFirst();
            assertEquals(source.uri(), top.sourceUri());
            assertEquals(breakpointLine, top.line());
            assertTrue(stopped.pause().variables().stream().anyMatch(variable ->
                    variable.name().equals("value") && variable.value().equals("42")));
            DebugEngine.StackFrame caller = stopped.pause().frames().stream()
                    .filter(frame -> frame.name().contains(".main("))
                    .findFirst().orElseThrow();
            assertEquals(source.uri(), caller.sourceUri());
            assertFalse(controller.variablesForFrame(caller).get(10, TimeUnit.SECONDS).isEmpty());

            controller.resume().get(10, TimeUnit.SECONDS);
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
