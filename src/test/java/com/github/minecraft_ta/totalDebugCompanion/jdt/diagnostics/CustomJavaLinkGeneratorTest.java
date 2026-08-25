package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CustomJavaLinkGeneratorTest {

    @Test
    void packageImportSegmentRevealsTheSelectedPackage() {
        String source = "import java.util.List; final class Sample { List<?> values; }";
        IJavaElement packageFragment = packageFragment("java.util");
        AtomicReference<String> revealedPackage = new AtomicReference<>();
        var generator = new CustomJavaLinkGenerator(
                offset -> packageFragment,
                offset -> "java.util.List",
                (packageName, ownerClass) -> revealedPackage.set(packageName + " in " + ownerClass)
        );

        var link = generator.isLinkAtOffset(new RSyntaxTextArea(source), source.indexOf("util"));

        assertNotNull(link);
        link.execute();
        assertEquals("java.util in java.util.List", revealedPackage.get());
    }

    private static IPackageFragment packageFragment(String name) {
        return (IPackageFragment) Proxy.newProxyInstance(
                CustomJavaLinkGeneratorTest.class.getClassLoader(),
                new Class<?>[]{IPackageFragment.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getElementName" -> name;
                    case "getElementType" -> IJavaElement.PACKAGE_FRAGMENT;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
