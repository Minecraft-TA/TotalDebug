package com.github.minecraft_ta.totalDebugCompanion.naming;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import javax.lang.model.SourceVersion;
import java.util.*;

/** One naming policy for declarations, independent of either compiler or decompiler models. */
public final class MethodParameterNames {
    public record Method(String owner, String name, String descriptor, int access, String[] originalNames) { }

    public interface Hierarchy {
        List<String> parents(String owner);
        /** Null means that this class does not declare the method. */
        Integer access(String owner, String name, String descriptor);
    }

    private static final class Mappings {
        static final ParchmentParameterIndex INDEX = ParchmentParameterIndex.load();
    }

    private MethodParameterNames() { }

    public static String[] resolve(Method method, Hierarchy hierarchy) {
        Type[] types = Type.getArgumentTypes(method.descriptor());
        int[] slots = slots(method.descriptor(), method.access());
        Map<Integer, String> mapped = method.owner().startsWith("net/minecraft/")
                ? mappings(method, hierarchy) : Map.of();
        String[] result = new String[types.length];
        Set<String> reserved = new HashSet<>();
        for (int i = 0; i < result.length; i++) {
            String name = mapped.get(slots[i]);
            if (name == null && method.originalNames() != null && i < method.originalNames().length) {
                name = method.originalNames()[i];
            }
            if (usable(name) && reserved.add(name)) result[i] = name;
        }
        JadLikeNameGenerator generator = new JadLikeNameGenerator();
        generator.reserve(reserved);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) result[i] = generator.next(types[i].getClassName().replace('$', '.'));
        }
        return result;
    }

    public static int[] slots(String descriptor, int access) {
        Type[] types = Type.getArgumentTypes(descriptor);
        int[] result = new int[types.length];
        int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < types.length; i++) { result[i] = slot; slot += types[i].getSize(); }
        return result;
    }

    private static boolean usable(String name) {
        return !GeneratedVariableNames.matches(name) && SourceVersion.isIdentifier(name)
                && !SourceVersion.isKeyword(name, SourceVersion.RELEASE_21);
    }

    private static Map<Integer, String> mappings(Method method, Hierarchy hierarchy) {
        Map<Integer, String> exact = Mappings.INDEX.find(method.owner(), method.name(), method.descriptor());
        if (!exact.isEmpty() || hierarchy == null || method.name().startsWith("<")
                || (method.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) return exact;
        List<String> level = hierarchy.parents(method.owner());
        Set<String> visited = new HashSet<>();
        while (!level.isEmpty()) {
            List<String> next = new ArrayList<>();
            List<Map<Integer, String>> candidates = new ArrayList<>();
            for (String owner : level) {
                if (!visited.add(owner)) continue;
                Integer access = hierarchy.access(owner, method.name(), method.descriptor());
                if (access != null && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    var names = Mappings.INDEX.find(owner, method.name(), method.descriptor());
                    if (!names.isEmpty()) candidates.add(names);
                }
                next.addAll(hierarchy.parents(owner));
            }
            if (!candidates.isEmpty()) {
                var first = candidates.getFirst();
                return candidates.stream().allMatch(first::equals) ? first : Map.of();
            }
            level = next;
        }
        return Map.of();
    }
}
