package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The link check against the JVM, for classes that changed between the client and the server. */
class ScriptLinkCheckTypesTest {
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
    void theCheckAgreesWithTheJvm(Probe probe) throws Exception {
        LinkCheckProbe.Outcome outcome = LinkCheckProbe.run(this.directory, probe.client(), probe.server(), List.of(),
                probe.source(), "Probe");
        if (probe.failure() == null) assertNull(outcome.failure(), outcome.toString());
        else assertTrue(probe.failure().isInstance(outcome.failure()), outcome.toString());
        assertEquals(probe.failure() != null, !outcome.unresolved().isEmpty(), outcome.toString());
    }
}
