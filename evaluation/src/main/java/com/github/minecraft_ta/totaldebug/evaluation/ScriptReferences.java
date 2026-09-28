package com.github.minecraft_ta.totaldebug.evaluation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The classes, fields and methods a script's compiled classes refer to, read from their bytecode, and which of them do
 * not link under a class loader. The loader and the JVM decide, not rules of this class: classes are loaded without
 * initializing them; the script's own classes are defined and linked, so the JVM checks their supertypes and verifies
 * their code against the classes it has; each type an instruction names is checked for access, and each member is
 * resolved, from the script class whose code uses it: through the linker's own bootstrap where the compiler routed it
 * through {@link ScriptAccessLinker}, otherwise with {@link MethodHandles.Lookup}, which resolves as the matching
 * instruction does. What the JVM checks at link time without an API for it is checked on the loaded class: a superclass
 * constructor a subclass calls, an allocation of an abstract class, and whether an instruction's owner is an interface.
 */
public final class ScriptReferences {
    private static final String LINKER = Type.getInternalName(ScriptAccessLinker.class);
    private static final String PROBE = "TotalDebug$LinkProbe";
    /** A superclass or own constructor called from a constructor, rather than an allocation. */
    private static final int SUPER_CONSTRUCTOR = -1;

    /**
     * A member and how the script uses it, as a {@link ScriptAccessLinker} operation or {@link #SUPER_CONSTRUCTOR}.
     * {@code callSite} is the type of the linker call site that uses it, or null for an instruction that uses it
     * directly; {@code referrer} is the script class whose code does.
     */
    private record Member(String owner, String name, String descriptor, int operation, String callSite, String referrer,
                          boolean isInterface) {
    }

    /** A type an instruction of {@code referrer} names, which the JVM resolves with an access check. */
    private record TypeUse(String type, String referrer) {
    }

    /**
     * An invokedynamic site of {@code referrer} whose bootstrap is the JDK's own, such as a lambda's or a string
     * concatenation's. Running it links the site as the JVM would, and runs no script code.
     */
    private record Dynamic(String referrer, String name, String descriptor, Handle bootstrap, List<Object> arguments) {
    }

    private final Map<String, byte[]> script;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<TypeUse> typeUses = new LinkedHashSet<>();
    private final Set<Member> members = new LinkedHashSet<>();
    private final List<Dynamic> dynamics = new ArrayList<>();

    private ScriptReferences(Map<String, byte[]> script) {
        this.script = Map.copyOf(script);
    }

