package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Type;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JDI member/type lookup shared by evaluation and completion. */
final class DebuggerJdiMembers {
    private DebuggerJdiMembers() {
    }

    static List<Field> fields(ReferenceType type) {
        List<Field> result = new ArrayList<>();
        collectFields(type, result, new HashSet<>());
        return result;
    }

    private static void collectFields(ReferenceType type, List<Field> result, Set<String> visitedTypes) {
        if (type == null || !visitedTypes.add(type.name())) return;
        result.addAll(type.fields());
        if (type instanceof ClassType classType) {
            collectFields(classType.superclass(), result, visitedTypes);
            for (ReferenceType iface : classType.interfaces()) collectFields(iface, result, visitedTypes);
        } else if (type instanceof InterfaceType interfaceType) {
            for (ReferenceType iface : interfaceType.superinterfaces()) collectFields(iface, result, visitedTypes);
        }
    }

    static List<Method> methods(ReferenceType type) {
        List<Method> result = new ArrayList<>();
        collectMethods(type, result, new HashSet<>());
        return result;
    }

    private static void collectMethods(ReferenceType type, List<Method> result, Set<String> visitedTypes) {
        if (type == null || !visitedTypes.add(type.name())) return;
        result.addAll(type.methods());
        if (type instanceof ClassType classType) {
            collectMethods(classType.superclass(), result, visitedTypes);
            for (ReferenceType iface : classType.interfaces()) collectMethods(iface, result, visitedTypes);
        } else if (type instanceof InterfaceType interfaceType) {
            for (ReferenceType iface : interfaceType.superinterfaces()) collectMethods(iface, result, visitedTypes);
        }
    }

    static Field findField(ReferenceType type, String name, boolean staticOnly) {
        return fields(type).stream()
                .filter(field -> field.name().equals(name) && (!staticOnly || field.isStatic()))
                .findFirst().orElse(null);
    }

    static ReferenceType resolveType(String name, VirtualMachine vm) {
        String normalized = name.replace("...", "[]");
        if (isPrimitive(normalized)) return null;
        List<ReferenceType> exact = vm.classesByName(normalized);
        if (!exact.isEmpty()) return exact.getFirst();
        String nestedName = normalized;
        int dot = nestedName.lastIndexOf('.');
        while (dot > 0) {
            nestedName = nestedName.substring(0, dot) + "$" + nestedName.substring(dot + 1);
            exact = vm.classesByName(nestedName);
            if (!exact.isEmpty()) return exact.getFirst();
            dot = nestedName.lastIndexOf('.', dot - 1);
        }
        return null;
    }

    static boolean isAssignableName(String source, String target, VirtualMachine vm) {
        ReferenceType sourceType = resolveType(source, vm);
        ReferenceType targetType = resolveType(target, vm);
        return isAssignable(sourceType, targetType);
    }

    static boolean isAssignable(ReferenceType source, ReferenceType target) {
        if (source == null || target == null) return false;
        if (source.name().equals(target.name())) return true;
        if (source instanceof ClassType classType) {
            if (isAssignable(classType.superclass(), target)) return true;
            return classType.interfaces().stream().anyMatch(iface -> isAssignable(iface, target));
        }
        if (source instanceof InterfaceType iface) {
            return iface.superinterfaces().stream().anyMatch(parent -> isAssignable(parent, target));
        }
        return false;
    }

    static boolean isPrimitive(String name) {
        return "void".equals(name) || DebuggerPrimitiveKind.fromPrimitiveName(name) != null;
    }
}
