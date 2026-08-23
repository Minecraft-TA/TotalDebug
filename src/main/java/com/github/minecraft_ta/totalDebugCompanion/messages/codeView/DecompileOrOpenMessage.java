package com.github.minecraft_ta.totalDebugCompanion.messages.codeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import javax.swing.SwingUtilities;

public class DecompileOrOpenMessage extends AbstractMessage {

    private static final String CU_NAME = "Name";

    private String name;
    private int targetType;
    private String targetIdentifier;

    public DecompileOrOpenMessage() {
    }

    public DecompileOrOpenMessage(String name) {
        this(name, -1, null);
    }

    public DecompileOrOpenMessage(String name, int targetType, String targetIdentifier) {
        this.name = name;
        this.targetType = targetType;
        this.targetIdentifier = targetIdentifier == null ? "" : targetIdentifier;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.name);
        messageStream.writeInt(this.targetType);
        messageStream.writeString(this.targetIdentifier);
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.name = messageStream.readString();
        this.targetType = messageStream.readInt();
        this.targetIdentifier = CodeUtils.minimalizeMethodIdentifier(messageStream.readString(), false);
    }

    public static void handle(DecompileOrOpenMessage message) {
        var filePath = Paths.get(message.name);
        if (!Files.exists(filePath) || !FileUtils.isSubPathOf(CompanionApp.getRootPath(), filePath))
            return;

        int offset = 0;
        if (message.targetType != -1) {
            var ast = ASTCache.rawParse(CU_NAME, CodeView.readCode(filePath));
            var firstType = ast.types().get(0);
            if (!(firstType instanceof AbstractTypeDeclaration type)) {
                System.err.println("Failed to get type declaration");
                return;
            }

            if (message.targetType == IJavaElement.METHOD) {
                var isDefaultConstructor = message.targetIdentifier.equals("()V");
                var targetMethod = findTargetMethod(message, type);
                if (targetMethod.isEmpty() && !isDefaultConstructor) {
                    System.err.println("Failed to find target method");
                    return;
                }

                offset = targetMethod.map(ASTNode::getStartPosition).orElse(0);
            } else if (message.targetType == IJavaElement.FIELD) {
                var targetField = findTargetField(message, type);

                if (targetField.isEmpty()) {
                    System.err.println("Failed to find target field");
                    return;
                }

                offset = targetField.getAsInt();
            } else {
                System.err.println("Unknown target type");
                return;
            }
        }

        var finalOffset = offset;
        SwingUtilities.invokeLater(() -> {
            var window = MainWindow.INSTANCE;
            window.getEditorTabs().focusOrCreateIfAbsent(
                    CodeView.class,
                    (cv) -> cv.getPath().equals(filePath),
                    () -> new CodeView(filePath, finalOffset)
            ).thenAccept(codeView -> {
                codeView.centerViewportOnOffset(finalOffset);
                UIUtils.focusWindow(window);
            });
        });
    }

    private static Optional<MethodDeclaration> findTargetMethod(DecompileOrOpenMessage message, AbstractTypeDeclaration type) {
        //noinspection unchecked
        return ((List<BodyDeclaration>) type.bodyDeclarations()).stream()
                .filter(decl -> decl instanceof MethodDeclaration)
                .map(decl -> (MethodDeclaration) decl)
                .filter((m) -> removeCUNameFromIdentifier(CodeUtils.minimalizeMethodIdentifier(m.resolveBinding().getKey(), false)).equals(message.targetIdentifier))
                .findFirst();
    }

    private static OptionalInt findTargetField(DecompileOrOpenMessage message, AbstractTypeDeclaration type) {
        List<Object> declarations = new ArrayList<>(type.bodyDeclarations());
        if (type instanceof EnumDeclaration enumDeclaration) {
            declarations.addAll(enumDeclaration.enumConstants());
        } else if (type instanceof RecordDeclaration recordDeclaration) {
            declarations.addAll(recordDeclaration.recordComponents());
        }
        return declarations.stream()
                .filter(decl -> decl instanceof FieldDeclaration
                        || decl instanceof EnumConstantDeclaration
                        || decl instanceof SingleVariableDeclaration)
                .filter(decl -> {
                    if (decl instanceof EnumConstantDeclaration enumConstant) {
                        return enumConstant.getName().getIdentifier().equals(message.targetIdentifier);
                    } else if (decl instanceof SingleVariableDeclaration recordComponent) {
                        return recordComponent.getName().getIdentifier().equals(message.targetIdentifier);
                    } else {
                        FieldDeclaration field = (FieldDeclaration) decl;
                        //noinspection unchecked
                        return field.fragments().stream().anyMatch(frag -> ((VariableDeclarationFragment) frag).getName().getIdentifier().equals(message.targetIdentifier));
                    }
                }).mapToInt(decl -> {
                    if (decl instanceof EnumConstantDeclaration enumConstant)
                        return enumConstant.getStartPosition();
                    else if (decl instanceof SingleVariableDeclaration recordComponent)
                        return recordComponent.getStartPosition();
                    else
                        return ((FieldDeclaration) decl).getStartPosition();
                }).findFirst();
    }

    private static String removeCUNameFromIdentifier(String identifier) {
        return identifier.replace(DecompileOrOpenMessage.CU_NAME + "~", "");
    }
}