    /** Reads what {@code script}, the compiled classes by binary name, refers to. */
    public static ScriptReferences read(Map<String, byte[]> script) {
        Objects.requireNonNull(script, "script");
        ScriptReferences references = new ScriptReferences(script);
        for (byte[] bytes : script.values()) {
            new ClassReader(bytes).accept(references.new Reader(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        references.classes.removeAll(script.keySet());
        return references;
    }

    /** The classes referred to outside the script, by binary name. */
    public Set<String> classes() {
        return Set.copyOf(this.classes);
    }

    /**
     * What does not link when the script runs under {@code parent}: classes, then the script's own classes the JVM
     * refuses to define, then fields and methods, each as Java would name it, with the JVM's reason when the member
     * exists but cannot be linked as the script uses it.
     */
    public List<String> unresolved(ClassLoader parent) {
        Objects.requireNonNull(parent, "parent");
        Map<String, byte[]> definitions = new HashMap<>(this.script);
        String probe = probeName();
        definitions.put(probe, probe(probe));
        ClassLoader loader = new ScriptClassLoader(parent, definitions);
        List<String> unresolved = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String name : this.classes) {
            if (failure(name, loader) != null) missing.add(name);
        }
        unresolved.addAll(missing);
        if (unresolved.isEmpty()) {
            // Defining a class checks its superclass and interfaces: not final, the right kind, accessible. Listing its
            // methods links it, which verifies its code against the classes this loader has, as the run would.
            for (String name : this.script.keySet().stream().sorted().toList()) {
                String refused = linkFailure(name, loader);
                if (refused != null) unresolved.add(name + " (" + refused + ")");
            }
        }
        MethodHandles.Lookup probeLookup;
        try {
            probeLookup = (MethodHandles.Lookup) loader.loadClass(probe).getMethod("lookup").invoke(null);
        } catch (ReflectiveOperationException unexpected) {
            throw new IllegalStateException("Unable to look up members from the script's position", unexpected);
        }
        Map<String, MethodHandles.Lookup> lookups = new HashMap<>();
        for (TypeUse use : this.typeUses) {
            try {
                lookup(use.referrer(), lookups, probeLookup, loader).accessClass(Class.forName(use.type(), false, loader));
            } catch (IllegalAccessException refused) {
                unresolved.add(use.type() + " (not accessible)");
            } catch (ReflectiveOperationException | LinkageError namedAbove) {
                // A missing type, or a script class the JVM refused to define, is named above.
            }
        }
        for (Member member : this.members) {
            String problem;
            try {
                problem = problem(member, lookup(member.referrer(), lookups, probeLookup, loader), probeLookup, loader, missing);
            } catch (ReflectiveOperationException | LinkageError referrer) {
                // A script class the JVM refused to define is named above.
                continue;
            }
            if (problem != null) unresolved.add(problem.isEmpty() ? display(member) : display(member) + " (" + problem + ")");
        }
        for (Dynamic site : this.dynamics) {
            String problem;
            try {
                problem = link(site, lookup(site.referrer(), lookups, probeLookup, loader), probeLookup, loader, missing);
            } catch (ReflectiveOperationException | LinkageError referrer) {
                continue;
            }
            if (problem != null) unresolved.add(display(site) + " (" + problem + ")");
        }
        if (unresolved.isEmpty()) unimplemented(loader, lookups, probeLookup, unresolved);
        // A member used both directly and through the linker is one problem.
        return List.copyOf(new LinkedHashSet<>(unresolved));
    }

    /**
     * A concrete script class whose inherited abstract method the server's supertypes no longer let it implement, such
     * as an override whose signature changed there. The JVM raises this only when the method is called; the resolver
     * answers it now.
     */
    private void unimplemented(ClassLoader loader, Map<String, MethodHandles.Lookup> lookups, MethodHandles.Lookup probeLookup,
                               List<String> unresolved) {
        for (String name : this.script.keySet().stream().sorted().toList()) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
                MethodHandles.Lookup lookup = lookup(name, lookups, probeLookup, loader);
                for (Method inherited : inheritedAbstract(type)) {
                    try {
                        MethodHandle chosen = lookup.findVirtual(type, inherited.getName(),
                                MethodType.methodType(inherited.getReturnType(), inherited.getParameterTypes()));
                        if (Modifier.isAbstract(lookup.revealDirect(chosen).getModifiers())) {
                            unresolved.add(name + " (does not implement " + inherited.getDeclaringClass().getName() + "."
                                    + inherited.getName() + "(" + Arrays.stream(inherited.getParameterTypes())
                                    .map(Class::getSimpleName).collect(Collectors.joining(", ")) + "))");
                        }
                    } catch (ReflectiveOperationException | RuntimeException notOurs) {
                        // An abstract method the script class cannot reach is one it could not implement either.
                    }
                }
            } catch (ReflectiveOperationException | LinkageError namedAbove) {
                // A script class the JVM refused to define is named above.
            }
        }
    }

    private static List<Method> inheritedAbstract(Class<?> type) {
        Map<String, Method> found = new LinkedHashMap<>();
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        if (type.getSuperclass() != null) pending.add(type.getSuperclass());
        pending.addAll(List.of(type.getInterfaces()));
        Set<Class<?>> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> supertype = pending.poll();
            if (!seen.add(supertype)) continue;
            try {
                for (Method method : supertype.getDeclaredMethods()) {
                    if (Modifier.isAbstract(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                        found.putIfAbsent(method.getName() + Type.getMethodDescriptor(method), method);
                    }
                }
            } catch (LinkageError unreadable) {
                continue;
            }
            if (supertype.getSuperclass() != null) pending.add(supertype.getSuperclass());
            pending.addAll(List.of(supertype.getInterfaces()));
        }
        return List.copyOf(found.values());
    }

