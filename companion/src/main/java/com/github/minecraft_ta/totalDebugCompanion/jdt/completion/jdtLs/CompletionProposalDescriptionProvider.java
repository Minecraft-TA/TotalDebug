/*******************************************************************************
 * Copyright (c) 2005, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Pivotal Software, Inc. - adopted for Flux
 *     Red Hat - adopted for JDT Server
 *******************************************************************************/
package com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs;

import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import org.eclipse.core.runtime.Assert;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.codeassist.CompletionEngine;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnSingleNameReference;

public class CompletionProposalDescriptionProvider {

    private static final String RETURN_TYPE_SEPARATOR = " : ";
    private static final String PACKAGE_NAME_SEPARATOR = " - ";
    private static final String VAR_TYPE_SEPARATOR = RETURN_TYPE_SEPARATOR;
    private static final String OBJECT = "java.lang.Object";

    private final CompletionContext context;

    public CompletionProposalDescriptionProvider(CompletionContext context) {
        this.context = context;
    }

    public StringBuilder createMethodProposalDescription(CompletionProposal proposal) {
        int kind = proposal.getKind();
        StringBuilder description = new StringBuilder();
        if (kind == CompletionProposal.METHOD_REF || kind == CompletionProposal.METHOD_NAME_REFERENCE || kind == CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER ||
            kind == CompletionProposal.POTENTIAL_METHOD_DECLARATION ||
            kind == CompletionProposal.CONSTRUCTOR_INVOCATION) {// method name

            description.append(proposal.getName());

            // parameters
            description.append('(');
            appendUnboundedParameterList(description, proposal);
            description.append(')');

            // return type
            if (!proposal.isConstructor()) {
                char[] returnType = createTypeDisplayName(SignatureHelper.getUpperBound(Signature.getReturnType(SignatureHelper.fix83600(proposal.getSignature()))));
                description.append(RETURN_TYPE_SEPARATOR);
                description.append(returnType);
            }
        }
        return description;
    }

    private void appendUnboundedParameterList(StringBuilder buffer, CompletionProposal methodProposal) {
        char[] signature = SignatureHelper.fix83600(methodProposal.getSignature());
        char[][] parameterNames;
//        try {
//            parameterNames = methodProposal.findParameterNames(null);
//        } catch (Exception e) {
//            e.printStackTrace();
        parameterNames = CompletionEngine.createDefaultParameterNames(Signature.getParameterCount(signature));
        methodProposal.setParameterNames(parameterNames);
//        }

        char[][] parameterTypes = Signature.getParameterTypes(signature);

        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = createTypeDisplayName(SignatureHelper.getLowerBound(parameterTypes[i]));
        }

        if (Flags.isVarargs(methodProposal.getFlags())) {
            int index = parameterTypes.length - 1;
            parameterTypes[index] = convertToVararg(parameterTypes[index]);
        }

