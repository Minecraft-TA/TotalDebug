package com.github.minecraft_ta.totaldebug.protocol.execution;

import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Exact NBT carried by a {@code DATA} fact: one tag in Minecraft's binary form, base64-encoded for the JSON codec.
 * When the tag did not fit the transport budget, entries were left out and {@code omissions} names where and how many,
 * so an incomplete part is never presented as the complete value.
 */
public record FactData(String nbt, List<Omission> omissions) {
    /** Largest decoded tag a single fact carries. */
    public static final int MAX_BYTES = 1 << 20;
    /** Largest decoded total of all data facts in one result. */
    public static final int MAX_TOTAL_BYTES = 4 << 20;
    public static final int MAX_OMISSIONS = 4_096;
    private static final int MAX_ENCODED_LENGTH = (MAX_BYTES + 2) / 3 * 4;

    public FactData {
        Objects.requireNonNull(nbt, "nbt");
        if (nbt.isEmpty() || nbt.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Fact data must hold one tag of at most " + MAX_BYTES + " bytes");
        }
        omissions = List.copyOf(Objects.requireNonNullElse(omissions, List.of()));
        if (omissions.size() > MAX_OMISSIONS) {
            throw new IllegalArgumentException("Fact data names more than " + MAX_OMISSIONS + " omissions");
        }
    }

    /** Where entries were left out: the NBT path of the compound or list, and how many of its entries are missing. */
    public record Omission(String path, int count) {
        public Omission {
            Objects.requireNonNull(path, "path");
            if (path.length() > 4_096 || count < 1) {
                throw new IllegalArgumentException("Invalid omission at " + path);
            }
        }
    }

    public static FactData of(byte[] bytes, List<Omission> omissions) {
        return new FactData(Base64.getEncoder().encodeToString(bytes), omissions);
    }

    /** The tag in Minecraft's binary form. */
    public byte[] bytes() {
        return Base64.getDecoder().decode(this.nbt);
    }

    /** Decodes the tag. */
    public NbtData.Tag tag() {
        return NbtData.read(bytes());
    }

    public boolean complete() {
        return this.omissions.isEmpty();
    }

    /** The decoded size of the tag in bytes. */
    public int size() {
        int padding = this.nbt.endsWith("==") ? 2 : this.nbt.endsWith("=") ? 1 : 0;
        return this.nbt.length() / 4 * 3 - padding;
    }
}
