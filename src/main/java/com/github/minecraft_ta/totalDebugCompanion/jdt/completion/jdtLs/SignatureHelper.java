/*******************************************************************************
 * Copyright (c) 2000, 2016 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import java.util.Arrays;

public class SignatureHelper {

    private static final String NULL_TYPE_SIGNATURE = "Tnull;";
    private static final char[] NULL_TYPE_SIGNATURE_ARRAY = NULL_TYPE_SIGNATURE.toCharArray();
    private static final String OBJECT_SIGNATURE = "Ljava.lang.Object;";
    private static final char[] OBJECT_SIGNATURE_ARRAY = OBJECT_SIGNATURE.toCharArray();

    private SignatureHelper() {
    }

    public static String stripSignatureToFQN(String signature) throws IllegalArgumentException {
        signature = Signature.getTypeErasure(signature);
        signature = Signature.getElementType(signature);
        var str = Signature.toString(signature);
        if (str.endsWith("."))
            str = str.substring(0, str.length() - 1);
        return str;
    }

    public static String qualifySignature(final String signature, final IType context) {
        if (context == null)
            return signature;

        String qualifier = Signature.getSignatureQualifier(signature);
        if (!qualifier.isEmpty())
            return signature;

        String elementType = Signature.getElementType(signature);
        String erasure = Signature.getTypeErasure(elementType);
        String simpleName = Signature.getSignatureSimpleName(erasure);
        String genericSimpleName = Signature.getSignatureSimpleName(elementType);

        int dim = Signature.getArrayCount(signature);

        try {
            String[][] strings = context.resolveType(simpleName);
            if (strings != null && strings.length > 0)
                qualifier = strings[0][0];
        } catch (JavaModelException e) {
            // ignore - not found
        }

        if (qualifier.isEmpty())
            return signature;

        String qualifiedType = Signature.toQualifiedName(new String[]{qualifier, genericSimpleName});
        String qualifiedSignature = Signature.createTypeSignature(qualifiedType, true);

        return Signature.createArraySignature(qualifiedSignature, dim);
    }

    public static char[] fix83600(char[] signature) {
        if (signature == null || signature.length < 2)
            return signature;

        return Signature.removeCapture(signature);
    }

    public static char[] getUpperBound(char[] signature) {
        if (signature.length < 1)
            return signature;

        if (signature[0] == Signature.C_STAR)
            return OBJECT_SIGNATURE_ARRAY;

        int superIndex = indexOf(signature, Signature.C_SUPER);
        if (superIndex == 0)
            return OBJECT_SIGNATURE_ARRAY;

        if (superIndex != -1) {
            char afterSuper = signature[superIndex + 1];
            if (afterSuper == Signature.C_STAR) {
                char[] type = new char[signature.length - 1];
                System.arraycopy(signature, 0, type, 0, superIndex);
                type[superIndex] = Signature.C_STAR;
                System.arraycopy(signature, superIndex + 2, type, superIndex + 1, signature.length - superIndex - 2);
                return getUpperBound(type);
            }

            if (afterSuper == Signature.C_EXTENDS) {
                int typeEnd = typeEnd(signature, superIndex + 1);
                char[] type = new char[signature.length - (typeEnd - superIndex - 1)];
                System.arraycopy(signature, 0, type, 0, superIndex);
                type[superIndex] = Signature.C_STAR;
                System.arraycopy(signature, typeEnd, type, superIndex + 1, signature.length - typeEnd);
                return getUpperBound(type);
            }

        }

        if (signature[0] == Signature.C_EXTENDS) {
            char[] type = new char[signature.length - 1];
            System.arraycopy(signature, 1, type, 0, signature.length - 1);
            return type;
        }

        return signature;
    }

    public static char[] getLowerBound(char[] signature) {
        if (signature.length < 1)
            return signature;

        if (signature.length == 1 && signature[0] == Signature.C_STAR)
            return signature;

        int superIndex = indexOf(signature, Signature.C_EXTENDS);
        if (superIndex == 0)
            return NULL_TYPE_SIGNATURE_ARRAY;

        if (superIndex != -1) {
            char afterSuper = signature[superIndex + 1];
            if (afterSuper == Signature.C_STAR || afterSuper == Signature.C_EXTENDS)
                // impossible captured type
                return NULL_TYPE_SIGNATURE_ARRAY;
        }

        for (char[] typeArgument : Signature.getTypeArguments(signature)) {
            if (Arrays.equals(typeArgument, NULL_TYPE_SIGNATURE_ARRAY)) {
                return NULL_TYPE_SIGNATURE_ARRAY;
            }
        }

        if (signature[0] == Signature.C_SUPER) {
            char[] type = new char[signature.length - 1];
            System.arraycopy(signature, 1, type, 0, signature.length - 1);
            return type;
        }

        return signature;
    }

    private static int indexOf(char[] signature, char ch) {
        for (int i = 0; i < signature.length; i++) {
            if (signature[i] == ch)
                return i;
        }
        return -1;
    }

    private static int typeEnd(char[] signature, int pos) {
        int depth = 0;
        while (pos < signature.length) {
            switch (signature[pos]) {
                case Signature.C_GENERIC_START:
                    depth++;
                    break;
                case Signature.C_GENERIC_END:
                    if (depth == 0)
                        return pos;
                    depth--;
                    break;
                case Signature.C_SEMICOLON:
                    if (depth == 0)
                        return pos + 1;
                    break;
            }
            pos++;
        }
        return pos + 1;
    }

    public static String getQualifiedTypeName(CompletionProposal proposal) {
        return String.valueOf(Signature.toCharArray(Signature
                .getTypeErasure(proposal.getSignature())));
    }

    public static String getSimpleTypeName(CompletionProposal proposal) {
        return Signature.getSimpleName(getQualifiedTypeName(proposal));
    }
}
