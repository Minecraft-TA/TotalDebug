package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

final class SourceFileNavigation {
    private static final String COMPILATION_UNIT_NAME = "Name";

    private SourceFileNavigation() {
    }

    static void open(Path filePath, int targetType, String targetIdentifier) {
        int offset = targetOffset(filePath, targetType, targetIdentifier);
        SwingUtilities.invokeLater(() -> {
            MainWindow window = MainWindow.INSTANCE;
            AtomicBoolean created = new AtomicBoolean();
            window.getEditorTabs().focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(filePath),
                    () -> {
                        created.set(true);
                        return new CodeView(filePath, offset);
                    }
            ).thenAccept(codeView -> {
                if (!created.get()) {
                    codeView.reload(offset);
                }
                UIUtils.focusWindow(window);
            });
        });
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
