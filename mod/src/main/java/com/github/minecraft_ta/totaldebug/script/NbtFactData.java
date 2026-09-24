package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a tag for a data fact. A tag within the byte budget is sent whole. A larger one is copied in Minecraft's
 * printing order until the budget is used up; each compound or list that lost entries is recorded as an omission.
 */
final class NbtFactData {
    private NbtFactData() {
    }

    static FactData encode(Tag tag, int maxBytes) {
        if (serializedSize(tag) + 1 <= maxBytes) {
            return FactData.of(write(tag), List.of());
        }
        List<FactData.Omission> omissions = new ArrayList<>();
        long[] remaining = {maxBytes - 1L};
        Tag pruned = prune(tag, new ArrayList<>(), remaining, omissions);
        if (pruned == null) {
            omissions.add(new FactData.Omission("", 1));
            pruned = new CompoundTag();
        }
        return FactData.of(write(pruned), omissions);
    }

    private static Tag prune(Tag tag, List<Object> path, long[] remaining, List<FactData.Omission> omissions) {
        if (tag instanceof CompoundTag compound) {
            if (remaining[0] < 1) return null;
            remaining[0] -= 1;
            CompoundTag copy = new CompoundTag();
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            keys.sort(null);
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                long entry = 3L + utfLength(key);
                Tag child = compound.get(key);
                if (remaining[0] < entry + minimumSize(child)) {
                    record(omissions, path, keys.size() - index);
                    break;
                }
                remaining[0] -= entry;
                path.add(key);
                Tag pruned = prune(child, path, remaining, omissions);
                path.removeLast();
                if (pruned == null) {
                    remaining[0] += entry;
                    record(omissions, path, keys.size() - index);
                    break;
                }
                copy.put(key, pruned);
            }
            return copy;
        }
        if (tag instanceof ListTag list) {
            if (remaining[0] < 5) return null;
            remaining[0] -= 5;
            ListTag copy = new ListTag();
            for (int index = 0; index < list.size(); index++) {
                path.add(index);
                Tag pruned = remaining[0] < minimumSize(list.get(index))
                        ? null : prune(list.get(index), path, remaining, omissions);
                path.removeLast();
                if (pruned == null) {
                    record(omissions, path, list.size() - index);
                    break;
                }
                copy.add(pruned);
            }
            return copy;
        }
        long size = serializedSize(tag);
        if (size > remaining[0]) return null;
        remaining[0] -= size;
        return tag;
    }

    private static void record(List<FactData.Omission> omissions, List<Object> path, int count) {
        if (omissions.size() < FactData.MAX_OMISSIONS) {
            omissions.add(new FactData.Omission(NbtData.path(path), count));
        }
    }

    private static long minimumSize(Tag tag) {
        return switch (tag) {
            case CompoundTag ignored -> 1;
            case ListTag ignored -> 5;
            default -> serializedSize(tag);
        };
    }

    /** The payload size {@code NbtIo} writes for the tag, without its type byte. */
    static long serializedSize(Tag tag) {
        return switch (tag) {
            case CompoundTag compound -> {
                long size = 1;
                for (String key : compound.getAllKeys()) {
                    size += 3L + utfLength(key) + serializedSize(compound.get(key));
                }
                yield size;
            }
            case ListTag list -> {
                long size = 5;
                for (Tag item : list) size += serializedSize(item);
                yield size;
            }
            case StringTag string -> 2L + utfLength(string.getAsString());
            case ByteArrayTag array -> 4L + array.size();
            case IntArrayTag array -> 4L + 4L * array.size();
            case LongArrayTag array -> 4L + 8L * array.size();
            default -> switch (tag.getId()) {
                case Tag.TAG_BYTE -> 1;
                case Tag.TAG_SHORT -> 2;
                case Tag.TAG_INT, Tag.TAG_FLOAT -> 4;
                case Tag.TAG_LONG, Tag.TAG_DOUBLE -> 8;
                default -> throw new IllegalArgumentException("Unsupported NBT tag " + tag.getType().getName());
            };
        };
    }

    /** The length of {@code text} in the modified UTF-8 that {@code DataOutput.writeUTF} writes. */
    private static long utfLength(String text) {
        long length = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            length += character >= 0x0001 && character <= 0x007F ? 1 : character <= 0x07FF ? 2 : 3;
        }
        return length;
    }

    private static byte[] write(Tag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.writeAnyTag(tag, output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }
}
