package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyBindingsTest {
    private static final String IN_GAME = KeyBindings.IN_GAME;
    private static final String GUI = "net.neoforged.neoforge.client.settings.KeyConflictContext.GUI";
    private static final String UNIVERSAL = "net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL";
    /** NeoForge's own contexts: in game and screens exclude each other, anywhere conflicts with both. */
    private static final List<PackCatalog.KeyContext> CONTEXTS = List.of(
            new PackCatalog.KeyContext(IN_GAME, "IN_GAME", List.of(IN_GAME, UNIVERSAL)),
            new PackCatalog.KeyContext(GUI, "GUI", List.of(GUI, UNIVERSAL)),
            new PackCatalog.KeyContext(UNIVERSAL, "UNIVERSAL", List.of(IN_GAME, GUI, UNIVERSAL)));

    @TempDir Path directory;

    @Test
    void overlapsFollowNeoForgeSplitByWhetherTheyActOnTheSameKeyPress() {
        KeyBindings bindings = new KeyBindings(List.of(
                spec("key.forward", "key.keyboard.w", IN_GAME),
                spec("key.ponder", "key.keyboard.w", UNIVERSAL),
                spec("key.details", "key.keyboard.w", GUI),
                spec("key.save", "key.keyboard.left.alt", UNIVERSAL),
                spec("key.focus", "key.keyboard.f", GUI),
                spec("key.sneak", "key.keyboard.x", IN_GAME),
                spec("key.boots", "key.keyboard.x", IN_GAME),
                spec("key.free", "key.keyboard.unknown", IN_GAME)), CONTEXTS,
                Map.of("key.focus", new KeyBindings.Assignment("key.keyboard.f", "ALT"),
                        "key.boots", new KeyBindings.Assignment("key.keyboard.x", "CONTROL")), Map.of());

        assertEquals(KeyBindings.Overlap.COLLISION, bindings.overlap(binding(bindings, "key.forward"), binding(bindings, "key.ponder")));
        assertEquals(KeyBindings.Overlap.SEPARATE_CONTEXTS, bindings.overlap(binding(bindings, "key.forward"), binding(bindings, "key.details")),
                "in game and screens never conflict, so NeoForge only reports the equal key");
        assertEquals(KeyBindings.Overlap.MODIFIER, bindings.overlap(binding(bindings, "key.save"), binding(bindings, "key.focus")));
        assertEquals(KeyBindings.Overlap.COLLISION, bindings.overlap(binding(bindings, "key.sneak"), binding(bindings, "key.boots")),
                "in game, X also fires while Ctrl is held");
        assertNull(bindings.overlap(binding(bindings, "key.free"), binding(bindings, "key.forward")));
        assertEquals(2, bindings.clashes(binding(bindings, "key.forward")).size());
        assertTrue(binding(bindings, "key.boots").changed());
        assertFalse(binding(bindings, "key.forward").changed());
    }

    @Test
    void anAnywhereBindingMinecraftOnlyHandlesInTheWorldDoesNotCollideWithScreens() {
        List<PackCatalog.KeyBinding> specs = List.of(
                new PackCatalog.KeyBinding("key.use", "Use", "key.categories.gameplay", "Gameplay", "minecraft",
                        "key.mouse.right", "NONE", UNIVERSAL),
                new PackCatalog.KeyBinding("key.pickItem", "Pick Block", "key.categories.gameplay", "Gameplay", "minecraft",
                        "key.mouse.middle", "NONE", UNIVERSAL),
                spec("key.jei.showUses", "key.mouse.right", GUI),
                spec("key.jei.bookmark", "key.mouse.middle", GUI));
        KeyBindings bindings = new KeyBindings(specs, CONTEXTS, Map.of(), Map.of());

        assertEquals(KeyBindings.Overlap.SEPARATE_CONTEXTS,
                bindings.overlap(binding(bindings, "key.use"), binding(bindings, "key.jei.showUses")));
        assertEquals(KeyBindings.Overlap.COLLISION,
                bindings.overlap(binding(bindings, "key.pickItem"), binding(bindings, "key.jei.bookmark")),
                "Minecraft also picks items in container screens");
    }

    @Test
    void readsKeysAndModifiersFromOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, """
                version:3955
                key_key.forward:key.keyboard.w
                key_key.sfm.manager.text_editor:key.keyboard.e:CONTROL
                soundCategory_master:1.0
                """);

        Map<String, KeyBindings.Assignment> assignments = KeyBindings.readOptions(options);

        assertEquals(Map.of("key.forward", new KeyBindings.Assignment("key.keyboard.w", "NONE"),
                "key.sfm.manager.text_editor", new KeyBindings.Assignment("key.keyboard.e", "CONTROL")), assignments);
        assertEquals(Map.of(), KeyBindings.readOptions(this.directory.resolve("missing.txt")));
    }

    @Test
    void keysReadTheWayTheGameNamesThemForTheKeyboardLayout() {
        KeyBindings bindings = new KeyBindings(List.of(), CONTEXTS, Map.of(),
                Map.of("key.keyboard.z", "Y", "key.keyboard.semicolon", "\uFFFD"));
        KeyBindings.Assignment undo = new KeyBindings.Assignment("key.keyboard.z", "CONTROL");

        assertEquals("Ctrl + Y", bindings.display(undo), "a German layout names the US Z key Y");
        assertEquals(List.of("Ctrl", "Y"), bindings.caps(undo));
        assertTrue(bindings.namesKey(undo, "ctrl+y"));
        assertTrue(bindings.namesKey(undo, "Control Y"));
        assertFalse(bindings.namesKey(undo, "y"));
        assertEquals("Not bound", bindings.display(new KeyBindings.Assignment(KeyBindings.UNBOUND, "NONE")));
        assertEquals("Semicolon", bindings.keyName("key.keyboard.semicolon"), "an undecodable name falls back to the id");
        assertEquals("Left Control", KeyBindings.fallbackName("key.keyboard.left.control"));
        assertEquals("Keypad 1", KeyBindings.fallbackName("key.keyboard.keypad.1"));
        assertEquals("Mouse Button 4", KeyBindings.fallbackName("key.mouse.4"));
        assertEquals("Left Button", KeyBindings.fallbackName("key.mouse.left"));
    }

    private static PackCatalog.KeyBinding spec(String name, String key, String context) {
        return new PackCatalog.KeyBinding(name, name, "key.categories.test", "Test", "testmod", key, "NONE", context);
    }

    private static KeyBindings.Binding binding(KeyBindings bindings, String name) {
        return bindings.bindings().stream().filter(binding -> binding.spec().name().equals(name)).findFirst().orElseThrow();
    }
}
