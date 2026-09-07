package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebuggerCompletionWireTest {
    @Test
    void preservesTypedMetadataAcrossTheAdapterItem() {
        DebuggerCompletionProposal proposal = new DebuggerCompletionProposal(
                "overload(java.lang.String)", "overload()", DebuggerCompletionProposal.Kind.METHOD,
                "java.lang.String", 12, 16, 9, 31
        );

        DebuggerCompletionWire.Metadata metadata = DebuggerCompletionWire.decode(
                DebuggerCompletionWire.encode(proposal)
        );

        assertEquals(31, metadata.rank());
        assertEquals(9, metadata.caretOffset());
        assertEquals("java.lang.String", metadata.detail());
    }
}
