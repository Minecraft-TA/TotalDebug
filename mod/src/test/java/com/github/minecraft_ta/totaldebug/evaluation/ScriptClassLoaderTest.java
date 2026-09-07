package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ScriptClassLoaderTest {
    private final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();

    @Test
    void definesEveryClassFromOneCompilationAndAllowsTheSameNamesInAnotherRun() throws Exception {
        String firstSource = sourceReturning(1);
        String secondSource = sourceReturning(2);

        Map<String, byte[]> firstBytes = this.compiler.compile(firstSource, "LoaderFixture", "");
        Map<String, byte[]> secondBytes = this.compiler.compile(secondSource, "LoaderFixture", "");
        ScriptClassLoader firstLoader = new ScriptClassLoader(getClass().getClassLoader(), firstBytes);
        ScriptClassLoader secondLoader = new ScriptClassLoader(getClass().getClassLoader(), secondBytes);

        Class<?> firstClass = firstLoader.loadClass("LoaderFixture");
        Class<?> secondClass = secondLoader.loadClass("LoaderFixture");

        assertNotSame(firstClass, secondClass);
        assertNotSame(firstClass.getClassLoader(), secondClass.getClassLoader());
        assertEquals(1, firstClass.getMethod("value").invoke(firstClass.getConstructor().newInstance()));
        assertEquals(2, secondClass.getMethod("value").invoke(secondClass.getConstructor().newInstance()));
        assertEquals(firstLoader, firstLoader.loadClass("LoaderHelper").getClassLoader());
    }

    private static String sourceReturning(int value) {
        return """
                public class LoaderFixture {
                    public int value() {
                        return LoaderHelper.value();
                    }
                }
                class LoaderHelper {
                    static int value() {
                        return %d;
                    }
                }
                """.formatted(value);
    }
}
