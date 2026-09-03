package com.github.minecraft_ta.totaldebug.script;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads hierarchy and member declarations without defining or initializing application classes. */
final class ScriptTypeResolver {
    private static final String OBJECT = "java/lang/Object";

    private final Map<String, byte[]> generatedClasses;
    private final JavaFileManager classpath;
    private final Map<String, ClassInfo> classes = new HashMap<>();

    ScriptTypeResolver(Map<String, byte[]> generatedClasses, JavaFileManager classpath) {
        this.generatedClasses = generatedClasses;
        this.classpath = classpath;
    }

    String commonSuperClass(String first, String second) {
        if (isAssignableFrom(first, second)) {
            return first;
        }
        if (isAssignableFrom(second, first)) {
            return second;
        }
        if (first.startsWith("[") && second.startsWith("[")) {
            if (hasReferenceComponent(first) && hasReferenceComponent(second)) {
                String common = commonSuperClass(component(first), component(second));
                return '[' + (common.startsWith("[") ? common : 'L' + common + ';');
            }
            return OBJECT;
        }
        if (first.startsWith("[") || second.startsWith("[")
                || info(first).isInterface() || info(second).isInterface()) {
            return OBJECT;
        }
        String parent = info(first).superName();
        while (parent != null && !isAssignableFrom(parent, second)) {
            parent = info(parent).superName();
        }
        return parent == null ? OBJECT : parent;
    }

    String fieldOwner(String owner, String name, String descriptor) {
        String declaration = findField(owner, new Member(name, descriptor), new HashSet<>());
        return requireMember(declaration, owner, name, descriptor);
    }

    String methodOwner(String owner, String name, String descriptor) {
        String declaration = findMethod(owner, new Member(name, descriptor), new HashSet<>());
        return requireMember(declaration, owner, name, descriptor);
    }

    int constructorAccess(String owner, String descriptor) {
        Integer access = info(owner).methods().get(new Member("<init>", descriptor));
        requireMember(access == null ? null : owner, owner, "<init>", descriptor);
        return access;
    }

    private boolean isAssignableFrom(String target, String source) {
        if (target.equals(source) || OBJECT.equals(target)) {
            return true;
        }
        if (source.startsWith("[")) {
            if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) {
                return true;
            }
            if (!target.startsWith("[")) {
                return false;
            }
            return hasReferenceComponent(target) && hasReferenceComponent(source)
                    && isAssignableFrom(component(target), component(source));
        }
        if (target.startsWith("[")) {
            return false;
        }
        return hasSupertype(source, target, new HashSet<>());
    }

    private boolean hasSupertype(String source, String target, Set<String> visited) {
        if (source.equals(target)) {
            return true;
        }
        if (!visited.add(source)) {
            return false;
        }
        ClassInfo type = info(source);
        if (type.superName() != null && hasSupertype(type.superName(), target, visited)) {
            return true;
        }
        return type.interfaces().stream().anyMatch(parent -> hasSupertype(parent, target, visited));
    }

    private String findField(String owner, Member member, Set<String> visited) {
        if (!visited.add(owner)) {
            return null;
        }
        ClassInfo type = info(owner);
        if (type.fields().containsKey(member)) {
            return owner;
        }
        for (String parent : type.interfaces()) {
            String declaration = findField(parent, member, visited);
            if (declaration != null) {
                return declaration;
            }
        }
        return type.superName() == null ? null : findField(type.superName(), member, visited);
    }

    private String findMethod(String owner, Member member, Set<String> visited) {
        if (!visited.add(owner)) {
            return null;
        }
        ClassInfo type = info(owner);
        if (type.methods().containsKey(member)) {
            return owner;
        }
        if (type.superName() != null) {
            String declaration = findMethod(type.superName(), member, visited);
            if (declaration != null) {
                return declaration;
            }
        }
        String declaration = null;
        for (String parent : type.interfaces()) {
            String candidate = findMethod(parent, member, visited);
            if (candidate != null && (declaration == null || isAssignableFrom(declaration, candidate))) {
                declaration = candidate;
            }
        }
        return declaration;
    }

    private ClassInfo info(String name) {
        return this.classes.computeIfAbsent(name, this::readClass);
    }

    private ClassInfo readClass(String name) {
        byte[] generated = this.generatedClasses.get(name.replace('/', '.'));
        ClassReader reader;
        try (InputStream input = generated == null ? openClass(name) : null) {
            if (generated == null && input == null) {
                throw new ScriptBytecodeTransformer.TransformationException("Compiler classpath is missing " + name.replace('/', '.'));
            }
            reader = generated == null ? new ClassReader(input) : new ClassReader(generated);
        } catch (IOException exception) {
            throw new ScriptBytecodeTransformer.TransformationException("Unable to read compiler class " + name.replace('/', '.'), exception);
        }
        Map<Member, Integer> fields = new HashMap<>();
        Map<Member, Integer> methods = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.put(new Member(name, descriptor), access);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.put(new Member(name, descriptor), access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassInfo(reader.getSuperName(), List.of(reader.getInterfaces()),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0, Map.copyOf(fields), Map.copyOf(methods));
    }

    private InputStream openClass(String name) throws IOException {
        JavaFileObject file = this.classpath.getJavaFileForInput(
                StandardLocation.CLASS_PATH, name.replace('/', '.'), JavaFileObject.Kind.CLASS);
        return file == null ? ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")
                : file.openInputStream();
    }

    private static String component(String array) {
        String descriptor = array.substring(1);
        return descriptor.startsWith("L") ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
    }

    private static boolean hasReferenceComponent(String array) {
        return array.startsWith("[[") || array.startsWith("[L");
    }

    private static String requireMember(String declaration, String owner, String name, String descriptor) {
        if (declaration == null) {
            throw new ScriptBytecodeTransformer.TransformationException(
                    "Compiler classpath is missing member " + owner.replace('/', '.') + '.' + name + descriptor);
        }
        return declaration;
    }

    private record Member(String name, String descriptor) { }

    private record ClassInfo(String superName, List<String> interfaces, boolean isInterface,
                             Map<Member, Integer> fields, Map<Member, Integer> methods) { }
}
