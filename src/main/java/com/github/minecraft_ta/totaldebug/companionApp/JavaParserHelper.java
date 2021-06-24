package com.github.minecraft_ta.totaldebug.companionApp;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    public static CompilationUnit parse(String code, boolean typeSolver) {
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        if (typeSolver) {
            config.setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver(false)));
        }

        return new JavaParser(config).parse(code).getResult().orElse(null);
    }

    public static Node getResolvableNodeAt(Node rootNode, Position position) {
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

        return node;
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
