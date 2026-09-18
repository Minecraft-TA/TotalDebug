package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import org.eclipse.jdt.core.Flags;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;

public class CodeCompletionPopup extends BaseListPopup<CompletionItem> {
    private String token = "";
    private Runnable dismissListener;

    public CodeCompletionPopup(Window owner) {
        super(owner);
        ((JPanel) getContentPane()).setBorder(PopupChrome.border());
        var list = new JList<CompletionItem>(new DefaultListModel<>());
        list.setCellRenderer(new Renderer());
        setList(list);
    }

    public void setDismissListener(Runnable listener) { dismissListener = listener; }

    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible && dismissListener != null) dismissListener.run();
    }

    public void setToken(String token) { this.token = token; }
    @Override protected Object selectionKey(CompletionItem item) { return item.getIdentity(); }
    @Override protected boolean acceptsTab() { return true; }

    private final class Renderer extends JPanel implements ListCellRenderer<CompletionItem> {
        private final JLabel name = new JLabel(), detail = new JLabel(), type = new JLabel(), symbol = new JLabel();
        private final JLabel cast = new JLabel();
        private final JPanel text = new JPanel(new BorderLayout());

        Renderer() {
            super(new BorderLayout(UIScale.scale(4), 0));
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(4), UIScale.scale(2), UIScale.scale(6)));
            text.setOpaque(false);
            text.add(name, BorderLayout.WEST);
            text.add(detail, BorderLayout.CENTER);
            add(symbol, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            var suffix = new JPanel(new BorderLayout());
            suffix.setOpaque(false);
            suffix.add(cast, BorderLayout.WEST);
            suffix.add(type, BorderLayout.EAST);
            add(suffix, BorderLayout.EAST);
            cast.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, 0));
            type.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, 0));
        }

        @Override public Component getListCellRendererComponent(JList<? extends CompletionItem> list, CompletionItem item,
                                                               int index, boolean selected, boolean focused) {
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            Color muted = selected ? foreground : ThemeColors.secondaryText();
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(foreground);
            detail.setForeground(muted);
            type.setForeground(muted);
            cast.setForeground(muted);
            name.setFont(list.getFont());
            type.setFont(list.getFont());
            cast.setFont(list.getFont());
            cast.setText(item.getCastType().isEmpty() ? "" : "cast to " + item.getCastType());
            cast.setVisible(!item.getCastType().isEmpty());
            detail.setFont(item.getDetail().startsWith(" (=") ? list.getFont().deriveFont(Font.ITALIC) : list.getFont());
            String value = item.getName();
            int match = token.isEmpty() ? -1 : value.toLowerCase(Locale.ROOT).indexOf(token.toLowerCase(Locale.ROOT));
            name.setText(match < 0 ? value : "<html>" + html(value.substring(0, match)) + "<b>"
                    + html(value.substring(match, match + token.length())) + "</b>" + html(value.substring(match + token.length())));
            detail.setText(item.getDetail());
            type.setText(item.getType());
            for (var label : new JLabel[]{type, cast}) {
                label.setPreferredSize(null);
                Dimension size = label.getPreferredSize();
                size.width = Math.min(size.width, UIScale.scale(200));
                label.setPreferredSize(size);
            }
            symbol.setIcon(symbol(item));
            getAccessibleContext().setAccessibleName(item.getLabel());
            return this;
        }

        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.width = Math.min(size.width, UIScale.scale(600));
            return size;
        }
    }

    private static Icon symbol(CompletionItem item) {
        Icon base = switch (item.getKind()) {
            case FIELD, CONSTANT, ENUM_MEMBER -> Icons.FIELD;
            case METHOD -> Icons.JAVA_METHOD;
            case CONSTRUCTOR -> Icons.JAVA_CONSTRUCTOR;
            case VARIABLE -> Icons.JAVA_VARIABLE;
            case SNIPPET -> Icons.TEMPLATE;
            case CLASS -> Icons.JAVA_CLASS;
            case INTERFACE -> Icons.JAVA_INTERFACE;
            case ENUM -> Icons.JAVA_ENUM;
            default -> null;
        };
        int flags = item.getModifiers();
        boolean member = switch (item.getKind()) {
            case FIELD, CONSTANT, ENUM_MEMBER, METHOD, CONSTRUCTOR, CLASS, INTERFACE, ENUM -> true;
            default -> false;
        };
        Icon visibility = !member ? null : Flags.isPrivate(flags) ? Icons.ACCESS_PRIVATE
                : Flags.isProtected(flags) ? Icons.ACCESS_PROTECTED : !Flags.isPublic(flags) ? Icons.ACCESS_LOCAL : null;
        return new Icon() {
            @Override public int getIconWidth() { return UIScale.scale(26); }
            @Override public int getIconHeight() { return UIScale.scale(16); }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                if (base != null) base.paintIcon(c, g, x, y);
                if (Flags.isFinal(flags)) Icons.FINAL_MARK.paintIcon(c, g, x, y);
                if (Flags.isStatic(flags)) Icons.STATIC_MARK.paintIcon(c, g, x, y);
                // Trim the visibility asset's transparent margins, keeping a separate aligned slot.
                if (visibility != null) visibility.paintIcon(c, g, x + UIScale.scale(13), y);
            }
        };
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
