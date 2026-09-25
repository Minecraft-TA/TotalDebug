package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Reads installed mods, key bindings and the registries {@link CatalogRegistries} lists into a {@link PackCatalog}.
 * Registries are frozen once the game has loaded, so one capture describes a runtime inventory. Work runs on the
 * client thread in slices, so a large pack does not stall a frame.
 */
public final class PackCatalogCapture {
    private static final long SLICE_NANOS = 5_000_000L;

    private final String inventoryId;
    private final String language;
    private final Map<String, String> moduleByModId;
    private final CompletableFuture<PackCatalog> result = new CompletableFuture<>();
    private final List<PackCatalog.Mod> mods = new ArrayList<>();
    private final List<PackCatalog.Registry> registries = new ArrayList<>();
    private final List<PackCatalog.KeyBinding> keyBindings = new ArrayList<>();
    private final List<PackCatalog.KeyContext> keyContexts = new ArrayList<>();
    private final Map<String, String> keyNames = new HashMap<>();
    private Iterator<Map.Entry<IModInfo, Path>> modCursor;
    private CatalogRegistries registryCaptures;
    private Iterator<CatalogRegistries.Capture<?>> captures;
    private CatalogRegistries.Cursor<?> cursor;
    private long startedAt;

    public PackCatalogCapture(String inventoryId, String language, Map<String, String> moduleByModId) {
        this.inventoryId = inventoryId;
        this.language = language;
        this.moduleByModId = Map.copyOf(moduleByModId);
    }

    public CompletableFuture<PackCatalog> result() {
        return this.result;
    }

