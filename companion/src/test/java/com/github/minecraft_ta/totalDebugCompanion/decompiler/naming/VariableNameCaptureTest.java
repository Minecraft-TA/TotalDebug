package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.token.TextRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableNameCaptureTest {
    @Test
    void retainsFinalDecompilerRenamesAsAMethodScopedBackstop() {
        String owner = "net/minecraft/test/Owner";
        String descriptor = "()V";
        String source = "header\nsourceName\n";

        try (VariableNameCapture capture = VariableNameCapture.open(owner)) {
            VariableNameCapture.recordRuntimeName(owner, "run", descriptor, 10, "runtimeName");
            TextTokenVisitor visitor = VariableNameCapture.textTokenVisitor(TextTokenVisitor.EMPTY);
            visitor.start(source);
            visitor.visitLocal(
                    new TextRange(source.indexOf("sourceName"), "sourceName".length()),
                    true,
                    owner,
                    "run",
                    MethodDescriptor.parseDescriptor(descriptor),
                    7,
                    "sourceName"
            );

            assertEquals(
                    "sourceName",
                    capture.result(SourceLineMap.fromOriginalToDisplayed(new int[]{10, 2}))
                            .displayedName("run", descriptor, "runtimeName")
            );
        }
    }
}
