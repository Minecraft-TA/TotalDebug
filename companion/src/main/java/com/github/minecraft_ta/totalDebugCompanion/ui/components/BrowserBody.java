package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Objects;

/**
 * The body of a browser (docs/UI_GUIDE.md, View patterns): the filter bar with the filter's own options at its right,
 * a line under it for what failed after it was asked for, and the content, which an empty or failed state replaces in
 * the same place. Typing in the content goes into the filter.
 */
public final class BrowserBody extends JPanel {
    private static final String CONTENT_CARD = "content";
    private static final String MESSAGE_CARD = "message";

    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JPanel options = new JPanel();
    private final JLabel notice = new JLabel();
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());

    /**
     * A body showing {@code content}, such as a table in its scroll pane; typing in {@code view}, the table or list
     * itself, goes into the filter. {@code filterChanged} runs whenever the filter's text changes.
     */
    public BrowserBody(String placeholder, JComponent content, JComponent view, Runnable filterChanged) {
        super(new BorderLayout());
        Objects.requireNonNull(filterChanged, "filterChanged");
        this.filter.putClientProperty("JTextField.placeholderText", placeholder);
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterChanged.run(); }
            @Override public void removeUpdate(DocumentEvent event) { filterChanged.run(); }
            @Override public void changedUpdate(DocumentEvent event) { filterChanged.run(); }
        });
        this.options.setLayout(new BoxLayout(this.options, BoxLayout.X_AXIS));
        // Hidden while empty, so the filter fills the bar.
        this.options.setVisible(false);
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.filter, BorderLayout.CENTER);
        bar.add(this.options, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(UiMetrics.noticePadding());
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        this.cards.add(content, CONTENT_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);
        TypeToFilter.install(view, this.filter);
    }

    /** {@code view} in a scroll pane without a border, as browser content sits under its bar. */
    public static JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    /** Adds an option of the filter at the right of the bar, such as a check box that narrows what is shown. */
    public void addOption(JComponent option) {
        if (this.options.getComponentCount() > 0) this.options.add(Box.createHorizontalStrut(10));
        this.options.add(option);
        this.options.setVisible(true);
    }

    public FlatIconTextField filter() {
        return this.filter;
    }

    /** The filter's text without surrounding spaces. */
    public String query() {
        return this.filter.getText().strip();
    }

    /** Shows the content. */
    public void showContent() {
        ((CardLayout) this.cards.getLayout()).show(this.cards, CONTENT_CARD);
    }

    /** Shows {@code text} in place of the content: why there is nothing, or why it could not be read. */
    public void showMessage(String text) {
        this.message.setText(text);
        ((CardLayout) this.cards.getLayout()).show(this.cards, MESSAGE_CARD);
    }

    /** Shows {@code text} in the line under the bar, or hides the line for an empty text. */
    public void showNotice(String text) {
        this.notice.setText(text);
        this.notice.setVisible(!text.isEmpty());
    }
}
