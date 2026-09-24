package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionRuns;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import org.junit.jupiter.api.AfterEach;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class StatusBarTestFixture {
    protected final NotificationCenter notifications = new NotificationCenter();
    private final CompanionSession session = new CompanionSession("status-bar-test-token");
    private final ScriptCompilationService compiler = new ScriptCompilationService(message -> false, message -> false);
    private final ExecutionRuns executions = new ExecutionRuns(session, new ScriptExecutionService(session, compiler, () -> false));
    private final EditorScriptRunService runs = new EditorScriptRunService(executions, notifications);
    private final List<ApplicationStatusBar> bars = new ArrayList<>();

    protected ApplicationStatusBar statusBar(Consumer<NavigationTarget> navigate) {
        var bar = new ApplicationStatusBar(navigate, () -> {}, notifications, runs, source -> "No source", source -> {});
        bars.add(bar);
        return bar;
    }

    @AfterEach void closeStatusBars() throws Exception {
        SwingUtilities.invokeAndWait(() -> bars.forEach(ApplicationStatusBar::dispose));
        executions.close();
        runs.close();
        compiler.close();
        session.close();
        notifications.close();
    }
}
