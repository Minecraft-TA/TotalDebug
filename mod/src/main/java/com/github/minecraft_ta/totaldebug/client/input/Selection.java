package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import java.util.Objects;
import java.util.Optional;

/**
 * What the inspect key selected, described from the client's copy: something in the game, such as a block, an entity or
 * a stack in a slot, or an item's definition when the item is shown in no slot, such as in a recipe viewer.
 */
public record Selection(SubjectRef subject, SubjectIdentity identity, Optional<ItemIcons.Icon> icon) {
    public Selection {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(icon, "icon");
    }
}
