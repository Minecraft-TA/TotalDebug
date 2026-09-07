package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptTypeResolverTest {
    private final StandardJavaFileManager classpath = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null);
    private final ScriptTypeResolver resolver = new ScriptTypeResolver(Map.of(), this.classpath);

    @AfterEach
    void closeClasspath() throws IOException {
        this.classpath.close();
    }

    @ParameterizedTest
    @CsvSource({
            "[I, [J, java/lang/Object",
            "[[I, [[J, [Ljava/lang/Object;",
            "[[I, [Ljava/lang/Object;, [Ljava/lang/Object;",
            "[Ljava/lang/String;, [Ljava/lang/Integer;, [Ljava/lang/Object;",
            "[[Ljava/lang/String;, [Ljava/lang/Integer;, [Ljava/lang/Object;",
            "[Ljava/lang/String;, java/lang/Cloneable, java/lang/Cloneable",
            "[Ljava/lang/String;, java/io/Serializable, java/io/Serializable",
            "[I, [I, [I",
            "[I, [Ljava/lang/String;, java/lang/Object",
            "java/util/ArrayList, java/util/LinkedList, java/util/AbstractList",
            "java/util/List, java/util/ArrayList, java/util/List",
            "java/lang/String, java/lang/Integer, java/lang/Object"
    })
    void resolvesArrayAndInterfaceMergesSymmetrically(String first, String second, String expected) {
        assertEquals(expected, this.resolver.commonSuperClass(first, second));
        assertEquals(expected, this.resolver.commonSuperClass(second, first));
    }

    @Test
    void resolvesGeneratedOneLetterClassNamesWithoutTreatingThemAsPrimitiveDescriptors() {
        ScriptTypeResolver types = new ScriptTypeResolver(
                Map.of("I", emptyClass("I", "java/lang/Object"), "J", emptyClass("J", "I")),
                this.classpath);
        assertEquals("I", types.commonSuperClass("I", "J"));
        assertEquals("[LI;", types.commonSuperClass("[LI;", "[LJ;"));
    }

    @Test
    void reportsTheMissingTypeInsteadOfLoadingIt() {
        ScriptBytecodeTransformer.TransformationException exception = assertThrows(
                ScriptBytecodeTransformer.TransformationException.class,
                () -> this.resolver.commonSuperClass("missing/Type", "java/lang/String"));
        assertTrue(exception.getMessage().contains("Compiler classpath is missing missing.Type"));
    }

    private static byte[] emptyClass(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, parent, null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
