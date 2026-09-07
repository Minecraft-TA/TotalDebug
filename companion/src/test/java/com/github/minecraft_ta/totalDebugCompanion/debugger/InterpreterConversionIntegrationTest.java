package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.InterpreterConversionDebuggee;
import com.github.minecraft_ta.totalDebugCompanion.debugger.harness.DebuggerTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class InterpreterConversionIntegrationTest {
    record Case(String source, Object expected) { }

    static Stream<Case> conversions() {
        Integer boxed = 7;
        Boolean flag = true;
        Object text = "text";
        Object[] array = new String[]{"one", "two"};
        return Stream.of(
                new Case("choose((Object) null)", InterpreterConversionDebuggee.choose((Object) null)),
                new Case("choose((Object) \"text\")", InterpreterConversionDebuggee.choose((Object) "text")),
                new Case("choose(text)", InterpreterConversionDebuggee.choose(text)),
                new Case("choose(objectText())", InterpreterConversionDebuggee.choose(InterpreterConversionDebuggee.objectText())),
                new Case("choose(null)", InterpreterConversionDebuggee.choose(null)),
                new Case("arguments((Object) array)", InterpreterConversionDebuggee.arguments((Object) array)),
                new Case("arguments(array)", InterpreterConversionDebuggee.arguments(array)),
                new Case("boxed + 1", boxed + 1),
                new Case("1 + boxed", 1 + boxed),
                new Case("-boxed", -boxed),
                new Case("~boxed", ~boxed),
                new Case("boxed << 2", boxed << 2),
                new Case("boxed < 8", boxed < 8),
                new Case("boxed == 7", boxed == 7),
                new Case("boxed == boxed", boxed == boxed),
                new Case("!flag", !flag),
                new Case("flag && true", flag && true),
                new Case("flag & false", flag & false),
                new Case("flag ? 1 : 2", flag ? 1 : 2),
                new Case("(long) boxed", (long) boxed),
                new Case("\"\" + boxed", "" + boxed),
                new Case("\"\" + flag", "" + flag),
                new Case("\"\" + text", "" + text),
                new Case("nullText() + 1", InterpreterConversionDebuggee.nullText() + 1)
        );
    }

    static Stream<Case> conditionals() {
        boolean yes = true;
        byte small = 3;
        long wide = 2L;
        return Stream.of(
                new Case("true ? 1 : 2L", yes ? 1 : 2L),
                new Case("false ? 1L : 2", !yes ? 1L : 2),
                new Case("true ? 1 : 2.5f", yes ? 1 : 2.5f),
                new Case("true ? 1f : 2.5", yes ? 1f : 2.5),
                new Case("true ? small : wide", yes ? small : wide),
                new Case("false ? small : 1 + 2", !yes ? small : 1 + 2),
                new Case("false ? small : 128", !yes ? small : 128),
                new Case("true ? (byte) 1 : (short) 2", yes ? (byte) 1 : (short) 2),
                new Case("true ? 1 : wideEffect()", yes ? 1 : InterpreterConversionDebuggee.wideEffect()),
                new Case("choose(true ? 1 : 2L)", InterpreterConversionDebuggee.choose(yes ? 1 : 2L)),
                new Case("choose(true ? \"text\" : (Object) null)", InterpreterConversionDebuggee.choose(yes ? "text" : (Object) null)),
                new Case("choose(true ? boxed : boxed)", InterpreterConversionDebuggee.choose(yes ? Integer.valueOf(7) : Integer.valueOf(7))),
                new Case("choose(true ? boxed : 1)", InterpreterConversionDebuggee.choose(yes ? Integer.valueOf(7) : 1)),
                new Case("true ? flag : false", yes ? Boolean.TRUE : false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"conversions", "conditionals"})
    void matchesCompiledJava(Case test) throws Exception {
        withFrame((engine, frame) -> {
            var actual = engine.evaluate(test.source(), frame.id()).get(10, TimeUnit.SECONDS);
            String expectedType = test.expected() instanceof String ? "java.lang.String"
                    : test.expected() instanceof Boolean ? "boolean"
                    : test.expected() instanceof Long ? "long"
                    : test.expected() instanceof Float ? "float"
                    : test.expected() instanceof Double ? "double"
                    : test.expected() instanceof Byte ? "byte"
                    : test.expected() instanceof Short ? "short" : "int";
            String expectedValue = test.expected() instanceof String ? "\"" + test.expected() + "\"" : test.expected().toString();
            assertEquals(expectedType, actual.type(), test.source());
            if (test.expected() instanceof Float || test.expected() instanceof Double) {
                assertEquals(String.format("%f", ((Number) test.expected()).doubleValue()), actual.value(), test.source());
            } else assertEquals(expectedValue, actual.value(), test.source());
            assertEquals("0", engine.evaluate("calls", frame.id()).get().value(), "An unselected branch ran");
        });
    }

    @Test
    void nullUnboxingFailsBeforeEvaluatingTheRightOperand() throws Exception {
        withFrame((engine, frame) -> {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.evaluate("absent + effect()", frame.id()).get(10, TimeUnit.SECONDS));
            assertInstanceOf(NullPointerException.class, failure.getCause());
            assertEquals("0", engine.evaluate("calls", frame.id()).get().value());
        });
    }

    private static void withFrame(Assertion assertion) throws Exception {
        try (var harness = DebuggerTestHarness.launch(InterpreterConversionDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("CONVERSION_STOP")));
            harness.start();
            var frame = harness.firstFrame(harness.awaitStop("conversion fixture").threadId());
            assertion.run(harness.engine(), frame);
        }
    }

    private interface Assertion { void run(DebugEngine engine, DebugEngine.StackFrame frame) throws Exception; }
}
