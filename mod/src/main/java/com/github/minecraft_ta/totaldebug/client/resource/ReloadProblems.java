package com.github.minecraft_ta.totaldebug.client.resource;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Collects the warnings and errors logged during a reload that name an edited resource, by its path or by the
 * resource locations the game uses for it, such as {@code ns:block/slab} for {@code assets/ns/models/block/slab.json}.
 * Every thread's log is watched while the collector is open, since reloads run on worker threads.
 */
final class ReloadProblems extends AbstractAppender implements AutoCloseable {
    private static final int MAX_PROBLEMS = 64;

    private final Set<String> names;
    private final Set<String> problems = new LinkedHashSet<>();

    private ReloadProblems(Set<String> names) {
        super("TotalDebugReloadProblems", null, null, true, Property.EMPTY_ARRAY);
        this.names = names;
    }

    /** Starts collecting problems about {@code paths}; closing stops it. */
    static ReloadProblems open(List<String> paths) {
        Set<String> names = new LinkedHashSet<>();
        for (String path : paths) names.addAll(names(path));
        ReloadProblems collector = new ReloadProblems(names);
        collector.start();
        if (LogManager.getContext(false) instanceof LoggerContext context) {
            context.getConfiguration().getRootLogger().addAppender(collector, Level.WARN, null);
            context.updateLoggers();
        }
        return collector;
    }

    /** The texts that identify a resource at {@code path} in log messages, in lowercase. */
    static Set<String> names(String path) {
        Set<String> names = new LinkedHashSet<>();
        String[] parts = path.split("/", 3);
        if (parts.length < 3) return names;
        String namespace = parts[1];
        String inside = parts[2];
        names.add((namespace + "/" + inside).toLowerCase(Locale.ROOT));
        names.add((namespace + ":" + inside).toLowerCase(Locale.ROOT));
        int dot = inside.lastIndexOf('.');
        String withoutExtension = dot > 0 ? inside.substring(0, dot) : inside;
        names.add((namespace + ":" + withoutExtension).toLowerCase(Locale.ROOT));
        int slash = withoutExtension.indexOf('/');
        // Models, textures and similar folders are named without their folder, as in ns:block/slab.
        if (slash > 0) names.add((namespace + ":" + withoutExtension.substring(slash + 1)).toLowerCase(Locale.ROOT));
        return names;
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Throwable thrown = event.getThrown();
        String detail = thrown == null ? "" : thrown.toString();
        String text = (message + " " + detail).toLowerCase(Locale.ROOT);
        for (String name : this.names) {
            if (!mentions(text, name)) continue;
            synchronized (this.problems) {
                if (this.problems.size() < MAX_PROBLEMS) this.problems.add(detail.isEmpty() ? message : message + ": " + detail);
            }
            return;
        }
    }

    /** Whether {@code text} names {@code name} whole, not as the start of a longer name such as {@code ns:gear_box}. */
    static boolean mentions(String text, String name) {
        for (int index = text.indexOf(name); index >= 0; index = text.indexOf(name, index + 1)) {
            int end = index + name.length();
            if (end == text.length()) return true;
            char next = text.charAt(end);
            if (!Character.isLetterOrDigit(next) && next != '_' && next != '/' && next != '-') return true;
        }
        return false;
    }

    List<String> problems() {
        synchronized (this.problems) {
            return new ArrayList<>(this.problems);
        }
    }

    @Override
    public void close() {
        if (LogManager.getContext(false) instanceof LoggerContext context) {
            context.getConfiguration().getRootLogger().removeAppender(getName());
            context.updateLoggers();
        }
        stop();
    }
}
