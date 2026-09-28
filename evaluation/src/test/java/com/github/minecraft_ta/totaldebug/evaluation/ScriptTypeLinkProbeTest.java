package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Differential review probes: compile against the client, then check and execute against the server. */
class ScriptTypeLinkProbeTest {
    @TempDir Path directory;

    record Probe(String name, String client, String server, String source, Class<? extends Throwable> failure) {
        @Override public String toString() { return name; }
    }

    static Stream<Probe> probes() {
        String concrete = "package fixture; public class Api {}";
        String allocation = "return new Api();";
        return Stream.of(
                probe("unchanged concrete allocation", concrete, concrete, allocation, null),
                probe("allocation became abstract", concrete, "package fixture; public abstract class Api {}", allocation, InstantiationException.class),
                probe("cast type became package private", concrete, "package fixture; class Api {}", "return (Api) new Object();", IllegalAccessError.class),
                probe("class literal became package private", concrete, "package fixture; class Api {}", "return Api.class;", IllegalAccessError.class),
                probe("array component became package private", concrete, "package fixture; class Api {}", "return new Api[0];", IllegalAccessError.class),
                probe("instanceof type became package private", concrete, "package fixture; class Api {}", "return new Object() instanceof Api;", IllegalAccessError.class),
                probe("allocation type became package private", concrete, "package fixture; class Api {}", allocation, IllegalAccessError.class),
                new Probe("return hierarchy changed", "package fixture; import java.util.ArrayList; public class Api extends ArrayList<Object> {}", concrete,
                        "import fixture.Api; import java.util.ArrayList; public class Probe { public static ArrayList<?> run() { return new Api(); } }", VerifyError.class),
                new Probe("superclass became final", concrete, "package fixture; public final class Api {}",
                        "import fixture.Api; public class Probe extends Api { public static Object run() { return new Probe(); } }", IncompatibleClassChangeError.class),
                new Probe("implemented interface became class", "package fixture; public interface Api {}", concrete,
                        "import fixture.Api; public class Probe implements Api { public static Object run() { return new Probe(); } }", IncompatibleClassChangeError.class),
                new Probe("super constructor became private", concrete, "package fixture; public class Api { private Api() {} }",
                        "import fixture.Api; public class Probe extends Api { public static Object run() { return new Probe(); } }", IllegalAccessError.class),
                new Probe("protected superclass constructor remains valid", "package fixture; public class Api { protected Api() {} }", "package fixture; public class Api { protected Api() {} }",
                        "import fixture.Api; public class Probe extends Api { public static Object run() { return new Probe(); } }", null),
                probe("check must not initialize application classes", "package fixture; public class Api { static { if (true) throw new AssertionError(\"initialized\"); } }",
                        "package fixture; public class Api { static { if (true) throw new AssertionError(\"initialized\"); } }", "return Api.class;", null)
        );
    }

    private static Probe probe(String name, String client, String server, String body, Class<? extends Throwable> failure) {
        return new Probe(name, client, server, "import fixture.Api; public class Probe { public static Object run() { " + body + " } }", failure);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    void preflightMatchesActualLinkage(Probe probe) throws Exception {
        Path client = compileFixture("client", probe.client());
        Path server = compileFixture("server", probe.server());
        try (var compiler = new InMemoryJavaCompiler();
             var target = new URLClassLoader(new URL[]{server.toUri().toURL()}, getClass().getClassLoader())) {
            var bytecode = compiler.compile(probe.source(), "Probe", client.toString());
            List<String> unresolved = ScriptReferences.read(bytecode).unresolved(target);
            Throwable failure = null;
            try {
                new ScriptClassLoader(target, bytecode).loadClass("Probe").getMethod("run").invoke(null);
            } catch (InvocationTargetException exception) {
                failure = exception.getCause();
            } catch (LinkageError exception) {
                failure = exception;
            }
            String observed = probe.name() + ": preflight=" + unresolved + ", execution=" + failure;
            System.out.println(observed);
            if (probe.failure() == null) assertEquals(null, failure, observed);
            else assertTrue(probe.failure().isInstance(failure), observed);
            assertEquals(probe.failure() != null, !unresolved.isEmpty(), observed);
        }
    }

    private Path compileFixture(String name, String source) throws Exception {
        Path output = Files.createDirectories(directory.resolve(name));
        Path file = output.resolve("Api.java");
        Files.writeString(file, source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", output.toString(), file.toString()));
        return output;
    }
}
