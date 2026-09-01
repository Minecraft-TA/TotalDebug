package com.github.minecraft_ta.totaldebug.script;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Links generated snippet bytecode to members without weakening the target classes. */
public final class ScriptAccessLinker {
    static final int GET_FIELD = 1;
    static final int PUT_FIELD = 2;
    static final int GET_STATIC = 3;
    static final int PUT_STATIC = 4;
    static final int INVOKE_VIRTUAL = 5;
    static final int INVOKE_STATIC = 6;
    static final int NEW_INSTANCE = 7;

    private ScriptAccessLinker() {
    }

    /** Bootstrap used by generated snippets. The caller is local code with unrestricted execution privileges. */
    public static CallSite bootstrap(
            MethodHandles.Lookup caller,
            String ignoredName,
            MethodType invokedType,
            String ownerName,
            String memberName,
            String memberDescriptor,
            int operation
    ) throws ReflectiveOperationException {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        Class<?> owner = Class.forName(ownerName, false, loader);
        MethodHandles.Lookup access = accessLookup(owner, caller);
        MethodHandle target = switch (operation) {
            case GET_FIELD -> access.findGetter(owner, memberName, fieldType(memberDescriptor, loader));
            case PUT_FIELD -> access.findSetter(owner, memberName, fieldType(memberDescriptor, loader));
            case GET_STATIC -> access.findStaticGetter(owner, memberName, fieldType(memberDescriptor, loader));
            case PUT_STATIC -> access.findStaticSetter(owner, memberName, fieldType(memberDescriptor, loader));
            case INVOKE_VIRTUAL -> access.findVirtual(
                    owner,
                    memberName,
                    MethodType.fromMethodDescriptorString(memberDescriptor, loader)
            );
            case INVOKE_STATIC -> access.findStatic(
                    owner,
                    memberName,
                    MethodType.fromMethodDescriptorString(memberDescriptor, loader)
            );
            case NEW_INSTANCE -> access.findConstructor(
                    owner,
                    MethodType.fromMethodDescriptorString(memberDescriptor, loader)
            );
            default -> throw new IllegalArgumentException("Unknown script access operation: " + operation);
        };
        return new ConstantCallSite(target.asType(invokedType));
    }

    private static Class<?> fieldType(String descriptor, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + descriptor, loader).returnType();
    }

    private static MethodHandles.Lookup accessLookup(Class<?> owner, MethodHandles.Lookup caller) {
        try {
            return MethodHandles.privateLookupIn(owner, caller);
        } catch (IllegalAccessException ignored) {
            return MethodHandles.publicLookup().in(owner);
        }
    }
}
