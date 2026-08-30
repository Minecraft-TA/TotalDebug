package com.github.minecraft_ta.totalDebugCompanion.jdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseScriptSourceTest {
    @Test
    void runtimeSourceIsInternalAndUsesTheObjectReturnContract() {
        String source = BaseScript.getText();

        assertTrue(source.contains("private final StringWriter logWriter"));
        assertTrue(source.contains("public abstract Object run() throws Throwable"));
        assertFalse(source.contains("resultValue"));
        assertFalse(source.contains("resultSet"));
        assertFalse(source.contains("void result(Object"));
    }

    @Test
    void mergesEditorSourceWithTheInternalRuntimeSource() {
        String merged = BaseScript.mergeWithNormalScript("""
                public class Proof extends BaseScript {
                    public Object run() {
                        return 42;
                    }
                }
                """);

        assertTrue(merged.contains("public class Proof extends BaseScript"));
        assertTrue(merged.contains("abstract class BaseScript"));
        assertTrue(merged.indexOf("import net.minecraft") < merged.indexOf("public class Proof"));
    }
}
