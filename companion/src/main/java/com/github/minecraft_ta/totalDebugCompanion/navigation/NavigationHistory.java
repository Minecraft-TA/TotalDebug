package com.github.minecraft_ta.totalDebugCompanion.navigation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class NavigationHistory {
    enum Direction {
        BACK,
        FORWARD
    }

    private final int limit;
    private final Deque<NavigationEntry> back = new ArrayDeque<>();
    private final Deque<NavigationEntry> forward = new ArrayDeque<>();

    NavigationHistory(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    synchronized void recordNewNavigation(NavigationEntry origin) {
        if (origin != null && !origin.equals(this.back.peekFirst())) {
            this.back.addFirst(origin);
            trim(this.back);
        }
        this.forward.clear();
    }

    synchronized void clear() {
        this.back.clear();
        this.forward.clear();
    }

    synchronized NavigationEntry destination(Direction direction, String runtimeSignature) {
        Deque<NavigationEntry> source = source(direction);
        while (!source.isEmpty() && !source.peekFirst().isValidForRuntime(runtimeSignature)) {
            source.removeFirst();
        }
        return source.peekFirst();
    }

    synchronized void complete(Direction direction, NavigationEntry destination, NavigationEntry origin) {
        Objects.requireNonNull(destination, "destination");
        Deque<NavigationEntry> source = source(direction);
        if (!destination.equals(source.peekFirst())) {
            throw new IllegalStateException("History destination changed during navigation");
        }
        source.removeFirst();
        if (origin != null) {
            Deque<NavigationEntry> opposite = source(opposite(direction));
            if (!origin.equals(opposite.peekFirst())) {
                opposite.addFirst(origin);
                trim(opposite);
            }
        }
    }

    synchronized void discard(Direction direction, NavigationEntry destination) {
        Deque<NavigationEntry> source = source(direction);
        if (destination.equals(source.peekFirst())) {
            source.removeFirst();
        }
    }

    synchronized boolean canNavigate(Direction direction, String runtimeSignature) {
        return destination(direction, runtimeSignature) != null;
    }

    private Deque<NavigationEntry> source(Direction direction) {
        return direction == Direction.BACK ? this.back : this.forward;
    }

    private static Direction opposite(Direction direction) {
        return direction == Direction.BACK ? Direction.FORWARD : Direction.BACK;
    }

    private void trim(Deque<NavigationEntry> entries) {
        while (entries.size() > this.limit) {
            entries.removeLast();
        }
    }
}
