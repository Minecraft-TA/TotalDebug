package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.SourceFileNavigation;
import com.github.minecraft_ta.totalDebugCompanion.model.BaseScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.LiteralUsagesView;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Resolves semantic destinations into the current Companion UI. */
public final class NavigationService {
    public enum Activation {
        ACTIVATE_WINDOW,
        KEEP_CURRENT_WINDOW
    }

    private final MainWindow window;
    private final EditorTabs tabs;
    private final FileTreeView fileTree;

    public NavigationService(MainWindow window, EditorTabs tabs, FileTreeView fileTree) {
        this.window = Objects.requireNonNull(window, "window");
        this.tabs = Objects.requireNonNull(tabs, "tabs");
        this.fileTree = Objects.requireNonNull(fileTree, "fileTree");
    }

    public CompletableFuture<Void> navigate(NavigationTarget target) {
        return navigate(target, Activation.ACTIVATE_WINDOW);
    }

    public CompletableFuture<Void> navigate(NavigationTarget target, Activation activation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(activation, "activation");
        CompletableFuture<Void> navigation;
        try {
            navigation = switch (target) {
                case NavigationTarget.RuntimeClass runtimeClass -> openRuntimeSource(
                        runtimeClass.binaryName(),
                        source -> 0,
                        -1,
                        activation
                );
                case NavigationTarget.RuntimeDeclaration declaration -> openRuntimeSource(
                        declaration.member().ownerClassName(),
                        source -> SourceFileNavigation.memberOffset(source.contents(), declaration.member()),
                        -1,
                        activation
                );
                case NavigationTarget.RuntimeLine line -> openRuntimeSource(
                        line.binaryName(),
                        source -> SourceFileNavigation.lineOffset(source.contents(), line.displayedLine()),
                        line.displayedLine(),
                        activation
                );
                case NavigationTarget.LocalFile file -> openLocalFile(file, activation);
                case NavigationTarget.ArchiveEntry entry -> openResource(
                        new ArchiveEntrySource(entry.archive(), entry.entryName(), -1),
                        activation
                );
                case NavigationTarget.UsageSite site -> openRuntimeSource(
                        site.usage().location().className(),
                        source -> SourceFileNavigation.usageOffset(
                                source.contents(),
                                site.usage().location(),
                                site.query()
                        ),
                        -1,
                        activation
                );
                case NavigationTarget.SymbolUsages usages -> onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                        UsagesView.class,
                        view -> view.symbol().equals(usages.symbol()),
                        () -> new UsagesView(usages.symbol())
                ).thenAccept(UsagesView::restartSearch), activation);
                case NavigationTarget.LiteralUsages usages -> onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                        LiteralUsagesView.class,
                        view -> view.literal().equals(usages.literal()),
                        () -> new LiteralUsagesView(usages.literal())
                ).thenAccept(LiteralUsagesView::restartSearch), activation);
                case NavigationTarget.RuntimePackage runtimePackage -> revealPackage(runtimePackage);
                case NavigationTarget.ModuleSearch search -> onEdt(() -> {
                    this.window.openSearchEverywhere(search);
                    return CompletableFuture.completedFuture(null);
                }, Activation.KEEP_CURRENT_WINDOW);
            };
        } catch (RuntimeException failure) {
            navigation = CompletableFuture.failedFuture(failure);
        }
        navigation.whenComplete((ignored, failure) -> {
            if (failure != null) {
                showFailure(target, unwrap(failure));
            }
        });
        return navigation;
    }

    private CompletableFuture<Void> openRuntimeSource(
            String binaryName,
            java.util.function.ToIntFunction<DecompiledSource> offsetResolver,
            int executionLine,
            Activation activation
    ) {
        return CompanionApp.getDecompilationService().load(binaryName).thenCompose(source -> {
            int offset = offsetResolver.applyAsInt(source);
            return onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(source.path()),
                    () -> new CodeView(source, offset, SourceFileNavigation.location(source))
            ).thenAccept(view -> {
                view.centerViewportOnOffset(offset);
                if (executionLine > 0) {
                    view.showExecutionLine(executionLine);
                }
            }), activation);
        });
    }

    private CompletableFuture<Void> openLocalFile(NavigationTarget.LocalFile target, Activation activation) {
        Path path = target.path();
        if (!Files.isRegularFile(path)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("File does not exist: " + path));
        }
        String fileName = path.getFileName().toString();
        Path scripts = CompanionApp.getRootPath().resolve("scripts").toAbsolutePath().normalize();
        if (path.getParent().equals(scripts)
                && fileName.endsWith(".java")
                && CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            String scriptName = fileName.substring(0, fileName.length() - ".java".length());
            return onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                    ScriptView.class,
                    view -> view.getTitle().equals(fileName),
                    () -> scriptName.equals("BaseScript")
                            ? new BaseScriptView(scriptName)
                            : new ScriptView(scriptName)
            ).thenAccept(view -> view.centerViewportOnOffset(target.offset())), activation);
        }
        if (fileName.endsWith(".java")) {
            return onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                    CodeView.class,
                    view -> view.getPath().equals(path),
                    () -> new CodeView(path, target.offset())
            ).thenAccept(view -> view.centerViewportOnOffset(target.offset())), activation);
        }
        return openResource(new LocalFileSource(path), activation);
    }

    private CompletableFuture<Void> openResource(ContentSource source, Activation activation) {
        return onEdt(() -> this.tabs.focusOrCreateIfAbsent(
                ResourceView.class,
                view -> view.source().identity().equals(source.identity()),
                () -> new ResourceView(source)
        ).thenApply(ignored -> null), activation);
    }

    private CompletableFuture<Void> revealPackage(NavigationTarget.RuntimePackage target) {
        var result = new CompletableFuture<Void>();
        CompanionApp.getCodeInsightService().locateClass(target.ownerClassName(), new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(RuntimeSnapshotBytecodeSource.Source source) {
                if (source == null) {
                    result.completeExceptionally(new IllegalStateException(
                            "Class " + target.ownerClassName() + " is not present in the runtime index"
                    ));
                    return;
                }
                Optional<String> archive = FileTreeView.workspaceArchiveName(source);
                if (archive.isEmpty()) {
                    result.completeExceptionally(new IllegalStateException(
                            "Class " + target.ownerClassName() + " is indexed outside the current mods tree"
                    ));
                    return;
                }
                NavigationService.this.fileTree.revealPackage(target.packageName(), archive.get())
                        .whenComplete((revealed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else if (!revealed) {
                        result.completeExceptionally(new IllegalStateException(
                                "Package " + target.packageName() + " is not present in the owning archive"
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

    private CompletableFuture<Void> onEdt(
            Supplier<CompletableFuture<Void>> operation,
            Activation activation
    ) {
        var result = new CompletableFuture<Void>();
        SwingUtilities.invokeLater(() -> {
            try {
                operation.get().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    if (activation == Activation.ACTIVATE_WINDOW) {
                        UIUtils.focusWindow(this.window);
                    }
                    result.complete(null);
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void showFailure(NavigationTarget target, Throwable failure) {
        failure.printStackTrace(System.err);
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        String message = "Unable to open " + label(target) + ": " + detail;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this.window,
                message,
                "Navigation failed",
                JOptionPane.ERROR_MESSAGE
        ));
    }

    private static String label(NavigationTarget target) {
        return switch (target) {
            case NavigationTarget.RuntimeClass runtimeClass -> runtimeClass.binaryName();
            case NavigationTarget.RuntimeDeclaration declaration -> declaration.member().ownerClassName();
            case NavigationTarget.RuntimeLine line -> line.binaryName() + ':' + line.displayedLine();
            case NavigationTarget.LocalFile file -> file.path().toString();
            case NavigationTarget.ArchiveEntry entry -> entry.archive() + "!/" + entry.entryName();
            case NavigationTarget.UsageSite site -> site.usage().location().className();
            case NavigationTarget.SymbolUsages usages -> usages.symbol().displayName();
            case NavigationTarget.LiteralUsages usages -> '"' + usages.literal() + '"';
            case NavigationTarget.RuntimePackage runtimePackage -> runtimePackage.packageName();
            case NavigationTarget.ModuleSearch ignored -> "Search Everywhere";
        };
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}
