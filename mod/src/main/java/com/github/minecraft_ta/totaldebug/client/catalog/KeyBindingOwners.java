package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which mod registered each key binding. NeoForge posts {@link RegisterKeyMappingsEvent} in priority phases, each
 * phase to every mod's event bus in turn. While a bus runs its highest phase, listeners for the later phases are added
 * to it; added after the mod's own listeners, each runs right after them and claims the bindings that appeared since
 * the previous claim. Bindings that exist when the first bus starts are Minecraft's.
 */
public final class KeyBindingOwners {
    private static final Map<KeyMapping, String> OWNERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<KeyMapping> CLAIMED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<EventPriority> LATER = List.of(EventPriority.HIGH, EventPriority.NORMAL, EventPriority.LOW,
            EventPriority.LOWEST);
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static Field options;

    private KeyBindingOwners() {
    }

    /** Listens on every mod's event bus. Call once while mods are constructed, on the client. */
    public static void install() {
        try {
            options = RegisterKeyMappingsEvent.class.getDeclaredField("options");
            options.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("Key bindings will be listed without the mods that registered them", exception);
            return;
        }
        ModList.get().forEachModContainer((modId, container) -> {
            IEventBus bus = container.getEventBus();
            if (bus == null) return;
            AtomicBoolean armed = new AtomicBoolean();
            bus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> {
                if (STARTED.compareAndSet(false, true)) claim(event, "minecraft");
                if (armed.compareAndSet(false, true)) {
                    for (EventPriority priority : LATER) {
                        bus.addListener(priority, RegisterKeyMappingsEvent.class, later -> claim(later, modId));
                    }
                }
            });
        });
    }

    /** Takes every binding not claimed yet as {@code modId}'s. */
    private static void claim(RegisterKeyMappingsEvent event, String modId) {
        KeyMapping[] mappings;
        try {
            mappings = ((Options) options.get(event)).keyMappings;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
        synchronized (CLAIMED) {
            for (KeyMapping mapping : mappings) {
                if (CLAIMED.add(mapping)) OWNERS.put(mapping, modId);
            }
        }
    }

    /** The id of the mod that registered {@code mapping}, or empty when no registration added it. */
    public static String owner(KeyMapping mapping) {
        return OWNERS.getOrDefault(mapping, "");
    }
}
