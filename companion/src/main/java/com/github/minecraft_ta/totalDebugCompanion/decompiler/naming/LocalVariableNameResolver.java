package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTableAttribute;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.Pair;
import org.jetbrains.java.decompiler.util.TextUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class LocalVariableNameResolver {
    private LocalVariableNameResolver() {
    }

    static Map<VarVersionPair, String> resolve(
            StructMethod method,
            Map<VarVersionPair, Pair<VarType, String>> variables,
            int parameterEnd
    ) {
        StructLocalVariableTableAttribute table = method.getLocalVariableAttr();
        if (table == null) {
            return Map.of();
        }

        List<Candidate> candidates = table.getVariables()
                .map(variable -> new Candidate(
                        variable.getVersion().var,
                        variable.getVarType(),
                        variable.getName()
                ))
                .filter(candidate -> candidate.name() != null
                        && TextUtil.isValidIdentifier(candidate.name(), method.getBytecodeVersion(), method))
                .distinct()
                .toList();
        Set<Candidate> claimed = new HashSet<>();
        if (!method.hasModifier(CodeConstants.ACC_STATIC)) {
            claimSlot(candidates, claimed, 0);
        }

        Map<VarVersionPair, String> resolved = new LinkedHashMap<>();
        variables.forEach((variable, typeName) -> {
            if (variable.var < parameterEnd) {
                String name = uniqueName(candidates, claimed, candidate -> candidate.slot() == variable.var);
                if (name != null) {
                    resolved.put(variable, name);
                    claimSlot(candidates, claimed, variable.var);
                }
                return;
            }
            if (typeName.a == null) {
                return;
            }
            String name = uniqueName(candidates, claimed, candidate -> candidate.slot() == variable.var
                    && candidate.type().equals(typeName.a));
            if (name != null) {
                resolved.put(variable, name);
                claim(candidates, claimed, candidate -> candidate.slot() == variable.var
                        && candidate.type().equals(typeName.a));
            }
        });

        Map<VarType, List<VarVersionPair>> unmatchedVariables = new LinkedHashMap<>();
        variables.forEach((variable, typeName) -> {
            if (variable.var >= parameterEnd && typeName.a != null && !resolved.containsKey(variable)) {
                unmatchedVariables.computeIfAbsent(typeName.a, ignored -> new ArrayList<>()).add(variable);
            }
        });
        unmatchedVariables.forEach((type, unmatched) -> {
            if (unmatched.size() != 1) {
                return;
            }
            String name = uniqueName(candidates, claimed, candidate -> candidate.type().equals(type));
            if (name != null) {
                resolved.put(unmatched.getFirst(), name);
            }
        });
        return Map.copyOf(resolved);
    }

    private static String uniqueName(
            List<Candidate> candidates,
            Set<Candidate> claimed,
            Predicate<Candidate> predicate
    ) {
        Set<String> names = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (!claimed.contains(candidate) && predicate.test(candidate)) {
                names.add(candidate.name());
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    private static void claimSlot(List<Candidate> candidates, Set<Candidate> claimed, int slot) {
        claim(candidates, claimed, candidate -> candidate.slot() == slot);
    }

    private static void claim(
            List<Candidate> candidates,
            Set<Candidate> claimed,
            Predicate<Candidate> predicate
    ) {
        candidates.stream().filter(predicate).forEach(claimed::add);
    }

    private record Candidate(int slot, VarType type, String name) {
    }
}
