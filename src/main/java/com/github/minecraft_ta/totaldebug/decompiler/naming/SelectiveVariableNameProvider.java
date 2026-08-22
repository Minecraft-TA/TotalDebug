package com.github.minecraft_ta.totaldebug.decompiler.naming;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.extern.IVariableNameProvider;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTableAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructMethodParametersAttribute;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SelectiveVariableNameProvider implements IVariableNameProvider {
    private static final String MINECRAFT_PACKAGE = "net/minecraft/";
    private static final Pattern GENERATED_NAME = Pattern.compile(
            "(?:p_\\d+_|var\\d+(?:_\\d+)?|arg\\d+|param\\d+|\\$\\$\\d+|☃.*)"
    );

    private final StructMethod method;
    private final boolean minecraftMethod;
    private final Map<Integer, String> parchmentNames;
    private final Map<Integer, String> methodParameterNames;
    private final Map<Integer, String> generatedParameterNames = new HashMap<>();
    private final JadLikeNameGenerator nameGenerator = new JadLikeNameGenerator();

    SelectiveVariableNameProvider(StructMethod method) {
        this.method = method;
        this.minecraftMethod = method.getClassQualifiedName().startsWith(MINECRAFT_PACKAGE);
        this.parchmentNames = this.minecraftMethod ? ParchmentParameterResolver.resolve(method) : Map.of();
        this.methodParameterNames = this.minecraftMethod ? readMethodParameterNames(method) : Map.of();
    }

    @Override
    public synchronized Map<VarVersionPair, String> rename(
            Map<VarVersionPair, Pair<VarType, String>> variables
    ) {
        if (!this.minecraftMethod) {
            return null;
        }

        Map<VarVersionPair, String> localVariableNames = readLocalVariableNames(this.method);
        Map<Integer, List<String>> localVariableNamesBySlot = readLocalVariableNamesBySlot(this.method);
        this.nameGenerator.reserve(localVariableNames.values());
        this.nameGenerator.reserve(this.methodParameterNames.values());
        this.nameGenerator.reserve(this.parchmentNames.values());

        int parameterEnd = parameterEnd(this.method);
        List<VarVersionPair> variablesInSlotOrder = new ArrayList<>(variables.keySet());
        variablesInSlotOrder.sort(Comparator
                .comparingInt((VarVersionPair variable) -> variable.var)
                .thenComparingInt(variable -> variable.version));

        Map<VarVersionPair, String> replacements = new LinkedHashMap<>();
        for (VarVersionPair variable : variablesInSlotOrder) {
            if (variable.var == 0 && !this.method.hasModifier(CodeConstants.ACC_STATIC)) {
                continue;
            }

            String mappedName = variable.var < parameterEnd ? this.parchmentNames.get(variable.var) : null;
            if (mappedName != null) {
                replacements.put(variable, mappedName);
                continue;
            }

            String existingName = localVariableNames.get(variable);
            if (existingName == null && variable.var < parameterEnd) {
                existingName = this.methodParameterNames.get(variable.var);
            }
            if (existingName == null && hasMeaningfulName(localVariableNamesBySlot.get(variable.var))) {
                continue;
            }
            if (isGenerated(existingName)) {
                String replacement = variable.var < parameterEnd
                        ? generatedParameterName(variable.var, variables.get(variable).b)
                        : this.nameGenerator.next(variables.get(variable).b);
                replacements.put(variable, replacement);
            }
        }
        return replacements.isEmpty() ? null : replacements;
    }

    @Override
    public synchronized String renameAbstractParameter(String name, int index) {
        if (!this.minecraftMethod) {
            return name;
        }
        String mappedName = this.parchmentNames.get(index);
        if (mappedName != null) {
            return mappedName;
        }
        return isGenerated(name) ? generatedParameterName(index, parameterType(index)) : name;
    }

    @Override
    public synchronized String renameParameter(int flags, VarType type, String name, int index) {
        if (!this.minecraftMethod) {
            return name;
        }
        String mappedName = this.parchmentNames.get(index);
        if (mappedName != null) {
            return mappedName;
        }
        return isGenerated(name)
                ? generatedParameterName(index, ExprProcessor.getCastTypeName(type))
                : name;
    }

    @Override
    public synchronized void addParentContext(IVariableNameProvider renamer) {
        if (renamer instanceof SelectiveVariableNameProvider parent) {
            this.nameGenerator.inherit(parent.nameGenerator);
        }
    }

    private String parameterType(int localVariableIndex) {
        int slot = this.method.hasModifier(CodeConstants.ACC_STATIC) ? 0 : 1;
        for (VarType parameter : MethodDescriptor.parseDescriptor(this.method.getDescriptor()).params) {
            if (slot == localVariableIndex) {
                return ExprProcessor.getCastTypeName(parameter);
            }
            slot += parameter.stackSize;
        }
        return "Object";
    }

    private String generatedParameterName(int localVariableIndex, String displayedType) {
        return this.generatedParameterNames.computeIfAbsent(
                localVariableIndex,
                ignored -> this.nameGenerator.next(displayedType)
        );
    }

    private static boolean isGenerated(String name) {
        return name == null || name.isBlank() || GENERATED_NAME.matcher(name).matches();
    }

    private static boolean hasMeaningfulName(List<String> names) {
        return names != null && names.stream().anyMatch(name -> !isGenerated(name));
    }

    private static int parameterEnd(StructMethod method) {
        int slot = method.hasModifier(CodeConstants.ACC_STATIC) ? 0 : 1;
        for (VarType parameter : MethodDescriptor.parseDescriptor(method.getDescriptor()).params) {
            slot += parameter.stackSize;
        }
        return slot;
    }

    private static Map<VarVersionPair, String> readLocalVariableNames(StructMethod method) {
        StructLocalVariableTableAttribute variables = method.getLocalVariableAttr();
        return variables == null ? Map.of() : variables.getMapNames();
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

    private static Map<Integer, String> readMethodParameterNames(StructMethod method) {
        StructMethodParametersAttribute attribute = method.getAttribute(
                StructGeneralAttribute.ATTRIBUTE_METHOD_PARAMETERS
        );
        if (attribute == null) {
            return Map.of();
        }

        List<StructMethodParametersAttribute.Entry> entries = attribute.getEntries();
        VarType[] parameterTypes = MethodDescriptor.parseDescriptor(method.getDescriptor()).params;
        Map<Integer, String> names = new HashMap<>();
        int slot = method.hasModifier(CodeConstants.ACC_STATIC) ? 0 : 1;
        for (int parameterIndex = 0; parameterIndex < Math.min(entries.size(), parameterTypes.length); parameterIndex++) {
            String name = entries.get(parameterIndex).myName;
            if (name != null) {
                names.put(slot, name);
            }
            slot += parameterTypes[parameterIndex].stackSize;
        }
        return names;
    }
}
