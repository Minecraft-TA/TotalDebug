package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

import java.util.List;

/** The tabs of a mod page, in display order. */
public enum ModTab {
    OVERVIEW("Overview"),
    BLOCKS("Blocks"),
    ITEMS("Items"),
    ENTITIES("Entities"),
    CONFIGURATION("Configuration"),
    KEY_BINDINGS("Key bindings"),
    RESOURCES("Resources");

    /** The tabs that list registered content, which the pack's Content page lists for every mod. */
    public static final List<ModTab> CONTENT = List.of(BLOCKS, ITEMS, ENTITIES);

    private final String title;

    ModTab(String title) {
        this.title = title;
    }

    public String title() {
        return this.title;
    }

    /** The name of the tab's list outside the mod page's tab strip, where Entities reads as Entity types. */
    public String listTitle() {
        return this == ENTITIES ? "Entity types" : this.title;
    }

    /** The kind of content a Blocks, Items or Entities tab lists. */
    public SubjectRef.DefinitionKind contentKind() {
        return switch (this) {
            case BLOCKS -> SubjectRef.DefinitionKind.BLOCK;
            case ITEMS -> SubjectRef.DefinitionKind.ITEM;
            case ENTITIES -> SubjectRef.DefinitionKind.ENTITY_TYPE;
            default -> throw new IllegalStateException(this + " lists no content");
        };
    }
}
