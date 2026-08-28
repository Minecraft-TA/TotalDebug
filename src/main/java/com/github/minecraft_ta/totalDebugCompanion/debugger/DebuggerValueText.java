package com.github.minecraft_ta.totalDebugCompanion.debugger;

/** Shared text formatting for debugger trees and editor hints. */
public final class DebuggerValueText {
    private DebuggerValueText() {
    }

    public static String visibleValue(DebugEngine.Variable variable) {
        return visibleValue(variable.value(), variable.type());
    }

    public static String visibleValue(String value, String type) {
        if (value.isBlank()) {
            return value;
        }
        int separator = value.lastIndexOf('@');
        if (separator <= 0 || separator == value.length() - 1) {
            return value;
        }
        for (int index = separator + 1; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return value;
            }
        }
        String identityOwner = value.substring(0, separator);
        int array = identityOwner.indexOf('[');
        if (array >= 0) {
            return simpleTypeName(identityOwner.substring(0, array)) + identityOwner.substring(array);
        }
        return simpleTypeName(identityOwner);
    }

    public static String simpleTypeName(String type) {
        int separator = type.lastIndexOf('.');
        return separator < 0 ? type : type.substring(separator + 1);
    }

    public static String inlineValue(DebugEngine.Variable variable, DebugEngine.ValuePreview preview) {
        String value = preview.available() ? preview.summary() : visibleValue(variable);
        return variable.name() + ": " + value;
    }
}
