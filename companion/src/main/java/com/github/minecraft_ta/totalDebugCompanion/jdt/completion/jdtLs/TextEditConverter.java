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

import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.Range;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.util.SimpleDocument;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.*;

import java.util.ArrayList;
import java.util.List;

public class TextEditConverter extends TextEditVisitor {

    private final TextEdit source;
    protected ICompilationUnit compilationUnit;
    protected List<CustomTextEdit> converted;

    public TextEditConverter(ICompilationUnit unit, TextEdit edit) {
        this.source = edit;
        this.converted = new ArrayList<>();
        if (unit == null) {
            throw new IllegalArgumentException("Compilation unit can not be null");
        }
        this.compilationUnit = unit;
    }

    public List<CustomTextEdit> convert() {
        if (this.source != null)
            this.source.accept(this);

        return converted;
    }

    @Override
    public boolean visit(InsertEdit edit) {
        converted.add(new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), edit.getText()));
        return super.visit(edit);
    }

    @Override
    public boolean visit(CopySourceEdit edit) {
        try {
            if (edit.getTargetEdit() == null)
                return false;

            CustomTextEdit te = new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), "");
            Document doc = new Document(compilationUnit.getSource());
            edit.apply(doc, TextEdit.UPDATE_REGIONS);
            String content = doc.get(edit.getOffset(), edit.getLength());
            if (edit.getSourceModifier() != null)
                content = applySourceModifier(content, edit.getSourceModifier());

            te.setNewText(content);
            converted.add(te);
            return false;
        } catch (JavaModelException | MalformedTreeException | BadLocationException e) {
            e.printStackTrace();
        }
        return super.visit(edit);
    }

    @Override
    public boolean visit(DeleteEdit edit) {
        converted.add(new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), ""));
        return super.visit(edit);
    }

    @Override
    public boolean visit(MultiTextEdit edit) {
        try {
            CustomTextEdit te = new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), "");
            Document doc = new Document(compilationUnit.getSource());
            edit.apply(doc, TextEdit.UPDATE_REGIONS);
            String content = doc.get(edit.getOffset(), edit.getLength());
            te.setNewText(content);
            converted.add(te);
            return false;
        } catch (JavaModelException | MalformedTreeException | BadLocationException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean visit(ReplaceEdit edit) {
        converted.add(new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), edit.getText()));
        return super.visit(edit);
    }

    @Override
    public boolean visit(CopyTargetEdit edit) {
        try {
            if (edit.getSourceEdit() == null)
                return false;

            var te = new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), "");

            Document doc = new Document(compilationUnit.getSource());
            edit.apply(doc, TextEdit.UPDATE_REGIONS);
            String content = doc.get(edit.getSourceEdit().getOffset(), edit.getSourceEdit().getLength());
            if (edit.getSourceEdit().getSourceModifier() != null)
                content = applySourceModifier(content, edit.getSourceEdit().getSourceModifier());

            te.setNewText(content);
            converted.add(te);
            return false;
        } catch (MalformedTreeException | BadLocationException | CoreException e) {
            e.printStackTrace();
        }
        return super.visit(edit);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.eclipse.text.edits.TextEditVisitor#visit(org.eclipse.text.edits.MoveSourceEdit)
     */
    @Override
    public boolean visit(MoveSourceEdit edit) {
        if (edit.getParent() == null || edit.getTargetEdit() == null || !edit.getParent().equals(edit.getTargetEdit().getParent()))
            return super.visit(edit);

        converted.add(new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), ""));
        return false;
    }

    @Override
    public boolean visit(MoveTargetEdit edit) {
        try {
            if (edit.getSourceEdit() != null) {
                var te = new CustomTextEdit(new Range(edit.getOffset(), edit.getLength()), "");

                Document doc = new Document(compilationUnit.getSource());
                edit.apply(doc, TextEdit.UPDATE_REGIONS);
                String content = doc.get(edit.getSourceEdit().getOffset(), edit.getSourceEdit().getLength());
                if (edit.getSourceEdit().getSourceModifier() != null) {
                    content = applySourceModifier(content, edit.getSourceEdit().getSourceModifier());
                }
                te.setNewText(content);
                converted.add(te);
                return false;
            }
        } catch (MalformedTreeException | BadLocationException | CoreException e) {
            e.printStackTrace();
        }
        return super.visit(edit);
    }

    private String applySourceModifier(String content, ISourceModifier modifier) {
        if (content == null || content.isBlank() || modifier == null) {
            return content;
        }

        SimpleDocument subDocument = new SimpleDocument(content);
        TextEdit newEdit = new MultiTextEdit(0, subDocument.getLength());
        ReplaceEdit[] replaces = modifier.getModifications(content);
        for (ReplaceEdit replace : replaces) {
            newEdit.addChild(replace);
        }
        try {
            newEdit.apply(subDocument, TextEdit.NONE);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
        return subDocument.get();
    }
}
