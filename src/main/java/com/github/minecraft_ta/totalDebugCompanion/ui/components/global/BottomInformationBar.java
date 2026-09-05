package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Mutable editor-status model rendered by the single application status bar. */
public final class BottomInformationBar {
    public enum Style {
        PLAIN,
        INFORMATION,
        PROCESS,
        SUCCESS,
        FAILURE
    }

    public record State(String text, Style style) {
        public State {
            text = Objects.requireNonNullElse(text, "");
            Objects.requireNonNull(style, "style");
        }
    }

    private final CopyOnWriteArrayList<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = new State("", Style.PLAIN);

    public State state() {
        return this.state;
    }

    public void addListener(Consumer<State> listener) {
        Consumer<State> checked = Objects.requireNonNull(listener, "listener");
        this.listeners.add(checked);
        checked.accept(this.state);
    }

    public void removeListener(Consumer<State> listener) {
        this.listeners.remove(listener);
    }

    public void setMutedInfoText(String text) {
        update(new State(text, Style.PLAIN));
    }

    public void setDefaultInfoText(String text) {
        update(new State(text, Style.INFORMATION));
    }

    public void setProcessInfoText(String text) {
        update(new State(text, Style.PROCESS));
    }

    public void setSuccessInfoText(String text) {
        update(new State(text, Style.SUCCESS));
    }

    public void setFailureInfoText(String text) {
        update(new State(text, Style.FAILURE));
    }

    private void update(State replacement) {
        this.state = replacement;
        for (Consumer<State> listener : this.listeners) {
            listener.accept(replacement);
        }
    }
}
