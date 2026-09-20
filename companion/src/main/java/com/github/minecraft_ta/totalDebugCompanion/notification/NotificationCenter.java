package com.github.minecraft_ta.totalDebugCompanion.notification;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Application-session history. Subscribers receive immutable, versioned snapshots outside the lock. */
public final class NotificationCenter implements AutoCloseable {
    public enum Severity { INFORMATION, SUCCESS, WARNING, ERROR }
    public record Source(String label, String projectId, String projectDirectory, NavigationTarget target, String runtimeSignature) {
        public static Source application(String label) { return new Source(label, null, null, null, null); }
        public static Source capture(ProjectScope project, String label, NavigationTarget target) {
            return new Source(label, project == null ? null : project.profile().id(),
                    project == null ? null : project.profile().workspaceDirectory().toString(), target,
                    target == null || target instanceof NavigationTarget.LocalFile || target instanceof NavigationTarget.LocalDirectory || project == null
                            ? null : project.runtimeSignature());
        }
    }
    public record Entry(long id, Instant time, Severity severity, String message, String details, Source source, boolean read, boolean shownAtSource) {
        public String copyText() {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(time)
                    + "\n" + source.label() + (source.projectDirectory() == null ? "" : "\nProject: " + source.projectDirectory())
                    + "\n" + message + (details.isEmpty() ? "" : "\n" + details);
        }
    }
    public record Snapshot(long revision, List<Entry> entries) {
        public Entry preview() {
            return entries.stream().filter(entry -> !entry.read()).filter(entry -> entry.severity() == Severity.ERROR || entry.severity() == Severity.WARNING)
                    .findFirst().orElseGet(() -> entries.stream().filter(entry -> !entry.read()).findFirst().orElse(null));
        }
        public long unread() { return entries.stream().filter(entry -> !entry.read()).count(); }
    }

    private static final int LIMIT = 100;
    private static final Logger LOGGER = Logger.getLogger(NotificationCenter.class.getName());
    private static final int TEXT_LIMIT = 16 * 1024;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Consumer<Snapshot>> listeners = new ArrayList<>();
    private long nextId;
    private long revision;
    private boolean closed;

    public long publish(Severity severity, String message, String details, Source source) {
        return publish(severity, message, details, source, false);
    }

    /** Mark outcomes also displayed by their editor, so that view can avoid duplicate alerts. */
    public long publish(Severity severity, String message, String details, Source source, boolean shownAtSource) {
        long id;
        synchronized (this) {
            if (closed) return 0;
            id = ++nextId;
            entries.addFirst(new Entry(id, Instant.now(), Objects.requireNonNull(severity), bounded(message), bounded(details),
                    Objects.requireNonNull(source), false, shownAtSource));
            if (entries.size() > LIMIT) entries.removeLast();
            revision++;
        }
        changed();
        return id;
    }

    /** Updating a removed or acknowledged event neither resurrects it nor marks it unread. */
    public void update(long id, String details) {
        synchronized (this) {
            if (closed) return;
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.id() != id) continue;
                entries.set(i, new Entry(id, entry.time(), entry.severity(), entry.message(), bounded(details), entry.source(), entry.read(), entry.shownAtSource()));
                revision++;
                break;
            }
        }
        changed();
    }

    public void acknowledge(Set<Long> ids) {
        synchronized (this) {
            if (closed) return;
            entries.replaceAll(entry -> ids.contains(entry.id()) && !entry.read()
                    ? new Entry(entry.id(), entry.time(), entry.severity(), entry.message(), entry.details(), entry.source(), true, entry.shownAtSource()) : entry);
            revision++;
        }
        changed();
    }

    public void dismiss(long id) {
        synchronized (this) { if (closed) return; entries.removeIf(entry -> entry.id() == id); revision++; }
        changed();
    }

    public void clear() {
        synchronized (this) { if (closed) return; entries.clear(); revision++; }
        changed();
    }

    public synchronized Snapshot snapshot() { return new Snapshot(revision, List.copyOf(entries)); }

    public Runnable subscribe(Consumer<Snapshot> listener) {
        Snapshot initial;
        synchronized (this) {
            if (closed) return () -> {};
            listeners.add(listener);
            initial = snapshot();
        }
        listener.accept(initial);
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }

    private void changed() {
        Snapshot snapshot;
        List<Consumer<Snapshot>> targets;
        synchronized (this) { snapshot = snapshot(); targets = List.copyOf(listeners); }
        for (Consumer<Snapshot> target : targets) {
            try { target.accept(snapshot); }
            catch (RuntimeException failure) { LOGGER.log(Level.WARNING, "Unable to update notification view", failure); }
        }
    }

    private static String bounded(String text) {
        text = Objects.requireNonNullElse(text, "");
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT) + "\n[Truncated]";
    }

    @Override public synchronized void close() { closed = true; listeners.clear(); entries.clear(); }
}