    /** Continues the capture for one slice. Client thread only; returns true once the result is complete. */
    public boolean step() {
        if (this.result.isDone()) {
            return true;
        }
        try {
            long deadline = System.nanoTime() + SLICE_NANOS;
            if (this.modCursor == null) {
                this.startedAt = System.nanoTime();
                List<Map.Entry<IModInfo, Path>> mods = new ArrayList<>();
                ModList.get().forEachModFile(file -> file.getModInfos()
                        .forEach(info -> mods.add(Map.entry(info, file.getFilePath()))));
                this.modCursor = mods.iterator();
            }
            // Every part, the mods included, is captured in steps within the slice, so a large pack never stalls a frame.
            while (System.nanoTime() < deadline) {
                if (this.modCursor.hasNext()) {
                    captureMod(this.modCursor.next());
                } else if (this.registryCaptures == null) {
                    captureKeyBindings();
                    this.registryCaptures = new CatalogRegistries();
                    this.captures = this.registryCaptures.captures().iterator();
                } else if (this.cursor != null && this.cursor.hasNext()) {
                    this.cursor.next();
                } else if (this.cursor != null) {
                    this.registries.add(this.cursor.result());
                    this.cursor = null;
                } else if (this.captures.hasNext()) {
                    this.cursor = this.captures.next().start();
                } else {
                    complete();
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            this.result.completeExceptionally(exception);
            return true;
        }
    }

    private void complete() {
        PackCatalog catalog = new PackCatalog(this.inventoryId, this.language, this.mods, this.registries,
                this.registryCaptures.itemAppearances(), this.keyBindings, this.keyContexts, this.keyNames);
        StringBuilder counts = new StringBuilder();
        for (PackCatalog.Registry registry : this.registries) {
            counts.append(", ").append(registry.entries().size()).append(' ').append(registry.id());
        }
        TotalDebug.LOGGER.info("Captured the pack catalog in {} ms: {} mods, {} key bindings{}",
                (System.nanoTime() - this.startedAt) / 1_000_000, this.mods.size(), this.keyBindings.size(), counts);
        this.result.complete(catalog);
    }

    private void captureMod(Map.Entry<IModInfo, Path> found) {
        try {
            this.mods.add(mod(found.getKey(), found.getValue()));
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("Leaving mod {} out of the pack catalog", found.getKey().getModId(), exception);
        }
    }

    /**
     * Every key binding with the mod that registered it, and every context they use with the contexts each says it
     * conflicts with, so collisions follow for any assignment of keys.
     */
    private void captureKeyBindings() {
        Map<IKeyConflictContext, String> contextIds = new IdentityHashMap<>();
        Map<String, Integer> perClass = new HashMap<>();
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            try {
                IKeyConflictContext context = mapping.getKeyConflictContext();
                String contextId = contextIds.computeIfAbsent(context, key -> KeyContexts.id(key, perClass));
                this.keyBindings.add(new PackCatalog.KeyBinding(mapping.getName(), I18n.get(mapping.getName()),
                        mapping.getCategory(), I18n.get(mapping.getCategory()), KeyBindingOwners.owner(mapping),
                        mapping.getDefaultKey().getName(), mapping.getDefaultKeyModifier().name(), contextId));
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Leaving key binding {} out of the pack catalog", mapping.getName(), exception);
            }
        }
        for (Map.Entry<IKeyConflictContext, String> context : contextIds.entrySet()) {
            List<String> conflicts = new ArrayList<>();
            for (Map.Entry<IKeyConflictContext, String> other : contextIds.entrySet()) {
                try {
                    if (context.getKey().conflicts(other.getKey())) conflicts.add(other.getValue());
                } catch (RuntimeException exception) {
                    TotalDebug.LOGGER.debug("Key context {} failed to compare with {}", context.getValue(), other.getValue(), exception);
                }
            }
            this.keyContexts.add(new PackCatalog.KeyContext(context.getValue(), KeyContexts.name(context.getKey()), conflicts));
        }
        captureKeyNames();
    }

    /**
     * The name the game shows for every key it knows, such as {@code Y} for {@code key.keyboard.z} on a German layout,
     * so Companion names keys the way the controls screen does. Render thread only; GLFW names the keys.
     */
    @SuppressWarnings("unchecked")
    private void captureKeyNames() {
        Map<String, InputConstants.Key> keys;
        try {
            Field names = InputConstants.Key.class.getDeclaredField("NAME_MAP");
            names.setAccessible(true);
            keys = Map.copyOf((Map<String, InputConstants.Key>) names.get(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("Key names are left to Companion", exception);
            return;
        }
        for (Map.Entry<String, InputConstants.Key> key : keys.entrySet()) {
            if (key.getValue().equals(InputConstants.UNKNOWN)) continue;
            try {
                this.keyNames.put(key.getKey(), key.getValue().getDisplayName().getString());
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Key {} has no name", key.getKey(), exception);
            }
        }
    }

    private PackCatalog.Mod mod(IModInfo info, Path file) {
        Map<String, String> urls = new LinkedHashMap<>();
        info.getModURL().map(URL::toString).ifPresent(url -> urls.put("display", url));
        info.getOwningFile().getConfig().getConfigElement("issueTrackerURL")
                .ifPresent(url -> urls.put("issues", String.valueOf(url)));
        List<PackCatalog.Dependency> dependencies = new ArrayList<>();
        for (IModInfo.ModVersion dependency : info.getDependencies()) {
            PackCatalogEntries.dependency(dependency.getModId(), dependency.getType().name(),
                    dependency.getVersionRange().toString(), dependency.getSide().name()).ifPresent(dependencies::add);
        }
        List<PackCatalog.ConfigFile> configs = new ArrayList<>();
        for (ModConfig config : ModConfigs.getModConfigs(info.getModId())) {
            ConfigSpecs.Spec spec = ConfigSpecs.of(config);
            configs.add(new PackCatalog.ConfigFile(config.getFileName(),
                    PackCatalogEntries.configType(config.getType().name()), loadedPath(config), spec.sections(),
                    spec.settings()));
        }
        return new PackCatalog.Mod(
                info.getModId(),
                info.getDisplayName(),
                info.getVersion().toString(),
                info.getDescription().strip(),
                PackCatalogEntries.authors(info.getConfig().getConfigElement("authors").orElse(null)),
                info.getOwningFile().getLicense(),
                urls,
                info.getLogoFile().orElse(""),
                this.moduleByModId.getOrDefault(info.getModId(), info.getModId()),
                file.toAbsolutePath().normalize().toUri(),
                dependencies,
                configs);
    }

    /** A configuration has a file only while it is loaded; a server configuration outside a world has none. */
    private static Path loadedPath(ModConfig config) {
        if (config.getLoadedConfig() == null) {
            return null;
        }
        try {
            return config.getFullPath().toAbsolutePath().normalize();
        } catch (IllegalStateException notAFile) {
            return null;
        }
    }
}
