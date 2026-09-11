package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomJavaLinkGeneratorTest {

    @Test
    void packageImportSegmentRevealsTheSelectedPackage() {
        String source = "import java.util.List; final class Sample { List<?> values; }";
        IJavaElement packageFragment = packageFragment("java.util");
        AtomicReference<String> revealedPackage = new AtomicReference<>();
        var generator = new CustomJavaLinkGenerator(new ASTCache(),
                offset -> packageFragment,
                offset -> "java.util.List",
                (packageName, ownerClass) -> revealedPackage.set(packageName + " in " + ownerClass),
                Path.of("Sample.java")
        );

        var link = generator.isLinkAtOffset(new RSyntaxTextArea(source), source.indexOf("util"));

        assertNotNull(link);
        link.execute();
        assertEquals("java.util in java.util.List", revealedPackage.get());
    }

    @Test
    void unresolvedPackageOwnerIsPassedToTheDiagnosticRoute() {
        String source = "import missing.Type; class Sample {}";
        AtomicReference<String> diagnostic = new AtomicReference<>();
        var generator = new CustomJavaLinkGenerator(new ASTCache(), offset -> packageFragment("missing"), offset -> null,
                (name, owner) -> {
                    assertNull(owner);
                    diagnostic.set("Unresolved owner for " + name);
                }, Path.of("Sample.java"));
        var link = generator.isLinkAtOffset(new RSyntaxTextArea(source), source.indexOf("missing"));
        assertNotNull(link);
        link.execute();
        assertEquals("Unresolved owner for missing", diagnostic.get());
    }

    @Test
    void linkResultStartsAtTheTokenSoRSyntaxTextAreaCanUnderlineIt() {
        String source = "final class Sample { java.util.List<?> values; }";
        int hoverOffset = source.indexOf("List") + 2;
        RSyntaxTextArea textArea = new RSyntaxTextArea(source);
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        var generator = new CustomJavaLinkGenerator(new ASTCache(),
                offset -> packageFragment("java.util"),
                offset -> "java.util.List",
                (packageName, ownerClass) -> {
                },
                Path.of("Sample.java")
        );

        var link = generator.isLinkAtOffset(textArea, hoverOffset);
        Token token = textArea.getTokenListForLine(0);
        while (token != null && !token.containsPosition(hoverOffset)) {
            token = token.getNextToken();
        }

        assertNotNull(link);
        assertNotNull(token);
        assertEquals(token.getOffset(), link.getSourceOffset());
    }

    @Test
    void punctuationNextToAResolvableSymbolIsNotALink() {
        String source = "final class Sample { Object value = target.call(); }";
        RSyntaxTextArea textArea = new RSyntaxTextArea(source);
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        var generator = new CustomJavaLinkGenerator(new ASTCache(),
                offset -> packageFragment("example"),
                offset -> "example.Target",
                (packageName, ownerClass) -> {
                },
                Path.of("Sample.java")
        );

        assertNull(generator.isLinkAtOffset(textArea, source.indexOf(".call")));
        assertNull(generator.isLinkAtOffset(textArea, source.indexOf("call()") + "call".length()));
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
