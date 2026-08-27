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
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
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

final class SourceFileNavigation {
    private static final String COMPILATION_UNIT_NAME = "Name";

    private SourceFileNavigation() {
    }

    static void open(
            DecompiledSource source,
            int targetType,
            String targetIdentifier,
            String binaryName
    ) {
        int offset = targetOffset(source.path(), targetType, targetIdentifier);
        openAt(source, offset, location(source.path(), binaryName, source.origin()), -1, true);
    }

    static void openUsage(
            DecompiledSource source,
            ReferenceLocation referenceLocation,
            ReferenceQuery query,
            String binaryName
    ) {
        openAt(
                source,
                usageOffset(source.contents(), referenceLocation, query),
                location(source.path(), binaryName, source.origin()),
                -1,
                true
        );
    }

    static void openLine(DecompiledSource source, int displayedLine) {
        openLine(source, displayedLine, true);
    }

    static void openLine(DecompiledSource source, int displayedLine, boolean activateEditor) {
        openAt(
                source,
                lineOffset(source.contents(), displayedLine),
                location(source.path(), source.binaryName(), source.origin()),
                displayedLine,
                activateEditor
        );
    }

    private static void openAt(
            DecompiledSource source,
            int offset,
            EditorLocation location,
            int executionLine,
            boolean activateEditor
    ) {
        SwingUtilities.invokeLater(() -> {
            MainWindow window = MainWindow.INSTANCE;
            window.getEditorTabs().focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(source.path()),
                    () -> new CodeView(source, offset, location)
            ).thenAccept(codeView -> {
                // A newly created editor can finish loading before its tab has been laid out. Apply
                // the destination again after EditorTabs reports both the tab and editor ready.
                codeView.centerViewportOnOffset(offset);
                if (executionLine > 0) {
                    codeView.showExecutionLine(executionLine);
                }
                if (activateEditor) {
                    UIUtils.focusWindow(window);
                }
            });
        });
    }

    static int lineOffset(String source, int line) {
        if (line < 1) {
            throw new IllegalArgumentException("Source line must be positive");
        }
        int currentLine = 1;
        int offset = 0;
        while (currentLine < line) {
            int newline = source.indexOf('\n', offset);
            if (newline < 0) {
                throw new IllegalArgumentException("Source has no line " + line);
            }
            offset = newline + 1;
            currentLine++;
        }
        return offset;
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
                        RuntimeModulePresentation.of(origin.module()).label()
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
        return targetOffset(CodeView.readCode(filePath), targetType, targetIdentifier);
    }

    static int targetOffset(String source, int targetType, String targetIdentifier) {
        if (targetType == -1) {
            return 0;
        }

        var ast = ASTCache.rawParse(COMPILATION_UNIT_NAME, source);
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
            return method.map(candidate -> candidate.getName().getStartPosition()).orElse(0);
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
                .mapToInt(declaration -> fieldOffset(declaration, targetIdentifier))
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

    private static int fieldOffset(Object declaration, String targetIdentifier) {
        return switch (declaration) {
            case EnumConstantDeclaration constant -> constant.getName().getStartPosition();
            case SingleVariableDeclaration component -> component.getName().getStartPosition();
            case FieldDeclaration field -> fieldFragmentOffset(field, targetIdentifier);
            default -> throw new IllegalArgumentException("Not a field declaration");
        };
    }

    private static int fieldFragmentOffset(FieldDeclaration field, String targetIdentifier) {
        for (Object candidate : field.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) candidate;
            if (fragment.getName().getIdentifier().equals(targetIdentifier)) {
                return fragment.getName().getStartPosition();
            }
        }
        throw new IllegalArgumentException("Field declaration does not contain " + targetIdentifier);
    }

    private static String removeCompilationUnitName(String identifier) {
        return identifier.replace(COMPILATION_UNIT_NAME + "~", "");
    }

    @SuppressWarnings("unchecked")
    private static List<BodyDeclaration> bodyDeclarations(AbstractTypeDeclaration type) {
        return (List<BodyDeclaration>) type.bodyDeclarations();
    }
}
