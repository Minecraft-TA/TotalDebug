package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.model.ChangesView;
import com.github.minecraft_ta.totalDebugCompanion.model.ConfigFileView;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import java.util.function.Predicate;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.DefinitionView;
import com.github.minecraft_ta.totalDebugCompanion.model.ModView;
import com.github.minecraft_ta.totalDebugCompanion.model.PackConfigurationView;
import com.github.minecraft_ta.totalDebugCompanion.model.LiteralUsagesView;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.InspectionView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Resolves semantic destinations into the current Companion UI. */
public final class NavigationService {
    public enum Activation {
        ACTIVATE_WINDOW,
        KEEP_CURRENT_WINDOW
    }

    private final MainWindow window;
    private final EditorTabs tabs;
    private final FileTreeView fileTree;
    private final Supplier<EditorContext> editors;
    private final Runnable activateWindow;
    private volatile ProjectScope project;
    private final NavigationState emptyNavigation = new NavigationState();
    private NavigationState state() { var scope = project; return scope == null ? emptyNavigation : scope.navigation(); }
    private record Context(ProjectScope project, RuntimeBinding runtime, long revision) { }
    private ProjectScope requireProject() {
        var scope = project;
        if (scope == null) throw new IllegalStateException("No Minecraft project is loaded");
        return scope;
    }
    private Context captureContext() {
        var scope = project;
        return new Context(scope, scope == null ? null : scope.runtime(), (scope == null ? emptyNavigation : scope.navigation()).revision.get());
    }
    private boolean isCurrent(Context captured) {
        ProjectScope selected = project;
        return captured.project() == selected && (selected == null
                || selected.isActive() && selected.runtime() == captured.runtime());
    }
    private boolean isCurrentNavigation(Context captured) {
        return isCurrent(captured) && state().revision.get() == captured.revision();
    }
    private void requireNavigationAdmission(Context captured) {
        if (!isCurrent(captured) || window.scriptFileActions().isBusy())
            throw new CancellationException("Navigation superseded by a project or file operation");
    }
    public void cancelPendingNavigation() {
        state().invalidatePending();
        refreshHistoryActions();
    }
    private final Action backAction = new AbstractAction("Back") {
        @Override
        public void actionPerformed(ActionEvent event) {
            goBack();
        }
    };
    private final Action forwardAction = new AbstractAction("Forward") {
        @Override
        public void actionPerformed(ActionEvent event) {
            goForward();
        }
    };

    public NavigationService(MainWindow window, EditorTabs tabs, FileTreeView fileTree, ProjectScope project, Supplier<EditorContext> editors) {
        this(window, tabs, fileTree, project, editors, () -> UIUtils.focusWindow(window));
    }

    NavigationService(MainWindow window, EditorTabs tabs, FileTreeView fileTree, ProjectScope project, Supplier<EditorContext> editors,
                      Runnable activateWindow) {
        this.project = project;
        this.editors = editors;
        this.activateWindow = Objects.requireNonNull(activateWindow, "activateWindow");
        this.window = Objects.requireNonNull(window, "window");
        this.tabs = Objects.requireNonNull(tabs, "tabs");
        this.fileTree = Objects.requireNonNull(fileTree, "fileTree");
        this.tabs.addSelectedEditorListener(this::selectedEditorChanged);
        this.tabs.setRevealActionProvider(this::revealAction);
        refreshHistoryActions();
    }

