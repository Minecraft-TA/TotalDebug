package com.github.minecraft_ta.totaldebug.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects the warnings and errors logged during a reload that name an edited resource, by its path or by the
 * resource locations the game uses for it, such as {@code ns:block/slab} for {@code assets/ns/models/block/slab.json}.
 * Every thread's log is watched while the collector is open, since reloads run on worker threads. Collectors of reloads
 * that run at once, such as of the client's resources and a world's data, each watch under a name of their own.
 */
public final class ReloadProblems extends AbstractAppender implements AutoCloseable {
    private static final int MAX_PROBLEMS = 64;
    private static final AtomicInteger COLLECTORS = new AtomicInteger();

    /** The watched paths each name identifies; one id, such as {@code ns:gear}, can stand for several kinds of file. */
    private final Map<String, List<String>> names;
    private final Set<ReloadResultPayload.Problem> problems = new LinkedHashSet<>();

    private ReloadProblems(Map<String, List<String>> names) {
        // The logger keeps its appenders by name, so a second collector under the same name would replace the first.
        super("TotalDebugReloadProblems-" + COLLECTORS.incrementAndGet(), null, null, true, Property.EMPTY_ARRAY);
        this.names = names;
    }

    /** Starts collecting problems about {@code paths}; closing stops it. */
    public static ReloadProblems open(List<String> paths) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String path : paths) {
            for (String name : names(path)) names.computeIfAbsent(name, ignored -> new ArrayList<>()).add(path);
        }
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
        for (Map.Entry<String, List<String>> name : this.names.entrySet()) {
            if (!mentions(text, name.getKey())) continue;
            synchronized (this.problems) {
                for (String path : name.getValue()) {
                    if (this.problems.size() < MAX_PROBLEMS) {
                        this.problems.add(new ReloadResultPayload.Problem(path, detail.isEmpty() ? message : message + ": " + detail));
                    }
                }
            }
            return;
        }
    }

    /**
     * Whether {@code text} names {@code name} whole: not as the start of a longer name such as {@code ns:gear_box}, nor as
     * the end of one such as {@code otherns:block/gear}.
     */
    static boolean mentions(String text, String name) {
        for (int index = text.indexOf(name); index >= 0; index = text.indexOf(name, index + 1)) {
            int end = index + name.length();
            // A namespace can follow a folder, as in assets/ns/..., but not end in letters of another namespace.
            char before = index == 0 ? ' ' : text.charAt(index - 1);
            boolean startsWhole = !Character.isLetterOrDigit(before) && before != '_' && before != '-' && before != '.';
            // A sentence's full stop can follow a name, but a longer path cannot.
            char after = end == text.length() ? ' ' : text.charAt(end);
            boolean endsWhole = !Character.isLetterOrDigit(after) && after != '_' && after != '/' && after != '-';
            if (startsWhole && endsWhole) return true;
        }
        return false;
    }

    public List<ReloadResultPayload.Problem> problems() {
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
