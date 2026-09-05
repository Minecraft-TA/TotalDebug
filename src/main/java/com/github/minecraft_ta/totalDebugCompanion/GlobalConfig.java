package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.github.minecraft_ta.totalDebugCompanion.storage.JsonStateWriter;
import com.google.gson.Gson;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Application wide, user facing settings.
 *
 * <p>Values are held in memory and mirrored to a small JSON file inside the per-user application
 * directory. Writes are coalesced, so dragging a slider does not produce one file write per
 * pixel; {@link #saveNow()} flushes synchronously during shutdown.
 *
 */
public final class GlobalConfig {

    public static final float MIN_FONT_SIZE = 8f;
    public static final float MAX_FONT_SIZE = 40f;

    /** Bumped only when the on-disk shape changes incompatibly. */
    private static final int SETTINGS_VERSION = 1;
    private static final String EDITOR_FONT_SIZE_PROPERTY = "editorFontSize";
    private static final String DEBUGGER_INLINE_VALUES_PROPERTY = "debuggerInlineValues";
    private static final String DEBUGGER_PREVIEWS_PROPERTY = "automaticDebuggerPreviews";

    private static final Gson GSON = JsonFiles.GSON;

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private volatile String themeId = "islands-dark";
    private volatile float editorFontSize = 14f;
    private volatile float uiFontSize = 13f;
    private volatile Rectangle debuggerWindowBounds;
    private volatile boolean debuggerInlineValues = true;
    private volatile boolean automaticDebuggerPreviews = true;
    private JsonStateWriter writer;

    GlobalConfig() {
    }

    public String themeId() {
        return this.themeId;
    }

    public synchronized void setThemeId(String themeId) {
        if (themeId == null || themeId.isBlank() || themeId.equals(this.themeId)) {
            return;
        }
        this.themeId = themeId;
        scheduleSave();
    }

    public float editorFontSize() {
        return this.editorFontSize;
    }

    public synchronized void setEditorFontSize(float editorFontSize) {
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

    public synchronized void setUiFontSize(float uiFontSize) {
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

    public synchronized void setDebuggerWindowBounds(Rectangle debuggerWindowBounds) {
        Rectangle replacement = debuggerWindowBounds == null ? null : new Rectangle(debuggerWindowBounds);
        Rectangle previous = this.debuggerWindowBounds;
        if (Objects.equals(previous, replacement)) {
            return;
        }
        this.debuggerWindowBounds = replacement;
        scheduleSave();
    }

    public boolean debuggerInlineValues() {
        return this.debuggerInlineValues;
    }

    public synchronized void setDebuggerInlineValues(boolean enabled) {
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

    public synchronized void setAutomaticDebuggerPreviews(boolean enabled) {
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
    public synchronized void loadFrom(Path appHome) throws IOException {
        Path target = new AppPaths(appHome).settings();
        PersistedSettings persisted = null;
        if (Files.exists(target)) {
            var json = JsonFiles.read(target);
            try {
                if (JsonFiles.integer(json, "version") != SETTINGS_VERSION) {
                    throw new IllegalArgumentException("Unsupported settings format");
                }
                JsonFiles.string(json, "theme");
                JsonFiles.bool(json, "debuggerInlineValues");
                JsonFiles.bool(json, "automaticDebuggerPreviews");
                for (String font : java.util.List.of("editorFontSize", "uiFontSize")) {
                    var value = json.get(font);
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                            || !Float.isFinite(value.getAsFloat())) {
                        throw new IllegalArgumentException("Invalid font size: " + font);
                    }
                }
                persisted = GSON.fromJson(json, PersistedSettings.class);
                var bounds = java.util.List.of("debuggerWindowX", "debuggerWindowY",
                        "debuggerWindowWidth", "debuggerWindowHeight");
                if (bounds.stream().anyMatch(json::has)) {
                    bounds.forEach(field -> JsonFiles.integer(json, field));
                    if (persisted.debuggerWindowWidth < 1 || persisted.debuggerWindowHeight < 1) {
                        throw new IllegalArgumentException("Invalid debugger window dimensions");
                    }
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid settings " + target + ": " + exception.getMessage(), exception);
            }
        }
        if (this.writer != null) {
            this.writer.close();
        }
        this.writer = new JsonStateWriter(target);
        if (persisted == null) {
            return;
        }
        this.themeId = persisted.theme;
        this.editorFontSize = clampFontSize(persisted.editorFontSize);
        this.uiFontSize = clampFontSize(persisted.uiFontSize);
        this.debuggerInlineValues = persisted.debuggerInlineValues;
        this.automaticDebuggerPreviews = persisted.automaticDebuggerPreviews;
        this.debuggerWindowBounds = persisted.debuggerWindowX == null ? null : new Rectangle(
                persisted.debuggerWindowX, persisted.debuggerWindowY,
                persisted.debuggerWindowWidth, persisted.debuggerWindowHeight);
    }

    private static float clampFontSize(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Font size must be finite");
        }
        return Math.clamp(value, MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    private synchronized void scheduleSave() {
        if (this.writer == null) {
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
                this.debuggerInlineValues,
                this.automaticDebuggerPreviews
        );

        this.writer.schedule(GSON.toJsonTree(snapshot));
    }

    /** Flushes any pending change synchronously. Called during shutdown. */
    public synchronized void saveNow() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
    }

    /** Nullable geometry means the debugger window has not been placed yet. */
    private record PersistedSettings(
            int version,
            String theme,
            Float editorFontSize,
            Float uiFontSize,
            Integer debuggerWindowX,
            Integer debuggerWindowY,
            Integer debuggerWindowWidth,
            Integer debuggerWindowHeight,
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
