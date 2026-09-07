package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Carries typed completion metadata through the Microsoft adapter's small DAP item shape. */
public final class DebuggerCompletionWire {
    private static final String SEPARATOR = "\u001f";

    private DebuggerCompletionWire() {
    }

    public static String encode(DebuggerCompletionProposal proposal) {
        String detail = Base64.getUrlEncoder().withoutPadding().encodeToString(
                proposal.detail().getBytes(StandardCharsets.UTF_8)
        );
        return proposal.rank() + SEPARATOR + proposal.caretOffset() + SEPARATOR + detail;
    }

    public static Metadata decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Completion metadata is missing");
        }
        String[] parts = encoded.split(SEPARATOR, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Completion metadata is malformed");
        }
        String detail = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        return new Metadata(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), detail);
    }

    public record Metadata(int rank, int caretOffset, String detail) {
        public Metadata {
            if (rank < 0 || caretOffset < 0) {
                throw new IllegalArgumentException("Completion metadata is invalid");
            }
        }
    }
}