    private Action revealAction(IEditorPanel editor) {
        NavigationTarget target = editor.getNavigationTarget();
        if (this.project == null) return null;
        boolean available = switch (target) {
            case NavigationTarget.LocalFile file -> file.path().toAbsolutePath().normalize().startsWith(project.paths().scripts());
            case NavigationTarget.RuntimeClass ignored -> project.runtime() != null;
            case NavigationTarget.ArchiveEntry entry -> project.sources().modules().stream()
                    .flatMap(module -> project.sources().sourcesForModule(module.id()).stream())
                    .anyMatch(source -> source.path().equals(entry.archive().toAbsolutePath().normalize()));
            case NavigationTarget.ModPage ignored -> true;
            case NavigationTarget.PackConfiguration ignored -> true;
            case NavigationTarget.Changes ignored -> true;
            case null, default -> false;
        };
        if (!available) return null;
        Context context = captureContext();
        return new AbstractAction("Reveal in tree") {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!isCurrent(context)) return;
                CompletableFuture<Void> result = switch (target) {
                    case NavigationTarget.LocalFile file -> requireRevealed(fileTree.revealLocalPath(file.path()));
                    case NavigationTarget.ArchiveEntry entry -> requireRevealed(fileTree.revealArchivePath(entry.archive(), entry.entryName()));
                    case NavigationTarget.RuntimeClass type -> revealRuntimePath(type.binaryName(), type.binaryName().replace('.', '/') + ".class");
                    case NavigationTarget.ModPage page -> requireRevealed(fileTree.revealModPage(page));
                    case NavigationTarget.PackConfiguration ignored -> requireRevealed(fileTree.revealPackConfiguration());
                    case NavigationTarget.Changes ignored -> requireRevealed(fileTree.revealChanges());
                    default -> throw new IllegalArgumentException("Editor has no tree location");
                };
                reportFailure(result, target);
            }
        };
    }

    private static CompletableFuture<Void> requireRevealed(CompletableFuture<Boolean> result) {
        return result.thenAccept(revealed -> {
            if (!revealed) throw new IllegalStateException("The editor's location is not present in the Files tree");
        });
    }

    public CompletableFuture<Void> navigate(NavigationTarget target) {
        return navigate(target, Activation.ACTIVATE_WINDOW);
    }

    public CompletableFuture<Void> navigate(NavigationTarget target, Activation activation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(activation, "activation");
        Context requested = captureContext();
        CompletableFuture<Void> navigation = CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ready -> {
            requireNavigationAdmission(requested);
            cancelPendingNavigation();
            Context context = captureContext();
            return captureCurrentEntry().thenCompose(origin ->
                (isCurrentNavigation(context) ? performNavigation(target, activation)
                        : CompletableFuture.<Void>failedFuture(new CancellationException("Project changed")))
                        .thenCompose(ignored -> captureDestination(target))
                        .thenAccept(destination -> {
                            if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
                            state().currentEntry = destination;
                            state().history.recordNewNavigation(origin);
                            refreshHistoryActions();
                        })
            );
        }, SwingUtilities::invokeLater);
        reportFailure(navigation, target);
        return navigation;
    }

    public void revealPackage(String packageName, String ownerClassName) {
        if (ownerClassName == null || ownerClassName.isBlank()) {
            reportNavigationFailure("JDT could not resolve the class owning package " + packageName);
            return;
        }
        navigate(new NavigationTarget.RuntimePackage(packageName, ownerClassName));
    }

    private void reportNavigationFailure(String message) {
        editors.get().notifications().publish(Severity.ERROR, message, "", Source.capture(project, "Navigation", null));
    }

    public Action backAction() {
        return this.backAction;
    }

    public Action forwardAction() {
        return this.forwardAction;
    }

    public void runtimeChanged() {
        Context context = captureContext();
        var navigationState = state();
        var traversal = navigationState.traversal.get();
        if (traversal != null && traversal.runtime() != context.runtime()) navigationState.traversal.compareAndSet(traversal, null);
        SwingUtilities.invokeLater(() -> {
            if (!isCurrent(context)) return;
            this.tabs.closeMatching(editor -> editor.runtimeBinding() != context.runtime()
                    && (editor.getNavigationTarget() instanceof NavigationTarget.RuntimeClass
                    || editor.getNavigationTarget() instanceof NavigationTarget.ArchiveEntry entry && !isLocalModArchive(entry.archive())
                    || editor.getNavigationTarget() instanceof NavigationTarget.SymbolUsages
                    || editor.getNavigationTarget() instanceof NavigationTarget.LiteralUsages));
        });
        refreshHistoryActions();
    }

    public void projectChanged(ProjectScope project) {
        this.project = project;
        refreshHistoryActions();
    }

    public CompletableFuture<Void> goBack() {
        return traverseHistory(NavigationHistory.Direction.BACK);
    }

    public CompletableFuture<Void> goForward() {
        return traverseHistory(NavigationHistory.Direction.FORWARD);
    }

    private CompletableFuture<Void> performNavigation(NavigationTarget target, Activation activation) {
        CompletableFuture<Void> navigation;
        try {
            RuntimeBinding requestedRuntime = project == null ? null : project.runtime();
            navigation = switch (target) {
                case NavigationTarget.RuntimeClass runtimeClass -> openRuntimeSource(
                        runtimeClass.binaryName(),
                        source -> source.document().classFallback(source.binaryName()).caret(),
                        -1,
                        activation
                );
                case NavigationTarget.RuntimeDeclaration declaration -> openRuntimeSource(
                        declaration.member().ownerClassName(),
                        source -> source.document().navigate(declaration.member()).caret(),
                        -1,
                        activation
                );
                case NavigationTarget.RuntimeLine line -> openRuntimeSource(
                        line.binaryName(),
                        source -> source.document().lineOffset(line.displayedLine()),
                        line.displayedLine(),
                        activation
                );
                case NavigationTarget.LocalFile file -> openLocalFile(file, activation);
                case NavigationTarget.LocalDirectory directory -> revealLocalPath(directory);
                case NavigationTarget.ArchiveEntry entry -> openResource(
                        new ArchiveEntrySource(entry.archive(), entry.entryName(), -1),
                        activation
                );
                case NavigationTarget.ArchiveDirectory directory -> revealArchivePath(directory);
                case NavigationTarget.UsageSite site -> openRuntimeSource(
                        site.usage().location().className(),
                        source -> source.document().usage(
                                site.usage().location(),
                                site.query()
                        ).caret(),
                        -1,
                        activation
                );
                case NavigationTarget.SymbolUsages usages -> dispatchNavigation(() -> openRuntimeEditor(requestedRuntime,
                        UsagesView.class,
                        view -> view.symbol().equals(usages.symbol()),
                        () -> new UsagesView(editors.get(), usages.symbol(), requestedRuntime)
                ).thenAccept(UsagesView::restartSearch), activation);
                case NavigationTarget.LiteralUsages usages -> dispatchNavigation(() -> openRuntimeEditor(requestedRuntime,
                        LiteralUsagesView.class,
                        view -> view.literal().equals(usages.literal()),
                        () -> new LiteralUsagesView(editors.get(), usages.literal(), requestedRuntime)
                ).thenAccept(LiteralUsagesView::restartSearch), activation);
                case NavigationTarget.RuntimePackage runtimePackage -> revealRuntimePath(runtimePackage.ownerClassName(), runtimePackage.packageName().replace('.', '/'));
                case NavigationTarget.Inspection inspection -> dispatchNavigation(() -> openRuntimeEditor(requestedRuntime,
                        InspectionView.class,
                        view -> view.shows(inspection.subject()),
                        () -> new InspectionView(editors.get(), inspection.subject(), requestedRuntime)
                ).thenAccept(InspectionView::refresh), activation);
                case NavigationTarget.ModuleSearch search -> dispatchNavigation(() -> {
                    this.window.openSearchEverywhere(search);
                    return CompletableFuture.completedFuture(null);
                }, Activation.KEEP_CURRENT_WINDOW);
                case NavigationTarget.ModPage page -> dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                        ModView.class,
                        view -> view.modId().equals(page.modId()),
                        () -> new ModView(editors.get(), page)
                ).thenAccept(view -> view.show(page)), activation);
                case NavigationTarget.PackConfiguration ignored -> dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                        PackConfigurationView.class,
                        view -> true,
                        () -> new PackConfigurationView(editors.get())
                ).thenAccept(PackConfigurationView::refresh), activation);
                case NavigationTarget.Changes ignored -> dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                        ChangesView.class,
                        view -> true,
                        () -> new ChangesView(editors.get())
                ).thenAccept(ChangesView::refresh), activation);
                case NavigationTarget.Definition definition -> dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                        DefinitionView.class,
                        view -> view.subject().equals(definition.subject()),
                        () -> new DefinitionView(editors.get(), definition.subject())
                ).thenApply(ignored -> null), activation);
                case NavigationTarget.RuntimeModuleNode node -> revealRuntimeModule(node);
            };
        } catch (RuntimeException failure) {
            navigation = CompletableFuture.failedFuture(failure);
        }
        return navigation;
    }

    private CompletableFuture<Void> traverseHistory(NavigationHistory.Direction direction) {
        Context requested = captureContext();
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored -> {
            requireNavigationAdmission(requested);
            return traverseAdmittedHistory(direction);
        }, SwingUtilities::invokeLater);
    }

    private CompletableFuture<Void> traverseAdmittedHistory(NavigationHistory.Direction direction) {
        NavigationState navigationState = state();
        if (navigationState.traversal.get() != null) return CompletableFuture.completedFuture(null);
        cancelPendingNavigation();
        Context context = captureContext();
        var traversal = new NavigationState.Traversal(context.runtime());
        if (!navigationState.traversal.compareAndSet(null, traversal)) {
            return CompletableFuture.completedFuture(null);
        }
        NavigationEntry destination = state().history.destination(
                direction,
                (project == null ? null : project.runtimeSignature())
        );
        if (destination == null) {
            state().traversal.set(null);
            refreshHistoryActions();
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> navigation = captureCurrentEntry().thenCompose(origin ->
                (isCurrentNavigation(context) && navigationState.traversal.get() == traversal ? performNavigation(destination.target(), Activation.KEEP_CURRENT_WINDOW)
                        : CompletableFuture.<Void>failedFuture(new CancellationException("Project changed")))
                        .thenCompose(ignored -> restoreSelectedEntry(destination, context))
                        .thenRun(() -> {
                            if (!isCurrentNavigation(context) || navigationState.traversal.get() != traversal)
                                throw new CancellationException("History traversal changed");
                            navigationState.currentEntry = destination;
                            navigationState.history.complete(direction, destination, origin);
                        })
        );
        navigation.whenComplete((ignored, failure) -> {
            if (isCurrentNavigation(context) && navigationState.traversal.get() == traversal
                    && failure != null && !(unwrap(failure) instanceof CancellationException)) {
                navigationState.history.discard(direction, destination);
            }
            // A reversible switch must not strand the old traversal; a new one has a different token.
            navigationState.traversal.compareAndSet(traversal, null);
            refreshHistoryActions();
        });
        reportFailure(navigation, destination.target());
        return navigation;
    }

    private CompletableFuture<NavigationEntry> captureCurrentEntry() {
        var result = new CompletableFuture<NavigationEntry>();
        SwingUtilities.invokeLater(() -> {
            try {
                IEditorPanel editor = this.tabs.getSelectedEditor();
                NavigationEntry current = state().currentEntry;
                if (current != null && (!isEditorDestination(current.target())
                        || editor == null
                        || sameEditorDestination(
                        current.target(),
                        editor.getNavigationTarget()
                ))) {
                    result.complete(editor == null
                            ? current
                            : entry(current.target(), editor.captureNavigationViewState()));
                    return;
                }
                result.complete(entryForEditor(editor));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletableFuture<NavigationEntry> captureDestination(NavigationTarget target) {
        var result = new CompletableFuture<NavigationEntry>();
        SwingUtilities.invokeLater(() -> {
            try {
                IEditorPanel editor = this.tabs.getSelectedEditor();
                NavigationViewState state = editor != null && sameEditorDestination(
                        target,
                        editor.getNavigationTarget()
                )
                        ? editor.captureNavigationViewState()
                        : NavigationViewState.EMPTY;
                result.complete(entry(target, state));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletableFuture<Void> restoreSelectedEntry(NavigationEntry entry, Context context) {
        return dispatchNavigation(() -> {
            if (!isCurrentNavigation(context)) return CompletableFuture.failedFuture(new CancellationException("Navigation changed"));
            IEditorPanel editor = this.tabs.getSelectedEditor();
            if (!isEditorDestination(entry.target())) {
                return CompletableFuture.completedFuture(null);
            }
            if (editor == null || !sameEditorDestination(entry.target(), editor.getNavigationTarget())) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Navigation opened a different editor than requested"
                ));
            }
            editor.restoreNavigationViewState(entry.viewState());
            return CompletableFuture.completedFuture(null);
        }, Activation.KEEP_CURRENT_WINDOW);
    }

    private void selectedEditorChanged(IEditorPanel editor) {
        state().currentEntry = entryForEditor(editor);
    }

    private NavigationEntry entryForEditor(IEditorPanel editor) {
        return editor == null || editor.getNavigationTarget() == null
                ? null
                : entry(editor.getNavigationTarget(), editor.captureNavigationViewState());
    }

    private NavigationEntry entry(NavigationTarget target, NavigationViewState state) {
        String runtimeSignature = null;
        if (NavigationEntry.requiresRuntime(target)) {
            runtimeSignature = (project == null ? null : project.runtimeSignature());
            if (runtimeSignature == null || runtimeSignature.isBlank()) {
                return null;
            }
        }
        return new NavigationEntry(target, runtimeSignature, state);
    }

    private static boolean isEditorDestination(NavigationTarget target) {
        return !(target instanceof NavigationTarget.RuntimePackage)
                && !(target instanceof NavigationTarget.LocalDirectory)
                && !(target instanceof NavigationTarget.ArchiveDirectory)
                && !(target instanceof NavigationTarget.ModuleSearch)
                && !(target instanceof NavigationTarget.RuntimeModuleNode);
    }

    private static boolean sameEditorDestination(NavigationTarget requested, NavigationTarget editorTarget) {
        if (editorTarget == null) {
            return false;
        }
        if (requested.equals(editorTarget)) {
            return true;
        }
        if (!(editorTarget instanceof NavigationTarget.RuntimeClass(String binaryName))) {
            return false;
        }
        String requestedClass = switch (requested) {
            case NavigationTarget.RuntimeClass type -> type.binaryName();
            case NavigationTarget.RuntimeDeclaration declaration -> declaration.member().ownerClassName();
            case NavigationTarget.RuntimeLine line -> line.binaryName();
            case NavigationTarget.UsageSite site -> site.usage().location().className();
            default -> null;
        };
        return binaryName.equals(requestedClass);
    }

    private void reportFailure(CompletableFuture<Void> navigation, NavigationTarget target) {
        Context context = captureContext();
        navigation.whenComplete((ignored, failure) -> {
            if (failure != null && isCurrent(context)) {
                showFailure(target, unwrap(failure));
            }
        });
    }

    private void refreshHistoryActions() {
        SwingUtilities.invokeLater(() -> {
            String runtimeSignature = (project == null ? null : project.runtimeSignature());
            boolean available = state().traversal.get() == null;
            this.backAction.setEnabled(available && state().history.canNavigate(
                    NavigationHistory.Direction.BACK,
                    runtimeSignature
            ));
            this.forwardAction.setEnabled(available && state().history.canNavigate(
                    NavigationHistory.Direction.FORWARD,
                    runtimeSignature
            ));
        });
    }

    private CompletableFuture<Void> openRuntimeSource(
            String binaryName,
            ToIntFunction<DecompiledSource> offsetResolver,
            int executionLine,
            Activation activation
    ) {
        Context context = captureContext();
        RuntimeBinding installed = context.runtime();
        if (installed == null) throw new IllegalStateException("Decompilation is unavailable");
        var service = installed.decompiler();
        return service.load(binaryName).thenCompose(source -> {
            int offset = offsetResolver.applyAsInt(source);
            return dispatchNavigation(() -> {
                if (!isCurrentNavigation(context) || service != requireProject().requireRuntime().decompiler()) {
                    return CompletableFuture.failedFuture(new CancellationException("Runtime changed during source navigation"));
                }
                return openRuntimeEditor(installed,
                        CodeView.class,
                        view -> view.getPath().equals(source.path()),
                        () -> new CodeView(editors.get(), source, offset, source.location(), installed)
                ).thenAcceptAsync(view -> {
                    if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
                    view.navigateToOffset(offset);
                    if (executionLine > 0) {
                        view.showExecutionLine(executionLine);
                    }
                }, SwingUtilities::invokeLater);
            }, activation);
        });
    }

    public CompletableFuture<Void> relocatePreview(IEditorPanel previous, Path path) {
        if (path.getFileName().toString().endsWith(ScriptView.FILE_EXTENSION)) {
            EditorContext context = editors.get();
            return CompletableFuture.supplyAsync(() -> new ScriptView(context, path)).thenComposeAsync(replacement -> {
                // A pending switch may still be vetoed by this active file operation.
                if (project != context.project() || context.project().phase() == ProjectScope.Phase.RETIRED) {
                    replacement.dispose();
                    return CompletableFuture.failedFuture(new CancellationException("Project changed while loading the moved script"));
                }
                return tabs.replacePreview(previous, replacement);
            }, SwingUtilities::invokeLater);
        }
        IEditorPanel replacement = path.getFileName().toString().endsWith(".java") ? new CodeView(editors.get(), path, 0)
                : new ResourceView(editors.get(), new LocalFileSource(path), null);
        return tabs.replacePreview(previous, replacement);
    }

    /** Finishes the owning file operation while its busy guard still excludes other navigation. */
    public CompletableFuture<Void> openCreatedScript(ProjectScope expected, Path path) {
        EditorContext context = editors.get();
        if (context.project() != expected || project != expected || expected.phase() == ProjectScope.Phase.RETIRED)
            return CompletableFuture.failedFuture(new CancellationException("Project changed while creating the script"));
        return captureCurrentEntry().thenCompose(origin -> CompletableFuture.supplyAsync(() -> new ScriptView(context, path))
                .thenComposeAsync(script -> {
                    if (project != expected || expected.phase() == ProjectScope.Phase.RETIRED) {
                        script.dispose();
                        return CompletableFuture.failedFuture(new CancellationException("Project changed while opening the created script"));
                    }
                    if (tabs.editors().stream().anyMatch(view -> view instanceof ScriptView existing && existing.getPath().equals(path)))
                        script.dispose();
                    return tabs.focusOrCreateIfAbsent(ScriptView.class, view -> view.getPath().equals(path), () -> script)
                            .thenAcceptAsync(view -> {
                                if (project != expected || expected.phase() == ProjectScope.Phase.RETIRED)
                                    throw new CancellationException("Project changed while opening the created script");
                                expected.navigation().currentEntry = entryForEditor(view);
                                expected.navigation().history.recordNewNavigation(origin);
                                refreshHistoryActions();
                            }, SwingUtilities::invokeLater);
                }, SwingUtilities::invokeLater));
    }

    private CompletableFuture<Void> openLocalFile(NavigationTarget.LocalFile target, Activation activation) {
        Context context = captureContext();
        Path path = target.path();
        if (!Files.isRegularFile(path)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("File does not exist: " + path));
        }
        String fileName = path.getFileName().toString();
        Path scripts = requireProject().paths().scripts().toAbsolutePath().normalize();
        if (path.startsWith(scripts) && !path.equals(scripts)
                && fileName.endsWith(ScriptView.FILE_EXTENSION)) {
            String scriptName = fileName.substring(0, fileName.length() - ScriptView.FILE_EXTENSION.length());
            return dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                    ScriptView.class,
                    view -> view.getPath().equals(path),
                    () -> new ScriptView(editors.get(), path)
            ).thenAcceptAsync(view -> {
                if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
                view.navigateToOffset(target.offset());
            }, SwingUtilities::invokeLater), activation);
        }
        if (fileName.endsWith(".java")) {
            return dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(path),
                    () -> new CodeView(editors.get(), path, target.offset())
            ).thenAcceptAsync(view -> {
                if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
                view.navigateToOffset(target.offset());
            }, SwingUtilities::invokeLater), activation);
        }
        // A mod's configuration file is edited, with the checks its Configuration tab applies.
        ProjectScope project = requireProject();
        var owner = project.catalog().index().flatMap(index ->
                ConfigSources.owner(index, project.profile().workspaceDirectory(), path));
        if (owner.isPresent()) {
            return dispatchNavigation(() -> this.tabs.focusOrCreateIfAbsent(
                    ConfigFileView.class,
                    view -> view.getPath().equals(path),
                    () -> new ConfigFileView(editors.get(), path, owner.get())
            ).thenAcceptAsync(view -> {
                if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
                if (target.offset() > 0) view.navigateToOffset(target.offset());
            }, SwingUtilities::invokeLater), activation);
        }
        return openResource(new LocalFileSource(path), activation);
    }

    private CompletableFuture<Void> openResource(ContentSource source, Activation activation) {
        RuntimeBinding installed = source instanceof ArchiveEntrySource entry && !isLocalModArchive(entry.archivePath())
                ? captureContext().runtime() : null;
        return dispatchNavigation(() -> openRuntimeEditor(installed,
                ResourceView.class,
                view -> view.source().identity().equals(source.identity()),
                () -> new ResourceView(editors.get(), source, installed)
        ).thenApply(ignored -> null), activation);
    }

    private boolean isLocalModArchive(Path archive) {
        return project != null && archive.toAbsolutePath().normalize().startsWith(project.profile().workspaceDirectory().resolve("mods"));
    }

    private <T extends IEditorPanel> CompletableFuture<T> openRuntimeEditor(
            RuntimeBinding runtime, Class<T> type, Predicate<T> matches, Supplier<T> create) {
        if (runtime != null && runtime != captureContext().runtime()) {
            return CompletableFuture.failedFuture(new CancellationException("Runtime changed"));
        }
        // Dispose a stale same-file editor before the new one installs its AST listeners.
        tabs.closeMatching(editor -> type.isInstance(editor) && matches.test(type.cast(editor))
                && editor.runtimeBinding() != runtime);
        return tabs.focusOrCreateIfAbsent(type, editor -> editor.runtimeBinding() == runtime && matches.test(editor), create);
    }

    private CompletableFuture<Void> revealRuntimePath(String ownerClassName, String entryPath) {
        var result = new CompletableFuture<Void>();
        Context context = captureContext();
        editors.get().insights().locateClass(ownerClassName, new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(RuntimeSnapshotBytecodeSource.Source source) {
                if (!isCurrentNavigation(context)) { result.cancel(false); return; }
                if (source == null) {
                    result.completeExceptionally(new IllegalStateException(
                            "Class " + ownerClassName + " is not present in the runtime index"
                    ));
                    return;
                }
                NavigationService.this.fileTree.revealRuntimePath(
                                ownerClassName,
                                entryPath,
                                source
                        )
                        .whenComplete((revealed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else if (!revealed) {
                        result.completeExceptionally(new IllegalStateException(
                                "Path " + entryPath + " is not present in the owning runtime source"
                        ));
                    } else {
                        result.complete(null);
                    }
                        });
            }

            @Override
            public void onFailed(Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletableFuture<Void> revealRuntimeModule(NavigationTarget.RuntimeModuleNode target) {
        requireNavigationAdmission(captureContext());
        return requireRevealed(this.fileTree.revealRuntimeModule(target.moduleId()));
    }

    private CompletableFuture<Void> revealLocalPath(NavigationTarget.LocalDirectory target) {
        requireNavigationAdmission(captureContext());
        return this.fileTree.revealLocalPath(target.path()).thenCompose(revealed -> revealed
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(
                "Directory is not present in the file tree: " + target.path()
        )));
    }

    private CompletableFuture<Void> revealArchivePath(NavigationTarget.ArchiveDirectory target) {
        requireNavigationAdmission(captureContext());
        return this.fileTree.revealArchivePath(target.archive(), target.entryName()).thenCompose(revealed ->
                revealed
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException(
                        "Archive directory is not present in the file tree: "
                                + target.archive() + "!/" + target.entryName()
                ))
        );
    }

    private CompletableFuture<Void> dispatchNavigation(
            Supplier<CompletableFuture<Void>> operation,
            Activation activation
    ) {
        Context context = captureContext();
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored -> {
            if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
            requireNavigationAdmission(context);
            return operation.get();
        }, SwingUtilities::invokeLater).thenRunAsync(() -> {
            if (!isCurrentNavigation(context)) throw new CancellationException("Navigation changed");
            if (activation == Activation.ACTIVATE_WINDOW) activateWindow.run();
        }, SwingUtilities::invokeLater);
    }

    private void showFailure(NavigationTarget target, Throwable failure) {
        if (failure instanceof CancellationException) return;
        Context context = captureContext();
        failure.printStackTrace(System.err);
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        String message = "Unable to open " + label(target) + ": " + detail;
        SwingUtilities.invokeLater(() -> {
            if (!isCurrent(context)) return;
            editors.get().notifications().publish(Severity.ERROR, "Navigation failed", message, Source.capture(context.project(), label(target), target));
        });
    }

    private static String label(NavigationTarget target) {
        return switch (target) {
            case NavigationTarget.RuntimeClass runtimeClass -> runtimeClass.binaryName();
            case NavigationTarget.RuntimeDeclaration declaration -> declaration.member().ownerClassName();
            case NavigationTarget.RuntimeLine line -> line.binaryName() + ':' + line.displayedLine();
            case NavigationTarget.LocalFile file -> file.path().toString();
            case NavigationTarget.LocalDirectory directory -> directory.path().toString();
            case NavigationTarget.ArchiveEntry entry -> entry.archive() + "!/" + entry.entryName();
            case NavigationTarget.ArchiveDirectory directory ->
                    directory.archive() + "!/" + directory.entryName();
            case NavigationTarget.UsageSite site -> site.usage().location().className();
            case NavigationTarget.SymbolUsages usages -> usages.symbol().displayName();
            case NavigationTarget.LiteralUsages usages -> '"' + usages.literal() + '"';
            case NavigationTarget.RuntimePackage runtimePackage -> runtimePackage.packageName();
            case NavigationTarget.ModuleSearch ignored -> "Search Everywhere";
            case NavigationTarget.Inspection inspection -> inspection.subject().subject();
            case NavigationTarget.ModPage page -> "mod " + page.modId();
            case NavigationTarget.PackConfiguration ignored -> "modpack configuration";
            case NavigationTarget.Changes ignored -> "changes";
            case NavigationTarget.Definition definition -> definition.subject().format();
            case NavigationTarget.RuntimeModuleNode node -> "module " + node.moduleId();
        };
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}
