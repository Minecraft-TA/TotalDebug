package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.evaluation.ScriptReferences;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;

import java.util.List;

/**
 * Whether a script links on this server: it refers to no client class, and every class, field and method it refers to
 * resolves through the classes the server loaded. See {@code docs/MOD_SIDES.md}, "Server scripts".
 */
final class ServerScriptLinks {
    /** Refused even on the integrated server, where they would resolve because it shares the client's JVM. */
    static final List<String> CLIENT = List.of("net.minecraft.client.", "net.neoforged.neoforge.client.",
            "com.mojang.blaze3d.", "com.github.minecraft_ta.totaldebug.client.");
    private static final int SHOWN = 20;

    private ServerScriptLinks() {
    }

    /** Why {@code bytecode} cannot run under {@code loader}, or empty when it links. */
    static String refusal(ScriptBytecode bytecode, ClassLoader loader) {
        ScriptReferences references;
        try {
            references = ScriptReferences.read(bytecode.classes());
        } catch (RuntimeException unreadable) {
            return "The script's bytecode could not be read: " + unreadable.getMessage();
        }
        List<String> client = references.classes().stream()
                .filter(name -> CLIENT.stream().anyMatch(name::startsWith))
                .sorted()
                .toList();
        if (!client.isEmpty()) return listed("Client classes are not available to server scripts:", client);
        List<String> unresolved = references.unresolved(loader);
        return unresolved.isEmpty() ? "" : listed("Not on the server:", unresolved);
    }

    private static String listed(String heading, List<String> names) {
        StringBuilder text = new StringBuilder(heading);
        names.stream().limit(SHOWN).forEach(name -> text.append("\n  ").append(name));
        if (names.size() > SHOWN) text.append("\n  and ").append(names.size() - SHOWN).append(" more");
        return text.toString();
    }
}
