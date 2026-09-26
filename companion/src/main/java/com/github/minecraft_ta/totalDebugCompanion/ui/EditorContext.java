package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import java.awt.Window;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.concurrent.Executor;

/** The collaborators shared by Java editors in one project. */
public record EditorContext(ASTCache astCache, Executor analysisExecutor, Window owner, ProjectScope project, CodeInsightService insights,
                            DebuggerSessionController debugger, NavigationService navigation,
                            ScriptExecutionService scripts, NotificationCenter notifications, EditorScriptRunService editorRuns,
                            BiConsumer<DebugEngine.StackFrame, DebugEngine.Variable> inspectVariable,
                            Supplier<SnippetExecutionService> snippets, ItemIconService itemIcons) { }
