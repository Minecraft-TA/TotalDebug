package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
     * One visible row. {@code tag} is null for rows standing for missing data. {@code omitted} counts the entries not
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
                case NbtData.ListTag list -> "list · " + list.items().size();
                case NbtData.ByteArrayTag array -> "byte[] · " + array.values().size();
                case NbtData.IntArrayTag array -> "int[] · " + array.values().size();
                case NbtData.LongArrayTag array -> "long[] · " + array.values().size();
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

    /**
     * The visible rows. Roots start expanded and other entries collapsed; {@code toggled} holds the keys of entries
     * switched from that default. With a filter, only matching entries and the entries leading to them show,
     * expanded as far as needed to reach every match.
     */
    static List<Row> visible(List<Decoded> roots, Set<String> toggled, String filter) {
        List<String> terms = terms(filter);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            Decoded decoded = roots.get(index);
            if (decoded.tag() == null) {
                if (terms.isEmpty() || matches(decoded.root().name(), terms)) {
                    rows.add(new Row(key(decoded.root(), List.of()), Kind.UNREADABLE, index, List.of(), 0,
                            decoded.root().name(), null, false, false, 0, decoded.problem()));
                }
                continue;
            }
            new Walk(decoded, index, toggled, terms, rows).add(decoded.root().name(), decoded.tag(),
                    new ArrayList<>(), 0);
        }
        return rows;
    }

    /** Entries not transferred at or below {@code path}, from the root's omission list. */
    static int omittedBelow(FactData data, String path) {
        int omitted = 0;
        for (FactData.Omission omission : data.omissions()) {
            String at = omission.path();
            if (path.isEmpty() || at.equals(path) || at.startsWith(path)
                    && (at.charAt(path.length()) == '.' || at.charAt(path.length()) == '[')) {
                omitted += omission.count();
            }
        }
        return omitted;
    }

    /** The one-line text of a value: its SNBT, shortened with an ellipsis beyond the preview length. */
    static String valueText(NbtData.Tag tag) {
        String snbt = NbtData.snbt(tag);
        return snbt.length() <= PREVIEW_LENGTH ? snbt : snbt.substring(0, PREVIEW_LENGTH - 1) + "…";
    }

    private static List<String> terms(String filter) {
        List<String> terms = new ArrayList<>();
        for (String term : Objects.requireNonNullElse(filter, "").toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!term.isEmpty()) terms.add(term);
        }
        return terms;
    }

    private static boolean matches(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!lower.contains(term)) return false;
        }
        return true;
    }

    private static String summary(NbtData.Tag tag, int omitted) {
        int size = tag instanceof NbtData.CompoundTag compound ? compound.entries().size()
                : ((NbtData.ListTag) tag).items().size();
        String entries = size + (size == 1 ? " entry" : " entries");
        return omitted > 0 ? entries + " shown, " + omitted + " not transferred" : entries;
    }

    private record Walk(Decoded decoded, int root, Set<String> toggled, List<String> terms, List<Row> rows) {
        /** Adds the entry and what shows below it; returns whether anything was added. */
        boolean add(String name, NbtData.Tag tag, List<Object> path, int depth) {
            List<Object> own = List.copyOf(path);
            String key = key(this.decoded.root(), own);
            String pathText = NbtData.path(own);
            boolean container = tag instanceof NbtData.CompoundTag || tag instanceof NbtData.ListTag;
            boolean filtering = !this.terms.isEmpty();
            boolean open = container && (depth == 0) != this.toggled.contains(key);
            boolean matched = !filtering || matches(name + " " + (container ? "" : NbtData.snbt(tag)), this.terms);

            int position = this.rows.size();
            this.rows.add(null);
            boolean childAdded = false;
            if (container && (open || filtering)) {
                childAdded = children(tag, path, depth + 1);
            }
            if (!matched && !childAdded) {
                this.rows.subList(position, this.rows.size()).clear();
                return false;
            }
            boolean shownOpen = container && (filtering ? childAdded : open);
            if (!shownOpen) {
                this.rows.subList(position + 1, this.rows.size()).clear();
            } else {
                int direct = directOmission(pathText);
                if (direct > 0) {
                    this.rows.add(new Row(key + "\u0002omitted", Kind.OMITTED, this.root, own, depth + 1,
                            direct + " more not transferred", null, false, false, direct, ""));
                }
            }
            int omitted = container ? omittedBelow(this.decoded.root().data(), pathText) : 0;
            this.rows.set(position, new Row(key, Kind.ENTRY, this.root, own, depth, name, tag, container, shownOpen,
                    omitted, shownOpen ? summary(tag, omitted) : valueText(tag)));
            return true;
        }

        private boolean children(NbtData.Tag tag, List<Object> path, int depth) {
            boolean added = false;
            if (tag instanceof NbtData.CompoundTag compound) {
                for (Map.Entry<String, NbtData.Tag> entry : compound.entries().entrySet()) {
                    path.add(entry.getKey());
                    added |= add(entry.getKey(), entry.getValue(), path, depth);
                    path.removeLast();
                }
            } else if (tag instanceof NbtData.ListTag list) {
                for (int index = 0; index < list.items().size(); index++) {
                    path.add(index);
                    added |= add("[" + index + "]", list.items().get(index), path, depth);
                    path.removeLast();
                }
            }
            return added;
        }

        private int directOmission(String pathText) {
            int omitted = 0;
            for (FactData.Omission omission : this.decoded.root().data().omissions()) {
                if (omission.path().equals(pathText)) omitted += omission.count();
            }
            return omitted;
        }
    }
}
