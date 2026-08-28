package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DebuggerTypeCatalogTest {
    @Test
    void scansLoadedClassesOnceUntilClearStateStartsANewGeneration() {
        AtomicInteger scans = new AtomicInteger();
        ReferenceType blocks = referenceType("net.minecraft.world.level.block.Blocks");
        ReferenceType blockState = referenceType("net.minecraft.world.level.block.state.BlockState");
        VirtualMachine vm = virtualMachine(scans, List.of(blocks, blockState));
        RichJavaExpressionEngine engine = new RichJavaExpressionEngine(
                (binaryName, methodName, descriptor, runtimeName) -> runtimeName,
                ignored -> null
        );

        DebuggerTypeCatalog first = engine.typeCatalog(vm);
        assertSame(first, engine.typeCatalog(vm));
        assertEquals(1, scans.get());
        assertEquals(List.of(blocks, blockState), first.withSimpleNamePrefix("Block"));

        engine.clearState(thread(7L));
        engine.typeCatalog(vm);
        assertEquals(2, scans.get());
    }

    private static VirtualMachine virtualMachine(AtomicInteger scans, List<ReferenceType> types) {
        return (VirtualMachine) Proxy.newProxyInstance(
                VirtualMachine.class.getClassLoader(),
                new Class<?>[]{VirtualMachine.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("allClasses")) {
                        scans.incrementAndGet();
                        return types;
                    }
                    throw new UnsupportedOperationException(method.toString());
                }
        );
    }

    private static ReferenceType referenceType(String name) {
        return (ReferenceType) Proxy.newProxyInstance(
                ReferenceType.class.getClassLoader(),
                new Class<?>[]{ReferenceType.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("name")) return name;
                    throw new UnsupportedOperationException(method.toString());
                }
        );
    }

    private static ThreadReference thread(long id) {
        return (ThreadReference) Proxy.newProxyInstance(
                ThreadReference.class.getClassLoader(),
                new Class<?>[]{ThreadReference.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("uniqueID")) return id;
                    throw new UnsupportedOperationException(method.toString());
                }
        );
    }
}
