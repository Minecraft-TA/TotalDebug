package com.github.minecraft_ta.totalDebugCompanion.navigation;

import org.eclipse.jdt.core.IJavaElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NavigationTargetsTest {
    @Test
    void adaptsClassFieldMethodAndDefaultConstructorSelections() {
        assertEquals(
                new NavigationTarget.RuntimeClass("example.Target"),
                NavigationTargets.fromClassOpen("example.Target", -1, "")
        );
        assertEquals(
                new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Field("example.Target", "value")),
                NavigationTargets.fromClassOpen("example.Target", IJavaElement.FIELD, "value")
        );
        assertEquals(
                new NavigationTarget.RuntimeDeclaration(
                        new RuntimeMember.Method("example.Target", "apply", "(Ljava/lang/String;)V")
                ),
                NavigationTargets.fromClassOpen(
                        "example.Target",
                        IJavaElement.METHOD,
                        "Lexample/Target;.apply(Ljava/lang/String;)V"
                )
        );
        assertEquals(
                new NavigationTarget.RuntimeDeclaration(
                        new RuntimeMember.Method("example.Target", "<init>", "()V")
                ),
                NavigationTargets.fromClassOpen("example.Target", IJavaElement.METHOD, "()V")
        );
    }

    @Test
    void rejectsUnknownProtocolElementKinds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NavigationTargets.fromClassOpen("example.Target", IJavaElement.LOCAL_VARIABLE, "value")
        );
    }
}
