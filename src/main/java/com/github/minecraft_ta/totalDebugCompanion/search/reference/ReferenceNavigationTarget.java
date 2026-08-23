package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import org.eclipse.jdt.core.IJavaElement;

import java.util.Objects;

/** Converts a bytecode usage site to the existing Companion-to-game source request. */
public record ReferenceNavigationTarget(String className, int elementType, String identifier) {
    public static final int WHOLE_CLASS = -1;

    public ReferenceNavigationTarget {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(identifier, "identifier");
    }

    public static ReferenceNavigationTarget from(ReferenceLocation location) {
        Objects.requireNonNull(location, "location");
        return switch (location.site()) {
            case ReferenceLocation.ClassDeclaration ignored ->
                    new ReferenceNavigationTarget(location.className(), WHOLE_CLASS, "");
            case ReferenceLocation.Field field ->
                    new ReferenceNavigationTarget(location.className(), IJavaElement.FIELD, field.name());
            case ReferenceLocation.RecordComponent component ->
                    new ReferenceNavigationTarget(location.className(), IJavaElement.FIELD, component.name());
            case ReferenceLocation.Method method -> new ReferenceNavigationTarget(
                    location.className(),
                    IJavaElement.METHOD,
                    methodIdentifier(method)
            );
        };
    }

    public DecompileOrOpenMessage toMessage() {
        return new DecompileOrOpenMessage(this.className, this.elementType, this.identifier);
    }

    private static String methodIdentifier(ReferenceLocation.Method method) {
        return "<init>".equals(method.name())
                ? method.descriptor()
                : method.name() + method.descriptor();
    }
}
