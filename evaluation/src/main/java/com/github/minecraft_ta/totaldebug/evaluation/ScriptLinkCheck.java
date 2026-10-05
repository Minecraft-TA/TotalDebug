package com.github.minecraft_ta.totaldebug.evaluation;

import com.github.minecraft_ta.totaldebug.evaluation.ScriptReferences.Dynamic;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptReferences.Member;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptReferences.TypeUse;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of a script's {@link ScriptReferences} do not link under a class loader. The loader and the JVM decide, not rules
 * of this class, and no script code runs:
 * <ul>
 *     <li>classes load without being initialized;</li>
 *     <li>the script's own classes are defined and linked, so the JVM checks their supertypes and verifies their code
 *     against the loader's class hierarchy;</li>
 *     <li>each type an instruction names is checked for access, and each member resolved, from the script class whose code
 *     uses it: through the linker's own bootstrap where the compiler routed it through {@link ScriptAccessLinker},
 *     otherwise with {@link MethodHandles.Lookup}, which resolves as the matching instruction does;</li>
 *     <li>the JDK's own bootstraps link lambda, string concatenation, record and switch sites.</li>
 * </ul>
 * Where the JVM checks something at link time that no API answers, the check reads it off the loaded class: a superclass
 * constructor a subclass calls, an allocation of an abstract class, whether an instruction's owner is an interface, and
 * whether a lambda's interface still has its method. It also asks the resolver whether each concrete script class still
 * implements the abstract methods it inherits, which the JVM only reports when one is called.
 */
final class ScriptLinkCheck {
    private static final String PROBE = "TotalDebug$LinkProbe";

    /** A resolution from one position; it throws what the JVM would refuse. */
    private interface Resolution {
        void run(MethodHandles.Lookup lookup) throws Throwable;
    }

    private final ScriptReferences references;
    private final ClassLoader loader;
    /** The probe's own lookup, with the original access caller-sensitive methods ask for. */
    private final MethodHandles.Lookup probe;
    private final Map<String, MethodHandles.Lookup> lookups = new HashMap<>();
    /** Classes named as missing, which the problems that follow from them do not repeat. */
    private final Set<String> missing = new LinkedHashSet<>();

    ScriptLinkCheck(ScriptReferences references, ClassLoader parent) {
        this.references = references;
        Map<String, byte[]> definitions = new HashMap<>(references.script());
        String probe = probeName(references.script().keySet());
        definitions.put(probe, probe(probe));
        this.loader = new ScriptClassLoader(parent, definitions);
        try {
            this.probe = (MethodHandles.Lookup) this.loader.loadClass(probe).getMethod("lookup").invoke(null);
        } catch (ReflectiveOperationException unexpected) {
            throw new IllegalStateException("Unable to look up members from the script's position", unexpected);
        }
    }

    /**
     * Classes the loader lacks, then the script's own classes the JVM refuses, then types, members and call sites that
     * do not link, each as Java would name it with the JVM's reason; one line per problem.
     */
    List<String> unresolved() {
        List<String> unresolved = new ArrayList<>();
        for (String name : this.references.classes()) {
            if (loadFailure(name, false) != null) this.missing.add(name);
        }
        unresolved.addAll(this.missing);
        if (this.missing.isEmpty()) {
            for (String name : this.references.script().keySet().stream().sorted().toList()) {
                String refused = loadFailure(name, true);
                if (refused != null) unresolved.add(name + " (" + refused + ")");
            }
        }
        for (TypeUse use : this.references.typeUses()) {
            String problem = attempt(use.referrer(), null, lookup -> lookup.accessClass(type(use.type())));
            if (problem != null) unresolved.add(use.type() + " (" + problem + ")");
        }
        for (Member member : this.references.members()) {
            String problem = attempt(member.referrer(), member, lookup -> resolve(member, lookup));
            // A member the server lacks, where the script catches the JVM's error for it, is one it runs without.
            if (problem != null && !(problem.isEmpty() && member.guarded())) {
                unresolved.add(problem.isEmpty() ? member.display() : member.display() + " (" + problem + ")");
            }
        }
        for (Dynamic site : this.references.dynamics()) {
            String problem = attempt(site.referrer(), null, lookup -> link(site, lookup));
            if (problem != null) unresolved.add(site.display() + " (" + problem + ")");
        }
        if (unresolved.isEmpty()) unimplemented(unresolved);
        // A member used both directly and through the linker is one problem.
        return List.copyOf(new LinkedHashSet<>(unresolved));
    }

    /**
     * Null when {@code resolution} succeeds from {@code referrer}'s position, otherwise {@link #reason}. When the JVM asks
     * for the original caller's access, which only a class's own code has, the resolution is repeated from the probe: it
     * shares the script's loader, module and package, all a caller-sensitive method looks at.
     */
    private String attempt(String referrer, Member member, Resolution resolution) {
        MethodHandles.Lookup lookup;
        try {
            lookup = lookup(referrer);
        } catch (ReflectiveOperationException | LinkageError namedAbove) {
            // A script class the JVM refused to define is named above.
            return null;
        }
        try {
            try {
                resolution.run(lookup);
            } catch (Throwable refused) {
                if (!needsOriginalAccess(refused)) throw refused;
                resolution.run(this.probe);
            }
            return null;
        } catch (Throwable refused) {
            return reason(refused, member);
        }
    }

