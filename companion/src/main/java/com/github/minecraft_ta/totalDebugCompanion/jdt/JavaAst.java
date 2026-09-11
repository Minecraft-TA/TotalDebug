package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Stateless parsing against the current JDT environment, independent of editor cache entries. */
public final class JavaAst {
    private JavaAst() {}

    public static CompilationUnit parse(String className, String contents) {
        ASTParser parser = JdtConfiguration.createParser();
        parser.setSource(new CompilationUnitImpl(className, contents));
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return (CompilationUnit) parser.createAST(null);
    }

}
