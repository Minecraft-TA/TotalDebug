package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-18 payload. The client's description of a subject selected with the inspect key. {@code gameSessionId}
 * identifies the joined world, so a later run can be rejected once that world is left. {@code iconModel} is the item
 * model shown for the subject, or empty, and {@code iconTints} maps its tint indexes to ARGB colors.
 */
public record InspectSubjectPayload(
        String gameSessionId,
        String subject,
        String displayName,
        String registryId,
        String modName,
        List<ClassLink> classes,
        String iconModel,
        Map<Integer, Integer> iconTints
) {
    public static final int MAX_CLASSES = 8;
    public static final int MAX_TINTS = 32;

    public InspectSubjectPayload {
        Objects.requireNonNull(gameSessionId, "gameSessionId");
        SubjectRef.parse(subject);
        displayName = Objects.requireNonNullElse(displayName, "");
        registryId = Objects.requireNonNullElse(registryId, "");
        modName = Objects.requireNonNullElse(modName, "");
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
        if (classes.size() > MAX_CLASSES) {
            throw new IllegalArgumentException("Too many class links");
        }
        iconModel = Objects.requireNonNullElse(iconModel, "");
        iconTints = Map.copyOf(Objects.requireNonNullElse(iconTints, Map.of()));
        if (iconTints.size() > MAX_TINTS) {
            throw new IllegalArgumentException("Too many icon tints");
        }
    }

    public record ClassLink(String label, String binaryName) {
        public ClassLink {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(binaryName, "binaryName");
        }
    }

    public static InspectSubjectPayload read(ByteBufferInputStream input) {
        String gameSessionId = input.readString();
        String subject = input.readString();
        String displayName = input.readString();
        String registryId = input.readString();
        String modName = input.readString();
        int count = input.readInt();
        if (count < 0 || count > MAX_CLASSES) {
            throw new IllegalArgumentException("Invalid class link count: " + count);
        }
        List<ClassLink> classes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            classes.add(new ClassLink(input.readString(), input.readString()));
        }
        String iconModel = input.readString();
        int tintCount = input.readInt();
        if (tintCount < 0 || tintCount > MAX_TINTS) {
            throw new IllegalArgumentException("Invalid icon tint count: " + tintCount);
        }
        Map<Integer, Integer> tints = new LinkedHashMap<>();
        for (int index = 0; index < tintCount; index++) {
            tints.put(input.readInt(), input.readInt());
        }
        return new InspectSubjectPayload(gameSessionId, subject, displayName, registryId, modName, classes,
                iconModel, tints);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.gameSessionId);
        output.writeString(this.subject);
        output.writeString(this.displayName);
        output.writeString(this.registryId);
        output.writeString(this.modName);
        output.writeInt(this.classes.size());
        for (ClassLink link : this.classes) {
            output.writeString(link.label());
            output.writeString(link.binaryName());
        }
        output.writeString(this.iconModel);
        output.writeInt(this.iconTints.size());
        for (Map.Entry<Integer, Integer> tint : this.iconTints.entrySet()) {
            output.writeInt(tint.getKey());
            output.writeInt(tint.getValue());
        }
    }
}
