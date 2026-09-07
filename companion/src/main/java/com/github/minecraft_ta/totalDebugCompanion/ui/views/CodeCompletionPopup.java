package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;

import javax.swing.*;
import java.awt.*;

public class CodeCompletionPopup extends BaseListPopup<CompletionItem> {

    private final JList<CompletionItem> completionItemList = new JList<>(new DefaultListModel<>());
    {
        completionItemList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var item = (CompletionItem) value;
                var component = super.getListCellRendererComponent(
                        list,
                        item.getLabel(),
                        index,
                        isSelected,
                        cellHasFocus
                );

                setIcon(switch (item.getKind()) {
                    case METHOD -> Icons.JAVA_METHOD;
                    case CLASS -> Icons.JAVA_CLASS;
                    case ENUM -> Icons.JAVA_ENUM;
                    case INTERFACE -> Icons.JAVA_INTERFACE;
                    case CONSTANT -> Icons.JAVA_CONSTANT;
                    case ENUM_MEMBER, FIELD -> Icons.JAVA_PROPERTY;
                    case VARIABLE -> Icons.JAVA_VARIABLE;
                    case CONSTRUCTOR -> Icons.JAVA_CONSTRUCTOR;
                    case KEYWORD, IMPORT, LABEL, TEXT -> null;
                });

                return component;
            }
        });
    }


    public CodeCompletionPopup(Window owner) {
        super(owner);
        setList(this.completionItemList);
    }
}
