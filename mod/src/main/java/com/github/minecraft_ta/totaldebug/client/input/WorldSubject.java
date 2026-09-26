package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import java.util.Objects;
import java.util.Optional;

/** A block or entity selected in the world, described from the client's copy. */
public record WorldSubject(SubjectRef subject, SubjectIdentity identity, Optional<ItemIcons.Icon> icon) {
    public WorldSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(icon, "icon");
    }
}
