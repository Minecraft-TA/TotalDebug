package com.github.minecraft_ta.totaldebug.decompiler.naming;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class ParchmentParameterResolver {
    private static final ParchmentParameterIndex INDEX = ParchmentParameterIndex.load();

    private ParchmentParameterResolver() {
    }

    static Map<Integer, String> resolve(StructMethod method) {
        String owner = method.getClassQualifiedName();
        Map<Integer, String> exact = INDEX.find(owner, method.getName(), method.getDescriptor());
        if (!exact.isEmpty()) {
            return exact;
        }
        if (method.getName().startsWith("<") || method.hasModifier(CodeConstants.ACC_STATIC)
                || method.hasModifier(CodeConstants.ACC_PRIVATE)) {
            return Map.of();
        }

        StructClass declaringClass = DecompilerContext.getStructContext().getClass(owner);
        if (declaringClass == null) {
            return Map.of();
        }
        return findInherited(declaringClass, method.getName(), method.getDescriptor());
    }

    private static Map<Integer, String> findInherited(
            StructClass declaringClass,
            String methodName,
            String descriptor
    ) {
        Queue<StructClass> currentLevel = new ArrayDeque<>(directParents(declaringClass));
        Set<String> visited = new HashSet<>();
        while (!currentLevel.isEmpty()) {
            Queue<StructClass> nextLevel = new ArrayDeque<>();
            List<Map<Integer, String>> candidates = new ArrayList<>();
            while (!currentLevel.isEmpty()) {
                StructClass parent = currentLevel.remove();
                if (!visited.add(parent.qualifiedName)) {
                    continue;
                }

                StructMethod declaration = parent.getMethod(methodName, descriptor);
                if (declaration != null && !declaration.hasModifier(CodeConstants.ACC_STATIC)
                        && !declaration.hasModifier(CodeConstants.ACC_PRIVATE)) {
                    Map<Integer, String> names = INDEX.find(parent.qualifiedName, methodName, descriptor);
                    if (!names.isEmpty()) {
                        candidates.add(names);
                    }
                }
                nextLevel.addAll(directParents(parent));
            }

            if (!candidates.isEmpty()) {
                return agree(candidates);
            }
            currentLevel = nextLevel;
        }
        return Map.of();
    }

    private static Map<Integer, String> agree(List<Map<Integer, String>> candidates) {
        Map<Integer, String> first = candidates.getFirst();
        return candidates.stream().allMatch(first::equals) ? first : Map.of();
    }

    private static List<StructClass> directParents(StructClass type) {
        Set<String> parentNames = new LinkedHashSet<>();
        PrimitiveConstant superClass = type.superClass;
        if (superClass != null) {
            parentNames.add(superClass.getString());
        }
        parentNames.addAll(List.of(type.getInterfaceNames()));

        List<StructClass> parents = new ArrayList<>(parentNames.size());
        for (String parentName : parentNames) {
            StructClass parent = DecompilerContext.getStructContext().getClass(parentName);
            if (parent != null) {
                parents.add(parent);
            }
        }
        return parents;
    }
}
