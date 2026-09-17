package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.ImportRewriteContext;
import org.eclipse.jdt.internal.codeassist.InternalCompletionProposal;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import static com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionLabels.text;

/** Converts JDT proposals into the replacement and import edits used by our snippet editor. */
final class CompletionEdits {
    private final ICompilationUnit unit;
    private final CompletionContext context;
    private final String source;
    private Set<String> declaredTypeNames;
    private IScanner scanner;

    CompletionEdits(ICompilationUnit unit, CompletionContext context) {
        this.unit = unit;
        this.context = context;
        try {
            this.source = unit.getSource();
        } catch (CoreException e) {
            throw new IllegalStateException("Cannot read completion source", e);
        }
    }

    void populate(CompletionProposal proposal, CompletionItem item) {
        try {
            ImportRewrite imports = imports();
            List<CustomTextEdit> additional = new ArrayList<>();
            Range range = range(proposal);
            String prefix = "";
            if (proposal.getRequiredProposals() != null) {
                for (CompletionProposal required : proposal.getRequiredProposals()) {
                    switch (required.getKind()) {
                        case CompletionProposal.TYPE_REF -> {
                            String type = typeReplacement(required, imports);
                            if (proposal.getKind() == CompletionProposal.CONSTRUCTOR_INVOCATION) {
                                prefix = type + constructorTypeArguments(required, proposal);
                                range = range(required);
                            } else {
                                additional.add(new CustomTextEdit(range(required), type));
                            }
                        }
                        case CompletionProposal.TYPE_IMPORT -> {
                            String type = imports.addImport(qualifiedType(required.getSignature()));
                            if (proposal.getKind() == CompletionProposal.METHOD_REF) prefix = type + ".";
                        }
                        case CompletionProposal.METHOD_IMPORT, CompletionProposal.FIELD_IMPORT -> {
                            String member = imports.addStaticImport(qualifiedType(required.getDeclarationSignature()),
                                    text(required.getName()), required.getKind() == CompletionProposal.FIELD_IMPORT);
                            int dot = member.lastIndexOf('.');
                            if (dot >= 0) prefix = imports.addImport(member.substring(0, dot)) + ".";
                        }
                        default -> throw new IllegalArgumentException("Unsupported required proposal: " + required.getKind());
                    }
                }
            }
            String replacement = prefix + replacement(proposal, imports);
            if (item.receiverCast() != null) {
                var cast = item.receiverCast();
                String type = imports.addImportFromSignature(cast.signature(), AST.newAST(AST.JLS21, false), castImportContext(imports)).toString();
                replacement = "((" + type + ") " + source.substring(cast.range().getOffset(), cast.range().getEndOffset())
                        + ")." + replacement;
                range = new Range(cast.range().getOffset(), range.getEndOffset() - cast.range().getOffset());
            }
            item.addTextEdit(new CustomTextEdit(range, replacement));
            if (!isImport(proposal)) additional.addAll(importEdits(imports));
            additional.forEach(item::addTextEdit);
        } catch (CoreException | BadLocationException e) {
            throw new IllegalStateException("Cannot prepare completion edits", e);
        }
    }

    String importType(String signature, CompletionItem item) {
        try {
            ImportRewrite imports = imports();
            String type = imports.addImportFromSignature(Signature.removeCapture(signature), AST.newAST(AST.JLS21, false),
                    castImportContext(imports)).toString();
            importEdits(imports).forEach(item::addTextEdit);
            return type;
        } catch (CoreException | BadLocationException e) {
            throw new IllegalStateException("Cannot prepare completion imports", e);
        }
    }

