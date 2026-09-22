package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.formdev.flatlaf.util.SystemFileChooser;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.FileSystemDirectoryItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryChain;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.FileSystemFileItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FileNamePopup;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.Desktop;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.awt.event.ActionEvent;
import java.util.function.UnaryOperator;

/** Coordinates file commands with open drafts. All entry points and completion handling run on the EDT. */
public final class ScriptFileActions {
    private final Window owner;
    private final EditorTabs tabs;
    private final FileTreeView tree;
    private final Supplier<EditorContext> context;
    private boolean busy;
    private record Change(Path from, Path to) { }
    private record Drag(ProjectScope project, List<Path> paths) { }
    record FileSelection(Path path, boolean directory) { }
    private static final DataFlavor DRAG = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Drag.class.getName(), "Scripts files");
    @FunctionalInterface private interface Work { void run(ScriptFiles files, List<Change> changes) throws IOException; }

    public ScriptFileActions(Window owner, EditorTabs tabs, FileTreeView tree, Supplier<EditorContext> context) {
        this.owner = owner;
        this.tabs = tabs;
        this.tree = tree;
        this.context = context;
        var component = tree.tree();
        component.getInputMap().put(KeyStroke.getKeyStroke("F2"), "renameFile");
        component.getActionMap().put("renameFile", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { var files = selectedFiles(); if (files.size() == 1) rename(files.getFirst()); }
        });
        component.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "deleteFiles");
        component.getActionMap().put("deleteFiles", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { confirmDelete(selectedFiles()); }
        });
        component.setDragEnabled(true);
        component.setDropMode(DropMode.ON);
        component.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent source) { return MOVE; }
            @Override protected Transferable createTransferable(JComponent source) {
                List<Path> paths = selected();
                if (busy || paths.isEmpty() || paths.stream().anyMatch(p -> p.equals(context.get().project().scriptFiles().root()))) return null;
                var drag = new Drag(context.get().project(), paths);
                return new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DRAG}; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return DRAG.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return drag; }
                };
            }
            @Override public boolean canImport(TransferSupport support) {
                if (busy || !support.isDrop() || !support.isDataFlavorSupported(DRAG)) return false;
                try {
                    var drag = (Drag) support.getTransferable().getTransferData(DRAG);
                    TreeItem target = tree.tree().itemAt(support.getDropLocation().getDropPoint());
                    Path destination = path(target);
                    boolean accepted = drag.project == context.get().project() && managed(destination)
                            && target != null && target.isDirectory()
                            && drag.paths.stream().noneMatch(p -> destination.startsWith(p) || destination.equals(p.getParent()));
                    if (accepted) support.setDropAction(MOVE);
                    return accepted;
                } catch (Exception failure) { return false; }
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    var drag = (Drag) support.getTransferable().getTransferData(DRAG);
                    report(move(drag.paths, path(tree.tree().itemAt(support.getDropLocation().getDropPoint()))));
                    return true;
                } catch (Exception failure) { report(CompletableFuture.failedFuture(failure)); return false; }
            }
        });
    }

    public boolean isBusy() { return busy; }
    private String unavailableReason() {
        if (busy) return "Another file operation is in progress.";
        var project = context.get().project();
        return project == null || !project.isActive() ? "Open a project first." : null;
    }
    private boolean canStartCommand() {
        String reason = unavailableReason();
        if (reason == null) return true;
        report(CompletableFuture.failedFuture(new IOException(reason)));
        return false;
    }
    public boolean managed(Path path) {
        var scope = context.get().project();
        return scope != null && path != null && scope.scriptFiles().contains(path);
    }
    private List<Path> selected() {
        return selectedFiles().stream().map(FileSelection::path).toList();
    }
    private List<FileSelection> selectedFiles() {
        var selection = tree.tree().getSelectionPaths();
        if (selection == null) return List.of();
        var result = new ArrayList<FileSelection>();
        for (var selected : selection) {
            if (!(selected.getLastPathComponent() instanceof LazyTreeNode node)) return List.of();
            TreeItem item = node.selectedItem();
            Path path = path(item);
            if (!managed(path)) return List.of();
            result.add(new FileSelection(path.toAbsolutePath().normalize(), item.isDirectory()));
        }
        var roots = topLevel(result.stream().map(FileSelection::path).toList());
        return result.stream().filter(file -> roots.contains(file.path)).distinct().toList();
    }
    static Path path(TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof LazyTreeNode node)) return null;
        return path(node.selectedItem());
    }
    private static Path path(TreeItem item) {
        return item instanceof FileSystemFileItem file ? file.getPath()
                : item instanceof FileSystemDirectoryItem folder ? folder.getPath() : null;
    }
    private static List<Path> topLevel(List<Path> paths) {
        var normalized = paths.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
        return normalized.stream().filter(p -> normalized.stream().noneMatch(parent -> !parent.equals(p) && p.startsWith(parent))).toList();
    }
    public void addMenu(JPopupMenu menu, Path target, boolean folder) {
        if (!managed(target)) return;
        boolean root = target.equals(context.get().project().scriptFiles().root());
        var targetFile = new FileSelection(target, folder);
        List<FileSelection> selection = selectedFiles();
        List<FileSelection> targets = selection.stream().anyMatch(file -> file.path.equals(target)) ? selection : List.of(targetFile);
        if (folder && targets.size() == 1) {
            item(menu, "New Script", Icons.SCRIPT_FILE, () -> create(target, false));
            item(menu, "New Folder", Icons.NEW_FOLDER, () -> create(target, true));
        }
        if (!root) {
            if (targets.size() == 1) item(menu, "Rename", Icons.RENAME, "F2", () -> rename(targetFile));
            item(menu, "Move to...", Icons.MOVE_TO_FOLDER, () -> chooseDestination(targets.stream().map(FileSelection::path).toList()));
            if (targets.size() == 1 && !folder && target.toString().endsWith(ScriptFiles.EXTENSION))
                item(menu, "Duplicate script", Icons.COPY, () -> duplicate(target));
        }
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy path", target.toString())));
        if (!root) item(menu, targets.size() > 1 ? "Delete" : folder ? "Delete folder" : "Delete file", Icons.DELETE,
                "DELETE", () -> confirmDelete(targets));
    }
    private void item(JPopupMenu menu, String text, Icon icon, Runnable action) {
        item(menu, text, icon, null, action);
    }
    private void item(JPopupMenu menu, String text, Icon icon, String shortcut, Runnable handler) {
        Action action = ContextMenus.action(text, icon, shortcut, handler);
        action.setEnabled(!busy);
        menu.add(action);
    }

    public void saveAsScript(String text) {
        if (!canStartCommand()) return;
        Path parent = creationParent();
        showName("Save as Script", "Script", Icons.SCRIPT_FILE, "", true, name -> create(parent, name, false, text));
    }
    private Path creationParent() {
        var selected = selectedFiles();
        Path root = context.get().project().scriptFiles().root();
        if (selected.size() != 1) return root;
        var file = selected.getFirst();
        return file.directory ? file.path : file.path.getParent();
    }
    public void newFolder() { if (canStartCommand()) create(creationParent(), true); }
    public void newScript() {
        if (!canStartCommand()) return;
        create(creationParent(), false);
    }
    public void create(Path parent, boolean folder) {
        if (!canStartCommand()) return;
        var popup = creationPopup(parent, folder);
        popup.setLocationRelativeTo(owner); popup.setVisible(true);
    }
    public FileNamePopup creationPopup(Path parent, boolean folder) {
        return namePopup(folder ? "New Folder" : "New Script", folder ? "Folder" : "Script", folder ? Icons.FOLDER : Icons.SCRIPT_FILE,
                "", !folder, name -> create(parent, name, folder, ""));
    }
    public CompletableFuture<Void> create(Path parent, String name, boolean folder, String text) {
        var created = new ArrayList<Path>();
        return execute(List.of(), false, (files, changes) -> created.add(files.create(parent, name, folder, text)),
                ctx -> revealCreated(ctx, created.getFirst(), folder));
    }

    private CompletableFuture<Void> revealCreated(EditorContext ctx, Path path, boolean folder) {
        requireOwner(ctx);
        return tree.refreshDirectory(path.getParent())
                .thenComposeAsync(ignored -> {
                    requireOwner(ctx);
                    return tree.revealLocalPath(path);
                }, SwingUtilities::invokeLater)
                .thenComposeAsync(found -> {
                    requireOwner(ctx);
                    if (!found) return CompletableFuture.failedFuture(new IOException("Created " + path.getFileName() + ", but could not reveal it in Files."));
                    return folder ? CompletableFuture.completedFuture(null)
                            : ctx.navigation().openCreatedScript(ctx.project(), path);
                }, SwingUtilities::invokeLater);
    }
    private void requireOwner(EditorContext ctx) {
        if (context.get().project() != ctx.project() || ctx.project().phase() == ProjectScope.Phase.RETIRED)
            throw new CancellationException("Project changed during file operation");
    }
    private void rename(FileSelection selected) {
        Path from = selected.path;
        if (!managed(from) || from.equals(context.get().project().scriptFiles().root())) return;
        boolean script = !selected.directory && from.toString().endsWith(ScriptFiles.EXTENSION);
        String initial = from.getFileName().toString();
        if (script) initial = initial.substring(0, initial.length() - ScriptFiles.EXTENSION.length());
        showName("Rename", null, Icons.RENAME, initial, script,
                name -> rename(from, from.resolveSibling(name + (script ? ScriptFiles.EXTENSION : ""))));
    }
    private void duplicate(Path from) {
        String name = from.getFileName().toString();
        showName("Duplicate Script", "Script", Icons.COPY, name.substring(0, name.length() - ScriptFiles.EXTENSION.length()) + "Copy", true,
                next -> duplicate(from, next));
    }
    public CompletableFuture<Void> duplicate(Path from, String name) {
        String draft = tabs.editors().stream().filter(ScriptView.class::isInstance).map(ScriptView.class::cast)
                .filter(view -> view.getPath().equals(from)).map(ScriptView::currentText).findFirst().orElse(null);
        var created = new ArrayList<Path>();
        return execute(List.of(), false, (files, changes) -> created.add(files.create(from.getParent(), name, false,
                draft == null ? files.read(from).text() : draft)), ctx -> revealCreated(ctx, created.getFirst(), false));
    }
    public CompletableFuture<Void> rename(Path from, Path to) {
        return execute(List.of(from), false, (files, changes) -> { files.move(from, to); changes.add(new Change(from, to)); });
    }
    public CompletableFuture<Void> move(List<Path> sources, Path destination) {
        var roots = topLevel(sources);
        return execute(roots, false, (files, changes) -> {
            files.resolve(destination);
            var destinations = new HashSet<Path>();
            for (var from : roots) {
                Path to = destination.resolve(from.getFileName());
                files.mutable(from);
                if (!destinations.add(to) || Files.exists(to) || destination.startsWith(from)) throw new IOException("Cannot move " + from.getFileName() + " into " + destination);
            }
            for (var from : roots) { Path to = destination.resolve(from.getFileName()); files.move(from, to); changes.add(new Change(from, to)); }
        });
    }
    private void chooseDestination(List<Path> paths) {
        var chooser = new SystemFileChooser(context.get().project().scriptFiles().root().toFile());
        chooser.setDialogTitle("Move to folder"); chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        if (chooser.showDialog(owner, "Move") == SystemFileChooser.APPROVE_OPTION) report(move(paths, chooser.getSelectedFile().toPath()));
    }
    private void confirmDelete(List<FileSelection> files) {
        if (files.isEmpty() || !canStartCommand()) return;
        boolean recycle = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        JDialog dialog = deleteDialog(owner, files, recycle, () -> report(delete(files.stream().map(FileSelection::path).toList(), recycle)));
        try { dialog.setVisible(true); }
        finally { dialog.dispose(); }
    }
    static JDialog deleteDialog(Window owner, List<FileSelection> files, boolean recycle, Runnable delete) {
        String verb = recycle ? "Delete" : "Permanently delete";
        String message;
        if (files.size() == 1) {
            var file = files.getFirst();
            message = verb + (file.directory ? " folder \"" : " file \"") + file.path.getFileName() + "\""
                    + (file.directory ? " and its contents?" : "?");
        } else {
            message = verb + " these " + files.size() + " items?\n\n"
                    + String.join("\n", files.stream().limit(5).map(file -> file.path.getFileName()
                    + (file.directory ? " (including contents)" : "")).toList());
            if (files.size() > 5) message += "\nand " + (files.size() - 5) + " more";
        }
        var remove = new JButton("Delete");
        var cancel = new JButton("Cancel");
        var pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION,
                null, new Object[]{remove, cancel}, remove);
        JDialog dialog = pane.createDialog(owner, "Delete");
        remove.addActionListener(event -> { dialog.dispose(); delete.run(); });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(remove);
        return dialog;
    }
    public CompletableFuture<Void> delete(List<Path> paths, boolean recycle) {
        return execute(topLevel(paths), true, (files, changes) -> {
            for (Path path : topLevel(paths)) { files.delete(path, recycle); changes.add(new Change(path, null)); }
        });
    }

    private CompletableFuture<Void> execute(List<Path> roots, boolean deleting, Work work) {
        return execute(roots, deleting, work, ctx -> CompletableFuture.completedFuture(null));
    }

    private CompletableFuture<Void> execute(List<Path> roots, boolean deleting, Work work,
                                          Function<EditorContext, CompletableFuture<Void>> finish) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("File commands must start on the EDT");
        String reason = unavailableReason();
        if (reason != null) return CompletableFuture.failedFuture(new IOException(reason));
        EditorContext ctx = context.get();
        var views = tabs.editors().stream().filter(ScriptView.class::isInstance).map(ScriptView.class::cast)
                .filter(view -> roots.stream().anyMatch(view.getPath()::startsWith)).toList();
        if (views.stream().anyMatch(ScriptView::fileOperation)) return CompletableFuture.failedFuture(new IOException("An affected editor is already saving or moving."));
        boolean running = views.stream().anyMatch(ScriptView::isRunning)
                || ctx.editorRuns().activeRuns().stream().anyMatch(run -> run.source().target() instanceof NavigationTarget.LocalFile file
                        && roots.stream().anyMatch(file.path()::startsWith));
        if (running) return CompletableFuture.failedFuture(new IOException("Stop the running scripts before moving, renaming, or deleting them."));
        if (deleting) {
            var references = new ArrayList<>(ctx.project().state().savedScriptReferences());
            ctx.debugger().breakpointDefinitions().forEach(d -> { var a = d.request().action(); if (a != null && a.script() != null) references.add(a.script()); });
            for (String reference : references) if (roots.stream().anyMatch(ctx.project().scriptFiles().root().resolve(reference).normalize()::startsWith))
                return CompletableFuture.failedFuture(new IOException("A breakpoint uses " + reference + ". Change or remove that action before deleting it."));
        }
        var expanded = tree.tree().getExpandedDescendants(new TreePath(tree.tree().getModel().getRoot()));
        List<Path> expandedPaths = expanded == null ? List.of() : Collections.list(expanded).stream()
                .map(value -> path(DirectoryChain.last(((LazyTreeNode) value.getLastPathComponent()).getUserObject())))
                .filter(this::managed).toList();
        List<Path> selectionPaths = selected();
        busy = true; tree.tree().setEnabled(false);
        ctx.navigation().cancelPendingNavigation();
        Map<ScriptView, String> drafts = new LinkedHashMap<>();
        views.forEach(view -> { view.setFileOperation(true); drafts.put(view, view.currentText()); });
        var previews = tabs.editors().stream().filter(view -> !(view instanceof ScriptView))
                .filter(view -> view.getNavigationTarget() instanceof NavigationTarget.LocalFile file && roots.stream().anyMatch(file.path()::startsWith)).toList();
        var removedPreviews = new ArrayList<IEditorPanel>();
        var saved = new ArrayList<ScriptView>();
        var removed = new ArrayList<ScriptView>();
        var changes = new ArrayList<Change>();
        var result = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            try {
                ctx.project().requireActive();
                for (Path root : roots) ctx.project().scriptFiles().mutable(root);
                for (var entry : drafts.entrySet()) { entry.getKey().pendingSave().join(); entry.getKey().persist(entry.getValue()); saved.add(entry.getKey()); }
                FileUtils.withPausedDirectoryWatchers(roots, () -> work.run(ctx.project().scriptFiles(), changes));
            } catch (IOException failure) { throw new CompletionException(failure); }
            finally {
                if (deleting) {
                    views.stream().filter(view -> !Files.exists(view.getPath())).forEach(removed::add);
                    previews.stream().filter(view -> !Files.exists(((NavigationTarget.LocalFile) view.getNavigationTarget()).path())).forEach(removedPreviews::add);
                }
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            try {
            requireOwner(ctx);
            saved.forEach(view -> view.saved(drafts.get(view)));
            var refreshes = new ArrayList<CompletableFuture<Void>>();
            for (Change change : changes) {
                if (change.to != null) {
                    views.forEach(view -> view.relocated(change.from, change.to));
                    for (var preview : previews) {
                        var target = (NavigationTarget.LocalFile) preview.getNavigationTarget();
                        if (target.path().startsWith(change.from)) {
                            var relocated = ctx.navigation().relocatePreview(preview, ScriptFiles.relocated(target.path(), change.from, change.to));
                            report(relocated);
                            refreshes.add(relocated);
                        }
                    }
                }
                ctx.project().navigation().relocateFiles(change.from, change.to);
                if (change.to != null) {
                    UnaryOperator<String> remap = value -> {
                        Path file = ctx.project().scriptFiles().root().resolve(value).normalize();
                        return file.startsWith(change.from) ? ctx.project().scriptFiles().root().relativize(ScriptFiles.relocated(file, change.from, change.to)).toString().replace('\\', '/') : value;
                    };
                    ctx.project().state().remapScriptActions(remap);
                    refreshes.add(ctx.debugger().remapScriptActions(remap));
                }
                refreshes.add(tree.refreshDirectory(change.from.getParent()));
                if (change.to != null) refreshes.add(tree.refreshDirectory(change.to.getParent()));
            }
            views.forEach(view -> view.setFileOperation(false));
            removed.forEach(ScriptView::deleted);
            tabs.closeMatching(view -> removed.contains(view) || removedPreviews.contains(view));
            tabs.refreshEditorTitles();
            roots.forEach(path -> refreshes.add(tree.refreshDirectory(path.getParent())));
            CompletableFuture.allOf(refreshes.toArray(CompletableFuture[]::new)).handle((value, refreshFailure) -> refreshFailure)
                    .thenCompose(refreshFailure -> restoreTree(ctx.project().scriptFiles().root(), expandedPaths, selectionPaths, changes)
                            .handle((value, restoreFailure) -> refreshFailure != null ? refreshFailure : restoreFailure))
                    .thenComposeAsync(refreshFailure -> {
                        if (failure != null) return CompletableFuture.failedFuture(failure);
                        if (refreshFailure != null) return CompletableFuture.failedFuture(refreshFailure);
                        requireOwner(ctx);
                        return finish.apply(ctx);
                    }, SwingUtilities::invokeLater)
                    .whenComplete((value, completionFailure) -> SwingUtilities.invokeLater(() -> {
                        tree.tree().setEnabled(true); busy = false;
                        if (completionFailure == null) result.complete(null); else result.completeExceptionally(completionFailure);
                    }));
            } catch (RuntimeException completionFailure) {
                views.forEach(view -> view.setFileOperation(false));
                tree.tree().setEnabled(true); busy = false;
                result.completeExceptionally(completionFailure);
            }
        }));
        return result;
    }

    private CompletableFuture<Void> restoreTree(Path root, List<Path> expanded, List<Path> selected, List<Change> changes) {
        CompletableFuture<Void> restored = CompletableFuture.completedFuture(null);
        for (boolean select : List.of(false, true)) {
            for (Path old : select ? selected : expanded) {
                Path moved = old;
                for (var change : changes) {
                    if (moved.startsWith(change.from)) { moved = change.to == null ? null : ScriptFiles.relocated(moved, change.from, change.to); break; }
                }
                if (moved == null) continue;
                List<String> segments = new ArrayList<>();
                root.relativize(moved).forEach(segment -> segments.add(segment.toString()));
                restored = restored.thenCompose(ignored -> tree.tree().restoreItemPath(root.getFileName().toString(), segments, select));
            }
        }
        return restored;
    }

    private void showName(String title, String kind, Icon icon, String initial, boolean script,
                          Function<String, CompletableFuture<Void>> submit) {
        if (!canStartCommand()) return;
        var popup = namePopup(title, kind, icon, initial, script, submit);
        popup.setLocationRelativeTo(owner); popup.setVisible(true);
    }
    private FileNamePopup namePopup(String title, String kind, Icon icon, String initial, boolean script,
                                    Function<String, CompletableFuture<Void>> submit) {
        return new FileNamePopup(owner, title, kind, icon, initial, name -> {
            try { ScriptFiles.validateName(name, script); return null; } catch (IOException failure) { return failure.getMessage(); }
        }, submit::apply);
    }
    private void report(CompletableFuture<?> work) {
        var notifications = context.get().notifications();
        Source source = Source.capture(context.get().project(), "Scripts", null);
        work.whenComplete((ignored, failure) -> { if (failure != null) SwingUtilities.invokeLater(() -> {
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
                cause = cause.getCause();
            notifications.publish(Severity.ERROR, "File operation failed", cause.toString(), source);
        }); });
    }
}
