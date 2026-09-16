package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class IndexedParameterNamesTest {
    @Test void replacesTheCacheWithTheRuntimeAndProtectsCachedArrays() {
        try (var first = ClassIndex.fromBytes(List.of(fixture("firstName")));
             var second = ClassIndex.fromBytes(List.of(fixture("secondName")))) {
            CompanionClassIndex.set(first);
            String[] names = CompanionClassIndex.parameterNames("Names", "accept", "(I)V");
            assertArrayEquals(new String[]{"firstName"}, names);
            names[0] = "changed";
            assertArrayEquals(new String[]{"firstName"}, CompanionClassIndex.parameterNames("Names", "accept", "(I)V"));
            CompanionClassIndex.set(second);
            assertArrayEquals(new String[]{"secondName"}, CompanionClassIndex.parameterNames("Names", "accept", "(I)V"));
        } finally {
            CompanionClassIndex.clear();
        }
    }

    private static byte[] fixture(String parameter) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "Names", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "accept", "(I)V", null, null);
        method.visitParameter(parameter, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
