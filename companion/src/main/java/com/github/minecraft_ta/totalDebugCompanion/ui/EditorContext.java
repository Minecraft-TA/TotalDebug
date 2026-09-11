package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import java.awt.Window;
import java.util.function.BiConsumer;

/** The collaborators shared by Java editors in one project. */
public record EditorContext(ASTCache astCache, Window owner, ProjectScope project, CodeInsightService insights,
                            DebuggerSessionController debugger, NavigationService navigation,
                            ScriptExecutionService scripts, CompanionSession session,
                            BiConsumer<DebugEngine.StackFrame, DebugEngine.Variable> inspectVariable) { }
