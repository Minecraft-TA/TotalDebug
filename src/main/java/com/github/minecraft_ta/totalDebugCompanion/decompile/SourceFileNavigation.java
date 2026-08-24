package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.SourceReferenceLocator;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

final class SourceFileNavigation {
    private static final String COMPILATION_UNIT_NAME = "Name";

    private SourceFileNavigation() {
    }

    static void open(
            Path filePath,
            int targetType,
            String targetIdentifier,
            String binaryName,
            RuntimeSnapshotBytecodeSource.ClassOrigin origin
    ) {
        int offset = targetOffset(filePath, targetType, targetIdentifier);
        openAt(filePath, offset, location(filePath, binaryName, origin));
    }

    static void openUsage(
            Path filePath,
            ReferenceLocation referenceLocation,
            ReferenceQuery query,
            String binaryName,
            RuntimeSnapshotBytecodeSource.ClassOrigin origin
    ) {
        openAt(
                filePath,
                usageOffset(CodeView.readCode(filePath), referenceLocation, query),
                location(filePath, binaryName, origin)
        );
    }

    private static void openAt(Path filePath, int offset, EditorLocation location) {
        SwingUtilities.invokeLater(() -> {
            MainWindow window = MainWindow.INSTANCE;
            AtomicBoolean created = new AtomicBoolean();
            window.getEditorTabs().focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(filePath),
                    () -> {
                        created.set(true);
                        return new CodeView(filePath, offset, location);
                    }
            ).thenAccept(codeView -> {
                if (!created.get()) {
                    codeView.reload(offset);
                }
                UIUtils.focusWindow(window);
            });
        });
    }

    private static EditorLocation location(
            Path filePath,
            String binaryName,
            RuntimeSnapshotBytecodeSource.ClassOrigin origin
    ) {
        return origin == null
                ? EditorLocation.forFile(filePath, null)
                : EditorLocation.forRuntimeClass(
                        binaryName,
                        origin.logicalSource(),
                        origin.module().displayName()
                );
    }

    static int usageOffset(String source, ReferenceLocation location, ReferenceQuery query) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(query, "query");

        var ast = ASTCache.rawParse(COMPILATION_UNIT_NAME, source);
        if (ast.types().isEmpty() || !(ast.types().getFirst() instanceof AbstractTypeDeclaration type)) {
            throw new IllegalStateException("Decompiled source has no top-level type");
        }
        ASTNode site = findSite(location.site(), type)
                .orElseThrow(() -> new IllegalStateException("Usage declaration not found in decompiled source: "
                        + location));
        return SourceReferenceLocator.findFirst(site, query).orElse(site.getStartPosition());
    }

    private static int targetOffset(Path filePath, int targetType, String targetIdentifier) {
        if (targetType == -1) {
            return 0;
        }

        var ast = ASTCache.rawParse(COMPILATION_UNIT_NAME, CodeView.readCode(filePath));
        if (ast.types().isEmpty() || !(ast.types().getFirst() instanceof AbstractTypeDeclaration type)) {
            throw new IllegalStateException("Decompiled source has no top-level type");
        }

        if (targetType == IJavaElement.METHOD) {
            String normalizedIdentifier = CodeUtils.minimalizeMethodIdentifier(targetIdentifier, false);
            boolean defaultConstructor = normalizedIdentifier.equals("()V");
            Optional<MethodDeclaration> method = findTargetMethod(normalizedIdentifier, type);
            if (method.isEmpty() && !defaultConstructor) {
                throw new IllegalStateException("Method not found in decompiled source: " + normalizedIdentifier);
            }
            return method.map(ASTNode::getStartPosition).orElse(0);
        }
        if (targetType == IJavaElement.FIELD) {
            OptionalInt field = findTargetField(targetIdentifier, type);
            if (field.isEmpty()) {
                throw new IllegalStateException("Field not found in decompiled source: " + targetIdentifier);
            }
            return field.getAsInt();
        }
        throw new IllegalArgumentException("Unknown source target type: " + targetType);
    }

    private static Optional<ASTNode> findSite(ReferenceLocation.Site site, AbstractTypeDeclaration type) {
        return switch (site) {
            case ReferenceLocation.ClassDeclaration ignored -> Optional.of(type);
            case ReferenceLocation.Method method -> findTargetMethod(method, type).map(ASTNode.class::cast);
            case ReferenceLocation.Field field -> findFieldSite(field.name(), type);
            case ReferenceLocation.RecordComponent component -> findRecordComponentSite(component.name(), type);
        };
    }

    @SuppressWarnings("unchecked")
    private static Optional<ASTNode> findFieldSite(String name, AbstractTypeDeclaration type) {
        List<Object> declarations = new ArrayList<>(bodyDeclarations(type));
        if (type instanceof EnumDeclaration enumDeclaration) {
            declarations.addAll(enumDeclaration.enumConstants());
        }
        return declarations.stream()
                .filter(declaration -> declaration instanceof FieldDeclaration
                        || declaration instanceof EnumConstantDeclaration)
                .filter(declaration -> fieldMatches(declaration, name))
                .map(ASTNode.class::cast)
                .findFirst();
    }

    private static Optional<ASTNode> findRecordComponentSite(String name, AbstractTypeDeclaration type) {
        if (!(type instanceof RecordDeclaration recordDeclaration)) {
            return Optional.empty();
        }
        for (Object candidate : recordDeclaration.recordComponents()) {
            SingleVariableDeclaration component = (SingleVariableDeclaration) candidate;
            if (component.getName().getIdentifier().equals(name)) {
                return Optional.of(component);
            }
        }
        return Optional.empty();
    }

    private static Optional<MethodDeclaration> findTargetMethod(
            String targetIdentifier,
            AbstractTypeDeclaration type
    ) {
        List<BodyDeclaration> declarations = bodyDeclarations(type);
        return declarations.stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .filter(method -> removeCompilationUnitName(
                        CodeUtils.minimalizeMethodIdentifier(method.resolveBinding().getKey(), false)
                ).equals(targetIdentifier))
                .findFirst();
    }

    private static Optional<MethodDeclaration> findTargetMethod(
            ReferenceLocation.Method target,
            AbstractTypeDeclaration type
    ) {
        return bodyDeclarations(type).stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .filter(method -> {
                    CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(method.resolveBinding());
                    return symbol instanceof CodeSymbol.MethodSymbol candidate
                            && candidate.name().equals(target.name())
                            && candidate.descriptor().equals(target.descriptor());
                })
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static OptionalInt findTargetField(String targetIdentifier, AbstractTypeDeclaration type) {
        List<Object> declarations = new ArrayList<>(bodyDeclarations(type));
        if (type instanceof EnumDeclaration enumDeclaration) {
            declarations.addAll(enumDeclaration.enumConstants());
        } else if (type instanceof RecordDeclaration recordDeclaration) {
            declarations.addAll(recordDeclaration.recordComponents());
        }
        return declarations.stream()
                .filter(declaration -> declaration instanceof FieldDeclaration
                        || declaration instanceof EnumConstantDeclaration
                        || declaration instanceof SingleVariableDeclaration)
                .filter(declaration -> fieldMatches(declaration, targetIdentifier))
                .mapToInt(SourceFileNavigation::fieldOffset)
                .findFirst();
    }

    private static boolean fieldMatches(Object declaration, String targetIdentifier) {
        return switch (declaration) {
            case EnumConstantDeclaration constant -> constant.getName().getIdentifier().equals(targetIdentifier);
            case SingleVariableDeclaration component -> component.getName().getIdentifier().equals(targetIdentifier);
            case FieldDeclaration field -> field.fragments().stream().anyMatch(fragment ->
                    ((VariableDeclarationFragment) fragment).getName().getIdentifier().equals(targetIdentifier)
            );
            default -> false;
        };
    }

    private static int fieldOffset(Object declaration) {
        return switch (declaration) {
            case EnumConstantDeclaration constant -> constant.getStartPosition();
            case SingleVariableDeclaration component -> component.getStartPosition();
            case FieldDeclaration field -> field.getStartPosition();
            default -> throw new IllegalArgumentException("Not a field declaration");
        };
    }

    private static String removeCompilationUnitName(String identifier) {
        return identifier.replace(COMPILATION_UNIT_NAME + "~", "");
    }

    @SuppressWarnings("unchecked")
    private static List<BodyDeclaration> bodyDeclarations(AbstractTypeDeclaration type) {
        return (List<BodyDeclaration>) type.bodyDeclarations();
    }
}
