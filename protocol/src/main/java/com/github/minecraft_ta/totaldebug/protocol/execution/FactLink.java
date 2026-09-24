package com.github.minecraft_ta.totaldebug.protocol.execution;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

import java.util.Objects;

/**
 * Where a fact leads when clicked: a class whose source opens ({@code CLASS}, a binary name) or another subject to
 * inspect ({@code SUBJECT}, a subject's text form). Companion decides how to open it.
 */
public record FactLink(Kind kind, String target) {
    public static final int MAX_TARGET_LENGTH = 512;

    public enum Kind {
        CLASS,
        SUBJECT
    }

    public FactLink {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        if (target.isBlank() || target.length() > MAX_TARGET_LENGTH) {
            throw new IllegalArgumentException("Invalid fact link target");
        }
    }

    public static FactLink toClass(String binaryName) {
        return new FactLink(Kind.CLASS, binaryName);
    }

    public static FactLink toSubject(SubjectRef subject) {
        return new FactLink(Kind.SUBJECT, subject.format());
    }
}
