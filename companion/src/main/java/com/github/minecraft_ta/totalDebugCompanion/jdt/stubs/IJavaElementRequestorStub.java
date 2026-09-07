package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.jdt.core.*;
import org.eclipse.jdt.internal.core.IJavaElementRequestor;

public interface IJavaElementRequestorStub extends IJavaElementRequestor {

    @Override
    default void acceptField(IField field) {

    }

    @Override
    default void acceptInitializer(IInitializer initializer) {

    }

    @Override
    default void acceptMemberType(IType type) {

    }

    @Override
    default void acceptMethod(IMethod method) {

    }

    @Override
    default void acceptPackageFragment(IPackageFragment packageFragment) {

    }

    @Override
    default void acceptType(IType type) {

    }

    @Override
    default void acceptModule(IModuleDescription module) {

    }

    @Override
    default boolean isCanceled() {
        return false;
    }
}
