package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import org.eclipse.jdt.core.IJavaElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceNavigationTargetTest {
    @Test
    void mapsUsageSitesToExistingSourceTargets() {
        assertEquals(
                new ReferenceNavigationTarget("example.Use", ReferenceNavigationTarget.WHOLE_CLASS, ""),
                ReferenceNavigationTarget.from(ReferenceLocation.classDeclaration("example.Use"))
        );
        assertEquals(
                new ReferenceNavigationTarget("example.Use", IJavaElement.FIELD, "value"),
                ReferenceNavigationTarget.from(ReferenceLocation.field("example.Use", "value", "Ljava/lang/String;"))
        );
        assertEquals(
                new ReferenceNavigationTarget("example.Use", IJavaElement.FIELD, "name"),
                ReferenceNavigationTarget.from(
                        ReferenceLocation.recordComponent("example.Use", "name", "Ljava/lang/String;")
                )
        );
        assertEquals(
                new ReferenceNavigationTarget("example.Use", IJavaElement.METHOD, "tick(I)V"),
                ReferenceNavigationTarget.from(ReferenceLocation.method("example.Use", "tick", "(I)V"))
        );
        assertEquals(
                new ReferenceNavigationTarget("example.Use", IJavaElement.METHOD, "(I)V"),
                ReferenceNavigationTarget.from(ReferenceLocation.method("example.Use", "<init>", "(I)V"))
        );
    }
}
