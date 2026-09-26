package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;

import javax.swing.Icon;
import java.util.List;
import java.util.Locale;

/**
 * How Companion names and draws each kind of registered content. Known registries have their own names and icons;
 * any other captured registry is named after its id, so a registry the game starts capturing is listed without a
 * change here.
 */
public final class ContentKinds {
    /** A registry as a kind of content: {@code singular} names one entry, such as Block, {@code plural} the list. */
    public record ContentKind(String registry, String singular, String plural, Icon icon) {
    }

    private static final List<ContentKind> KNOWN = List.of(
            new ContentKind(RegistryIds.BLOCK, "Block", "Blocks", Icons.BLOCK),
            new ContentKind(RegistryIds.ITEM, "Item", "Items", Icons.ITEM),
            new ContentKind(RegistryIds.ENTITY_TYPE, "Entity type", "Entity types", Icons.ENTITY),
            new ContentKind(RegistryIds.FLUID, "Fluid", "Fluids", Icons.FLUID),
            new ContentKind(RegistryIds.SOUND_EVENT, "Sound event", "Sound events", Icons.SOUND));

    private ContentKinds() {
    }

    public static ContentKind of(String registry) {
        for (ContentKind kind : KNOWN) {
            if (kind.registry().equals(registry)) return kind;
        }
        String name = label(registry.substring(registry.indexOf(':') + 1));
        return new ContentKind(registry, name, name, Icons.CONTENT);
    }

    /** A stable key of the catalog as a label, such as Spawn egg for {@code spawn_egg}. */
    public static String label(String key) {
        String words = key.replace('_', ' ').replace('/', ' ');
        return words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
