package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The keys {@code options.txt} assigns, whoever writes the file: the game when a key is rebound in its controls screen,
 * Companion or an editor. Followers hear of it once the assignments differ from those read before; another option
 * written, such as the volume, tells nobody, and a deleted file assigns nothing. The file is followed as a
 * {@link FileReading}, which reads it once it settled, tries again after a failed read and watches the game's folder
 * also before it exists. Companion's own writes are read at once ({@link #readNow()}).
 */
public final class KeyAssignments implements AutoCloseable {
    private final FileReading<Map<String, KeyBindings.Assignment>> reading;

    public KeyAssignments(Path options) {
        this.reading = new FileReading<>(options, KeyBindings::readOptions, Duration.ofMillis(300));
    }

    /** Fires after the assignments changed. */
    public Signal changed() {
        return this.reading.changed();
    }

    /** The keys the file assigns, as read last; read now where none were read yet. Blocking then. */
    public Map<String, KeyBindings.Assignment> assignments() throws IOException {
        return this.reading.value();
    }

    /** Reads the file now, as after Companion wrote it. */
    public void readNow() {
        this.reading.readNow();
    }

    @Override
    public void close() {
        this.reading.close();
    }
}
