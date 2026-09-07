package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totaldebug.protocol.navigation.SourceTargetKind;

/** Adapts protocol-level source selections into semantic navigation targets. */
public final class NavigationTargets {
    private NavigationTargets() {
    }

    public static NavigationTarget fromClassOpen(
            String binaryName,
            int targetType,
            String targetIdentifier
    ) {
        if (targetType == SourceTargetKind.WHOLE_CLASS) {
            return new NavigationTarget.RuntimeClass(binaryName);
        }
        if (targetType == SourceTargetKind.FIELD) {
            return new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Field(binaryName, targetIdentifier));
        }
        if (targetType == SourceTargetKind.METHOD) {
            int descriptorStart = targetIdentifier.indexOf('(');
            if (descriptorStart < 0) {
                throw new IllegalArgumentException("Method target has no JVM descriptor: " + targetIdentifier);
            }
            String qualifiedName = targetIdentifier.substring(0, descriptorStart);
            int ownerSeparator = qualifiedName.lastIndexOf('.');
            String name = qualifiedName.substring(ownerSeparator + 1);
            return new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Method(
                    binaryName,
                    name.isEmpty() ? "<init>" : name,
                    targetIdentifier.substring(descriptorStart)
            ));
        }
        throw new IllegalArgumentException("Unknown source target type: " + targetType);
    }
}
