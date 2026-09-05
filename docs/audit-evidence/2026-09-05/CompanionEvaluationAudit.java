import com.github.minecraft_ta.totalDebugCompanion.debugger.*;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.*;
import com.github.minecraft_ta.totalDebugCompanion.debugger.harness.DebuggerTestHarness;
import java.util.concurrent.TimeUnit;

public class CompanionEvaluationAudit {
    public static void main(String[] args) throws Exception {
        try (var h = DebuggerTestHarness.launch(RichExpressionDebuggeeMain.class)) {
            h.setBreakpoints(new DebugEngine.SourceBreakpoint(h.lineContaining("DEBUG_RICH_EXPRESSION") + 1));
            h.start();
            var f = h.firstFrame(h.awaitStop("audit").threadId());
            check(h.engine(), f, "target.nullOverload((Object) null)");
            check(h.engine(), f, "target.nullOverload((Object) \"x\")");
            check(h.engine(), f, "\"\" + warmedBoxingType");
            check(h.engine(), f, "true ? 1 : 2L");
            check(h.engine(), f, "warmedBoxingType + 1");
        }
        try (var h = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            h.setBreakpoints(new DebugEngine.SourceBreakpoint(h.lineContaining("EVALUATION_STOP")));
            h.start();
            var f = h.firstFrame(h.awaitStop("audit").threadId());
            check(h.engine(), f, "{ int calls = 2; } return calls;");
            check(h.engine(), f, "return calls;");
            for (int i = 0; i < 140; i++) h.engine().evaluate("\"audit" + i + "\"", f.id()).get(10, TimeUnit.SECONDS);
            var expressionField = MicrosoftJavaDebugEngine.class.getDeclaredField("expressionEngine");
            expressionField.setAccessible(true);
            var expressionEngine = expressionField.get(h.engine());
            var pinnedField = expressionEngine.getClass().getDeclaredField("pinned");
            pinnedField.setAccessible(true);
            var runnerField = expressionEngine.getClass().getDeclaredField("evaluations");
            runnerField.setAccessible(true);
            var runner = runnerField.get(expressionEngine);
            var historyField = runner.getClass().getDeclaredField("history");
            historyField.setAccessible(true);
            System.out.println("AUDIT retention history=" + ((java.util.Map<?,?>) historyField.get(runner)).size()
                    + " targetPins=" + ((java.util.Set<?>) pinnedField.get(expressionEngine)).size());
        }
    }
    private static void check(DebugEngine e, DebugEngine.StackFrame f, String source) {
        try {
            var v = e.evaluate(source, f.id()).get(10, TimeUnit.SECONDS);
            System.out.println("AUDIT " + source + " => " + v.type() + " " + v.value());
        } catch (Exception ex) { System.out.println("AUDIT " + source + " => ERROR " + ex.getCause()); }
    }
}
