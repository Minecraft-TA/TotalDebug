/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.Range;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.codeassist.CompletionEngine;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnSingleNameReference;
import org.eclipse.jdt.internal.core.DocumentAdapter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.text.edits.TextEdit;

import java.util.*;

public class CompletionProposalReplacementProvider {

    private static final boolean SNIPPETS_SUPPORTED = true;

    private static final String CURSOR_POSITION = "${0}";
    private static final char SPACE = ' ';
    private static final char LPAREN = '(';
    private static final char RPAREN = ')';
    private static final char SEMICOLON = ';';
    private static final char COMMA = ',';
    private static final char LESS = '<';
    private static final char GREATER = '>';
    private final ICompilationUnit compilationUnit;
    private final int offset;
    private final CompletionContext context;
    private ImportRewrite importRewrite;

    public CompletionProposalReplacementProvider(ICompilationUnit compilationUnit, CompletionContext context, int offset) {
        super();
        this.compilationUnit = compilationUnit;
        this.context = context;
        this.offset = offset;
    }

    public void updateReplacement(CompletionProposal proposal, CompletionItem item, char trigger) {
        // reset importRewrite
        this.importRewrite = TypeProposalUtils.createImportRewrite(compilationUnit);

        List<CustomTextEdit> additionalTextEdits = new ArrayList<>();

        StringBuilder completionBuffer = new StringBuilder();
        Range range = null;
        if (isSupportingRequiredProposals(proposal)) {
            CompletionProposal[] requiredProposals = proposal.getRequiredProposals();
            if (requiredProposals != null) {
                for (CompletionProposal requiredProposal : requiredProposals) {
                    switch (requiredProposal.getKind()) {
                        case CompletionProposal.TYPE_IMPORT, CompletionProposal.METHOD_IMPORT, CompletionProposal.FIELD_IMPORT ->
                                appendImportProposal(completionBuffer, requiredProposal, proposal.getKind());
                        case CompletionProposal.TYPE_REF -> {
                            CustomTextEdit edit = toRequiredTypeEdit(requiredProposal, trigger, proposal.canUseDiamond(context));
                            if (proposal.getKind() == CompletionProposal.CONSTRUCTOR_INVOCATION) {
                                completionBuffer.append(edit.getNewText());
                                range = edit.getRange();
                            } else {
                                additionalTextEdits.add(edit);
                            }
                        }
                        default ->
                            /*
                             * In 3.3 we only support the above required proposals, see
                             * CompletionProposal#getRequiredProposals()
                             */
                                Assert.isTrue(false);
                    }
                }
            }
        }

        if (range == null)
            range = toReplacementRange(proposal);

        switch (proposal.getKind()) {
            case CompletionProposal.LAMBDA_EXPRESSION -> appendLambdaExpressionReplacement(completionBuffer, proposal);
            default -> appendReplacementString(completionBuffer, proposal);
        }

        String text = completionBuffer.toString();
        item.addTextEdit(new CustomTextEdit(range, text));

        if (!isImportCompletion(proposal)) {
            addImports(additionalTextEdits);
            additionalTextEdits.forEach(item::addTextEdit);
        }
    }

    private void appendLambdaExpressionReplacement(StringBuilder completionBuffer, CompletionProposal proposal) {
        completionBuffer.append(LPAREN);

        var completionNode = ((InternalCompletionContext) this.context).getCompletionNode();
        if (completionNode instanceof CompletionOnSingleNameReference && this.context.getToken().length != 0) {
            completionBuffer.append(this.context.getToken());
        } else {
            appendGuessingCompletion(completionBuffer, proposal);
        }

        completionBuffer.append(RPAREN);
        completionBuffer.append(" -> ");
        if (SNIPPETS_SUPPORTED) {
            completionBuffer.append(CURSOR_POSITION);
        }
    }

    private Range toReplacementRange(CompletionProposal proposal) {
        return new Range(proposal.getReplaceStart(), proposal.getReplaceEnd() - proposal.getReplaceStart());
    }

    public List<CustomTextEdit> singleImportRewrite(String... typeNames) {
        this.importRewrite = TypeProposalUtils.createImportRewrite(compilationUnit);
        for (var typeName : typeNames)
            this.importRewrite.addImport(typeName);

        var list = new ArrayList<CustomTextEdit>();
        addImports(list);
        return list;
    }

    private void addImports(List<CustomTextEdit> additionalEdits) {
        if (this.importRewrite == null)
            return;

        try {
            TextEdit edit = this.importRewrite.rewriteImports(new NullProgressMonitor());
            TextEditConverter converter = new TextEditConverter(this.compilationUnit, edit);
            additionalEdits.addAll(converter.convert());
        } catch (CoreException e) {
            e.printStackTrace();
        }
    }

