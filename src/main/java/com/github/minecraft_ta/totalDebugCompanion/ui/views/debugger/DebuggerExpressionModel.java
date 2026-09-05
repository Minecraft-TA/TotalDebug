package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
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

    private final Set<String> watches = new LinkedHashSet<>(CompanionApp.instanceState().debuggerWatches());
    private Set<Key> submitted = new HashSet<>();
    private Map<Key, Outcome> outcomes = new HashMap<>();
    private final Map<Integer, FrameResults> frames = new HashMap<>();
    private FrameResults current = new FrameResults(this.submitted, this.outcomes);
    private String pauseId;
    private static final class FrameResults {
        private final Set<Key> submitted;
        private final Map<Key, Outcome> outcomes;
        private final Map<Key, DebuggerValueLease> values = new HashMap<>();
        private final Map<Key, Object> requests = new HashMap<>();
        private boolean retained = true;
        private FrameResults(Set<Key> submitted, Map<Key, Outcome> outcomes) {
            this.submitted = submitted;
            this.outcomes = outcomes;
        }
        private void remove(Key key) {
            this.submitted.remove(key);
            this.outcomes.remove(key);
            this.requests.remove(key);
            DebuggerValueLease value = this.values.remove(key);
            if (value != null) value.close();
        }
        private void close() {
            this.retained = false;
            this.values.values().forEach(DebuggerValueLease::close);
            this.values.clear();
            this.outcomes.clear();
            this.submitted.clear();
            this.requests.clear();
        }
    }
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
            this.frames.values().forEach(frame -> frame.remove(previous));
            this.current.remove(previous);
        }
        this.retainedExpression = normalized;
        Key key = new Key(normalized, false);
        this.current.remove(key);
        this.submitted.add(key);
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
        this.frames.values().forEach(frame -> frame.remove(key));
        this.current.remove(key);
    }

    void nextFrame(String pauseId, int frameId) {
        if (!Objects.equals(this.pauseId, pauseId)) {
            this.frames.values().forEach(FrameResults::close);
            this.current.close();
            this.frames.clear();
            this.pauseId = pauseId;
        }
        FrameResults results = this.frames.computeIfAbsent(frameId,
                ignored -> new FrameResults(new HashSet<>(), new HashMap<>()));
        this.current = results;
        this.submitted = results.submitted;
        this.outcomes = results.outcomes;
    }

    java.util.function.BiFunction<Outcome, DebuggerValueLease, Boolean> completionFor(Key key) {
        FrameResults destination = this.current;
        Object request = new Object();
        destination.requests.put(key, request);
        return (outcome, retained) -> {
            if (!destination.retained || destination.requests.get(key) != request || !destination.submitted.contains(key)) {
                retained.close();
                return false;
            }
            DebuggerValueLease previous = destination.values.put(key, retained);
            if (previous != null) previous.close();
            destination.outcomes.put(key, outcome);
            return true;
        };
    }

    void clearSession() {
        this.frames.values().forEach(FrameResults::close);
        this.current.close();
        this.frames.clear();
        this.submitted = new HashSet<>();
        this.outcomes = new HashMap<>();
        this.current = new FrameResults(this.submitted, this.outcomes);
        this.pauseId = null;
        this.retainedExpression = "";
    }

    private void persistWatches() {
        CompanionApp.instanceState().setDebuggerWatches(List.copyOf(this.watches));
    }
}
