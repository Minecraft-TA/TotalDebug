package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.io.IOException;
import java.util.Objects;

/** Indicates that the requested item could not be rendered from the supplied resources. */
public class ItemRenderException extends IOException {

    private final Kind kind;
    private final String detail;

    public ItemRenderException(String message) {
        this(Kind.RENDER_ERROR, message, message, null);
    }

    public ItemRenderException(String message, Throwable cause) {
        this(Kind.RENDER_ERROR, message, message, cause);
    }

    public ItemRenderException(Kind kind, String detail, String message) {
        this(kind, detail, message, null);
    }

    public ItemRenderException(Kind kind, String detail, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public Kind kind() {
        return this.kind;
    }

    /** Stable grouping key for diagnostics, such as the unsupported loader identifier. */
    public String detail() {
        return this.detail;
    }

    public enum Kind {
        UNSUPPORTED_FEATURE,
        MISSING_RESOURCE,
        INVALID_MODEL,
        NO_GEOMETRY,
        RESOURCE_ERROR,
        RENDER_ERROR
    }
}
