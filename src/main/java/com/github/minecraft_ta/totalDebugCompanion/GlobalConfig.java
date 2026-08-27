package com.github.minecraft_ta.totalDebugCompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application wide, user facing settings.
 *
 * <p>Values are held in memory and mirrored to a small JSON file inside the per-user application
 * directory. Writes are coalesced, so dragging a slider does not produce one file write per
 * pixel; {@link #saveNow()} flushes synchronously during shutdown.
 *
 * <p>A missing or corrupt settings file is never fatal - the defaults are used and the file is
 * rewritten on the next save.
 */
public final class GlobalConfig {

    public static final float MIN_FONT_SIZE = 8f;
    public static final float MAX_FONT_SIZE = 40f;

    /** Bumped only when the on-disk shape changes incompatibly. */
    private static final int SETTINGS_VERSION = 3;
    private static final String SETTINGS_FILE_NAME = "companion-ui-settings.json";
    private static final String EDITOR_FONT_SIZE_PROPERTY = "editorFontSize";
    private static final String DEBUGGER_INLINE_VALUES_PROPERTY = "debuggerInlineValues";
    private static final String DEBUGGER_PREVIEWS_PROPERTY = "automaticDebuggerPreviews";
    private static final long SAVE_DELAY_MILLIS = 500;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final AtomicBoolean savePending = new AtomicBoolean();

    private volatile String themeId = "islands-dark";
    private volatile float editorFontSize = 14f;
    private volatile float uiFontSize = 13f;
    private volatile Rectangle debuggerWindowBounds;
    private volatile List<String> debuggerWatches = List.of();
    private volatile boolean breakOnCaughtExceptions;
    private volatile boolean breakOnUncaughtExceptions;
    private volatile boolean debuggerInlineValues = true;
    private volatile boolean automaticDebuggerPreviews = true;
    private volatile Path settingsFile;
    private volatile ScheduledExecutorService saveExecutor;

    private GlobalConfig() {
    }

    public String themeId() {
        return this.themeId;
    }

    public void setThemeId(String themeId) {
        if (themeId == null || themeId.isBlank() || themeId.equals(this.themeId)) {
            return;
        }
        this.themeId = themeId;
        scheduleSave();
    }

    public float editorFontSize() {
        return this.editorFontSize;
    }

    public void setEditorFontSize(float editorFontSize) {
        float value = clampFontSize(editorFontSize);
        float previous = this.editorFontSize;
        if (Float.compare(previous, value) == 0) {
            return;
        }
        this.editorFontSize = value;
        pcs.firePropertyChange(EDITOR_FONT_SIZE_PROPERTY, previous, value);
        scheduleSave();
    }

    public float uiFontSize() {
        return this.uiFontSize;
    }

    public void setUiFontSize(float uiFontSize) {
        float value = clampFontSize(uiFontSize);
        if (Float.compare(this.uiFontSize, value) == 0) {
            return;
        }
        this.uiFontSize = value;
        scheduleSave();
    }

    public Rectangle debuggerWindowBounds() {
        Rectangle bounds = this.debuggerWindowBounds;
        return bounds == null ? null : new Rectangle(bounds);
    }

    public void setDebuggerWindowBounds(Rectangle debuggerWindowBounds) {
        Rectangle replacement = debuggerWindowBounds == null ? null : new Rectangle(debuggerWindowBounds);
        Rectangle previous = this.debuggerWindowBounds;
        if (Objects.equals(previous, replacement)) {
            return;
        }
        this.debuggerWindowBounds = replacement;
        scheduleSave();
    }

    public List<String> debuggerWatches() {
        return this.debuggerWatches;
    }

    public void setDebuggerWatches(List<String> expressions) {
        List<String> replacement = normalizeWatches(expressions);
        if (replacement.equals(this.debuggerWatches)) {
            return;
        }
        this.debuggerWatches = replacement;
        scheduleSave();
    }

    public boolean breakOnCaughtExceptions() {
        return this.breakOnCaughtExceptions;
    }

    public void setBreakOnCaughtExceptions(boolean enabled) {
        if (this.breakOnCaughtExceptions == enabled) {
            return;
        }
        this.breakOnCaughtExceptions = enabled;
        scheduleSave();
    }

    public boolean breakOnUncaughtExceptions() {
        return this.breakOnUncaughtExceptions;
    }

    public void setBreakOnUncaughtExceptions(boolean enabled) {
        if (this.breakOnUncaughtExceptions == enabled) {
            return;
        }
        this.breakOnUncaughtExceptions = enabled;
        scheduleSave();
    }

    public boolean debuggerInlineValues() {
        return this.debuggerInlineValues;
    }

    public void setDebuggerInlineValues(boolean enabled) {
        boolean previous = this.debuggerInlineValues;
        if (previous == enabled) {
            return;
        }
        this.debuggerInlineValues = enabled;
        this.pcs.firePropertyChange(DEBUGGER_INLINE_VALUES_PROPERTY, previous, enabled);
        scheduleSave();
    }

    public boolean automaticDebuggerPreviews() {
        return this.automaticDebuggerPreviews;
    }

    public void setAutomaticDebuggerPreviews(boolean enabled) {
        boolean previous = this.automaticDebuggerPreviews;
        if (previous == enabled) {
            return;
        }
        this.automaticDebuggerPreviews = enabled;
        this.pcs.firePropertyChange(DEBUGGER_PREVIEWS_PROPERTY, previous, enabled);
        scheduleSave();
    }

    public void addEditorFontSizeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(EDITOR_FONT_SIZE_PROPERTY, listener);
    }

    public void removeEditorFontSizeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(EDITOR_FONT_SIZE_PROPERTY, listener);
    }

    public void addDebuggerInlineValuesListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(DEBUGGER_INLINE_VALUES_PROPERTY, listener);
    }

    public void removeDebuggerInlineValuesListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(DEBUGGER_INLINE_VALUES_PROPERTY, listener);
    }

    public void addAutomaticDebuggerPreviewsListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(DEBUGGER_PREVIEWS_PROPERTY, listener);
    }

    public void removeAutomaticDebuggerPreviewsListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(DEBUGGER_PREVIEWS_PROPERTY, listener);
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Points this config at {@code dataDirectory} and loads any previously persisted settings.
     * Safe to call before the UI exists; must be called before the look and feel is installed so the
     * persisted theme is the first one applied.
     */
    public void loadFrom(Path dataDirectory) {
        this.settingsFile = dataDirectory.resolve(SETTINGS_FILE_NAME);
        if (!Files.isRegularFile(this.settingsFile)) {
            return;
        }

        PersistedSettings persisted;
        try {
            String json = Files.readString(this.settingsFile, StandardCharsets.UTF_8);
            persisted = GSON.fromJson(json, PersistedSettings.class);
        } catch (IOException | JsonParseException exception) {
            System.err.println("Ignoring unreadable companion settings at " + this.settingsFile
                    + " (" + exception.getMessage() + "); falling back to defaults");
            return;
        }
        if (persisted == null) {
            return;
        }
        if (persisted.version != SETTINGS_VERSION) {
            System.err.println("Ignoring companion settings version " + persisted.version
                    + " at " + this.settingsFile + "; expected " + SETTINGS_VERSION);
            return;
        }

        if (persisted.theme != null && !persisted.theme.isBlank()) {
            this.themeId = persisted.theme;
        }
        if (persisted.editorFontSize != null) {
            this.editorFontSize = clampFontSize(persisted.editorFontSize);
        }
        if (persisted.uiFontSize != null) {
            this.uiFontSize = clampFontSize(persisted.uiFontSize);
        }
        if (persisted.debuggerWindowX != null
                && persisted.debuggerWindowY != null
                && persisted.debuggerWindowWidth != null
                && persisted.debuggerWindowHeight != null
                && persisted.debuggerWindowWidth > 0
                && persisted.debuggerWindowHeight > 0) {
            this.debuggerWindowBounds = new Rectangle(
                    persisted.debuggerWindowX,
                    persisted.debuggerWindowY,
                    persisted.debuggerWindowWidth,
                    persisted.debuggerWindowHeight
            );
        }
        if (persisted.debuggerWatches != null) {
            this.debuggerWatches = normalizeWatches(persisted.debuggerWatches);
        }
        if (persisted.breakOnCaughtExceptions != null) {
            this.breakOnCaughtExceptions = persisted.breakOnCaughtExceptions;
        }
        if (persisted.breakOnUncaughtExceptions != null) {
            this.breakOnUncaughtExceptions = persisted.breakOnUncaughtExceptions;
        }
        if (persisted.debuggerInlineValues != null) {
            this.debuggerInlineValues = persisted.debuggerInlineValues;
        }
        if (persisted.automaticDebuggerPreviews != null) {
            this.automaticDebuggerPreviews = persisted.automaticDebuggerPreviews;
        }
    }

    private static float clampFontSize(float value) {
        return Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, value));
    }

    private static List<String> normalizeWatches(List<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String expression : expressions) {
            if (expression == null) {
                continue;
            }
            String trimmed = expression.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private void scheduleSave() {
        if (this.settingsFile == null || !savePending.compareAndSet(false, true)) {
            return;
        }
        saveExecutor().schedule(() -> {
            savePending.set(false);
            writeSettings();
        }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private synchronized ScheduledExecutorService saveExecutor() {
        if (this.saveExecutor == null) {
            this.saveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Companion settings writer");
                thread.setDaemon(true);
                return thread;
            });
        }
        return this.saveExecutor;
    }

    /** Flushes any pending change synchronously. Called during shutdown. */
    public void saveNow() {
        savePending.set(false);
        writeSettings();
    }

    private void writeSettings() {
        Path target = this.settingsFile;
        if (target == null) {
            return;
        }

        Rectangle debuggerBounds = this.debuggerWindowBounds;
        PersistedSettings snapshot = new PersistedSettings(
                SETTINGS_VERSION,
                this.themeId,
                this.editorFontSize,
                this.uiFontSize,
                debuggerBounds == null ? null : debuggerBounds.x,
                debuggerBounds == null ? null : debuggerBounds.y,
                debuggerBounds == null ? null : debuggerBounds.width,
                debuggerBounds == null ? null : debuggerBounds.height,
                this.debuggerWatches,
                this.breakOnCaughtExceptions,
                this.breakOnUncaughtExceptions,
                this.debuggerInlineValues,
                this.automaticDebuggerPreviews
        );

        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        Path staged = null;
        try {
            Files.createDirectories(parent);
            staged = Files.createTempFile(parent, ".companion-ui-settings-", ".tmp");
            Files.writeString(staged, GSON.toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            staged = null;
        } catch (IOException exception) {
            System.err.println("Failed to persist companion settings to " + target
                    + " (" + exception.getMessage() + ")");
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** On-disk shape. Boxed fields so an absent entry falls back to the in-memory default. */
    private record PersistedSettings(
            int version,
            String theme,
            Float editorFontSize,
            Float uiFontSize,
            Integer debuggerWindowX,
            Integer debuggerWindowY,
            Integer debuggerWindowWidth,
            Integer debuggerWindowHeight,
            List<String> debuggerWatches,
            Boolean breakOnCaughtExceptions,
            Boolean breakOnUncaughtExceptions,
            Boolean debuggerInlineValues,
            Boolean automaticDebuggerPreviews
    ) {
    }

    // ---------------------------------------------------------------- singleton

    private static final class Holder {
        private static final GlobalConfig INSTANCE = new GlobalConfig();
    }

    public static GlobalConfig getInstance() {
        return Holder.INSTANCE;
    }
}
