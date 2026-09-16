package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.SignatureHelp;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import java.awt.*;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

public class SignatureHelpPopup extends BasePopup {
    private final JPanel rows = new JPanel();
    private final JScrollPane scroll = new JScrollPane(rows);
    private SignatureHelp current;

    public SignatureHelpPopup(Window owner) {
        super(owner);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        super.setFont(UIManager.getFont("TextArea.font"));
        rows.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        ((JPanel) getContentPane()).setBorder(DynamicMatteBorder.rule(1, 1, 1, 1));
    }

    public void apply(SignatureHelp help) {
        current = help;
        rows.removeAll();
        for (var signature : help.signatures()) {
            var parameters = Box.createHorizontalBox();
            parameters.setAlignmentX(Component.LEFT_ALIGNMENT);
            parameters.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            if (signature.parameters().isEmpty()) parameters.add(label("No parameters", false));
            int active = signature.varargs() ? Math.min(help.argument(), signature.parameters().size() - 1) : help.argument();
            for (int i = 0; i < signature.parameters().size(); i++) {
                if (i > 0) parameters.add(label(", ", false));
                parameters.add(label(signature.parameters().get(i), i == active));
            }
            rows.add(parameters);
        }
        Dimension content = rows.getPreferredSize();
        int width = Math.min(UIScale.scale(650), content.width);
        int height = Math.min(UIScale.scale(200), content.height);
        scroll.setPreferredSize(new Dimension(width + (content.height > height ? scroll.getVerticalScrollBar().getPreferredSize().width : 0),
                height + (content.width > width ? scroll.getHorizontalScrollBar().getPreferredSize().height : 0)));
        pack();
    }

    private JLabel label(String text, boolean bold) {
        var label = new JLabel(text);
        label.setFont(getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN));
        return label;
    }

    public void showAtCall(JTextComponent editor, int openingOffset) throws BadLocationException {
        var position = editor.modelToView2D(openingOffset);
        if (position != null) show(editor, (int) position.getX(), (int) position.getY() - UIScale.scale(4), Alignment.TOP_CENTER);
    }

    @Override public void setFont(Font font) {
        super.setFont(font);
        if (current != null) apply(current);
    }
}
