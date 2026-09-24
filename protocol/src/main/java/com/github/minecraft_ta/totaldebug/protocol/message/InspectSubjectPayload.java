package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-20 payload. The client's description of a subject selected with the inspect key. {@code gameSessionId}
 * identifies the joined world, so a later run can be rejected once that world is left. {@code identity} is what the
 * client saw at selection; reads report the current identity. {@code iconModel} is the item model shown for the
 * subject, or empty, and {@code iconTints} maps its tint indexes to ARGB colors.
 */
public record InspectSubjectPayload(
        String gameSessionId,
        String subject,
        SubjectIdentity identity,
        String iconModel,
        Map<Integer, Integer> iconTints
) {
    public static final int MAX_TINTS = 32;

    public InspectSubjectPayload {
        Objects.requireNonNull(gameSessionId, "gameSessionId");
        SubjectRef.parseWorld(subject);
        Objects.requireNonNull(identity, "identity");
        iconModel = Objects.requireNonNullElse(iconModel, "");
        iconTints = Map.copyOf(Objects.requireNonNullElse(iconTints, Map.of()));
        if (iconTints.size() > MAX_TINTS) {
            throw new IllegalArgumentException("Too many icon tints");
        }
    }

    public static InspectSubjectPayload read(ByteBufferInputStream input) {
        String gameSessionId = input.readString();
        String subject = input.readString();
        SubjectIdentity identity = SubjectIdentity.read(input);
        String iconModel = input.readString();
        int tintCount = input.readInt();
        if (tintCount < 0 || tintCount > MAX_TINTS) {
            throw new IllegalArgumentException("Invalid icon tint count: " + tintCount);
        }
        Map<Integer, Integer> tints = new LinkedHashMap<>();
        for (int index = 0; index < tintCount; index++) {
            tints.put(input.readInt(), input.readInt());
        }
        return new InspectSubjectPayload(gameSessionId, subject, identity, iconModel, tints);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.gameSessionId);
        output.writeString(this.subject);
        this.identity.write(output);
        output.writeString(this.iconModel);
        output.writeInt(this.iconTints.size());
        for (Map.Entry<Integer, Integer> tint : this.iconTints.entrySet()) {
            output.writeInt(tint.getKey());
            output.writeInt(tint.getValue());
        }
    }
}
