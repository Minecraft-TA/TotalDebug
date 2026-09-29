package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.client.companion.ClientChanges;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.util.concurrent.CompletableFuture;

/**
 * Puts key bindings on keys the way the controls screen does: the key and modifier change, the key lookup is rebuilt
 * and {@code options.txt} is saved. A binding is named as in {@code options.txt}, such as {@code key.jump}, and its
 * value written as there: the key, with {@code :} and the modifier when there is one, such as
 * {@code key.keyboard.g:CONTROL}. Client thread only.
 */
public final class KeyBindingEdits implements ClientChanges.Category {
    public static final String CATEGORY = "keyBinding";

    @Override
    public String read(String name) {
        KeyMapping mapping = mapping(name);
        KeyModifier modifier = mapping.getKeyModifier();
        return mapping.getKey().getName() + (modifier == KeyModifier.NONE ? "" : ":" + modifier.name());
    }

    @Override
    public void check(String name, String value) {
        mapping(name);
        key(value);
        modifier(value);
    }

    @Override
    public void set(String name, String value) {
        mapping(name).setKeyModifierAndCode(modifier(value), key(value));
    }

    @Override
    public CompletableFuture<Void> finish() {
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        return CompletableFuture.completedFuture(null);
    }

    private static KeyMapping mapping(String name) {
        for (KeyMapping candidate : Minecraft.getInstance().options.keyMappings) {
            if (candidate.getName().equals(name)) return candidate;
        }
        throw new IllegalArgumentException(name + " is not a key binding of this game");
    }

    private static InputConstants.Key key(String value) {
        String key = value.split(":", 2)[0];
        try {
            return InputConstants.getKey(key);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(key + " is not a key of this game");
        }
    }

    private static KeyModifier modifier(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length < 2) return KeyModifier.NONE;
        try {
            return KeyModifier.valueOf(parts[1]);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(parts[1] + " is not a key modifier");
        }
    }
}
