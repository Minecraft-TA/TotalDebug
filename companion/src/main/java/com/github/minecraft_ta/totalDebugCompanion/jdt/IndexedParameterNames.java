package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.naming.MethodParameterNames;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Naming metadata and results live only as long as their runtime index. */
final class IndexedParameterNames implements MethodParameterNames.Hierarchy {
    private record ClassData(List<String> parents, Map<String, MethodParameterNames.Method> methods) { }
    private final ClassIndex index;
    private final Map<String, ClassData> classes = new ConcurrentHashMap<>();
    private final Map<String, String[]> names = new ConcurrentHashMap<>();

    IndexedParameterNames(ClassIndex index) { this.index = index; }

    String[] resolve(String owner, String name, String descriptor) {
        return names.computeIfAbsent(owner + "." + name + descriptor, ignored -> {
            var method = data(owner).methods().get(name + descriptor);
            return method == null ? new String[0] : MethodParameterNames.resolve(method, this);
        }).clone();
    }

    private ClassData data(String owner) {
        return classes.computeIfAbsent(owner, ignored -> {
            IndexedClass type = index.findClass(owner);
            if (type == null) return new ClassData(List.of(), Map.of());
            List<String> parents = new ArrayList<>();
            var superclass = type.getSuperClass();
            if (superclass != null) parents.add(superclass.getNameWithPackage());
            for (var parent : type.getInterfaces()) {
                if (parent != null) parents.add(parent.getNameWithPackage());
            }
            Map<String, MethodParameterNames.Method> methods = new HashMap<>();
            for (var method : type.getMethods()) {
                String name = method.getName(), descriptor = method.getDescriptorString();
                methods.put(name + descriptor, new MethodParameterNames.Method(owner, name, descriptor,
                        method.getAccessFlags(), method.getParameterNames()));
            }
            return new ClassData(List.copyOf(parents), Map.copyOf(methods));
        });
    }

    @Override public List<String> parents(String owner) { return data(owner).parents(); }

    @Override public Integer access(String owner, String name, String descriptor) {
        var method = data(owner).methods().get(name + descriptor);
        return method == null ? null : method.access();
    }
}