    private ImportRewriteContext castImportContext(ImportRewrite imports) {
        if (declaredTypeNames == null) {
            declaredTypeNames = new HashSet<>();
            var parser = JdtConfiguration.createParser();
            parser.setSource(source.toCharArray());
            parser.setStatementsRecovery(true);
            parser.createAST(null).accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    SimpleName name;
                    if (node instanceof AbstractTypeDeclaration declaration) name = declaration.getName();
                    else if (node instanceof TypeParameter parameter) name = parameter.getName();
                    else return;
                    ASTNode scope = node.getParent();
                    if (scope instanceof TypeDeclarationStatement) {
                        if (node.getStartPosition() > context.getOffset()) return;
                        scope = scope.getParent();
                    }
                    if (scope != null && scope.getStartPosition() <= context.getOffset()
                            && context.getOffset() <= scope.getStartPosition() + scope.getLength()) {
                        declaredTypeNames.add(name.getIdentifier());
                    }
                }
            });
        }
        return new ImportRewriteContext() {
            @Override public int findInContext(String qualifier, String name, int kind) {
                if (kind == KIND_TYPE && declaredTypeNames.contains(name)) return RES_NAME_CONFLICT;
                return imports.getDefaultImportRewriteContext().findInContext(qualifier, name, kind);
            }
        };
    }

    private String replacement(CompletionProposal proposal, ImportRewrite imports) {
        String completion = text(proposal.getCompletion());
        if (proposal.getKind() == CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER) return text(proposal.getName());
        if (proposal.getKind() == CompletionProposal.TYPE_REF) return typeReplacement(proposal, imports);
        if (proposal.getKind() == CompletionProposal.LAMBDA_EXPRESSION) {
            String token = text(context.getToken());
            return "(" + (token.isEmpty() ? arguments(proposal) : token) + ") -> ${0}";
        }
        if (proposal.getKind() == CompletionProposal.METHOD_NAME_REFERENCE || context.isInJavadoc()
                || !completion.endsWith(")")) return completion;

        String name = proposal.isConstructor() ? "" : text(proposal.getName());
        if (nextTokenChar(replacementEnd(proposal)) == '(') return name;
        String result = name + "(" + arguments(proposal) + ")";
        if (!proposal.isConstructor() && "V".equals(text(Signature.getReturnType(proposal.getSignature())))
                && nextTokenChar(replacementEnd(proposal)) != ';') result += ";";
        return result + "${0}";
    }

    private static String arguments(CompletionProposal proposal) {
        char[][] names = proposal.findParameterNames(null);
        StringJoiner arguments = new StringJoiner(", ");
        for (int i = 0; i < names.length; i++) arguments.add("${" + (i + 1) + ":" + new String(names[i]) + "}");
        return arguments.toString();
    }

    private String typeReplacement(CompletionProposal proposal, ImportRewrite imports) {
        String completion = text(proposal.getCompletion());
        if (isImport(proposal) || completion.isEmpty()) return completion;
        String type = qualifiedType(proposal.getSignature());
        if (context.isInJavadoc()) return Signature.getSimpleName(type);
        int start = Math.max(0, Math.min(proposal.getReplaceStart(), source.length()));
        int end = Math.max(start, Math.min(proposal.getReplaceEnd(), source.length()));
        String typed = source.substring(start, end);
        int dot = typed.lastIndexOf('.');
        if (dot >= 0 && type.regionMatches(true, 0, typed, 0, dot + 1)) return type;
        return imports.addImport(type);
    }

    private String constructorTypeArguments(CompletionProposal type, CompletionProposal constructor) throws CoreException {
        if (nextTokenChar(type.getReplaceEnd()) == '<') return "";
        if (constructor instanceof InternalCompletionProposal internal && internal.getBinding() instanceof MethodBinding method) {
            if (method.original().declaringClass.typeVariables().length == 0) return "";
        } else {
            // Search-based constructor proposals have no binding. JDT's diamond check alone also accepts nongeneric types.
            var declaration = unit.getJavaProject().findType(qualifiedType(type.getSignature()));
            if (declaration != null && declaration.getTypeParameters().length == 0) return "";
        }
        if (constructor.canUseDiamond(context)) return "<>";
        char[][] arguments = Signature.getTypeArguments(type.getSignature());
        if (arguments.length == 0) return "";
        StringJoiner names = new StringJoiner(", ", "<", ">");
        for (char[] argument : arguments) names.add(CompletionLabels.type(argument));
        return names.toString();
    }

    private char nextTokenChar(int offset) {
        if (offset >= source.length()) return '\0';
        if (scanner == null) {
            scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
            scanner.setSource(source.toCharArray());
        }
        scanner.resetTo(Math.max(0, offset), source.length() - 1);
        try {
            scanner.getNextToken();
            int start = scanner.getCurrentTokenStartPosition();
            if (start < source.length()) return source.charAt(start);
        } catch (InvalidInputException ignored) { }
        return '\0';
    }

    private ImportRewrite imports() throws CoreException {
        ImportRewrite imports = ImportRewrite.create(unit, true);
        imports.setImportOrder(new String[]{"java", "javax", "com", "org", "net"});
        imports.setOnDemandImportThreshold(99);
        imports.setStaticOnDemandImportThreshold(99);
        return imports;
    }

    private List<CustomTextEdit> importEdits(ImportRewrite imports) throws CoreException, BadLocationException {
        TextEdit edits = imports.rewriteImports(null);
        if (!edits.hasChildren() && edits.getLength() == 0) return List.of();
        Range originalRange = new Range(edits.getOffset(), edits.getLength());
        Document document = new Document(source);
        edits.apply(document, TextEdit.UPDATE_REGIONS);
        if (source.equals(document.get())) return List.of();
        // Preserve JDT's import-section boundaries; source maps use them to separate imports from the body.
        String replacement = document.get(edits.getOffset(), edits.getLength());
        return List.of(new CustomTextEdit(originalRange, replacement));
    }

    private static String qualifiedType(char[] signature) {
        return Signature.toString(new String(Signature.getTypeErasure(signature)));
    }

    private static boolean isImport(CompletionProposal proposal) {
        String completion = text(proposal.getCompletion());
        return completion.endsWith(";") || completion.endsWith(".");
    }

    private int replacementEnd(CompletionProposal proposal) {
        int caret = context.getOffset();
        if (caret < proposal.getReplaceStart() || caret > proposal.getReplaceEnd()) return proposal.getReplaceEnd();
        switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.METHOD_NAME_REFERENCE, CompletionProposal.CONSTRUCTOR_INVOCATION,
                    CompletionProposal.TYPE_REF -> {
                // JDT may offer to replace the whole recovered call. Preserve its existing arguments and terminator.
                while (caret < source.length() && Character.isJavaIdentifierPart(source.charAt(caret))) caret++;
                return caret;
            }
            default -> { return proposal.getReplaceEnd(); }
        }
    }

    private Range range(CompletionProposal proposal) {
        return new Range(proposal.getReplaceStart(), replacementEnd(proposal) - proposal.getReplaceStart());
    }
}
