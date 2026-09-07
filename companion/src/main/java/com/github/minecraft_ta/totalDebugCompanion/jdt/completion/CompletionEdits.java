package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionLabels.text;

/** Converts JDT proposals into the replacement and import edits used by our snippet editor. */
final class CompletionEdits {
    private final ICompilationUnit unit;
    private final CompletionContext context;
    private final String source;

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
            item.addTextEdit(new CustomTextEdit(range, prefix + replacement(proposal, imports)));
            if (!isImport(proposal)) additional.addAll(importEdits(imports));
            additional.forEach(item::addTextEdit);
        } catch (CoreException | BadLocationException e) {
            throw new IllegalStateException("Cannot prepare completion edits", e);
        }
    }

    List<CustomTextEdit> addImports(String... names) {
        try {
            ImportRewrite imports = imports();
            for (String name : names) imports.addImport(name);
            return importEdits(imports);
        } catch (CoreException | BadLocationException e) {
            throw new IllegalStateException("Cannot prepare completion imports", e);
        }
    }

    private String replacement(CompletionProposal proposal, ImportRewrite imports) {
        String completion = text(proposal.getCompletion());
        if (proposal.getKind() == CompletionProposal.TYPE_REF) return typeReplacement(proposal, imports);
        if (proposal.getKind() == CompletionProposal.LAMBDA_EXPRESSION) {
            String token = text(context.getToken());
            return "(" + (token.isEmpty() ? arguments(proposal) : token) + ") -> ${0}";
        }
        if (proposal.getKind() == CompletionProposal.METHOD_NAME_REFERENCE || context.isInJavadoc()
                || !completion.endsWith(")")) return completion;

        String name = proposal.isConstructor() ? "" : text(proposal.getName());
        if (proposal.getKind() == CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER) {
            name = completion.substring(0, completion.lastIndexOf('.') + 1) + name;
        }
        String result = name + "(" + arguments(proposal) + ")${0}";
        if (!proposal.isConstructor() && "V".equals(text(Signature.getReturnType(proposal.getSignature())))
                && nextNonWhitespace(proposal.getReplaceEnd()) != ';') result += ";";
        return result;
    }

    private static String arguments(CompletionProposal proposal) {
        int count = Signature.getParameterCount(Signature.removeCapture(proposal.getSignature()));
        StringJoiner arguments = new StringJoiner(", ");
        for (int i = 0; i < count; i++) arguments.add("${" + (i + 1) + ":arg" + i + "}");
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

    private String constructorTypeArguments(CompletionProposal type, CompletionProposal constructor) {
        if (nextNonWhitespace(type.getReplaceEnd()) == '<') return "";
        if (constructor.canUseDiamond(context)) return "<>";
        char[][] arguments = Signature.getTypeArguments(type.getSignature());
        if (arguments.length == 0) return "";
        StringJoiner names = new StringJoiner(", ", "<", ">");
        for (char[] argument : arguments) names.add(CompletionLabels.type(argument));
        return names.toString();
    }

    private char nextNonWhitespace(int offset) {
        for (int i = Math.max(0, offset); i < source.length(); i++) {
            if (!Character.isWhitespace(source.charAt(i))) return source.charAt(i);
        }
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

    private static Range range(CompletionProposal proposal) {
        return new Range(proposal.getReplaceStart(), proposal.getReplaceEnd() - proposal.getReplaceStart());
    }
}
