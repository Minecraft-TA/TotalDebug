package com.github.minecraft_ta.totalDebugCompanion.jdt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;

import java.util.HashMap;
import java.util.Map;

public final class JdtConfiguration {

    public static final String JAVA_VERSION = JavaCore.VERSION_21;

    private JdtConfiguration() {
    }

    public static ASTParser createParser() {
        ASTParser parser = ASTParser.newParser(AST.JLS21);
        Map<String, String> options = new HashMap<>();
        applyJavaCompilerOptions(options);
        parser.setCompilerOptions(options);
        return parser;
    }

    public static void applyJavaCompilerOptions(Map<String, String> options) {
        JavaCore.setComplianceOptions(JAVA_VERSION, options);
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
        options.put(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.IGNORE);
    }
}
