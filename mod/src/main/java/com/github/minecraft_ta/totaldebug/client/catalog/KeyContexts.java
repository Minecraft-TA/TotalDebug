package com.github.minecraft_ta.totaldebug.client.catalog;

import net.neoforged.neoforge.client.settings.IKeyConflictContext;

import java.util.Map;

/** Stable ids and readable names for key conflict contexts, which mods implement as enums or plain objects. */
final class KeyContexts {
    private KeyContexts() {
    }

    /**
     * {@code net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME} for an enum constant; otherwise the
     * class name and how many contexts of that class came before, such as {@code mod.CustomContext#2}.
     */
    static String id(IKeyConflictContext context, Map<String, Integer> perClass) {
        if (context instanceof Enum<?> constant) return constant.getDeclaringClass().getName() + "." + constant.name();
        String type = context.getClass().getName();
        int index = perClass.merge(type, 1, Integer::sum) - 1;
        return type + "#" + index;
    }

    /** The context's own name, or its class's simple name when it only has the default object text. */
    static String name(IKeyConflictContext context) {
        if (context instanceof Enum<?> constant) return constant.name();
        String text = String.valueOf(context);
        if (!text.contains("@")) return text;
        String type = context.getClass().getName();
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
