package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The rows of the data tree-table: every data fact of a read as a root, its entries nested below. Rows are identified
 * by a key made of the root's name and the entry's NBT path, so expansion, selection and change marks survive a newer
 * read that inserts or removes entries. Parts that were not transferred appear as their own rows and make every
 * enclosing value incomplete.
 */
final class DataRows {
    static final int PREVIEW_LENGTH = 240;

    private DataRows() {
    }

    /** One data fact: {@code name} identifies it among the read's data, e.g. {@code NBT › Block entity}. */
    record Root(String name, FactData data) {
        Root {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(data, "data");
        }
    }

    /** A root after decoding: its tag, or why it cannot be shown. */
    record Decoded(Root root, NbtData.Tag tag, String problem) {
        static Decoded of(Root root) {
            try {
                return new Decoded(root, root.data().tag(), "");
            } catch (IllegalArgumentException unreadable) {
                return new Decoded(root, null, Objects.requireNonNullElse(unreadable.getMessage(), "Unreadable data"));
            }
        }
    }

    enum Kind {
        ENTRY,
        OMITTED,
        UNREADABLE
    }

    /**
     * One row. {@code tag} is null for rows standing for missing data. {@code omitted} counts the entries not
     * transferred: below an entry that is therefore incomplete, or the entries an omission row stands for.
     */
    record Row(
            String key,
            Kind kind,
            int root,
            List<Object> path,
            int depth,
            String name,
            NbtData.Tag tag,
            boolean expandable,
            boolean expanded,
            int omitted,
            String value
    ) {
        String type() {
            return switch (this.tag) {
                case null -> "";
                case NbtData.ListTag list -> "list (" + list.items().size() + ")";
                case NbtData.ByteArrayTag array -> "byte[] (" + array.values().size() + ")";
                case NbtData.IntArrayTag array -> "int[] (" + array.values().size() + ")";
                case NbtData.LongArrayTag array -> "long[] (" + array.values().size() + ")";
                case NbtData.Tag other -> other.typeName();
            };
        }

        /** Whether this entry's value was transferred completely, so copying it is exact. */
        boolean complete() {
            return this.kind == Kind.ENTRY && this.omitted == 0;
        }

        /** The entry's NBT path, empty for a root. */
        String pathText() {
            return NbtData.path(this.path);
        }
    }

    static String key(Root root, List<Object> path) {
        return root.name() + '\u0001' + NbtData.path(path);
    }

    /** The keys of the entries enclosing {@code row}, outermost first. */
    static List<String> ancestorKeys(Root root, Row row) {
        List<String> keys = new ArrayList<>();
        for (int length = 0; length < row.path().size(); length++) {
            keys.add(key(root, row.path().subList(0, length)));
        }
        return keys;
    }

    /**
     * The visible rows. Roots start expanded and other entries collapsed; {@code toggled} holds the keys of entries
     * switched from that default.
     */
    static List<Row> visible(List<Decoded> roots, Set<String> toggled) {
        return rows(roots, toggled, false);
    }

    /** Every entry of every root, as if all were expanded; used to search entries that are not visible. */
    static List<Row> all(List<Decoded> roots) {
        return rows(roots, Set.of(), true);
    }

    private static List<Row> rows(List<Decoded> roots, Set<String> toggled, boolean everything) {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            Decoded decoded = roots.get(index);
            if (decoded.tag() == null) {
                rows.add(new Row(key(decoded.root(), List.of()), Kind.UNREADABLE, index, List.of(), 0,
                        decoded.root().name(), null, false, false, 0, decoded.problem()));
                continue;
            }
            new Walk(decoded, index, toggled, everything, rows).add(decoded.root().name(), decoded.tag(),
                    new ArrayList<>(), 0);
        }
        return rows;
    }

    /**
     * Entries not transferred at or below {@code path}, from the root's omission list. A full list could not name every
     * omission, so then no part of the data counts as complete.
     */
    static int omittedBelow(FactData data, String path) {
        int omitted = 0;
        for (FactData.Omission omission : data.omissions()) {
            String at = omission.path();
            if (path.isEmpty() || at.equals(path) || at.startsWith(path)
                    && (at.charAt(path.length()) == '.' || at.charAt(path.length()) == '[')) {
                omitted += omission.count();
            }
        }
        return data.omissions().size() >= FactData.MAX_OMISSIONS ? Math.max(1, omitted) : omitted;
    }

    /** The one-line text of a value: its SNBT, shortened with an ellipsis beyond the preview length. */
    static String valueText(NbtData.Tag tag) {
        String snbt = NbtData.snbt(tag);
        return snbt.length() <= PREVIEW_LENGTH ? snbt : snbt.substring(0, PREVIEW_LENGTH - 1) + "…";
    }

    private static String summary(NbtData.Tag tag, int omitted) {
        int size = tag instanceof NbtData.CompoundTag compound ? compound.entries().size()
                : ((NbtData.ListTag) tag).items().size();
        String entries = size + (size == 1 ? " entry" : " entries");
        return omitted > 0 ? entries + " shown, " + omitted + " not transferred" : entries;
    }

    private record Walk(Decoded decoded, int root, Set<String> toggled, boolean everything, List<Row> rows) {
        void add(String name, NbtData.Tag tag, List<Object> path, int depth) {
            List<Object> own = List.copyOf(path);
            String key = key(this.decoded.root(), own);
            String pathText = NbtData.path(own);
            boolean container = tag instanceof NbtData.CompoundTag || tag instanceof NbtData.ListTag;
            boolean open = container && (this.everything || (depth == 0) != this.toggled.contains(key));
            int omitted = container ? omittedBelow(this.decoded.root().data(), pathText) : 0;
            this.rows.add(new Row(key, Kind.ENTRY, this.root, own, depth, name, tag, container, open, omitted,
                    open ? summary(tag, omitted) : valueText(tag)));
            if (!open) {
                return;
            }
            if (tag instanceof NbtData.CompoundTag compound) {
                for (Map.Entry<String, NbtData.Tag> entry : compound.entries().entrySet()) {
                    path.add(entry.getKey());
                    add(entry.getKey(), entry.getValue(), path, depth + 1);
                    path.removeLast();
                }
            } else if (tag instanceof NbtData.ListTag list) {
                for (int index = 0; index < list.items().size(); index++) {
                    path.add(index);
                    add("[" + index + "]", list.items().get(index), path, depth + 1);
                    path.removeLast();
                }
            }
            int direct = 0;
            for (FactData.Omission omission : this.decoded.root().data().omissions()) {
                if (omission.path().equals(pathText)) direct += omission.count();
            }
            if (direct > 0) {
                this.rows.add(new Row(key + "\u0002omitted", Kind.OMITTED, this.root, own, depth + 1,
                        direct + " more not transferred", null, false, false, direct, ""));
            }
        }
    }
}
