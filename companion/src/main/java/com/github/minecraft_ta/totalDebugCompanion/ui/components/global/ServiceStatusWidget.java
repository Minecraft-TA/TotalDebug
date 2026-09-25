package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.HtmlText;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/** Compact status-bar widget backed entirely by a published service status. */
final class ServiceStatusWidget extends JButton implements AutoCloseable {
    private final JPopupMenu popup = new JPopupMenu();
    private boolean closed;
    private boolean popupAvailable = true;
    private final String serviceName;
    private ServiceStatus status;

    ServiceStatusWidget(String serviceName, ServiceStatus initialStatus) {
        if (Objects.requireNonNull(serviceName, "serviceName").isBlank()) {
            throw new IllegalArgumentException("Service name must not be blank");
        }
        this.serviceName = serviceName;
        FlatIconButton.configure(this);
        setMargin(UiMetrics.statusWidgetMargin());
        setRolloverEnabled(true);
        setIcon(new StatusDotIcon());
        setIconTextGap(5);
        applyStatus(initialStatus);
        addActionListener(event -> showStatusPopup());
    }

    ServiceStatus status() { return status; }

    void setStatus(ServiceStatus status) {
        UIUtils.onEdt(() -> applyStatus(status));
    }

    private void applyStatus(ServiceStatus status) {
        if (closed) return;
        this.status = Objects.requireNonNull(status, "status");
        setText(HtmlText.nameAndValue(this.serviceName, status.summary()));
        getAccessibleContext().setAccessibleName(this.serviceName + " " + status.summary());
        setToolTipText(popupAvailable ? "Show " + this.serviceName + " Controls" : Tooltip.of("").text(status.detail()).html());
        repaint();
    }

    private void showStatusPopup() {
        if (popupAvailable) PopupElements.showAbove(popup, this);
    }

    void setPopupAvailable(boolean available) {
        popupAvailable = available;
        setEnabled(available);
        if (!available) popup.setVisible(false);
        applyStatus(status);
    }

    void setContent(JComponent content) { PopupElements.content(popup, content); }

    void refreshPopup() { if (popup.isVisible()) PopupElements.showAbove(popup, this); }

    void applyTheme() {
        SwingUtilities.updateComponentTreeUI(popup);
        applyStatus(status);
    }

    @Override public void close() { closed = true; popup.setVisible(false); }

    private final class StatusDotIcon implements Icon {
        private static final int SIZE = 7;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D dotGraphics = (Graphics2D) graphics.create();
            dotGraphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            dotGraphics.setColor(stateColor(status.state()));
            dotGraphics.fillOval(x, y, SIZE, SIZE);
            dotGraphics.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        private static Color stateColor(ServiceStatus.State state) {
            return switch (state) {
                case INACTIVE -> ThemeColors.mutedText();
                case PENDING -> ThemeColors.warning();
                case AVAILABLE -> ThemeColors.success();
                case FAILED -> ThemeColors.error();
            };
        }
    }
}
