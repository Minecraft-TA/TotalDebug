package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload.ClassLink;
import java.util.List;
import java.util.Objects;

/** A block or entity selected in the world, described from the client's copy. */
public record WorldSubject(
        SubjectRef subject,
        String displayName,
        String registryId,
        String modName,
        List<ClassLink> classes
) {
    public WorldSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(modName, "modName");
        classes = List.copyOf(classes);
    }
}
