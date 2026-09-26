package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

/** A run bound to one subject found something else there, for example a furnace replaced by a chest. */
public final class SubjectChangedException extends IllegalStateException {
    public SubjectChangedException(SubjectRef subject, String expectedId, String currentId) {
        super("The target changed: " + subject.format() + " is now " + currentId + ", not " + expectedId);
    }
}
