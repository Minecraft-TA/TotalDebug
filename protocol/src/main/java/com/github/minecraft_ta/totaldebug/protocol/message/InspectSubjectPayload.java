package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-17 payload. The client's description of a subject selected with the inspect key. {@code gameSessionId}
 * identifies the joined world, so a later run can be rejected once that world is left.
 */
public record InspectSubjectPayload(
        String gameSessionId,
        String subject,
        String displayName,
        String registryId,
        String modName,
        List<ClassLink> classes
) {
    public static final int MAX_CLASSES = 8;

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
        return new InspectSubjectPayload(gameSessionId, subject, displayName, registryId, modName, classes);
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
    }
}
