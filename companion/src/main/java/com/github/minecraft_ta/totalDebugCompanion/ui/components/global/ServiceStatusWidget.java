package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/** Compact status-bar widget backed entirely by a published service status. */
final class ServiceStatusWidget extends JButton {
    private final String serviceName;
    private ServiceStatus status;

    ServiceStatusWidget(String serviceName, ServiceStatus initialStatus) {
        if (Objects.requireNonNull(serviceName, "serviceName").isBlank()) {
            throw new IllegalArgumentException("Service name must not be blank");
        }
        this.serviceName = serviceName;
        setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusable(false);
        setRolloverEnabled(true);
        setIcon(new StatusDotIcon());
        setIconTextGap(5);
        applyStatus(initialStatus);
        addActionListener(event -> showStatusPopup());
    }

    void setStatus(ServiceStatus status) {
        UIUtils.onEdt(() -> applyStatus(status));
    }

    private void applyStatus(ServiceStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        setText(this.serviceName + ": " + status.summary());
        setToolTipText("Show " + this.serviceName + " status");
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (getModel().isRollover()) {
            Graphics hoverGraphics = graphics.create();
            hoverGraphics.setColor(ThemeColors.hoverBackground());
            hoverGraphics.fillRect(0, 0, getWidth(), getHeight());
            hoverGraphics.dispose();
        }
        super.paintComponent(graphics);
    }

    private void showStatusPopup() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem heading = new JMenuItem(this.serviceName);
        heading.setEnabled(false);
        popup.add(heading);
        JMenuItem description = new JMenuItem(this.status.detail());
        description.setEnabled(false);
        popup.add(description);
        popup.show(
                this,
                Math.min(0, getWidth() - popup.getPreferredSize().width),
                -popup.getPreferredSize().height
        );
    }

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
