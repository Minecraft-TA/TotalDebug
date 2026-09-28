package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A world's {@code level.dat}, read and written as the game does: a gzipped compound with a name, saved into
 * {@code level.dat_new}, after which the last {@code level.dat} becomes {@code level.dat_old} and the new one
 * {@code level.dat}. Blocking.
 */
public final class LevelDat {
    private static final int MAX_LEVEL_BYTES = 16 * 1024 * 1024;

    /** The file's root compound and the name it is written with, usually empty. */
    public record Root(String name, NbtData.CompoundTag tag) {
    }

    private LevelDat() {
    }

    /** The file the game loads for {@code world}: {@code level.dat}, or {@code level.dat_old} while it is missing. */
    public static Path file(Path world) {
        Path level = world.resolve("level.dat");
        return !Files.exists(level) && Files.isRegularFile(world.resolve("level.dat_old")) ? world.resolve("level.dat_old") : level;
    }

    public static Root read(Path file) throws IOException {
        byte[] named;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            named = input.readNBytes(MAX_LEVEL_BYTES + 1);
        }
        if (named.length > MAX_LEVEL_BYTES) throw new IOException(file + " is larger than " + MAX_LEVEL_BYTES + " bytes");
        if (named.length < 3 || named[0] != 10) throw new IOException(file + " does not start with a compound");
        int nameLength = ((named[1] & 0xFF) << 8) | (named[2] & 0xFF);
        if (3 + nameLength > named.length) throw new IOException(file + " ends in the root's name");
        String name = new DataInputStream(new ByteArrayInputStream(named, 1, 2 + nameLength)).readUTF();
        byte[] unnamed = new byte[named.length - 2 - nameLength];
        unnamed[0] = named[0];
        System.arraycopy(named, 3 + nameLength, unnamed, 1, unnamed.length - 1);
        try {
            if (NbtData.read(unnamed) instanceof NbtData.CompoundTag root) return new Root(name, root);
        } catch (IllegalArgumentException unreadable) {
            throw new IOException(file + " could not be read: " + unreadable.getMessage(), unreadable);
        }
        throw new IOException(file + " does not hold a compound");
    }

    /**
     * Writes {@code root} as {@code world}'s {@code level.dat}, as the game saves it. The world must not be open in a
     * game, which would write it over.
     */
    public static void write(Path world, Root root) throws IOException {
        if (Worlds.isOpen(world)) throw new IOException("The world " + world.getFileName() + " is open in a game, which writes its level.dat");
        byte[] unnamed = NbtData.write(root.tag());
        ByteArrayOutputStream named = new ByteArrayOutputStream(unnamed.length + 2 + root.name().length());
        try (DataOutputStream output = new DataOutputStream(named)) {
            output.writeByte(unnamed[0]);
            output.writeUTF(root.name());
            output.write(unnamed, 1, unnamed.length - 1);
        }
        Path fresh = world.resolve("level.dat_new");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(fresh))) {
            named.writeTo(output);
        }
        Path level = world.resolve("level.dat");
        if (Files.exists(level)) Files.move(level, world.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(fresh, level, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code parent} with {@code key} set to {@code value}. */
    public static NbtData.CompoundTag with(NbtData.CompoundTag parent, String key, NbtData.Tag value) {
        Map<String, NbtData.Tag> entries = new HashMap<>(parent.entries());
        entries.put(key, value);
        return new NbtData.CompoundTag(entries);
    }
}
