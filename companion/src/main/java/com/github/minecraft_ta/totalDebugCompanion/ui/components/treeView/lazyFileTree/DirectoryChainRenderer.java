package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Painting and pointer targeting share the same label layout and segment measurements. */
final class DirectoryChainRenderer extends JLabel {
    private LazyFileJTree tree;
    private DirectoryChain chain;
    private int active;
    private int hover;
    private int drop;

    DirectoryChainRenderer() { putClientProperty("html.disable", true); setIconTextGap(5); }

    void configure(LazyFileJTree tree, LazyTreeNode node, boolean selected, Color foreground, Color background, int hover, int drop) {
        this.tree = tree;
        this.chain = (DirectoryChain) node.getUserObject();
        this.active = selected ? node.selectedSegment() : -1;
        this.hover = hover;
        this.drop = drop;
        setText(chain.getPresentation().primary());
        setIcon(chain.getIcon());
        setFont(tree.getFont());
        setForeground(selected ? foreground : ThemeColors.text());
        setOpaque(selected);
        setBackground(background);
    }

    private Rectangle textBounds() {
        var insets = getInsets();
        var view = new Rectangle(insets.left, insets.top, getWidth() - insets.left - insets.right, getHeight() - insets.top - insets.bottom);
        var text = new Rectangle();
        SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), getText(), getIcon(),
                getVerticalAlignment(), getHorizontalAlignment(), getVerticalTextPosition(), getHorizontalTextPosition(),
                view, new Rectangle(), text, getIconTextGap());
        return text;
    }

    Rectangle segmentBounds(int index) {
        Rectangle text = textBounds();
        var parts = DirectoryChain.segments(chain);
        String prefix = String.join(chain.separator(), parts.subList(0, index).stream().map(TreeItem::getName).toList());
        if (index > 0) prefix += chain.separator();
        var metrics = getFontMetrics(getFont());
        return new Rectangle(text.x + metrics.stringWidth(prefix), text.y, metrics.stringWidth(parts.get(index).getName()), text.height);
    }

    int segmentAt(int x) {
        for (int i = 0; i < DirectoryChain.segments(chain).size(); i++) {
            var bounds = segmentBounds(i);
            if (x >= bounds.x && x < bounds.x + bounds.width) return i;
        }
        return -1;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (isOpaque()) { graphics.setColor(getBackground()); graphics.fillRect(0, 0, getWidth(), getHeight()); }
        paintSegmentBackground(graphics, hover, 20);
        paintSegmentBackground(graphics, active, 45);
        paintSegmentBackground(graphics, drop, 70);
        Rectangle text = textBounds();
        var metrics = getFontMetrics(getFont());
        graphics.setColor(ThemeColors.searchMatch());
        for (var match : SpeedSearch.matchingRanges(tree, getText())) {
            int x = text.x + metrics.stringWidth(getText().substring(0, match.start()));
            int width = metrics.stringWidth(getText().substring(match.start(), match.end()));
            graphics.fillRect(x, text.y, width, text.height);
        }
        getUI().paint(graphics, this);
    }

    private void paintSegmentBackground(Graphics graphics, int index, int alpha) {
        if (index < 0 || index >= DirectoryChain.segments(chain).size()) return;
        Rectangle bounds = segmentBounds(index);
        Color color = index == drop ? ThemeColors.accent() : getForeground();
        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        graphics.fillRoundRect(bounds.x - 2, bounds.y - 1, bounds.width + 4, bounds.height + 2, 4, 4);
        if (index == drop) {
            graphics.setColor(color);
            graphics.drawRoundRect(bounds.x - 2, bounds.y - 1, bounds.width + 3, bounds.height + 1, 4, 4);
        }
    }
}
