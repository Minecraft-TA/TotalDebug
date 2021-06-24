package com.github.minecraft_ta.totaldebug.companionApp.messages;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.JavaParserHelper;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

            Resolvable<?> node = JavaParserHelper.getResolvableNodeAt(unit.findRootNode(), position);
            if (node == null) {
                TotalDebug.LOGGER.warn("Unable to find resolvable node at position: " + position);
                return;
            }

            Object resolvedObject;
            if (node instanceof NameExpr)
                resolvedObject = ((NameExpr) node).calculateResolvedType();
            else
                resolvedObject = node.resolve();

            if(resolvedObject instanceof ReferenceTypeImpl)
                resolvedObject = ((ReferenceTypeImpl) resolvedObject).getTypeDeclaration().get();

            ResolvedTypeDeclaration declaringType = JavaParserHelper.getDeclaringTypeFromResolvedObject(resolvedObject);
            String name = declaringType.getQualifiedName();
            //Fix type name if it's an inner class
            try {
                if (declaringType.containerType().isPresent())
                    name = name.substring(0, name.lastIndexOf('.')) + "$" + name.substring(name.lastIndexOf('.') + 1);
            } catch (Throwable ignored) {} //Calling containerType() breaks JavaParser sometimes
            //Decompile the target class
            TotalDebug.PROXY.getDecompilationManager().decompileClassIfNotExists(Class.forName(name));

            //Parse the target class
            config.setSymbolResolver(null);
            CompilationUnit declaringTypeUnit = javaParser.parse(
                    new String(
                            Files.readAllBytes(decompilationDir.resolve(name + ".java")),
                            StandardCharsets.UTF_8
                    )
            ).getResult().get();

            //Find the resolved object in the target class
            String signatureToMatch = JavaParserHelper.getSimplifiedSignatureForResolvedObject(resolvedObject);
            Optional<BodyDeclaration<?>> optionalMember;
            if (declaringTypeUnit.getTypes().isNonEmpty()) {
                optionalMember = declaringTypeUnit.getType(0).getMembers().stream()
                        .filter(m -> {
                            if (m instanceof MethodDeclaration)
                                return ((MethodDeclaration) m).getSignature().toString().equals(signatureToMatch);
                            else if (m instanceof FieldDeclaration)
                                return ((FieldDeclaration) m).getVariables().stream().anyMatch(v -> v.getName().asString().equals(signatureToMatch));
                            return false;
                        }).findFirst();
            } else {
                optionalMember = Optional.empty();
            }

            int line = optionalMember.map(bodyDeclaration -> bodyDeclaration.getRange().get().begin.line).orElse(1);
            TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(name), line);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
