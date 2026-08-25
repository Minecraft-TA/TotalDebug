package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.ReferenceResult;
import com.github.tth05.jindex.ReferenceSearchPage;
import com.github.tth05.jindex.ReferenceTarget;

import java.util.Objects;
import java.util.ArrayList;

/** Adapts JIndex's persisted reference graph to Companion navigation locations. */
public final class IndexedReferenceSearch {
    private final ClassIndex index;

    public IndexedReferenceSearch(ClassIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public ReferenceUsagePage search(ReferenceQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        ReferenceSearchPage page = switch (query) {
            case ReferenceQuery.StringLiteralReference literal ->
                    this.index.findLiteralReferences(literal.value(), limit);
            default -> this.index.findReferences(toTarget(query), limit);
        };
        ArrayList<ReferenceUsage> usages = new ArrayList<>(page.results().length);
        for (ReferenceResult result : page.results()) {
            usages.add(new ReferenceUsage(
                    result.siteId(),
                    toLocation(result),
                    result.sourceId(),
                    result.kinds(),
                    result.occurrenceCount()
            ));
        }
        usages.sort(null);
        return new ReferenceUsagePage(usages, page.truncated());
    }

    private static ReferenceTarget toTarget(ReferenceQuery query) {
        return switch (query) {
            case ReferenceQuery.ClassReference classReference ->
                    ReferenceTarget.classTarget(internalName(classReference.className()));
            case ReferenceQuery.FieldReference fieldReference -> ReferenceTarget.fieldTarget(
                    internalName(fieldReference.ownerClassName()),
                    fieldReference.name(),
                    fieldReference.descriptor()
            );
            case ReferenceQuery.MethodReference methodReference -> ReferenceTarget.methodTarget(
                    internalName(methodReference.ownerClassName()),
                    methodReference.name(),
                    methodReference.descriptor()
            );
            case ReferenceQuery.StringLiteralReference ignored -> throw new IllegalArgumentException(
                    "String literals are not symbol targets"
            );
        };
    }

    private static ReferenceLocation toLocation(ReferenceResult result) {
        String className = result.ownerInternalName().replace('/', '.');
        return switch (result.kind()) {
            case CLASS -> ReferenceLocation.classDeclaration(className);
            case FIELD -> ReferenceLocation.field(className, result.name(), result.descriptor());
            case METHOD -> ReferenceLocation.method(className, result.name(), result.descriptor());
            case RECORD_COMPONENT -> ReferenceLocation.recordComponent(
                    className,
                    result.name(),
                    result.descriptor()
            );
        };
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
