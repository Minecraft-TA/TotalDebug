package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import com.github.minecraft_ta.totalDebugCompanion.naming.MethodParameterNames;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructMethodParametersAttribute;
import java.util.*;

/** Converts Vineflower metadata into the shared declaration naming inputs. */
final class DecompilerParameterNames {
    private static final MethodParameterNames.Hierarchy HIERARCHY = new MethodParameterNames.Hierarchy() {
        @Override public List<String> parents(String owner) {
            var type = DecompilerContext.getStructContext().getClass(owner);
            if (type == null) return List.of();
            var parents = new LinkedHashSet<String>();
            if (type.superClass != null) parents.add(type.superClass.getString());
            parents.addAll(List.of(type.getInterfaceNames()));
            return List.copyOf(parents);
        }
        @Override public Integer access(String owner, String name, String descriptor) {
            var type = DecompilerContext.getStructContext().getClass(owner);
            var method = type == null ? null : type.getMethod(name, descriptor);
            return method == null ? null : method.getAccessFlags();
        }
    };

    static Map<Integer, String> originals(StructMethod method) {
        int[] slots = MethodParameterNames.slots(method.getDescriptor(), method.getAccessFlags());
        Map<Integer, String> names = new HashMap<>();
        StructMethodParametersAttribute attribute = method.getAttribute(StructGeneralAttribute.ATTRIBUTE_METHOD_PARAMETERS);
        if (attribute != null) {
            var entries = attribute.getEntries();
            for (int i = 0; i < Math.min(entries.size(), slots.length); i++) {
                if (entries.get(i).myName != null) names.put(slots[i], entries.get(i).myName);
            }
        }
        var locals = method.getLocalVariableAttr();
        if (locals != null) locals.getVariables().filter(variable -> variable.getStart() == 0)
                .forEach(variable -> names.putIfAbsent(variable.getVersion().var, variable.getName()));
        return names;
    }

    static Map<Integer, String> resolve(StructMethod method, Map<Integer, String> originals) {
        int[] slots = MethodParameterNames.slots(method.getDescriptor(), method.getAccessFlags());
        String[] raw = Arrays.stream(slots).mapToObj(originals::get).toArray(String[]::new);
        String[] names = MethodParameterNames.resolve(new MethodParameterNames.Method(
                method.getClassQualifiedName(), method.getName(), method.getDescriptor(), method.getAccessFlags(), raw), HIERARCHY);
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int i = 0; i < slots.length; i++) result.put(slots[i], names[i]);
        return Map.copyOf(result);
    }
}
