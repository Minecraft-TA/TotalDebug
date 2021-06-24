package com.github.minecraft_ta.totaldebug.companionApp;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;

public class JavaParserHelper {

    public static Method getReflectMethodFromReflectionMethodDeclaration(ReflectionMethodDeclaration methodDeclaration) {
        try {
            Field field = methodDeclaration.getClass().getDeclaredField("method");
            field.setAccessible(true);
            return (Method) field.get(methodDeclaration);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static ResolvedTypeDeclaration getDeclaringTypeFromResolvedObject(Object o) {
        if (o instanceof ResolvedMethodLikeDeclaration)
            return ((ResolvedMethodLikeDeclaration) o).declaringType();
        else if (o instanceof ResolvedFieldDeclaration)
            return ((ResolvedFieldDeclaration) o).declaringType();
        else if (o instanceof ResolvedTypeDeclaration)
            return (ResolvedTypeDeclaration) o;
        else if (o instanceof ResolvedEnumConstantDeclaration)
            return ((ReferenceTypeImpl) ((ResolvedEnumConstantDeclaration) o).getType()).getTypeDeclaration().get();

        throw new IllegalArgumentException("Unable to get declaring type for parameter: " + o);
    }

    public static String getSimplifiedSignatureForResolvedObject(Object o) {
        if (o instanceof JavaParserMethodDeclaration)
            return getSimplifiedSignatureForJavaParserMethod((JavaParserMethodDeclaration) o);
        else if (o instanceof ReflectionMethodDeclaration)
            return getSimplifiedSignatureForReflectionMethod(getReflectMethodFromReflectionMethodDeclaration((ReflectionMethodDeclaration) o));
        else if (o instanceof ResolvedFieldDeclaration)
            return ((ResolvedFieldDeclaration) o).getName();
        else if(o instanceof ResolvedEnumConstantDeclaration)
            return ((ResolvedEnumConstantDeclaration) o).getName();
        else if (o instanceof ResolvedTypeDeclaration)
            return "";

        throw new IllegalArgumentException("Unable to generate simplified signature for parameter: " + o);
    }

    @Nonnull
    public static String getSimplifiedSignatureForJavaParserMethod(@Nonnull JavaParserMethodDeclaration method) {
        StringBuilder signatureBuilder = new StringBuilder(method.getName()).append('(');
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration parameter = method.getParam(i);
            String typeName = parameter.describeType();
            if (typeName.endsWith(">"))
                typeName = typeName.substring(0, typeName.indexOf('<'));
            if (typeName.contains("."))
                typeName = typeName.substring(typeName.lastIndexOf('.') + 1);

            signatureBuilder.append(typeName);

            if (i != method.getNumberOfParams() - 1)
                signatureBuilder.append(", ");
        }

        return signatureBuilder.append(')').toString();
    }

    /**
     * @return a type signature for the given {@code method} which looks like javaparsers signature when it has no type
     * information
     */
    @Nonnull
    public static String getSimplifiedSignatureForReflectionMethod(@Nonnull Method method) {
        StringBuilder signatureBuilder = new StringBuilder(method.getName()).append('(');
        Type[] parameters = method.getGenericParameterTypes().length != 0 ? method.getGenericParameterTypes() : method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            String typeName = parameter.getTypeName();
            if (typeName.endsWith(">"))
                typeName = typeName.substring(0, typeName.indexOf('<'));
            if (typeName.contains("."))
                typeName = typeName.substring(typeName.lastIndexOf('.') + 1);

            signatureBuilder.append(typeName);

            if (i != parameters.length - 1)
                signatureBuilder.append(", ");
        }

        return signatureBuilder.append(')').toString();
    }

    public static CompilationUnit parse(String code, boolean typeSolver) {
        ParserConfiguration config = new ParserConfiguration();
        if (typeSolver) {
            config.setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver(false)));
        }

        return new JavaParser(config).parse(code).getResult().orElse(null);
    }

    public static <T extends Node & Resolvable<?>> T getResolvableNodeAt(Node rootNode, Position position) {
        Node node = getNodeAt(rootNode, position);

        if (node == null)
            return null;

        while (true) {
            if (node instanceof Resolvable)
                break;

            Optional<Node> parent = node.getParentNode();
            if (!parent.isPresent())
                break;
            node = parent.get();
        }

        if (!(node instanceof Resolvable))
            return null;

        return (T) node;
    }

    public static Node getNodeAt(Node rootNode, Position position) {
        boolean bounds = true;
        if (position.isBefore(rootNode.getBegin().get()) || position.isAfter(rootNode.getEnd().get())) {
            bounds = false;
        }

        for (Node child : rootNode.getChildNodes()) {
            Node ret = getNodeAt(child, position);
            if (ret != null)
                return ret;
        }

        if (!bounds)
            return null;
        return rootNode;
    }
}
