package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.harness.DebuggerScenarios;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MicrosoftJavaDebugEngineIntegrationTest {
    @Test
    @Timeout(30)
    void attachesBreaksInspectsStepsAndResumes() throws Exception {
        DebuggerScenarios.breakpointInspectionAndStep();
    }

    @Test
    @Timeout(30)
    void stepsIntoAndOutOfAMethod() throws Exception {
        DebuggerScenarios.stepIntoAndOut();
    }

    @Test
    @Timeout(30)
    void evaluatesConditionalAndHitCountBreakpoints() throws Exception {
        DebuggerScenarios.conditionalAndHitCountBreakpoints();
    }

    @Test
    @Timeout(30)
    void evaluatesRichExpressionsAndCompletesRuntimeMembers() throws Exception {
        DebuggerScenarios.richExpressions();
    }

    @Test
    @Timeout(30)
    void pausesARunningThreadAndInspectsItsFrame() throws Exception {
        DebuggerScenarios.pauseAndDetach();
    }

    @Test
    @Timeout(30)
    void stopsOnAndDescribesAnUncaughtException() throws Exception {
        DebuggerScenarios.uncaughtException();
    }

    @Test
    @Timeout(30)
    void mapsBreakpointsAndFramesToVineflowerSourceLines() throws Exception {
        DebuggerScenarios.decompiledSourceLineMapping();
    }

    @Test
    @Timeout(30)
    void resolvesSourceForAStackFrameWhoseClassWasNotPreviouslyOpened() throws Exception {
        DebuggerScenarios.unopenedCallerFrameNavigation();
    }

    @Test
    @Timeout(30)
    void attachesDetachesAndReattachesByPublishedProcessId() throws Exception {
        DebuggerScenarios.lateAttach();
    }
}
