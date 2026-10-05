package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionRuns;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.content.DefinitionDetails;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.SubjectPanel;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An inspection reads the game only for a page that is shown (docs/HIDDEN_READS.md). */
@UiTest
class InspectionReadsTest {
    private static final InspectSubjectPayload SUBJECT = new InspectSubjectPayload("game-session",
            "block minecraft:overworld 12 64 -3", new SubjectIdentity(SubjectIdentity.Kind.BLOCK,
            "minecraft:furnace", "Furnace", "Minecraft", List.of(), "minecraft:furnace"), "minecraft:item/furnace", Map.of());

    @TempDir Path directory;

    @Test
    void theCompilerBecomingReadyAsksForAReadThatSwitchingLiveReadingOffKeeps() throws Exception {
        ScriptCompilationService compiler = new ScriptCompilationService(message -> false);
        CompanionSession connection = new CompanionSession("inspection-reads-test-token");
        ExecutionRuns runs = new ExecutionRuns(connection, new ScriptExecutionService(connection, compiler, () -> true));
        ProjectScope project = new ProjectScope(new Object(), new CompanionProfile("project", this.directory, this.directory),
                InstanceState.inMemory(), ChangeRecord.inMemory());
        SnippetExecutionService snippets = new SnippetExecutionService(runs, project);
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger wanted = new AtomicInteger();
        InspectionSession[] session = new InspectionSession[1];
        try {
            UiTestScope.onEdt(() -> {
                session[0] = new InspectionSession(SUBJECT, () -> {
                    asked.incrementAndGet();
                    return snippets;
                }, () -> { throw new IllegalStateException("No project tools"); }, state -> { });
                session[0].readWanted().subscribe(wanted::incrementAndGet);
                // The runtime index is not loaded: the first live read waits for the compiler.
                session[0].setLive(true, 60_000);
            });
            assertEquals(1, asked.get(), "the read asked whether it can run");

            compiler.serverAccess("", "Waiting for the server");
            UiTestScope.await(() -> wanted.get() == 1);
            Thread.sleep(200);
            assertEquals(1, asked.get(), "the session does not read itself; its page reads once it is shown");

            // Live reading switched off before the page read: the read the compiler asked for is still wanted.
            UiTestScope.onEdt(() -> {
                session[0].setLive(false, 60_000);
                session[0].readIfWanted();
            });
            assertEquals(2, asked.get(), "the read that waited for the compiler runs");
        } finally {
            UiTestScope.onEdt(() -> { if (session[0] != null) session[0].dispose(); });
            runs.close();
            compiler.close();
            connection.close();
            project.retire();
            project.close();
        }
    }

    @Test
    void aHiddenLivePageReadsNothingUntilItIsShown() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (ItemIconService icons = new ItemIconService()) {
            SubjectPanel panel = panel(icons, reads);
            try {
                // Read Live, switched on while the page is not shown: that read is the user's; the next ones wait.
                UiTestScope.onEdt(() -> panel.session().setLive(true, 500));
                int asked = reads.get();
                Thread.sleep(1_600);
                assertEquals(asked, reads.get(), "a hidden live page reads nothing, however many intervals pass");

                UiTestScope.onEdt(() -> UiTestScope.showPages(panel));
                UiTestScope.await(() -> reads.get() > asked);
            } finally {
                UiTestScope.onEdt(panel::dispose);
            }
        }
    }

    @Test
    void aNavigationShowingAHiddenLivePageReadsItOnce() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (ItemIconService icons = new ItemIconService()) {
            SubjectPanel panel = panel(icons, reads);
            try {
                UiTestScope.onEdt(() -> panel.session().setLive(true, 300));
                Thread.sleep(700);
                // The live interval passed while hidden; the next one is far off.
                UiTestScope.onEdt(() -> panel.session().setLive(true, 60_000));
                int asked = reads.get();

                // As NavigationService does when the game inspects the subject again: show the page, then read it anew.
                UiTestScope.onEdt(() -> {
                    UiTestScope.showPages(panel);
                    panel.session().refresh();
                });
                // A read asks for the snippets twice: whether the side can run, then to run.
                UiTestScope.await(() -> reads.get() >= asked + 2);
                assertEquals(asked + 2, reads.get(), "the navigation's read answers the read the live interval asked for");
            } finally {
                UiTestScope.onEdt(panel::dispose);
            }
        }
    }

    @Test
    void aReadTheSessionNoLongerWantsIsNotStartedWhenThePageIsShown() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (ItemIconService icons = new ItemIconService()) {
            SubjectPanel panel = panel(icons, reads);
            try {
                UiTestScope.onEdt(() -> panel.session().setLive(true, 500));
                Thread.sleep(900);
                // The live interval passed while hidden; then live reading is switched off.
                UiTestScope.onEdt(() -> panel.session().setLive(false, 500));
                int asked = reads.get();

                UiTestScope.onEdt(() -> UiTestScope.showPages(panel));
                Thread.sleep(500);
                assertEquals(asked, reads.get(), "the read live reading wanted is not started once live is off");
            } finally {
                UiTestScope.onEdt(panel::dispose);
            }
        }
    }

    /** An inspection page, not shown, whose every read counts in {@code reads} and fails, as no game runs. */
    private SubjectPanel panel(ItemIconService icons, AtomicInteger reads) throws Exception {
        return UiTestScope.onEdt(() -> SubjectPanel.occurrence(SUBJECT,
                () -> {
                    reads.incrementAndGet();
                    throw new IllegalStateException("Tests run no snippets");
                },
                () -> { throw new IllegalStateException("No project tools"); },
                new DefinitionDetails.Services(new PackCatalogService(new InstancePaths(this.directory)),
                        RuntimeSourceCatalog::empty, icons, target -> { }, new Signal())));
    }
}
