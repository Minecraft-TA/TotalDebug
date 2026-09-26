package com.github.minecraft_ta.totaldebug.protocol.inspection;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What currently occupies an inspected subject, as seen by the side that resolved it: the name and registry id shown
 * in the inspection header and used to select tools, the classes behind it and the item drawn as its icon, or empty.
 */
public record SubjectIdentity(
        Kind kind,
        String registryId,
        String displayName,
        String modName,
        List<ClassLink> classes,
        String iconItem
) {
    public static final int MAX_TEXT_LENGTH = 256;
    public static final int MAX_CLASSES = 8;

    public enum Kind {
        BLOCK,
        ENTITY
    }

    public SubjectIdentity {
        Objects.requireNonNull(kind, "kind");
        registryId = bounded(registryId, "registryId");
        displayName = bounded(displayName, "displayName");
        modName = bounded(modName, "modName");
        classes = List.copyOf(Objects.requireNonNullElse(classes, List.of()));
        if (classes.size() > MAX_CLASSES) {
            throw new IllegalArgumentException("Too many class links");
        }
        iconItem = bounded(iconItem, "iconItem");
    }

    /** The name shown for the subject: its display name, or its registry id when it has none. */
    public String title() {
        return this.displayName.isBlank() ? this.registryId : this.displayName;
    }

    public record ClassLink(String label, String binaryName) {
        public ClassLink {
            label = bounded(label, "label");
            binaryName = bounded(binaryName, "binaryName");
        }
    }

    public static SubjectIdentity read(ByteBufferInputStream input) {
        Kind kind = Kind.valueOf(input.readString());
        String registryId = input.readString();
        String displayName = input.readString();
        String modName = input.readString();
        int count = input.readInt();
        if (count < 0 || count > MAX_CLASSES) {
            throw new IllegalArgumentException("Invalid class link count: " + count);
        }
        List<ClassLink> classes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            classes.add(new ClassLink(input.readString(), input.readString()));
        }
        return new SubjectIdentity(kind, registryId, displayName, modName, classes, input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.kind.name());
        output.writeString(this.registryId);
        output.writeString(this.displayName);
        output.writeString(this.modName);
        output.writeInt(this.classes.size());
        for (ClassLink link : this.classes) {
            output.writeString(link.label());
            output.writeString(link.binaryName());
        }
        output.writeString(this.iconItem);
    }

    private static String bounded(String text, String name) {
        String value = Objects.requireNonNullElse(text, "");
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Subject " + name + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