    private static boolean needsOriginalAccess(Throwable refused) {
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            // A lookup refuses a caller-sensitive method; a lambda cannot crack a handle another lookup made.
            if (message.contains("caller-sensitive") || message.contains("cannot be cracked")) return true;
        }
        return false;
    }

    /**
     * Null when the refusal follows from a class already named missing, empty when the member does not exist, otherwise
     * why the JVM refused it, in words for {@code member} where it names one.
     */
    private String reason(Throwable refused, Member member) {
        // The first failure this check knows, from the outside in: a lookup's own exception wraps the JVM's internal
        // error, while a bootstrap's error wraps the failure that caused it.
        Throwable cause = refused;
        while (!known(cause) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof ClassNotFoundException || cause instanceof TypeNotPresentException || cause instanceof NoClassDefFoundError) {
            // A server class can itself name a class the server lacks, which hides whether the member exists at all.
            String absent = (cause instanceof TypeNotPresentException type ? type.typeName() : String.valueOf(cause.getMessage()))
                    .replace('/', '.');
            return this.missing.contains(absent) ? null : "needs " + absent + ", which the server does not have";
        }
        if (cause instanceof NoSuchMethodException || cause instanceof NoSuchFieldException
                || cause instanceof NoSuchMethodError || cause instanceof NoSuchFieldError) return "";
        if (cause instanceof IllegalAccessException) {
            if (member == null) return "not accessible";
            String message = String.valueOf(cause.getMessage());
            // The name and type exist, but not as the instruction uses them.
            if (message.startsWith("no such") || message.startsWith("expected a")) {
                return member.isStatic() ? "not static on the server" : "static on the server";
            }
            return message.contains(":") ? message.substring(0, message.indexOf(':')) : message;
        }
        // Thrown by this check where the JVM checks something no API answers, with the reason as its message.
        if (cause instanceof IncompatibleClassChangeError) return cause.getMessage();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static boolean known(Throwable cause) {
        return cause instanceof ClassNotFoundException || cause instanceof TypeNotPresentException || cause instanceof NoClassDefFoundError
                || cause instanceof NoSuchMethodException || cause instanceof NoSuchFieldException
                || cause instanceof IllegalAccessException || cause instanceof IncompatibleClassChangeError;
    }

    /** The full-access lookup of script class {@code referrer}, which shares the probe's loader and module. */
    private MethodHandles.Lookup lookup(String referrer) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = this.lookups.get(referrer);
        if (lookup == null) {
            lookup = MethodHandles.privateLookupIn(type(referrer), this.probe);
            this.lookups.put(referrer, lookup);
        }
        return lookup;
    }

    /**
     * Null when {@code name} loads, otherwise why not. Listing a script class's methods also links it, which verifies its
     * code against the classes this loader has, as the run would.
     */
    private String loadFailure(String name, boolean link) {
        try {
            Class<?> type = type(name);
            if (link) type.getDeclaredMethods();
            return null;
        } catch (ClassNotFoundException absent) {
            return "not found";
        } catch (LinkageError refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    private void resolve(Member member, MethodHandles.Lookup lookup) throws Throwable {
        if (member.callSite() != null) {
            ScriptAccessLinker.bootstrap(lookup, member.name(), methodType(member.callSite()), member.owner(), member.name(),
                    member.descriptor(), member.operation());
        } else {
            try {
                find(member, lookup, false);
            } catch (IllegalAccessException refused) {
                // A lookup also wants the class an instruction names to be related to the caller; the JVM only wants the
                // caller to extend the class that declares the member.
                if (!String.valueOf(refused.getMessage()).startsWith("member is protected") || !member.isStatic()) throw refused;
                find(atDeclaration(member), lookup, false);
            }
        }
        if (member.operation() == ScriptAccessLinker.NEW_INSTANCE) {
            // Resolving new refuses an abstract class, which a lookup still finds a constructor of.
            Class<?> owner = type(member.owner());
            if (owner.isInterface() || Modifier.isAbstract(owner.getModifiers())) throw new InstantiationError("abstract on the server");
        }
    }

    /**
     * {@code member} found as the instruction or, for a method handle {@code constant}, the constant that uses it
     * resolves it; null for a superclass constructor, which no lookup finds.
     */
    private MethodHandle find(Member member, MethodHandles.Lookup lookup, boolean constant) throws ReflectiveOperationException {
        Class<?> owner = type(member.owner());
        // An instruction names an interface or a class, and resolving it refuses the other kind.
        if (member.isInvocation() && owner.isInterface() != member.isInterface()) {
            throw new IncompatibleClassChangeError(owner.isInterface() ? "an interface on the server" : "a class on the server");
        }
        // The script writes its own final fields in its own constructors, which the JVM allows and a lookup does not;
        // through a script class, an instruction's write is checked as the field it names.
        boolean ownField = !constant && owner.getClassLoader() == this.loader;
        String name = member.name();
        return switch (member.operation()) {
            case ScriptAccessLinker.GET_FIELD -> lookup.findGetter(owner, name, fieldType(member));
            case ScriptAccessLinker.PUT_FIELD -> ownField ? lookup.findGetter(owner, name, fieldType(member))
                    : lookup.findSetter(owner, name, fieldType(member));
            case ScriptAccessLinker.GET_STATIC -> lookup.findStaticGetter(owner, name, fieldType(member));
            case ScriptAccessLinker.PUT_STATIC -> ownField ? lookup.findStaticGetter(owner, name, fieldType(member))
                    : lookup.findStaticSetter(owner, name, fieldType(member));
            case ScriptAccessLinker.INVOKE_STATIC -> lookup.findStatic(owner, name, methodType(member.descriptor()));
            case ScriptAccessLinker.INVOKE_VIRTUAL -> lookup.findVirtual(owner, name, methodType(member.descriptor()));
            case ScriptAccessLinker.INVOKE_SPECIAL -> lookup.findSpecial(owner, name, methodType(member.descriptor()), lookup.lookupClass());
            case ScriptAccessLinker.NEW_INSTANCE -> lookup.findConstructor(owner, methodType(member.descriptor()));
            default -> {
                superConstructor(owner, member);
                yield null;
            }
        };
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

    /** {@code member} named by the superclass that declares it, as the JVM's access check treats it. */
    private Member atDeclaration(Member member) throws ReflectiveOperationException {
        for (Class<?> type = type(member.owner()); type != null; type = type.getSuperclass()) {
            boolean declared = member.isField()
                    ? Arrays.stream(type.getDeclaredFields()).anyMatch(candidate -> candidate.getName().equals(member.name())
                    && Type.getDescriptor(candidate.getType()).equals(member.descriptor()))
                    : Arrays.stream(type.getDeclaredMethods()).anyMatch(candidate -> candidate.getName().equals(member.name())
                    && Type.getMethodDescriptor(candidate).equals(member.descriptor()));
            if (declared) {
                return new Member(type.getName(), member.name(), member.descriptor(), member.operation(), null,
                        member.referrer(), type.isInterface(), member.guarded());
            }
        }
        throw new NoSuchMethodException(member.owner() + "." + member.name() + member.descriptor());
    }

    /** Links {@code site} with its bootstrap, which the JDK owns. */
    private void link(Dynamic site, MethodHandles.Lookup lookup) throws Throwable {
        Handle bootstrap = site.bootstrap();
        MethodHandle method = lookup.findStatic(type(bootstrap.getOwner().replace('/', '.')), bootstrap.getName(),
                methodType(bootstrap.getDesc()));
        MethodType type = methodType(site.descriptor());
        List<Object> arguments = new ArrayList<>(List.of(lookup, site.name(), type));
        for (Object argument : site.arguments()) arguments.add(value(argument, lookup));
        method.invokeWithArguments(arguments);
        if (site.isLambda()) {
            // The factory does not check that the interface still has the method the lambda implements; whoever calls
            // it does, as this lookup.
            try {
                lookup.findVirtual(type.returnType(), site.name(), (MethodType) arguments.get(3));
            } catch (NoSuchMethodException renamed) {
                throw new AbstractMethodError("the interface has no such method on the server");
            }
        }
    }

    /** A bootstrap argument as the JVM hands it to the bootstrap. */
    private Object value(Object constant, MethodHandles.Lookup lookup) throws ReflectiveOperationException {
        return switch (constant) {
            case Type type when type.getSort() == Type.METHOD -> methodType(type.getDescriptor());
            case Type type -> methodType("()" + type.getDescriptor()).returnType();
            case Handle handle -> find(new Member(handle.getOwner().replace('/', '.'), handle.getName(), handle.getDesc(),
                    ScriptReferences.operation(handle.getTag(), handle.getName()), null, lookup.lookupClass().getName(),
                    handle.isInterface(), false), lookup, true);
            default -> constant;
        };
    }

    /**
     * A concrete script class whose inherited abstract method the server's supertypes no longer let it implement, such as
     * an override whose signature changed there. The JVM raises this only when the method is called; the resolver
     * answers it now.
     */
    private void unimplemented(List<String> unresolved) {
        for (String name : this.references.script().keySet().stream().sorted().toList()) {
            try {
                Class<?> type = type(name);
                if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
                MethodHandles.Lookup lookup = lookup(name);
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

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, this.loader);
    }

    private MethodType methodType(String descriptor) {
        return MethodType.fromMethodDescriptorString(descriptor, this.loader);
    }

    private Class<?> fieldType(Member member) {
        return methodType("()" + member.descriptor()).returnType();
    }

    /**
     * A class next to the script's that hands out a lookup from the script's package, named apart from the script's own;
     * no script code runs.
     */
    private static String probeName(Set<String> script) {
        String any = script.stream().sorted().findFirst().orElse("");
        int dot = any.lastIndexOf('.');
        String name = dot < 0 ? PROBE : any.substring(0, dot + 1) + PROBE;
        while (script.contains(name)) name += "$";
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
}
