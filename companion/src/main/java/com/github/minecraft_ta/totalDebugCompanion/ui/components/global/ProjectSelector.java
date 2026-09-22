package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.formdev.flatlaf.util.SystemFileChooser;

import com.github.minecraft_ta.totalDebugCompanion.ui.views.PrismInstancePicker;
import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import java.io.File;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import java.util.Objects;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectControls;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.PrismInstances;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectDirectories;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Compact project navigation. Instance discovery happens outside the EDT. */
public final class ProjectSelector extends JMenu {
    private boolean disposed;
    private PrismInstancePicker prismPicker;
    private final ProjectControls projects;
    private final NotificationCenter notifications;

    public ProjectSelector(ProjectControls projects, NotificationCenter notifications) {
        this.notifications = notifications;
        this.projects = Objects.requireNonNull(projects);
        setMargin(new Insets(4, 8, 4, 8));
        setIcon(Icons.DOWN_ARROW);
        setHorizontalTextPosition(SwingConstants.LEFT);
        setIconTextGap(4);
        addMenuListener(new MenuListener() {
            public void menuSelected(MenuEvent event) { loadMenu(); }
            public void menuDeselected(MenuEvent event) { }
            public void menuCanceled(MenuEvent event) { }
        });
        refresh();
    }

    public void refresh() {
        if (disposed) return;
        if (isPopupMenuVisible()) setPopupMenuVisible(false);
        CompanionProfile current = this.projects.currentProject();
        String name = current == null ? "Open project" : this.projects.projects().stream()
                .filter(project -> project.profile().equals(current)).map(ProjectRegistry.Project::name)
                .findFirst().orElseGet(() -> ProjectRegistry.defaultName(current));
        setText(shorten(name, 34));
        setToolTipText(current == null ? "Open a Minecraft instance" : name + " — " + current.workspaceDirectory());
        setEnabled(!this.projects.isSwitching());
    }

    public void dispose() {
        disposed = true;
        if (prismPicker != null) prismPicker.dispose();
        removeAll();
        setEnabled(false);
    }

    private void loadMenu() {
        removeAll();
        action("Open…", Icons.FOLDER, this::chooseDirectory);
        action("Prism instances…", Icons.PRISM, this::choosePrism);
        var known = projects.projects();
        var current = projects.currentProject();
        var selected = known.stream().filter(project -> project.profile().equals(current)).findFirst();
        if (selected.isPresent()) {
            addSeparator();
            section("Current project");
            addProject(selected.get());
        }
        var recent = known.stream().filter(project -> !project.profile().equals(current)).toList();
        if (!recent.isEmpty()) {
            addSeparator();
            section("Projects");
            recent.forEach(this::addProject);
        }
    }

    private void section(String text) {
        var label = new JLabel(text);
        label.setFont(getFont());
        label.setForeground(ThemeColors.secondaryText());
        var section = new JMenuItem();
        section.setEnabled(false);
        section.setLayout(new BorderLayout());
        section.setBorder(BorderFactory.createEmptyBorder(7, 12, 3, 12));
        section.setPreferredSize(new Dimension(UIScale.scale(360), UIScale.scale(28)));
        section.add(label, BorderLayout.CENTER);
        add(section);
    }

    private void addProject(ProjectRegistry.Project project) {
        var item = new ProjectItem(project.name(), shortenPath(project.profile().workspaceDirectory()));
        item.setToolTipText(project.profile().workspaceDirectory().toString());
        item.addActionListener(event -> open(project.profile()));
        var options = new JPopupMenu();
        options.add("Rename…").addActionListener(event -> {
            String name = JOptionPane.showInputDialog(getTopLevelAncestor(), "Project name", project.name());
            if (name != null && !name.isBlank()) finish(projects.renameProject(project.profile().id(), name));
        });
        if (project.nameOverride() != null) {
            options.add("Reset to automatic name").addActionListener(event -> finish(projects.renameProject(project.profile().id(), null)));
        }
        if (!project.profile().equals(projects.currentProject())) {
            options.add("Remove from projects").addActionListener(event -> finish(projects.forgetProject(project.profile().id())));
        }
        item.setComponentPopupMenu(options);
        add(item);
    }

    private void action(String title, Icon icon, Runnable action) {
        var item = new JMenuItem(title, icon);
        item.addActionListener(event -> action.run());
        add(item);
    }

    private final class ProjectItem extends JMenuItem {
        private final PrimarySecondaryLabel label = new PrimarySecondaryLabel(true);
        private final PrimarySecondaryText text;

        ProjectItem(String name, String path) {
            this.text = new PrimarySecondaryText(name, path);
            label.configure(text, null, getFont(), false, null);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            setPreferredSize(new Dimension(UIScale.scale(360), UIScale.scale(48)));
            add(label, BorderLayout.CENTER);
            getAccessibleContext().setAccessibleName(name);
        }

        @Override protected void paintComponent(Graphics graphics) {
            label.configure(text, null, getFont(), isArmed(), UIManager.getColor("MenuItem.selectionForeground"));
            super.paintComponent(graphics);
        }

        @Override protected void processMouseEvent(MouseEvent event) {
            if (SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger()) {
                if (event.isPopupTrigger()) {
                    Point location = SwingUtilities.convertPoint(this, event.getPoint(), ProjectSelector.this);
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    getComponentPopupMenu().show(ProjectSelector.this, location.x, location.y);
                }
                return;
            }
            super.processMouseEvent(event);
        }
    }

    private void chooseDirectory() {
        var chooser = new SystemFileChooser();
        chooser.setDialogTitle("Open Minecraft instance");
        chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(getTopLevelAncestor()) == SystemFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            openDirectory(selected);
        }
    }

    private void choosePrism() {
        setPopupMenuVisible(false);
        if (prismPicker != null && prismPicker.isDisplayable()) {
            prismPicker.toFront();
            return;
        }
        prismPicker = new PrismInstancePicker(SwingUtilities.getWindowAncestor(this), PrismInstances.home(), projects.currentProject(),
                profile -> openDirectory(profile.workspaceDirectory()));
        prismPicker.setVisible(true);
    }
    private void openDirectory(Path selected) {
        finish(CompletableFuture.supplyAsync(() -> ProjectDirectories.resolve(selected))
                .thenCompose(profile -> projects.openProject(profile, null)));
    }
    private void open(CompanionProfile profile) { finish(this.projects.openProject(profile, null)); }

    private void finish(CompletableFuture<Void> operation) {
        setEnabled(false);
        operation.whenComplete((ignored, failure) -> UIUtils.onEdt(() -> {
            if (disposed) return;
            refresh();
            if (failure != null) showFailure(failure);
        }));
    }

    private void showFailure(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        notifications.publish(Severity.ERROR, "Project operation failed", failure.toString(), Source.application("Projects"));
    }

    private static String shortenPath(Path path) {
        Path prism = PrismInstances.home().resolve("instances");
        if (path.startsWith(prism)) return "Prism / " + prism.relativize(path);
        String value = path.toString();
        String home = System.getProperty("user.home");
        if (value.startsWith(home + File.separator)) value = "~" + value.substring(home.length());
        return value;
    }
    private static String shorten(String value, int limit) { return value.length() > limit ? value.substring(0, limit - 1) + "…" : value; }
}
