package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClassSearchTest {
    @AfterEach
    void closeIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void resolvesExactBinaryNamesAndSearchesInsideQualifiedPackages() throws Exception {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(
                classBytes(ExactFixture.class),
                classBytes(QualifiedSearchFixture.class)
        )));

        Map<String, Object> exact = CompanionMcpServer.searchClasses(ExactFixture.class.getName(), 10);
        List<?> exactClasses = (List<?>) exact.get("classes");
        assertEquals(1, exactClasses.size());
        Map<?, ?> exactClass = (Map<?, ?>) exactClasses.getFirst();
        assertEquals(ExactFixture.class.getName(), exactClass.get("binary_name"));
        assertFalse(exactClass.containsKey("access_flags"));
        assertFalse(exactClass.containsKey("source_name"));

        String qualifiedPrefix = McpClassSearchTest.class.getPackageName()
                + ".McpClassSearchTest$Qualified";
        Map<String, Object> qualified = CompanionMcpServer.searchClasses(qualifiedPrefix, 10);
        List<?> qualifiedClasses = (List<?>) qualified.get("classes");
        assertEquals(1, qualifiedClasses.size());
        assertEquals(
                QualifiedSearchFixture.class.getName(),
                ((Map<?, ?>) qualifiedClasses.getFirst()).get("binary_name")
        );

        assertFalse(qualified.containsKey("query"));
        assertFalse(qualified.containsKey("count"));
    }

    @Test
    void decodesClassAccessFlagsInsteadOfReturningAnInteger() {
        int flags = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD | Opcodes.ACC_SYNTHETIC;

        assertEquals("record", CompanionMcpServer.classKind(flags));
        assertEquals(List.of("public", "final", "synthetic"), CompanionMcpServer.classModifiers(flags));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing class resource " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static final class ExactFixture {
    }

    private static final class QualifiedSearchFixture {
    }
}