    private boolean isSupportingRequiredProposals(CompletionProposal proposal) {
        return proposal != null
               && (proposal.getKind() == CompletionProposal.METHOD_REF
                   || proposal.getKind() == CompletionProposal.FIELD_REF
                   || proposal.getKind() == CompletionProposal.TYPE_REF
                   || proposal.getKind() == CompletionProposal.CONSTRUCTOR_INVOCATION);
    }

    protected boolean hasArgumentList(CompletionProposal proposal) {
        if (CompletionProposal.METHOD_NAME_REFERENCE == proposal.getKind()) {
            return false;
        } else if (CompletionProposal.LAMBDA_EXPRESSION == proposal.getKind()) {
            return true;
        }
        char[] completion = proposal.getCompletion();
        return !isInJavadoc() && completion.length > 0 && completion[completion.length - 1] == RPAREN;
    }

    private boolean isInJavadoc() {
        return context.isInJavadoc();
    }

    private void appendReplacementString(StringBuilder buffer, CompletionProposal proposal) {
        if (!hasArgumentList(proposal)) {
            String str = proposal.getKind() == CompletionProposal.TYPE_REF ? computeJavaTypeReplacementString(proposal) : String.valueOf(proposal.getCompletion());
            buffer.append(str);
            return;
        }

        // we're inserting a method plus the argument list - respect formatter preferences
        appendMethodNameReplacement(buffer, proposal);
        if (SNIPPETS_SUPPORTED)
            buffer.append(LPAREN);

        if (hasParameters(proposal))
            appendGuessingCompletion(buffer, proposal);

        if (SNIPPETS_SUPPORTED) {
            buffer.append(RPAREN);
            buffer.append("${0}");
            // add semicolons only if there are parentheses
            if (canAutomaticallyAppendSemicolon(proposal))
                buffer.append(SEMICOLON);
        }
    }

    private boolean hasParameters(CompletionProposal proposal) throws IllegalArgumentException {
        return hasArgumentList(proposal) &&
               Signature.getParameterCount(proposal.getSignature()) > 0;
    }

    private void appendMethodNameReplacement(StringBuilder buffer, CompletionProposal proposal) {
        if (proposal.getKind() == CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER) {
            String coreCompletion = String.valueOf(proposal.getCompletion());
            if (SNIPPETS_SUPPORTED) {
                coreCompletion = sanitizeCompletion(coreCompletion);
            }
            String lineDelimiter = "\n";
            String replacement = CodeFormatterUtil.format(CodeFormatter.K_EXPRESSION, coreCompletion, 0, lineDelimiter, JDTHacks.DUMMY_JAVA_PROJECT.getOptions(true));
            buffer.append(replacement, 0, replacement.lastIndexOf('.') + 1);
        }

        if (proposal.getKind() != CompletionProposal.CONSTRUCTOR_INVOCATION) {
            String str = new String(proposal.getName());
            if (SNIPPETS_SUPPORTED)
                str = sanitizeCompletion(str);

            buffer.append(str);
        }
    }

    private void appendGuessingCompletion(StringBuilder buffer, CompletionProposal proposal) {
        char[][] parameterNames;
//        try {
//            parameterNames = proposal.findParameterNames(null);
//        } catch (Exception e) {
//            e.printStackTrace();
        char[] signature = SignatureHelper.fix83600(proposal.getSignature());
        parameterNames = CompletionEngine.createDefaultParameterNames(Signature.getParameterCount(signature));
        proposal.setParameterNames(parameterNames);
//        }

        int count = parameterNames.length;

        if (SNIPPETS_SUPPORTED) {
            for (int i = 0; i < count; i++) {
                if (i != 0) {
                    buffer.append(COMMA);
                    buffer.append(SPACE);
                }
                char[] argument;
                argument = parameterNames[i];
                if (SNIPPETS_SUPPORTED) {
                    String replace = new String(argument);
                    replace = sanitizeCompletion(replace);
                    argument = replace.toCharArray();
                }
                buffer.append("${");
                buffer.append(i + 1);
                buffer.append(":");
                buffer.append(argument);
                buffer.append("}");
            }
        }
    }

    private boolean canAutomaticallyAppendSemicolon(CompletionProposal proposal) {
        return !proposal.isConstructor() && CharOperation.equals(new char[]{Signature.C_VOID}, Signature.getReturnType(proposal.getSignature()));
    }

