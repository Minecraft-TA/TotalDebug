package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import javax.swing.Icon;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Tab titles with a count, drawn like tree rows: the name, then the count in secondary text (docs/UI_GUIDE.md). */
public final class TabTitles {
    private TabTitles() {
    }

    /**
     * Shows the tab of {@code content} with its count, or hides it while it has nothing to show (docs/UI_GUIDE.md).
     * {@code order} holds the content of every tab in display order, so a tab comes back in its place; the first tab
     * always stays.
     */
    public static void setShown(JTabbedPane tabs, List<? extends Component> order, Component content, String title, Icon icon,
                                int count) {
        int index = tabs.indexOfComponent(content);
        if (count == 0) {
            if (index > 0) tabs.removeTabAt(index);
            return;
        }
        if (index < 0) {
            index = 0;
            for (Component earlier : order) {
                if (earlier == content) break;
                if (tabs.indexOfComponent(earlier) >= 0) index++;
            }
            tabs.insertTab(title, icon, content, null, index);
        }
        setCounted(tabs, index, title, count);
    }

    /** Titles the tab at {@code index} with {@code title} and {@code count}, keeping the tab's icon. */
    public static void setCounted(JTabbedPane tabs, int index, String title, int count) {
        String counted = NumberFormat.getIntegerInstance(Locale.ROOT).format(count);
        // The plain title stays the tab's accessible name; the component draws it.
        tabs.setTitleAt(index, title + " " + counted);
        Component shown = tabs.getTabComponentAt(index);
        PrimarySecondaryLabel label = shown instanceof PrimarySecondaryLabel existing ? existing : new PrimarySecondaryLabel();
        label.configure(new PrimarySecondaryText(title, counted), tabs.getIconAt(index), tabs.getFont(), false, null);
        if (shown != label) tabs.setTabComponentAt(index, label);
    }
}
