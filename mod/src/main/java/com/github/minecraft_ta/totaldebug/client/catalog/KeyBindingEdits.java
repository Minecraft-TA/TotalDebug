package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.protocol.message.KeyBindingResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetKeyBindingPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.settings.KeyModifier;

/**
 * Puts a key binding on a key the way the controls screen does: the key and modifier change, the key lookup is
 * rebuilt and {@code options.txt} is saved.
 */
public final class KeyBindingEdits {
    private KeyBindingEdits() {
    }

    /** Applies a request and answers with the binding before and after, or why it stayed. Render thread only. */
    public static KeyBindingResultPayload apply(SetKeyBindingPayload request) {
        Options options = Minecraft.getInstance().options;
        KeyMapping mapping = null;
        for (KeyMapping candidate : options.keyMappings) {
            if (candidate.getName().equals(request.name())) {
                mapping = candidate;
                break;
            }
        }
        if (mapping == null) return failed(request, request.name() + " is not a key binding of this game");
        String previousKey = mapping.getKey().getName();
        String previousModifier = mapping.getKeyModifier().name();
        InputConstants.Key key;
        KeyModifier modifier;
        try {
            key = InputConstants.getKey(request.key());
        } catch (IllegalArgumentException unknown) {
            return failed(request, request.key() + " is not a key of this game");
        }
        try {
            modifier = KeyModifier.valueOf(request.modifier());
        } catch (IllegalArgumentException unknown) {
            return failed(request, request.modifier() + " is not a key modifier");
        }
        mapping.setKeyModifierAndCode(modifier, key);
        KeyMapping.resetMapping();
        options.save();
        return new KeyBindingResultPayload(request.requestId(), request.name(), previousKey, previousModifier,
                mapping.getKey().getName(), mapping.getKeyModifier().name(), "");
    }

    private static KeyBindingResultPayload failed(SetKeyBindingPayload request, String error) {
        return new KeyBindingResultPayload(request.requestId(), request.name(), "", "", "", "", error);
    }
}
