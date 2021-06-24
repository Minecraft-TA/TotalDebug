package com.github.minecraft_ta.totaldebug.companionApp.messages;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.JavaParserHelper;
import com.github.minecraft_ta.totaldebug.util.mappings.ClassUtil;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CodeViewClickMessage extends AbstractMessageIncoming {

    private String fileName;
    private int row;
    private int column;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.fileName = messageStream.readString();
        this.row = messageStream.readInt();
        this.column = messageStream.readInt();
    }

    public static void handle(CodeViewClickMessage message) {
        Path decompilationDir = TotalDebug.PROXY.getDecompilationManager().getDecompilationDir();
        Path file = decompilationDir.resolve(message.fileName).toAbsolutePath();
        if (!Files.exists(file)) {
            TotalDebug.LOGGER.error("Companion app sent file path that doesn't exist: {}", file.toString());
            return;
        }

        try {
            String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Position position = new Position(message.row + 1, message.column + 1);

            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver(false)));

            JavaParser javaParser = new JavaParser(config);
            CompilationUnit unit = javaParser.parse(code).getResult().orElse(null);
            if (unit == null) {
                TotalDebug.LOGGER.error("Unable to parse java file requested by companion app {}", file.toString());
                return;
            }

            Node node = JavaParserHelper.getResolvableNodeAt(unit.findRootNode(), position);
            if (node == null)
                return;

            ReflectionMethodDeclaration method = (ReflectionMethodDeclaration) ((Resolvable<?>) node).resolve();

            //TODO: check for method.declaringType().getContainerType().isPresent()
            String name = method.declaringType().getQualifiedName();
            TotalDebug.PROXY.getDecompilationManager().decompileClassIfNotExists(Class.forName(name));

            config.setSymbolResolver(null);
            CompilationUnit declaringTypeUnit = javaParser.parse(
                    new String(
                            Files.readAllBytes(decompilationDir.resolve(name + ".java")),
                            StandardCharsets.UTF_8
                    )
            ).getResult().get();

            String signatureToMatch = ClassUtil.getSimplifiedSignatureForMethod(JavaParserHelper.getReflectMethodFromReflectionMethodDeclaration(method));
            int line = declaringTypeUnit.getType(0).getMembers().stream()
                    .filter(m -> m instanceof MethodDeclaration)
                    .filter(m -> ((MethodDeclaration) m).getSignature().toString().equals(signatureToMatch))
                    .findFirst().get().getRange().get().begin.line;

            TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(name), line);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
