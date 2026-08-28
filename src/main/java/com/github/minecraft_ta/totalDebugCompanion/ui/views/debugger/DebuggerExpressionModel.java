package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.DebugValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Per-frame expression/watch results and exactly-once submission state. */
final class DebuggerExpressionModel {
    record Key(String expression, boolean watch) {
        Key {
            expression = Objects.requireNonNull(expression, "expression");
        }
    }

    record Outcome(DebugValue value, String failure) {
        static Outcome success(DebugValue value) {
            return new Outcome(Objects.requireNonNull(value, "value"), null);
        }

        static Outcome failure(String failure) {
            return new Outcome(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private final Set<String> watches = new LinkedHashSet<>(GlobalConfig.getInstance().debuggerWatches());
    private final Set<Key> submitted = new HashSet<>();
    private final Map<Key, Outcome> outcomes = new HashMap<>();
    private String retainedExpression = "";

    List<Key> rows() {
        List<Key> rows = new ArrayList<>(this.watches.size() + 1);
        if (!this.retainedExpression.isBlank()) {
            rows.add(new Key(this.retainedExpression, false));
        }
        this.watches.stream().map(watch -> new Key(watch, true)).forEach(rows::add);
        return rows;
    }

    Outcome outcome(Key key) {
        return this.outcomes.get(key);
    }

    boolean submitOnce(Key key) {
        return this.submitted.add(key);
    }

    void complete(Key key, Outcome outcome) {
        this.outcomes.put(key, Objects.requireNonNull(outcome, "outcome"));
    }

    Key beginExplicit(String expression) {
        String normalized = Objects.requireNonNull(expression, "expression").trim();
        if (!normalized.equals(this.retainedExpression)) {
            Key previous = new Key(this.retainedExpression, false);
            this.submitted.remove(previous);
            this.outcomes.remove(previous);
        }
        this.retainedExpression = normalized;
        Key key = new Key(normalized, false);
        this.submitted.add(key);
        this.outcomes.remove(key);
        return key;
    }

    void addWatch(String expression) {
        String normalized = Objects.requireNonNull(expression, "expression").trim();
        if (normalized.isEmpty() || !this.watches.add(normalized)) {
            return;
        }
        persistWatches();
    }

    void remove(Key key) {
        if (key.watch()) {
            if (this.watches.remove(key.expression())) {
                persistWatches();
            }
        } else if (key.expression().equals(this.retainedExpression)) {
            this.retainedExpression = "";
        }
        this.submitted.remove(key);
        this.outcomes.remove(key);
    }

    void nextFrame() {
        this.submitted.clear();
        this.outcomes.clear();
    }

    void clearSession() {
        nextFrame();
        this.retainedExpression = "";
    }

    private void persistWatches() {
        GlobalConfig.getInstance().setDebuggerWatches(List.copyOf(this.watches));
    }
}