    private CustomTextEdit toRequiredTypeEdit(CompletionProposal typeProposal, char trigger, boolean canUseDiamond) {
        StringBuilder buffer = new StringBuilder();
        appendReplacementString(buffer, typeProposal);

        if (compilationUnit == null /*|| getContext() != null && getContext().isInJavadoc()*/) {
            Range range = toReplacementRange(typeProposal);
            return new CustomTextEdit(range, buffer.toString());
        }

        IJavaProject project = compilationUnit.getJavaProject();
        if (!shouldProposeGenerics(project)) {
            Range range = toReplacementRange(typeProposal);
            return new CustomTextEdit(range, buffer.toString());
        }

        char[] completion = typeProposal.getCompletion();
        // don't add parameters for import-completions nor for proposals with an empty completion (e.g. inside the type argument list)
        if (completion.length > 0 && (completion[completion.length - 1] == SEMICOLON || completion[completion.length - 1] == '.')) {
            Range range = toReplacementRange(typeProposal);
            return new CustomTextEdit(range, buffer.toString());
        }

        /*
         * Add parameter types
         */
        boolean onlyAppendArguments;
        try {
            onlyAppendArguments = typeProposal.getCompletion().length == 0 && offset > 0 && compilationUnit.getBuffer().getChar(offset - 1) == '<';
        } catch (JavaModelException e) {
            onlyAppendArguments = false;
        }
        if (onlyAppendArguments || shouldAppendArguments(typeProposal, trigger)) {
            String[] typeArguments = computeTypeArgumentProposals(typeProposal);
            if (typeArguments.length > 0) {
                if (canUseDiamond) {
                    buffer.append("<>");
                } else {
                    appendParameterList(buffer, typeArguments, onlyAppendArguments);
                }
            }
        }
        Range range = toReplacementRange(typeProposal);
        return new CustomTextEdit(range, buffer.toString());
    }

    private boolean shouldProposeGenerics(IJavaProject project) {
        return true;
    }

    private IJavaElement resolveJavaElement(IJavaProject project, CompletionProposal proposal) throws JavaModelException {
        char[] signature = proposal.getSignature();
        String typeName = SignatureHelper.stripSignatureToFQN(String.valueOf(signature));
        return project.findType(typeName);
    }

