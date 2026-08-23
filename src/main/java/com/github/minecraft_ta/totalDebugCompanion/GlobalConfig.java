package com.github.minecraft_ta.totalDebugCompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final int SETTINGS_VERSION = 1;
    private static final String SETTINGS_FILE_NAME = "companion-ui-settings.json";
    private static final String EDITOR_FONT_SIZE_PROPERTY = "editorFontSize";
    private static final long SAVE_DELAY_MILLIS = 500;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final AtomicBoolean savePending = new AtomicBoolean();

    private volatile String themeId = "islands-dark";
    private volatile float editorFontSize = 14f;
    private volatile float uiFontSize = 13f;
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

    public void addEditorFontSizeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(EDITOR_FONT_SIZE_PROPERTY, listener);
    }

    public void removeEditorFontSizeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(EDITOR_FONT_SIZE_PROPERTY, listener);
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
    }

    private static float clampFontSize(float value) {
        return Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, value));
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

        PersistedSettings snapshot = new PersistedSettings(
                SETTINGS_VERSION,
                this.themeId,
                this.editorFontSize,
                this.uiFontSize
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
    private record PersistedSettings(int version, String theme, Float editorFontSize, Float uiFontSize) {
    }

    // ---------------------------------------------------------------- singleton

    private static final class Holder {
        private static final GlobalConfig INSTANCE = new GlobalConfig();
    }

    public static GlobalConfig getInstance() {
        return Holder.INSTANCE;
    }
}
