package com.github.minecraft_ta.totalDebugCompanion.testui;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class UiTestExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(UiTestExtension.class);

    @Override public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent("guard", ignored -> {
            try { return new SuiteGuard(UiTestScope.installSuiteGuard()); }
            catch (Exception failure) { throw new IllegalStateException("Cannot install the UI safety guard", failure); }
        }, SuiteGuard.class);
    }

    @Override public void beforeEach(ExtensionContext context) throws Exception {
        if (!context.getTags().contains("ui")) return;
        context.getStore(NAMESPACE).put("scope", UiTestScope.open(context.getTags().contains("desktop")));
    }

    @Override public void afterEach(ExtensionContext context) throws Exception {
        var scope = context.getStore(NAMESPACE).remove("scope", UiTestScope.class);
        try { if (scope != null) scope.close(); }
        finally { UiTestScope.verifyUnscopedWindows(); }
    }

    private record SuiteGuard(AutoCloseable guard) implements ExtensionContext.Store.CloseableResource {
        @Override public void close() throws Exception { guard.close(); }
    }
}
