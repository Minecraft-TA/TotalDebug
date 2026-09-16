package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import com.github.minecraft_ta.totalDebugCompanion.naming.GeneratedVariableNames;
import com.github.minecraft_ta.totalDebugCompanion.naming.JadLikeNameGenerator;
import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.extern.IVariableNameProvider;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLineNumberTableAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTableAttribute;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SelectiveVariableNameProvider implements IVariableNameProvider {
    private static final String MINECRAFT_PACKAGE = "net/minecraft/";
    private final StructMethod method;
    private final boolean minecraftMethod;
    private final Map<Integer, String> parameterNames;
    private final Map<Integer, String> methodParameterNames;
    private final JadLikeNameGenerator nameGenerator = new JadLikeNameGenerator();

    SelectiveVariableNameProvider(StructMethod method) {
        this.method = method;
        this.minecraftMethod = method.getClassQualifiedName().startsWith(MINECRAFT_PACKAGE);
        this.methodParameterNames = DecompilerParameterNames.originals(method);
        this.parameterNames = new LinkedHashMap<>(DecompilerParameterNames.resolve(method, this.methodParameterNames));
        this.nameGenerator.reserve(this.parameterNames.values());
    }

    @Override
    public synchronized Map<VarVersionPair, String> rename(
            Map<VarVersionPair, Pair<VarType, String>> variables
    ) {
        Map<Integer, List<String>> localVariableNamesBySlot = readLocalVariableNamesBySlot(this.method);
        localVariableNamesBySlot.values().forEach(this.nameGenerator::reserve);
        this.nameGenerator.reserve(this.methodParameterNames.values());
        this.nameGenerator.reserve(this.parameterNames.values());

        int parameterEnd = parameterEnd(this.method);
        Map<VarVersionPair, String> resolvedLocalNames = LocalVariableNameResolver.resolve(
                this.method,
                variables,
                parameterEnd
        );
        List<VarVersionPair> variablesInSlotOrder = new ArrayList<>(variables.keySet());
        variablesInSlotOrder.sort(Comparator
                .comparingInt((VarVersionPair variable) -> variable.var)
                .thenComparingInt(variable -> variable.version));

        recordRuntimeDeclarations();

        Map<VarVersionPair, String> replacements = new LinkedHashMap<>();
        for (VarVersionPair variable : variablesInSlotOrder) {
            if (variable.var == 0 && !this.method.hasModifier(CodeConstants.ACC_STATIC)) {
                continue;
            }

            String existingName = resolvedLocalNames.get(variable);
            if (existingName == null && variable.var < parameterEnd) {
                existingName = this.methodParameterNames.get(variable.var);
            }
            String runtimeName = existingName == null
                    ? uniqueGeneratedName(localVariableNamesBySlot.get(variable.var))
                    : existingName;
            String mappedName = variable.var < parameterEnd ? this.parameterNames.get(variable.var) : null;
            if (mappedName != null) {
                replacements.put(variable, mappedName);
                recordRename(runtimeName, mappedName);
                continue;
            }

            if (existingName != null && this.parameterNames.containsValue(existingName)) {
                String replacement = this.nameGenerator.next(variables.get(variable).b);
                replacements.put(variable, replacement);
                recordRename(existingName, replacement);
                continue;
            }
            if (!this.minecraftMethod) continue;

            if (existingName == null && hasMeaningfulName(localVariableNamesBySlot.get(variable.var))) {
                continue;
            }
            if (existingName != null && !isGenerated(existingName)) {
                replacements.put(variable, existingName);
                continue;
            }
            if (isGenerated(existingName)) {
                String replacement = this.nameGenerator.next(variables.get(variable).b);
                replacements.put(variable, replacement);
                recordRename(runtimeName, replacement);
            }
        }
        return replacements.isEmpty() ? null : replacements;
    }

    @Override
    public synchronized String renameAbstractParameter(String name, int index) {
        String renamed = this.parameterNames.getOrDefault(index, name);
        recordRename(name, renamed);
        return renamed;
    }

    @Override
    public synchronized String renameParameter(int flags, VarType type, String name, int index) {
        String renamed = this.parameterNames.getOrDefault(index, name);
        recordRename(name, renamed);
        return renamed;
    }

    private void recordRename(String runtimeName, String displayedName) {
        VariableNameCapture.recordRename(
                this.method.getClassQualifiedName(),
                this.method.getName(),
                this.method.getDescriptor(),
                runtimeName,
                displayedName
        );
    }

    private void recordRuntimeDeclarations() {
        StructLocalVariableTableAttribute variables = this.method.getLocalVariableAttr();
        StructLineNumberTableAttribute lines = this.method.getAttribute(
                StructGeneralAttribute.ATTRIBUTE_LINE_NUMBER_TABLE
        );
        if (variables == null || lines == null) {
            return;
        }
        variables.getVariables().forEach(variable -> {
            // An LVT scope begins after the store which declares the local.
            int declarationOffset = Math.max(0, variable.getStart() - 1);
            VariableNameCapture.recordRuntimeName(
                    this.method.getClassQualifiedName(),
                    this.method.getName(),
                    this.method.getDescriptor(),
                    lines.findLineNumber(declarationOffset),
                    variable.getName()
            );
        });
    }

    @Override
    public synchronized void addParentContext(IVariableNameProvider renamer) {
        if (renamer instanceof SelectiveVariableNameProvider parent) {
            this.nameGenerator.inherit(parent.nameGenerator);
            // Lambda parameters share their enclosing scope, unlike ordinary method declarations.
            if (this.method.hasModifier(CodeConstants.ACC_SYNTHETIC)) {
                int slot = this.method.hasModifier(CodeConstants.ACC_STATIC) ? 0 : 1;
                for (VarType type : MethodDescriptor.parseDescriptor(this.method.getDescriptor()).params) {
                    if (parent.nameGenerator.isReserved(this.parameterNames.get(slot))) {
                        this.parameterNames.put(slot, this.nameGenerator.next(ExprProcessor.getCastTypeName(type)));
                    }
                    slot += type.stackSize;
                }
            }
        }
    }

    private static boolean isGenerated(String name) {
        return GeneratedVariableNames.matches(name);
    }

    private static boolean hasMeaningfulName(List<String> names) {
        return names != null && names.stream().anyMatch(name -> !isGenerated(name));
    }

    private static String uniqueGeneratedName(List<String> names) {
        if (names == null) {
            return null;
        }
        String unique = null;
        for (String name : names) {
            if (name == null || !isGenerated(name)) {
                continue;
            }
            if (unique == null) {
                unique = name;
            } else if (!unique.equals(name)) {
                return null;
            }
        }
        return unique;
    }

    private static int parameterEnd(StructMethod method) {
        int slot = method.hasModifier(CodeConstants.ACC_STATIC) ? 0 : 1;
        for (VarType parameter : MethodDescriptor.parseDescriptor(method.getDescriptor()).params) {
            slot += parameter.stackSize;
        }
        return slot;
    }

    private static Map<Integer, List<String>> readLocalVariableNamesBySlot(StructMethod method) {
        StructLocalVariableTableAttribute variables = method.getLocalVariableAttr();
        if (variables == null) {
            return Map.of();
        }

        Map<Integer, List<String>> names = new HashMap<>();
        variables.getVariables().forEach(variable -> names
                .computeIfAbsent(variable.getVersion().var, ignored -> new ArrayList<>())
                .add(variable.getName()));
        return names;
    }

}
