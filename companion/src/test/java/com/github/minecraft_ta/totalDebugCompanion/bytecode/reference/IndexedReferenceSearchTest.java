package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.ReferenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.REFERENCES;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.TARGET;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.classReferenceLocations;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.hierarchyReferencesFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.implementationFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.interfaceFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.interfaceReferencesFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.location;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.memberOwnerFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.referenceFixture;
import static com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceFixtures.targetDeclarationFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedReferenceSearchTest {
    @Test
    void matchesTheExistingScannerForClassFieldAndMethodReferences() {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                referenceFixture(REFERENCES),
                targetDeclarationFixture()
        ))) {
            IndexedReferenceSearch search = new IndexedReferenceSearch(index);

            assertEquals(
                    classReferenceLocations(),
                    locations(search.search(ReferenceQuery.classReference("fixture.Target"), 100))
            );
            assertEquals(
                    List.of(location("methodHandle", "()V"), location("methodNoArgs", "()V")),
                    locations(search.search(ReferenceQuery.methodReference("fixture.Target", "run", "()V"), 100))
            );
            assertEquals(
                    List.of(location("methodInt", "()V")),
                    locations(search.search(ReferenceQuery.methodReference("fixture.Target", "run", "(I)V"), 100))
            );
            assertEquals(
                    List.of(location("fieldInt", "()V")),
                    locations(search.search(ReferenceQuery.fieldReference("fixture.Target", "VALUE", "I"), 100))
            );
            assertEquals(
                    List.of(location("fieldString", "()V")),
                    locations(search.search(
                            ReferenceQuery.fieldReference("fixture.Target", "VALUE", "Ljava/lang/String;"),
                            100
                    ))
            );

            ReferenceUsagePage limited = search.search(ReferenceQuery.classReference("fixture.Target"), 1);
            assertEquals(1, limited.usages().size());
            assertTrue(limited.truncated());
            assertFalse(search.search(ReferenceQuery.classReference("fixture.Target"), 100).truncated());
        }
    }

    @Test
    void matchesInheritedClassMemberResolution() {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                memberOwnerFixture(TARGET, "java/lang/Object", true),
                memberOwnerFixture("fixture/Sub", TARGET, false),
                memberOwnerFixture("fixture/Override", TARGET, true),
                hierarchyReferencesFixture()
        ))) {
            IndexedReferenceSearch search = new IndexedReferenceSearch(index);
            assertEquals(
                    List.of(ReferenceLocation.method("fixture.HierarchyReferences", "inheritedMethod", "()V")),
                    locations(search.search(ReferenceQuery.methodReference("fixture.Target", "run", "()V"), 100))
            );
            assertEquals(
                    List.of(ReferenceLocation.method("fixture.HierarchyReferences", "inheritedField", "()V")),
                    locations(search.search(ReferenceQuery.fieldReference("fixture.Target", "VALUE", "I"), 100))
            );
        }
    }

    @Test
    void matchesInheritedInterfaceMethodResolution() {
        String target = "fixture/InterfaceTarget";
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                interfaceFixture(target, null, true),
                interfaceFixture("fixture/ChildInterface", target, false),
                interfaceFixture("fixture/OverrideInterface", target, true),
                implementationFixture("fixture/InterfaceImpl", "fixture/ChildInterface"),
                implementationFixture("fixture/OverrideImpl", "fixture/OverrideInterface"),
                interfaceReferencesFixture()
        ))) {
            assertEquals(
                    List.of(ReferenceLocation.method("fixture.InterfaceReferences", "inheritedInterface", "()V")),
                    locations(new IndexedReferenceSearch(index)
                            .search(
                                    ReferenceQuery.methodReference("fixture.InterfaceTarget", "run", "()V"),
                                    100
                            ))
            );
        }
    }

    @Test
    void preservesRelationKindsAndOccurrenceCounts() {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                referenceFixture(REFERENCES),
                targetDeclarationFixture()
        ))) {
            IndexedReferenceSearch search = new IndexedReferenceSearch(index);
            ReferenceUsage fieldUsage = search.search(
                    ReferenceQuery.fieldReference("fixture.Target", "VALUE", "I"),
                    100
            ).usages().getFirst();
            assertEquals(Set.of(ReferenceKind.FIELD_READ), fieldUsage.kinds());
            assertEquals(1, fieldUsage.occurrenceCount());

            List<ReferenceUsage> methodUsages = search.search(
                    ReferenceQuery.methodReference("fixture.Target", "run", "()V"),
                    100
            ).usages();
            assertEquals(Set.of(ReferenceKind.METHOD_HANDLE), methodUsages.getFirst().kinds());
            assertEquals(Set.of(ReferenceKind.METHOD_INVOKE), methodUsages.getLast().kinds());
        }
    }

    private static List<ReferenceLocation> locations(ReferenceUsagePage page) {
        return page.usages().stream().map(ReferenceUsage::location).toList();
    }
}
