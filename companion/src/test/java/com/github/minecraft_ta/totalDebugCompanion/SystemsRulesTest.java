package com.github.minecraft_ta.totalDebugCompanion;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of docs/SYSTEMS.md that the build checks, so a new listener list, thread or watcher is found here rather than
 * in a review. Each file that breaks a rule today is listed with how often and why; a new one fails, and so does a listed
 * one that no longer breaks it, so the lists only shrink. Adding to a list is a decision recorded in docs/SYSTEMS.md.
 */
class SystemsRulesTest {
    private static final Path SOURCES = Path.of("src/main/java/com/github/minecraft_ta/totalDebugCompanion");
    /** A collection of callbacks: any list, set, map, queue or deque whose elements are called. */
    private static final Pattern LISTENER_COLLECTION = Pattern.compile(
            "\\w*(List|Set|Collection|Map|Queue|Deque)<.*\\b(Runnable|Consumer|BiConsumer|IntConsumer|\\w*Listener|\\w*Callback)\\b.*>");
    /** Names of fields that keep callbacks to tell, rather than, say, what removes subscriptions. */
    private static final Pattern LISTENER_NAME = Pattern.compile(".*(listener|callback|subscriber|observer|handler).*");
    private static final Set<String> THREAD_TYPES = Set.of("Thread", "java.lang.Thread", "ThreadPoolExecutor",
            "ScheduledThreadPoolExecutor", "ForkJoinPool", "java.util.Timer");
    /** Executor factories of {@code Executors}, also when imported statically. */
    private static final Set<String> EXECUTOR_FACTORIES = Set.of("newFixedThreadPool", "newCachedThreadPool",
            "newSingleThreadExecutor", "newSingleThreadScheduledExecutor", "newScheduledThreadPool", "newWorkStealingPool",
            "newVirtualThreadPerTaskExecutor", "newThreadPerTaskExecutor", "unconfigurableExecutorService");
    /** {@code CompletableFuture}'s async methods, by how many arguments they take without an executor. */
    private static final Map<String, Integer> ASYNC_WITHOUT_EXECUTOR = Map.ofEntries(
            Map.entry("supplyAsync", 1), Map.entry("runAsync", 1), Map.entry("thenApplyAsync", 1), Map.entry("thenAcceptAsync", 1),
            Map.entry("thenRunAsync", 1), Map.entry("thenComposeAsync", 1), Map.entry("whenCompleteAsync", 1),
            Map.entry("handleAsync", 1), Map.entry("exceptionallyAsync", 1), Map.entry("exceptionallyComposeAsync", 1),
            Map.entry("thenCombineAsync", 2), Map.entry("thenAcceptBothAsync", 2), Map.entry("runAfterBothAsync", 2),
            Map.entry("applyToEitherAsync", 2), Map.entry("acceptEitherAsync", 2), Map.entry("runAfterEitherAsync", 2));

    /** What a file does against a rule, and how often. */
    private record Found(Map<String, Integer> threads, Map<String, Integer> sharedPool, Map<String, Integer> listenerLists,
                         Map<String, Integer> watchers) {
    }

    private static Found found;

    /** A file allowed to break a rule this many times, and why. */
    private record Allowed(int times, String why) {
    }

