package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaBreadcrumbResolverTest {
    private static final String SOURCE = """
            package example;

            public class Sample {
                private int count;

                public void update() {
                    count++;
                }
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(classBytes(Object.class))));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void resolvesTheEnclosingLocalMethodAtItsDeclaration() {
        var unit = ASTCache.rawParse("Sample", SOURCE);
        Path file = this.temporaryDirectory.resolve("Sample.java");

        JavaBreadcrumbResolver.Member member = JavaBreadcrumbResolver.resolve(
                unit,
                SOURCE.indexOf("count++"),
                new NavigationTarget.LocalFile(file)
        );

        assertNotNull(member);
        assertEquals("update()", member.label());
        NavigationTarget.LocalFile target = assertInstanceOf(NavigationTarget.LocalFile.class, member.target());
        assertEquals(file.toAbsolutePath().normalize(), target.path());
        assertEquals(SOURCE.indexOf("update"), target.offset());
    }

    @Test
    void resolvesAnExactRuntimeMemberTarget() {
        var unit = ASTCache.rawParse("Sample", SOURCE);

        JavaBreadcrumbResolver.Member member = JavaBreadcrumbResolver.resolve(
                unit,
                SOURCE.indexOf("count++"),
                new NavigationTarget.RuntimeClass("example.Sample")
        );

        assertNotNull(member);
        assertEquals(
                new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Method(
                        "example.Sample",
                        "update",
                        "()V"
                )),
                member.target()
        );
    }

    @Test
    void resolvesAFieldButNotClassLevelWhitespace() {
        var unit = ASTCache.rawParse("Sample", SOURCE);
        NavigationTarget.LocalFile target = new NavigationTarget.LocalFile(
                this.temporaryDirectory.resolve("Sample.java")
        );

        JavaBreadcrumbResolver.Member field = JavaBreadcrumbResolver.resolve(
                unit,
                SOURCE.indexOf("private int count"),
                target
        );

        assertNotNull(field);
        assertEquals("count", field.label());
        assertNull(JavaBreadcrumbResolver.resolve(unit, SOURCE.indexOf("public class"), target));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