    private String[] computeTypeArgumentProposals(CompletionProposal proposal) {
        try {
            IType type = (IType) resolveJavaElement(compilationUnit.getJavaProject(), proposal);
            if (type == null) {
                return new String[0];
            }

            ITypeParameter[] parameters = type.getTypeParameters();
            if (parameters.length == 0) {
                return new String[0];
            }

            String[] arguments = new String[parameters.length];

            ITypeBinding expectedTypeBinding = getExpectedTypeForGenericParameters();
            if (expectedTypeBinding != null && expectedTypeBinding.isParameterizedType()) {
                // in this case, the type arguments we propose need to be compatible
                // with the corresponding type parameters to declared type

                IType expectedType = (IType) expectedTypeBinding.getJavaElement();

                IType[] path = TypeProposalUtils.computeInheritancePath(type, expectedType);
                if (path == null) {
                    // proposed type does not inherit from expected type
                    // the user might be looking for an inner type of proposed type
                    // to instantiate -> do not add any type arguments
                    return new String[0];
                }

                int[] indices = new int[parameters.length];
                for (int paramIdx = 0; paramIdx < parameters.length; paramIdx++) {
                    indices[paramIdx] = TypeProposalUtils.mapTypeParameterIndex(path, path.length - 1, paramIdx);
                }

                // for type arguments that are mapped through to the expected type's
                // parameters, take the arguments of the expected type
                ITypeBinding[] typeArguments = expectedTypeBinding.getTypeArguments();
                for (int paramIdx = 0; paramIdx < parameters.length; paramIdx++) {
                    if (indices[paramIdx] != -1) {
                        // type argument is mapped through
                        ITypeBinding binding = typeArguments[indices[paramIdx]];
                        arguments[paramIdx] = computeTypeProposal(binding, parameters[paramIdx]);
                    }
                }
            }

            // for type arguments that are not mapped through to the expected type,
            // take the lower bound of the type parameter
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] == null) {
                    arguments[i] = computeTypeProposal(parameters[i]);
                }
            }
            return arguments;
        } catch (JavaModelException e) {
            return new String[0];
        }
    }

    private String computeTypeProposal(ITypeParameter parameter) throws JavaModelException {
        String[] bounds = parameter.getBounds();
        String elementName = parameter.getElementName();
        if (bounds.length == 1 && !"java.lang.Object".equals(bounds[0])) {
            return Signature.getSimpleName(bounds[0]);
        } else {
            return elementName;
        }
    }

    private String computeTypeProposal(ITypeBinding binding, ITypeParameter parameter) throws JavaModelException {
        final String name = TypeProposalUtils.getTypeQualifiedName(binding);
        if (binding.isWildcardType()) {
            if (binding.isUpperbound()) {
                // replace the wildcard ? with the type parameter name to get "E extends Bound" instead of "? extends Bound"
                //				String contextName= name.replaceFirst("\\?", parameter.getElementName()); //$NON-NLS-1$
                // upper bound - the upper bound is the bound itself
                return binding.getBound().getName();
            }

            // no or upper bound - use the type parameter of the inserted type, as it may be more
            // restrictive (eg. List<?> list= new SerializableList<Serializable>())
            return computeTypeProposal(parameter);
        }

        // not a wildcard but a type or type variable - this is unambigously the right thing to insert
        return name;
    }

    private void appendParameterList(StringBuilder buffer, String[] typeArguments, boolean onlyAppendArguments) {
        if (typeArguments == null || typeArguments.length <= 0)
            return;

        if (!onlyAppendArguments)
            buffer.append(LESS);

        StringBuilder separator = new StringBuilder(3);
        separator.append(COMMA);

        for (int i = 0; i != typeArguments.length; i++) {
            if (i != 0)
                buffer.append(separator);

            buffer.append(typeArguments[i]);
        }

        if (!onlyAppendArguments)
            buffer.append(GREATER);
    }


    private boolean shouldAppendArguments(CompletionProposal proposal,
                                          char trigger) {
        /*
         * No argument list if there were any special triggers (for example a
         * period to qualify an inner type).
         */
        if (trigger != '\0' && trigger != '<' && trigger != LPAREN) {
            return false;
        }

        /*
         * No argument list if the completion is empty (already within the
         * argument list).
         */
        char[] completion = proposal.getCompletion();
        if (completion.length == 0) {
            return false;
        }

        /*
         * No argument list if there already is a generic signature behind the
         * name.
         */
        try {
            IDocument document = new DocumentAdapter(this.compilationUnit.getBuffer());
            IRegion region = document.getLineInformationOfOffset(proposal.getReplaceEnd());
            String line = document.get(region.getOffset(), region.getLength());

            int index = proposal.getReplaceEnd() - region.getOffset();
            while (index != line.length() && Character.isUnicodeIdentifierPart(line.charAt(index))) {
                ++index;
            }

            if (index == line.length()) {
                return true;
            }

            char ch = line.charAt(index);
            return ch != '<';

        } catch (BadLocationException | JavaModelException e) {
            return true;
        }

    }

    private void appendImportProposal(StringBuilder buffer, CompletionProposal proposal, int coreKind) {
        int proposalKind = proposal.getKind();
        String qualifiedTypeName;
        char[] qualifiedType;
        if (proposalKind == CompletionProposal.TYPE_IMPORT) {
            qualifiedType = proposal.getSignature();
            qualifiedTypeName = String.valueOf(Signature.toCharArray(qualifiedType));
        } else if (proposalKind == CompletionProposal.METHOD_IMPORT || proposalKind == CompletionProposal.FIELD_IMPORT) {
            qualifiedType = Signature.getTypeErasure(proposal.getDeclarationSignature());
            qualifiedTypeName = String.valueOf(Signature.toCharArray(qualifiedType));
        } else {
            throw new RuntimeException("Unsupported proposal kind: " + proposalKind);
        }

        /* Add imports if the preference is on. */
        if (importRewrite != null) {
            if (proposalKind == CompletionProposal.TYPE_IMPORT) {
                String simpleType = importRewrite.addImport(qualifiedTypeName, null);
                if (coreKind == CompletionProposal.METHOD_REF) {
                    buffer.append(simpleType);
                    buffer.append(COMMA);
                }
            } else {
                String res = importRewrite.addStaticImport(qualifiedTypeName, String.valueOf(proposal.getName()), proposalKind == CompletionProposal.FIELD_IMPORT, null);
                int dot = res.lastIndexOf('.');
                if (dot != -1) {
                    buffer.append(importRewrite.addImport(res.substring(0, dot), null));
                    buffer.append('.');
                }
            }
            return;
        }

        // Case where we don't have an import rewrite (see allowAddingImports)

        if (compilationUnit != null && TypeProposalUtils.isImplicitImport(Signature.getQualifier(qualifiedTypeName), compilationUnit)) {
            /* No imports for implicit imports. */

            if (proposal.getKind() == CompletionProposal.TYPE_IMPORT && coreKind == CompletionProposal.FIELD_REF) {
                return;
            }
            qualifiedTypeName = String.valueOf(Signature.getSignatureSimpleName(qualifiedType));
        }
        buffer.append(qualifiedTypeName);
        buffer.append('.');
    }

    private ITypeBinding getExpectedTypeForGenericParameters() {
        char[][] chKeys = context.getExpectedTypesKeys();
        if (chKeys == null || chKeys.length == 0) {
            return null;
        }

        String[] keys = new String[chKeys.length];
        Arrays.fill(keys, String.valueOf(chKeys[0]));

        final ASTParser parser = JdtConfiguration.createParser();
        parser.setProject(compilationUnit.getJavaProject());
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);

        final Map<String, IBinding> bindings = new HashMap<>();
        ASTRequestor requestor = new ASTRequestor() {
            @Override
            public void acceptBinding(String bindingKey, IBinding binding) {
                bindings.put(bindingKey, binding);
            }
        };
        parser.createASTs(new ICompilationUnit[0], keys, requestor, null);

        if (bindings.size() > 0) {
            return (ITypeBinding) bindings.get(keys[0]);
        }

        return null;
    }

    private String computeJavaTypeReplacementString(CompletionProposal proposal) {
        String replacement = String.valueOf(proposal.getCompletion());

        /* No import rewriting ever from within the import section. */
        if (isImportCompletion(proposal)) {
            return replacement;
        }

        /*
         * Always use the simple name for non-formal javadoc references to
         * types.
         */
        // TODO fix
        if (proposal.getKind() == CompletionProposal.TYPE_REF
            && context.isInJavadocText()) {
            return SignatureHelper.getSimpleTypeName(proposal);
        }

        String qualifiedTypeName = SignatureHelper.getQualifiedTypeName(proposal);

        // Type in package info must be fully qualified.
        if (compilationUnit != null
            && TypeProposalUtils.isPackageInfo(compilationUnit)) {
            return qualifiedTypeName;
        }

        if (qualifiedTypeName.indexOf('.') == -1 && replacement.length() > 0) {
            // default package - no imports needed
            return qualifiedTypeName;
        }

        /*
         * If the user types in the qualification, don't force import rewriting
         * on him - insert the qualified name.
         */
        String prefix = "";
        try {
            IDocument document = new DocumentAdapter(this.compilationUnit.getBuffer());
            org.eclipse.jface.text.IRegion region = document.getLineInformationOfOffset(proposal.getReplaceEnd());
            prefix = document.get(region.getOffset(), proposal.getReplaceEnd() - region.getOffset()).trim();
        } catch (BadLocationException | JavaModelException ignored) {

        }
        int dotIndex = prefix.lastIndexOf('.');
        // match up to the last dot in order to make higher level matching still
        // work (camel case...)
        if (dotIndex != -1
            && qualifiedTypeName.toLowerCase().startsWith(
                prefix.substring(0, dotIndex + 1).toLowerCase())) {
            return qualifiedTypeName;
        }

        /*
         * The replacement does not contain a qualification (e.g. an inner type
         * qualified by its parent) - use the replacement directly.
         */
        if (replacement.indexOf('.') == -1) {
            if (isInJavadoc()) {
                return SignatureHelper.getSimpleTypeName(proposal); // don't use
            }
            // the
            // braces
            // added for
            // javadoc
            // link
            // proposals
            return replacement;
        }

        /* Add imports if the preference is on. */
        if (importRewrite != null) {
            return importRewrite.addImport(qualifiedTypeName, null);
        }

        // fall back for the case we don't have an import rewrite (see
        // allowAddingImports)

        /* No imports for implicit imports. */
        if (compilationUnit != null
            && TypeProposalUtils.isImplicitImport(
                Signature.getQualifier(qualifiedTypeName),
                compilationUnit)) {
            return Signature.getSimpleName(qualifiedTypeName);
        }


        /* Default: use the fully qualified type name. */
        return qualifiedTypeName;
    }

    private boolean isImportCompletion(CompletionProposal proposal) {
        char[] completion = proposal.getCompletion();
        if (completion.length == 0) {
            return false;
        }

        char last = completion[completion.length - 1];
        /*
         * Proposals end in a semicolon when completing types in normal imports
         * or when completing static members, in a period when completing types
         * in static imports.
         */
        return last == SEMICOLON || last == '.';
    }

    public static String sanitizeCompletion(String replace) {
        return replace == null ? null : replace.replace("$", "\\$");
    }
}