    // Counted per call: an executor made with a thread factory counts twice. Services that own a thread for a reason of their
    // own (section 4), and those that move onto Workers.
    private static final Map<String, Allowed> THREADS = Map.ofEntries(
            Map.entry("CompanionApplication.java", new Allowed(4, "project switching and the MCP lifecycle")),
            Map.entry("debugger/DebuggerSessionQueue.java", new Allowed(2, "the debugger")),
            Map.entry("debugger/expression/DebuggerEvaluationRunner.java", new Allowed(1, "the debugger")),
            Map.entry("ui/components/global/EditorTabs.java", new Allowed(2, "the editor's Java analysis")),
            Map.entry("script/ScriptCompilationService.java", new Allowed(2, "script compilation")),
            Map.entry("decompile/CompanionDecompilationService.java", new Allowed(3, "decompilation")),
            Map.entry("search/SearchManager.java", new Allowed(2, "search")),
            Map.entry("search/reference/ReferenceSearchService.java", new Allowed(2, "search")),
            Map.entry("search/insight/CodeInsightService.java", new Allowed(2, "search")),
            Map.entry("ui/views/SearchEverywherePopup.java", new Allowed(2, "search")),
            Map.entry("runtime/RuntimeIndexService.java", new Allowed(2, "the runtime index")),
            Map.entry("mcp/CodeModeJobService.java", new Allowed(2, "the MCP job service")),
            Map.entry("session/ProjectSelectionServer.java", new Allowed(2, "accepts connections")),
            Map.entry("inspection/ItemIconService.java", new Allowed(2, "the item icon renderer is confined to one thread")),
            Map.entry("catalog/ConfigChanges.java", new Allowed(2, "the project's write queue, which the pipeline takes over in PR 8")),
            Map.entry("storage/JsonStateWriter.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("ui/components/catalog/TextureThumbnails.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("ui/components/catalog/ModLogoIcons.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("ui/components/editors/ResourceViewPanel.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("util/Workers.java", new Allowed(6, "the file work, the owners' strands and the timer everything shares")),
            Map.entry("util/FileWatch.java", new Allowed(1, "the one watcher of the folders Companion follows")));

    // All move onto Workers' file work in PR 8.
    private static final Map<String, Allowed> SHARED_POOL = Map.ofEntries(
            Map.entry("CompanionApplication.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("inspection/ItemIconService.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/inspection/DataView.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("inspection/InspectionSession.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("model/CodeView.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("navigation/NavigationService.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("script/SnippetExpressionSupport.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("ui/components/PageLoader.java", new Allowed(1, "reads on Workers' file work from PR 7")),
            Map.entry("ui/components/catalog/ModPanel.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/editors/PackResourceEditor.java", new Allowed(2, "its saves move onto Workers in PR 8")),
            Map.entry("ui/components/editors/ResourceTextEditor.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/editors/ScriptPanel.java", new Allowed(3, "moves onto Workers in PR 8")),
            Map.entry("ui/components/global/NotificationWidget.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/global/ProjectSelector.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/treeView/FileTreeView.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/treeView/ScriptFileActions.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/components/treeView/lazyFileTree/LazyFileJTree.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/views/PrismInstancePicker.java", new Allowed(1, "moves onto Workers in PR 8")),
            Map.entry("ui/views/debugger/BreakpointsWindow.java", new Allowed(2, "moves onto Workers in PR 8")),
            Map.entry("ui/views/debugger/DebuggerInspector.java", new Allowed(1, "moves onto Workers in PR 8")));

    // Signal itself; events inside a subsystem or a control, which are not state (section 1).
    private static final Map<String, Allowed> LISTENER_LISTS = Map.ofEntries(
            Map.entry("util/Signal.java", new Allowed(1, "the signal every owner uses")),
            Map.entry("jdt/diagnostics/ASTCache.java", new Allowed(1, "the editor's analysis")),
            Map.entry("debugger/DebuggerSessionController.java", new Allowed(1, "the debugger's session events")),
            Map.entry("debugger/MicrosoftJavaDebugEngine.java", new Allowed(1, "the debugger's session events")),
            Map.entry("ui/components/editors/DebuggerEditorPresentation.java", new Allowed(1, "the debugger's session events")),
            Map.entry("notification/NotificationCenter.java", new Allowed(1, "notifications, an event")),
            Map.entry("script/EditorScriptRunService.java", new Allowed(2, "script runs, an event")),
            Map.entry("session/CompanionSession.java", new Allowed(1, "script results, an event")),
            Map.entry("search/SearchManager.java", new Allowed(2, "search matches, an event")),
            Map.entry("ui/components/SegmentedToggle.java", new Allowed(1, "a control's choice, an event")),
            Map.entry("ui/components/global/EditorTabs.java", new Allowed(1, "the tab chosen, an event")),
            Map.entry("ui/components/inspection/DataView.java", new Allowed(1, "speed search, an event")),
            Map.entry("ui/components/treeView/lazyFileTree/LazyFileJTree.java", new Allowed(1, "a double click, an event")),
            Map.entry("ui/theme/ThemeManager.java", new Allowed(1, "the theme, which stays as it is")),
            Map.entry("pack/ExternalEdits.java", new Allowed(1, "an external save's result, an event")));

    private static final Map<String, Allowed> WATCHERS = Map.ofEntries(
            Map.entry("util/FileWatch.java", new Allowed(1, "the one watcher of the folders Companion follows")));

    @BeforeAll
    static void scan() throws IOException {
        Map<String, Integer> threads = new TreeMap<>();
        Map<String, Integer> sharedPool = new TreeMap<>();
        Map<String, Integer> listenerLists = new TreeMap<>();
        Map<String, Integer> watchers = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            String name = SOURCES.relativize(file).toString().replace('\\', '/');
            CompilationUnit unit = parse(file);
            // A Timer is Swing's, which runs on the Swing thread, unless the file names java.util's.
            boolean utilTimer = unit.imports().stream().anyMatch(imported -> imported.toString().contains("java.util.Timer;"));
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation call) {
                    String method = call.getName().getIdentifier();
                    String target = call.getExpression() instanceof SimpleName simple ? simple.getIdentifier() : "";
                    if (EXECUTOR_FACTORIES.contains(method) && (target.equals("Executors") || call.getExpression() == null)
                            || target.equals("Thread") && Set.of("ofPlatform", "ofVirtual", "startVirtualThread").contains(method)) {
                        threads.merge(name, 1, Integer::sum);
                    }
                    Integer withoutExecutor = ASYNC_WITHOUT_EXECUTOR.get(method);
                    if (withoutExecutor != null && call.arguments().size() == withoutExecutor || method.equals("commonPool")) {
                        sharedPool.merge(name, 1, Integer::sum);
                    }
                    if (method.equals("newWatchService")) watchers.merge(name, 1, Integer::sum);
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation creation) {
                    String type = creation.getType().toString();
                    if (THREAD_TYPES.contains(type) || utilTimer && type.equals("Timer")) threads.merge(name, 1, Integer::sum);
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration type) {
                    if (type.getSuperclassType() != null && THREAD_TYPES.contains(type.getSuperclassType().toString())) {
                        threads.merge(name, 1, Integer::sum);
                    }
                    return true;
                }

                @Override
                public boolean visit(FieldDeclaration field) {
                    if (!LISTENER_COLLECTION.matcher(field.getType().toString()).find()) return true;
                    for (Object fragment : field.fragments()) {
                        String variable = ((VariableDeclarationFragment) fragment).getName().getIdentifier();
                        if (LISTENER_NAME.matcher(variable.toLowerCase(Locale.ROOT)).matches()) listenerLists.merge(name, 1, Integer::sum);
                    }
                    return true;
                }
            });
        }
        found = new Found(threads, sharedPool, listenerLists, watchers);
    }

    private static CompilationUnit parse(Path file) throws IOException {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
        parser.setCompilerOptions(options);
        parser.setSource(Files.readString(file, StandardCharsets.UTF_8).toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    @Test
    void threadsAndExecutorsComeFromTheirOwners() {
        check("creates a thread or executor", found.threads(), THREADS);
    }

    @Test
    void workRunsOnANamedWorkerNotTheSharedPool() {
        check("runs async work on the shared pool", found.sharedPool(), SHARED_POOL);
    }

    @Test
    void stateIsFollowedThroughSignals() {
        check("keeps a list of listeners", found.listenerLists(), LISTENER_LISTS);
    }

    @Test
    void filesAreWatchedInOnePlace() {
        check("watches files", found.watchers(), WATCHERS);
    }

    private static void check(String breaks, Map<String, Integer> actual, Map<String, Allowed> allowed) {
        List<String> problems = new ArrayList<>();
        actual.forEach((file, times) -> {
            Allowed exception = allowed.get(file);
            if (exception == null) problems.add(file + " " + breaks + " " + times + " times; use the system of docs/SYSTEMS.md");
            else if (times > exception.times()) problems.add(file + " " + breaks + " " + times + " times, " + exception.times() + " allowed");
        });
        allowed.forEach((file, exception) -> {
            int times = actual.getOrDefault(file, 0);
            if (times < exception.times()) problems.add(file + " " + breaks + " " + times + " times now, not " + exception.times()
                    + "; lower its exception, or remove it at 0");
        });
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }
}