    /** The full-access lookup of script class {@code referrer}, which shares the probe's loader and module. */
    private static MethodHandles.Lookup lookup(String referrer, Map<String, MethodHandles.Lookup> lookups,
                                               MethodHandles.Lookup probeLookup, ClassLoader loader)
            throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = lookups.get(referrer);
        if (lookup == null) {
            lookup = MethodHandles.privateLookupIn(Class.forName(referrer, false, loader), probeLookup);
            lookups.put(referrer, lookup);
        }
        return lookup;
    }

    /** Null when script class {@code name} defines and links, otherwise why not. */
    private static String linkFailure(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader).getDeclaredMethods();
            return null;
        } catch (ClassNotFoundException missing) {
            return "not found";
        } catch (LinkageError refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    /** Null when {@code name} loads, otherwise why not. */
    private static String failure(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return null;
        } catch (ClassNotFoundException missing) {
            return "not found";
        } catch (LinkageError refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    /**
     * Null when {@code member} links or depends on a class already named in {@code missing}, empty when it does not
     * exist, otherwise why the JVM refused it.
     */
    private static String problem(Member member, MethodHandles.Lookup lookup, MethodHandles.Lookup probeLookup,
                                  ClassLoader loader, Set<String> missing) {
        try {
            try {
                resolve(member, lookup, loader);
            } catch (IllegalAccessException refused) {
                String reason = String.valueOf(refused.getMessage());
                if (reason.contains("caller-sensitive")) {
                    // A script class's lookup lacks the original access a caller-sensitive method asks for. The probe's
                    // has it, from the same loader, module and package, which is all such a method looks at.
                    resolve(member, probeLookup, loader);
                } else if (reason.startsWith("member is protected") && isStatic(member) && member.callSite() == null) {
                    // A lookup also wants the class the instruction names to be related to the caller; the JVM only
                    // wants the caller to extend the class that declares the member.
                    lookUp(atDeclaration(member, loader), lookup, loader);
                } else {
                    throw refused;
                }
            }
            if (member.operation() == ScriptAccessLinker.NEW_INSTANCE) {
                // Resolving new refuses an abstract class, which a lookup still finds a constructor of.
                Class<?> owner = Class.forName(member.owner(), false, loader);
                if (owner.isInterface() || Modifier.isAbstract(owner.getModifiers())) return "abstract on the server";
            }
            return null;
        } catch (ClassNotFoundException | TypeNotPresentException | NoClassDefFoundError dependsOnMissing) {
            // Only a class the check already named stays unnamed here: a server class can itself name one the server
            // lacks, which hides whether the member exists at all.
            String absent = switch (dependsOnMissing) {
                case TypeNotPresentException type -> type.typeName();
                default -> String.valueOf(dependsOnMissing.getMessage());
            };
            absent = absent.replace('/', '.');
            return missing.contains(absent) ? null : "needs " + absent + ", which the server does not have";
        } catch (NoSuchMethodException | NoSuchFieldException absentMember) {
            return "";
        } catch (IllegalAccessException refused) {
            String reason = String.valueOf(refused.getMessage());
            // The JVM found the name and type, but not as the instruction uses it: static where the script expects an
            // instance member, or the reverse.
            if (reason.startsWith("no such") || reason.startsWith("expected a")) {
                return isStatic(member) ? "not static on the server" : "static on the server";
            }
            return reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        } catch (IncompatibleClassChangeError kind) {
            return kind.getMessage();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    private static void resolve(Member member, MethodHandles.Lookup lookup, ClassLoader loader) throws ReflectiveOperationException {
        if (member.callSite() != null) {
            ScriptAccessLinker.bootstrap(lookup, member.name(), MethodType.fromMethodDescriptorString(member.callSite(), loader),
                    member.owner(), member.name(), member.descriptor(), member.operation());
        } else {
            lookUp(member, lookup, loader);
        }
    }

    /** {@code member} named by the superclass that declares it, as the JVM's access check treats it. */
    private static Member atDeclaration(Member member, ClassLoader loader) throws ReflectiveOperationException {
        boolean field = member.operation() == ScriptAccessLinker.GET_STATIC || member.operation() == ScriptAccessLinker.PUT_STATIC;
        for (Class<?> type = Class.forName(member.owner(), false, loader); type != null; type = type.getSuperclass()) {
            boolean declared = field
                    ? Arrays.stream(type.getDeclaredFields()).anyMatch(candidate -> candidate.getName().equals(member.name())
                    && Type.getDescriptor(candidate.getType()).equals(member.descriptor()))
                    : Arrays.stream(type.getDeclaredMethods()).anyMatch(candidate -> candidate.getName().equals(member.name())
                    && Type.getMethodDescriptor(candidate).equals(member.descriptor()));
            if (declared) {
                return new Member(type.getName(), member.name(), member.descriptor(), member.operation(), null,
                        member.referrer(), type.isInterface());
            }
        }
        throw new NoSuchMethodException(member.owner() + "." + member.name() + member.descriptor());
    }

    /**
     * Null when {@code site} links, otherwise why its bootstrap refused it. A site whose arguments need a caller-sensitive
     * method is linked from the probe: the bootstrap must crack those handles with the lookup that made them, and the
     * script class's own lookup, which the run uses for both, is not available here.
     */
    private static String link(Dynamic site, MethodHandles.Lookup lookup, MethodHandles.Lookup probeLookup, ClassLoader loader,
                               Set<String> missing) {
        String problem = attempt(site, lookup, probeLookup, loader, missing);
        return problem != null && problem.contains("cannot be cracked") ? attempt(site, probeLookup, probeLookup, loader, missing) : problem;
    }

    private static String attempt(Dynamic site, MethodHandles.Lookup lookup, MethodHandles.Lookup probeLookup, ClassLoader loader,
                                  Set<String> missing) {
        try {
            Handle bootstrap = site.bootstrap();
            MethodHandle method = lookup.findStatic(Class.forName(bootstrap.getOwner().replace('/', '.'), false, loader),
                    bootstrap.getName(), MethodType.fromMethodDescriptorString(bootstrap.getDesc(), loader));
            MethodType type = MethodType.fromMethodDescriptorString(site.descriptor(), loader);
            List<Object> arguments = new ArrayList<>(List.of(lookup, site.name(), type));
            for (Object argument : site.arguments()) arguments.add(value(argument, lookup, probeLookup, loader));
            method.invokeWithArguments(arguments);
            if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                // The factory does not check that the interface still has the method the lambda implements; whoever
                // calls it does, with this lookup.
                try {
                    lookup.findVirtual(type.returnType(), site.name(), (MethodType) arguments.get(3));
                } catch (NoSuchMethodException renamed) {
                    return "the interface has no such method on the server";
                }
            }
            return null;
        } catch (ClassNotFoundException | TypeNotPresentException | NoClassDefFoundError dependsOnMissing) {
            String absent = dependsOnMissing instanceof TypeNotPresentException type ? type.typeName()
                    : String.valueOf(dependsOnMissing.getMessage());
            absent = absent.replace('/', '.');
            return missing.contains(absent) ? null : "needs " + absent + ", which the server does not have";
        } catch (Throwable refused) {
            Throwable cause = refused;
            while (cause.getCause() != null) cause = cause.getCause();
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    /** A bootstrap argument as the JVM hands it to the bootstrap. */
    private static Object value(Object constant, MethodHandles.Lookup lookup, MethodHandles.Lookup probeLookup, ClassLoader loader)
            throws ReflectiveOperationException {
        return switch (constant) {
            case Type type when type.getSort() == Type.METHOD -> MethodType.fromMethodDescriptorString(type.getDescriptor(), loader);
            case Type type -> MethodType.fromMethodDescriptorString("()" + type.getDescriptor(), loader).returnType();
            case Handle handle -> {
                Member member = new Member(handle.getOwner().replace('/', '.'), handle.getName(), handle.getDesc(),
                        operation(handle.getTag(), handle.getName()), null, lookup.lookupClass().getName(), handle.isInterface());
                try {
                    yield handle(member, lookup, loader);
                } catch (IllegalAccessException refused) {
                    if (!String.valueOf(refused.getMessage()).contains("caller-sensitive")) throw refused;
                    yield handle(member, probeLookup, loader);
                }
            }
            default -> constant;
        };
    }

    private static MethodHandle handle(Member member, MethodHandles.Lookup lookup, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> owner = Class.forName(member.owner(), false, loader);
        return switch (member.operation()) {
            case ScriptAccessLinker.GET_FIELD -> lookup.findGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_FIELD -> lookup.findSetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.GET_STATIC -> lookup.findStaticGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_STATIC -> lookup.findStaticSetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.INVOKE_STATIC -> lookup.findStatic(owner, member.name(), methodType(member, loader));
            case ScriptAccessLinker.INVOKE_SPECIAL ->
                    lookup.findSpecial(owner, member.name(), methodType(member, loader), lookup.lookupClass());
            case ScriptAccessLinker.NEW_INSTANCE -> lookup.findConstructor(owner, methodType(member, loader));
            default -> lookup.findVirtual(owner, member.name(), methodType(member, loader));
        };
    }

    private static String display(Dynamic site) {
        String kind = site.bootstrap().getOwner().substring(site.bootstrap().getOwner().lastIndexOf('/') + 1);
        if (kind.equals("LambdaMetafactory")) {
            String functional = Type.getReturnType(site.descriptor()).getClassName();
            return "lambda for " + functional + "." + site.name() + " in " + site.referrer();
        }
        return kind + " call site " + site.name() + " in " + site.referrer();
    }

    /** Looks up {@code member} as the instruction that uses it resolves it. */
    private static void lookUp(Member member, MethodHandles.Lookup lookup, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(member.owner(), false, loader);
        boolean invocation = member.operation() == ScriptAccessLinker.INVOKE_STATIC
                || member.operation() == ScriptAccessLinker.INVOKE_VIRTUAL || member.operation() == ScriptAccessLinker.INVOKE_SPECIAL;
        // An instruction names an interface or a class, and resolving it refuses the other kind.
        if (invocation && owner.isInterface() != member.isInterface()) {
            throw new IncompatibleClassChangeError(owner.isInterface() ? "an interface on the server" : "a class on the server");
        }
        // The script writes its own final fields in its own constructors, which the JVM allows and a lookup does not;
        // through a script class, a write is checked as the field it names.
        boolean ownField = owner.getClassLoader() == lookup.lookupClass().getClassLoader();
        switch (member.operation()) {
            case ScriptAccessLinker.GET_FIELD -> lookup.findGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_FIELD -> {
                if (ownField) lookup.findGetter(owner, member.name(), fieldType(member, loader));
                else lookup.findSetter(owner, member.name(), fieldType(member, loader));
            }
            case ScriptAccessLinker.GET_STATIC -> lookup.findStaticGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_STATIC -> {
                if (ownField) lookup.findStaticGetter(owner, member.name(), fieldType(member, loader));
                else lookup.findStaticSetter(owner, member.name(), fieldType(member, loader));
            }
            case ScriptAccessLinker.INVOKE_STATIC -> lookup.findStatic(owner, member.name(), methodType(member, loader));
            case ScriptAccessLinker.INVOKE_VIRTUAL -> lookup.findVirtual(owner, member.name(), methodType(member, loader));
            case ScriptAccessLinker.INVOKE_SPECIAL ->
                    lookup.findSpecial(owner, member.name(), methodType(member, loader), lookup.lookupClass());
            case ScriptAccessLinker.NEW_INSTANCE -> lookup.findConstructor(owner, methodType(member, loader));
            default -> superConstructor(owner, member);
        }
    }

    /**
     * A constructor the script's constructor calls on its superclass. No lookup reaches it, since a lookup only finds a
     * constructor to allocate with; from another runtime package, which the script's loader always is, the JVM lets a
     * subclass call a public or protected one.
     */
    private static void superConstructor(Class<?> owner, Member member) throws ReflectiveOperationException {
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (!Type.getConstructorDescriptor(constructor).equals(member.descriptor())) continue;
            if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers())) return;
            throw new IllegalAccessException("not accessible to a subclass: " + member.owner());
        }
        throw new NoSuchMethodException(member.owner() + ".<init>" + member.descriptor());
    }

    private static boolean isStatic(Member member) {
        return member.operation() == ScriptAccessLinker.GET_STATIC || member.operation() == ScriptAccessLinker.PUT_STATIC
                || member.operation() == ScriptAccessLinker.INVOKE_STATIC;
    }

    private static Class<?> fieldType(Member member, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + member.descriptor(), loader).returnType();
    }

    private static MethodType methodType(Member member, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString(member.descriptor(), loader);
    }

    /**
     * A class next to the script's that hands out a lookup from the script's package, named apart from the script's own;
     * no script code runs.
     */
    private String probeName() {
        String any = this.script.keySet().stream().sorted().findFirst().orElse("");
        int dot = any.lastIndexOf('.');
        String name = dot < 0 ? PROBE : any.substring(0, dot + 1) + PROBE;
        while (this.script.containsKey(name)) name += "$";
        return name;
    }

    private static byte[] probe(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name.replace('.', '/'), null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String display(Member member) {
        boolean field = member.operation() >= ScriptAccessLinker.GET_FIELD && member.operation() <= ScriptAccessLinker.PUT_STATIC;
        if (field) return member.owner() + "." + member.name();
        String parameters = Arrays.stream(Type.getArgumentTypes(member.descriptor()))
                .map(type -> type.getClassName().substring(type.getClassName().lastIndexOf('.') + 1))
                .collect(Collectors.joining(", "));
        return member.name().equals("<init>") ? "new " + member.owner() + "(" + parameters + ")"
                : member.owner() + "." + member.name() + "(" + parameters + ")";
    }

    /**
     * The {@link ScriptAccessLinker} operation matching a field or method instruction, or a method handle's tag. A
     * constructor call counts as an allocation until {@link Reader} finds it allocates nothing.
     */
    private static int operation(int opcode, String name) {
        return switch (opcode) {
            case Opcodes.GETFIELD, Opcodes.H_GETFIELD -> ScriptAccessLinker.GET_FIELD;
            case Opcodes.PUTFIELD, Opcodes.H_PUTFIELD -> ScriptAccessLinker.PUT_FIELD;
            case Opcodes.GETSTATIC, Opcodes.H_GETSTATIC -> ScriptAccessLinker.GET_STATIC;
            case Opcodes.PUTSTATIC, Opcodes.H_PUTSTATIC -> ScriptAccessLinker.PUT_STATIC;
            case Opcodes.INVOKESTATIC, Opcodes.H_INVOKESTATIC -> ScriptAccessLinker.INVOKE_STATIC;
            case Opcodes.H_NEWINVOKESPECIAL -> ScriptAccessLinker.NEW_INSTANCE;
            case Opcodes.INVOKESPECIAL, Opcodes.H_INVOKESPECIAL ->
                    name.equals("<init>") ? ScriptAccessLinker.NEW_INSTANCE : ScriptAccessLinker.INVOKE_SPECIAL;
            default -> ScriptAccessLinker.INVOKE_VIRTUAL;
        };
    }

    private void type(Type type) {
        switch (type.getSort()) {
            case Type.ARRAY -> type(type.getElementType());
            case Type.OBJECT -> this.classes.add(type.getClassName());
            case Type.METHOD -> {
                for (Type argument : type.getArgumentTypes()) type(argument);
                type(type.getReturnType());
            }
            default -> {
            }
        }
    }

    /** An internal name or, for an array, a descriptor, as instructions name their types. */
    private void internalName(String name) {
        if (name != null) type(name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name));
    }

    private void member(String referrer, String owner, String name, String descriptor, int operation, String callSite,
                        boolean isInterface) {
        internalName(owner);
        type(Type.getType(descriptor));
        // Arrays answer only length and Object's methods. A member named through a script class is resolved too, since
        // it may be inherited from a class the server has another version of; only a constructor it calls on one of
        // the script's own classes shares their runtime package, where the JVM allows every constructor.
        String binary = owner.replace('/', '.');
        if (owner.startsWith("[") || operation == SUPER_CONSTRUCTOR && this.script.containsKey(binary)) return;
        this.members.add(new Member(binary, name, descriptor, operation, callSite, referrer, isInterface));
    }

    /** The types of a method descriptor an instruction of {@code referrer} names, which the JVM resolves with access. */
    private void descriptorUses(String referrer, Type method) {
        for (Type argument : method.getArgumentTypes()) typeUse(referrer, argument);
        typeUse(referrer, method.getReturnType());
    }

    private void typeUse(String referrer, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) typeUse(referrer, type.getInternalName());
    }

    /** A type an instruction of {@code referrer} names: an internal name, or a descriptor for an array. */
    private void typeUse(String referrer, String name) {
        internalName(name);
        Type type = name.startsWith("[") ? Type.getType(name).getElementType() : Type.getObjectType(name);
        if (type.getSort() != Type.OBJECT || this.script.containsKey(type.getClassName())) return;
        this.typeUses.add(new TypeUse(type.getClassName(), referrer));
    }

    /** A call site {@link ScriptBytecodeTransformer} made for a member: its declaring class, name, descriptor and use. */
    private boolean linked(String referrer, String callSite, Handle bootstrap, Object[] arguments) {
        if (!bootstrap.getOwner().equals(LINKER) || arguments.length != 4 || !(arguments[0] instanceof String declaration)
                || !(arguments[1] instanceof String name) || !(arguments[2] instanceof String descriptor)
                || !(arguments[3] instanceof Integer operation)) {
            return false;
        }
        String owner = declaration.replace('.', '/');
        if (operation == ScriptAccessLinker.INITIALIZE_CLASS) internalName(owner);
        else member(referrer, owner, name, descriptor, operation, callSite, false);
        return true;
    }

    private void constant(String referrer, Object value) {
        switch (value) {
            case Type type when type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY -> typeUse(referrer, type.getInternalName());
            case Type type when type.getSort() == Type.METHOD -> {
                type(type);
                descriptorUses(referrer, type);
            }
            case Type type -> type(type);
            case Handle handle -> member(referrer, handle.getOwner(), handle.getName(), handle.getDesc(),
                    operation(handle.getTag(), handle.getName()), null, handle.isInterface());
            case ConstantDynamic dynamic -> {
                type(Type.getType(dynamic.getDescriptor()));
                constant(referrer, dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    constant(referrer, dynamic.getBootstrapMethodArgument(index));
                }
            }
            default -> {
            }
        }
    }

    private final class Reader extends ClassVisitor {
        private String referrer;

        private Reader() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.referrer = name.replace('/', '.');
            internalName(superName);
            if (interfaces != null) for (String implemented : interfaces) internalName(implemented);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            type(Type.getType(descriptor));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            type(Type.getMethodType(descriptor));
            if (exceptions != null) for (String exception : exceptions) internalName(exception);
            String referrer = this.referrer;
            return new MethodVisitor(Opcodes.ASM9) {
                /** The types allocated and not yet constructed, innermost first: a constructor call pairs with one. */
                private final ArrayDeque<String> allocations = new ArrayDeque<>();

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    typeUse(referrer, type);
                    if (opcode == Opcodes.NEW) this.allocations.push(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    member(referrer, owner, name, descriptor, operation(opcode, name), null, false);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    int operation = operation(opcode, name);
                    if (operation == ScriptAccessLinker.NEW_INSTANCE) {
                        if (owner.equals(this.allocations.peek())) this.allocations.pop();
                        else operation = SUPER_CONSTRUCTOR;
                    }
                    member(referrer, owner, name, descriptor, operation, null, isInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                    type(Type.getMethodType(descriptor));
                    // Resolving a call site resolves its type, with an access check for each class it names.
                    descriptorUses(referrer, Type.getMethodType(descriptor));
                    if (linked(referrer, descriptor, bootstrap, arguments)) return;
                    constant(referrer, bootstrap);
                    for (Object argument : arguments) constant(referrer, argument);
                    boolean jdk = bootstrap.getOwner().startsWith("java/lang/invoke/") || bootstrap.getOwner().startsWith("java/lang/runtime/");
                    if (jdk && Arrays.stream(arguments).noneMatch(ConstantDynamic.class::isInstance)) {
                        dynamics.add(new Dynamic(referrer, name, descriptor, bootstrap, List.of(arguments)));
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(referrer, value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    typeUse(referrer, descriptor);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (type != null) typeUse(referrer, type);
                }
            };
        }
    }
}
