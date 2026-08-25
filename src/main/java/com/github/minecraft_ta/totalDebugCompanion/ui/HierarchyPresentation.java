package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyDirection;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;

import javax.swing.Icon;

/** Shared wording and icons for hierarchy relationships across editor surfaces. */
public final class HierarchyPresentation {
    private HierarchyPresentation() {
    }

    public static Icon gutterIcon(HierarchyRelation relation) {
        return switch (relation) {
            case IMPLEMENTED_BY -> Icons.IMPLEMENTED_METHOD;
            case OVERRIDDEN_BY -> Icons.OVERRIDDEN_METHOD;
            case IMPLEMENTS -> Icons.IMPLEMENTING_METHOD;
            case OVERRIDES -> Icons.OVERRIDING_METHOD;
            case SUBTYPES -> Icons.OVERRIDDEN_METHOD;
        };
    }

    public static String codeVisionCount(HierarchyRelation relation, int count) {
        String singular = switch (relation) {
            case IMPLEMENTED_BY -> "implementation";
            case OVERRIDDEN_BY -> "override";
            case SUBTYPES -> "subtype";
            case IMPLEMENTS, OVERRIDES -> throw new IllegalArgumentException(
                    "Base relationships are not displayed in code vision"
            );
        };
        return count + " " + (count == 1 ? singular : singular + 's');
    }

    public static String chooserAction(HierarchyRelation relation) {
        return switch (relation) {
            case IMPLEMENTED_BY -> "Choose implementation of ";
            case OVERRIDDEN_BY -> "Choose override of ";
            case SUBTYPES -> "Choose subtype of ";
            case IMPLEMENTS, OVERRIDES -> "Choose base declaration of ";
        };
    }

    public static String emptyResult(HierarchyRelation relation) {
        return switch (relation) {
            case IMPLEMENTED_BY -> "No implementations found";
            case OVERRIDDEN_BY -> "No overrides found";
            case SUBTYPES -> "No subtypes found";
            case IMPLEMENTS, OVERRIDES -> "No base declarations found";
        };
    }

    public static String previewTitle(HierarchyRelation relation, int count, boolean mixedBaseRelations) {
        if (mixedBaseRelations) {
            if (relation.direction() != HierarchyDirection.BASE_METHODS) {
                throw new IllegalArgumentException("Only base declarations can have mixed relationship semantics");
            }
            return "Overrides and implements";
        }
        return switch (relation) {
            case IMPLEMENTED_BY -> count == 1 ? "Is implemented in" : "Is implemented in " + count + " types";
            case OVERRIDDEN_BY -> count == 1 ? "Is overridden in" : "Is overridden in " + count + " subclasses";
            case SUBTYPES -> count == 1 ? "Has subtype" : "Has " + count + " subtypes";
            case IMPLEMENTS -> "Implements";
            case OVERRIDES -> "Overrides";
        };
    }
}