        appendParameterSignature(buffer, parameterTypes, parameterNames);
    }

    private char[] convertToVararg(char[] typeName) {
        if (typeName == null)
            return null;

        int len = typeName.length;
        if (len < 2) {
            return typeName;
        }

        if (typeName[len - 1] != ']')
            return typeName;
        if (typeName[len - 2] != '[')
            return typeName;

        char[] vararg = new char[len + 1];
        System.arraycopy(typeName, 0, vararg, 0, len - 2);
        vararg[len - 2] = '.';
        vararg[len - 1] = '.';
        vararg[len] = '.';
        return vararg;
    }

    private char[] createTypeDisplayName(char[] typeSignature) throws IllegalArgumentException {
        return Signature.getSimpleName(Signature.toCharArray(typeSignature));
    }

    private void appendParameterSignature(StringBuilder buffer, char[][] parameterTypes, char[][] parameterNames) {
        if (parameterTypes == null)
            return;

        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                buffer.append(',');
                buffer.append(' ');
            }
            buffer.append(parameterTypes[i]);
            if (parameterNames != null && parameterNames[i] != null) {
                buffer.append(' ');
                buffer.append(parameterNames[i]);
            }
        }
    }

    private void createMethodProposalLabel(CompletionProposal methodProposal, CompletionItem item) {
        StringBuilder description = this.createMethodProposalDescription(methodProposal);
        item.setLabel(description.toString());
//        item.setReplacement(String.valueOf(methodProposal.getName()));

        // declaring type
        StringBuilder typeInfo = new StringBuilder();
        String declaringType = extractDeclaringTypeFQN(methodProposal);

        if (methodProposal.getRequiredProposals() != null) {
            String qualifier = Signature.getQualifier(declaringType);
            if (!qualifier.isEmpty()) {
                typeInfo.append(qualifier);
                typeInfo.append('.');
            }
        }

        declaringType = Signature.getSimpleName(declaringType);
        typeInfo.append(declaringType);
        StringBuilder detail = new StringBuilder();
        if (!typeInfo.isEmpty()) {
            detail.append(typeInfo);
            detail.append('.');
        }
        detail.append(description);

//        item.setDetail(detail.toString());
    }

    private String extractDeclaringTypeFQN(CompletionProposal methodProposal) {
        char[] declaringTypeSignature = methodProposal.getDeclarationSignature();
        if (declaringTypeSignature == null) {
            return OBJECT;
        }
        return SignatureHelper.stripSignatureToFQN(String.valueOf(declaringTypeSignature));
    }

    private void createTypeProposalLabel(CompletionProposal typeProposal, CompletionItem item) {
        char[] signature;
        if (context != null && context.isInJavadoc()) {
            signature = Signature.getTypeErasure(typeProposal.getSignature());
        } else {
            signature = typeProposal.getSignature();
        }
        char[] fullName = Signature.toCharArray(signature);
        createTypeProposalLabel(fullName, item);
    }

    private void createTypeProposalLabel(char[] fullName, CompletionItem item) {
        int qIndex = findSimpleNameStart(fullName);

        String name = new String(fullName, qIndex, fullName.length - qIndex);
        StringBuilder nameBuffer = new StringBuilder();
        nameBuffer.append(name);
        if (qIndex > 0) {
            nameBuffer.append(PACKAGE_NAME_SEPARATOR);
            nameBuffer.append(new String(fullName, 0, qIndex - 1));
        }

//        item.setReplacement(name);
        item.setLabel(nameBuffer.toString());

//        item.setDetail(new String(fullName));
    }

    private int findSimpleNameStart(char[] array) {
        int lastDot = 0;
        for (int i = 0, len = array.length; i < len; i++) {
            char ch = array[i];
            if (ch == '<') {
                return lastDot;
            } else if (ch == '.') {
                lastDot = i + 1;
            }
        }
        return lastDot;
    }

    private void createSimpleLabelWithType(CompletionProposal proposal, CompletionItem item) {
        StringBuilder nameBuffer = new StringBuilder();
        nameBuffer.append(proposal.getCompletion());
//        item.setReplacement(nameBuffer.toString());
        char[] typeName = Signature.getSignatureSimpleName(proposal.getSignature());
        if (typeName.length > 0) {
            nameBuffer.append(VAR_TYPE_SEPARATOR);
            nameBuffer.append(typeName);
        }
        item.setLabel(nameBuffer.toString());
    }

    private boolean isThisPrefix(char[] string) {
        if (string == null || string.length < 5) {
            return false;
        }
        return string[0] == 't' && string[1] == 'h' && string[2] == 'i' && string[3] == 's' && string[4] == '.';
    }

    private void createLabelWithTypeAndDeclaration(CompletionProposal proposal, CompletionItem item) {
        char[] name = proposal.getCompletion();
        if (!isThisPrefix(name)) {
            name = proposal.getName();
        }
        StringBuilder buf = new StringBuilder();

        buf.append(name);
//        item.setReplacement(buf.toString());
        char[] typeName = Signature.getSignatureSimpleName(proposal.getSignature());
        if (typeName.length > 0) {
            buf.append(VAR_TYPE_SEPARATOR);
            buf.append(typeName);
        }
        item.setLabel(buf.toString());

        char[] declaration = proposal.getDeclarationSignature();
        StringBuilder detailBuf = new StringBuilder();
        if (declaration != null) {
            declaration = Signature.getSignatureSimpleName(declaration);
            if (declaration.length > 0) {
                if (proposal.getRequiredProposals() != null) {
                    String declaringType = extractDeclaringTypeFQN(proposal);
                    String qualifier = Signature.getQualifier(declaringType);
                    if (!qualifier.isEmpty()) {
                        detailBuf.append(qualifier);
                        detailBuf.append('.');
                    }
                }
                detailBuf.append(declaration);
            }
        }
        if (!detailBuf.isEmpty()) {
            detailBuf.append('.');
        }
        detailBuf.append(buf);

//        item.setDetail(detailBuf.toString());
    }

    private void createPackageProposalLabel(CompletionProposal proposal, CompletionItem item) {
        Assert.isTrue(proposal.getKind() == CompletionProposal.PACKAGE_REF);
        item.setLabel(String.valueOf(proposal.getDeclarationSignature()));
        String detail = (proposal.getKind() == CompletionProposal.PACKAGE_REF ? "(package) " : "(module) ") +
                        String.valueOf(proposal.getDeclarationSignature());

//        item.setDetail(detail.toString());
    }

    private StringBuilder createSimpleLabel(CompletionProposal proposal) {
        StringBuilder buf = new StringBuilder();
        buf.append(String.valueOf(proposal.getCompletion()));
        return buf;
    }

    private void createAnonymousTypeLabel(CompletionProposal proposal, CompletionItem item) {
        char[] declaringTypeSignature = proposal.getDeclarationSignature();
        declaringTypeSignature = Signature.getTypeErasure(declaringTypeSignature);
        String name = new String(Signature.getSignatureSimpleName(declaringTypeSignature));
//        item.setReplacement(name);
        StringBuilder buf = new StringBuilder();
        buf.append(name);
        buf.append('(');
        appendUnboundedParameterList(buf, proposal);
        buf.append(')');
        buf.append("  ");
        buf.append("Anonymous Inner Type"); //TODO: consider externalization
        item.setLabel(buf.toString());

        if (proposal.getRequiredProposals() != null) {
            char[] signatureQualifier = Signature.getSignatureQualifier(declaringTypeSignature);
            if (signatureQualifier.length > 0) {
//                item.setDetail(String.valueOf(signatureQualifier) + "." + name);
            }
        }
    }

    private void createLabelWithLambdaExpression(CompletionProposal proposal, CompletionItem item) {
        StringBuilder buffer = new StringBuilder();
        buffer.append('(');
        var completionNode = ((InternalCompletionContext) this.context).getCompletionNode();
        if (completionNode instanceof CompletionOnSingleNameReference && this.context.getToken().length != 0) {
            buffer.append(this.context.getToken());
        } else {
            appendUnboundedParameterList(buffer, proposal);
        }

        buffer.append(')');
        buffer.append(" ->");
        char[] returnType = createTypeDisplayName(SignatureHelper.getUpperBound(Signature.getReturnType(SignatureHelper.fix83600(proposal.getSignature()))));
        buffer.append(RETURN_TYPE_SEPARATOR);
        buffer.append(returnType);
        String label = buffer.toString();
        item.setLabel(label);
    }

    public boolean updateDescription(CompletionProposal proposal, CompletionItem item) {
        switch (proposal.getKind()) {
            case CompletionProposal.METHOD_NAME_REFERENCE, CompletionProposal.METHOD_REF, CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER -> {
                if (context != null && context.isInJavadoc()) {
                    return false;
                }
                createMethodProposalLabel(proposal, item);
            }
            case CompletionProposal.TYPE_REF -> createTypeProposalLabel(proposal, item);
            case CompletionProposal.PACKAGE_REF -> createPackageProposalLabel(proposal, item);
            case CompletionProposal.ANNOTATION_ATTRIBUTE_REF, CompletionProposal.FIELD_REF, CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER ->
                    createLabelWithTypeAndDeclaration(proposal, item);
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION ->
                    createSimpleLabelWithType(proposal, item);
            case CompletionProposal.KEYWORD, CompletionProposal.LABEL_REF ->
                    item.setLabel(createSimpleLabel(proposal).toString());
            case CompletionProposal.LAMBDA_EXPRESSION -> createLabelWithLambdaExpression(proposal, item);
            default -> {return false;}
        }

        return true;
    }
}
