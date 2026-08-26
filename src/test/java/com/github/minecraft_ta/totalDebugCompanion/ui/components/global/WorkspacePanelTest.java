package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeViewHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkspacePanelTest {

    @Test
    void workspaceOwnsEveryStructuralRule() throws Exception {
        AtomicReference<WorkspaceFixture> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileTreeViewHeader header = new FileTreeViewHeader();
            ApplicationStatusBar statusBar = new ApplicationStatusBar();
            assertEquals(0, header.getBorder().getBorderInsets(header).bottom);
            assertEquals(1, statusBar.getBorder().getBorderInsets(statusBar).top);

            WorkspacePanel workspace = new WorkspacePanel(
                    header,
                    new JPanel(),
                    new JPanel(),
                    statusBar
            );
            result.set(new WorkspaceFixture(workspace, header, statusBar));
        });

        WorkspaceFixture fixture = result.get();
        Insets workspaceInsets = fixture.workspace().getBorder().getBorderInsets(fixture.workspace());
        Insets headerInsets = fixture.header().getBorder().getBorderInsets(fixture.header());
        Insets statusInsets = fixture.statusBar().getBorder().getBorderInsets(fixture.statusBar());
        JSplitPane splitPane = find(fixture.workspace(), JSplitPane.class);

        assertEquals(1, workspaceInsets.top);
        assertEquals(1, headerInsets.bottom);
        assertEquals(2, statusInsets.top);
        assertEquals(1, statusInsets.bottom);
        assertNotNull(splitPane);
        assertEquals(1, splitPane.getDividerSize());
    }

    @Test
    void existingWorkspaceRulesFollowThemeChangesAtPaintTime() throws Exception {
        AtomicReference<WorkspacePanel> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
            WorkspacePanel workspace = new WorkspacePanel(
                    new FileTreeViewHeader(),
                    new JPanel(),
                    new JPanel(),
                    new ApplicationStatusBar()
            );
            workspace.setSize(500, 300);
            layoutRecursively(workspace);
            result.set(workspace);
        });

        WorkspacePanel workspace = result.get();
        Color dark = paintTopRule(workspace);

        AtomicReference<Color> light = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.installTheme(CompanionTheme.ISLANDS_LIGHT);
            light.set(paintTopRule(workspace));
        });

        assertEquals(dark, colorFor(CompanionTheme.ISLANDS_DARK));
        assertEquals(light.get(), colorFor(CompanionTheme.ISLANDS_LIGHT));
        assertNotEquals(dark, light.get());
    }

    private static Color colorFor(CompanionTheme theme) {
        ThemeManager.installTheme(theme);
        return ThemeColors.separator();
    }

    private static Color paintTopRule(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        component.paint(graphics);
        graphics.dispose();
        return new Color(image.getRGB(component.getWidth() / 2, 0));
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T result = find(nested, type);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private record WorkspaceFixture(
            WorkspacePanel workspace,
            FileTreeViewHeader header,
            ApplicationStatusBar statusBar
    ) {
    }
}
