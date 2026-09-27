package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's logs and crash reports: {@code logs/latest.log}, {@code logs/debug.log} and {@code crash-reports/*.txt}.
 * A log is read for its warnings and errors, each with the lines that follow it, such as a stack trace. A crash report
 * is read for its description, its exceptions and their stack frames, each frame with the mod it belongs to, and the
 * mods that failed to load. Offsets count characters as the file holds them, line breaks included, so an editor opens
 * the file at the entry. Blocking.
 */
public final class GameLogs {
    /** At most this many entries are kept per file; the counts still cover every entry. */
    public static final int MAX_ENTRIES = 2_000;
    private static final int MAX_ENTRY_LINES = 200;
    /** {@code [26Sept2026 15:58:19.545] [main/WARN] [logger/MARKER]: message} */
    private static final Pattern LOG_LINE = Pattern.compile("^\\[([^]]+)] \\[(.+)/([A-Z]+)] \\[([^]]*)]: ?(.*)$");
    /**
     * {@code at TRANSFORMER/total_debug@2.0.0/com.example.Type.method(Type.java:64) ~[...]}: a layer and a module, each
     * optional, the module with or without its version, then the frame.
     */
    private static final Pattern FRAME = Pattern.compile(
            "^\\s+at (?:([^/\\s]+)/)?(?:([^/\\s]+)/)?([\\w$.]+)\\.([\\w$<>]+)\\(([^)]*)\\).*$");
    /** An exception's first line: a qualified class name, maybe with a message, maybe as a cause. */
    private static final Pattern EXCEPTION = Pattern.compile("^(?:Caused by: |Suppressed: )?[\\w$]+(?:\\.[\\w$]+)+(?::.*)?$");
    /** A mixin handler merged into another class, named {@code handler$zfe000$sodium$onTick}: the third part is its mod. */
    private static final Pattern MIXIN_HANDLER = Pattern.compile("^[a-z]+\\$[a-z0-9]+\\$([a-z][a-z0-9_]*)\\$.+$");
    /** {@code -- Mod loading issue for: total_debug --} in a crash report of failed mod loading. */
    private static final String MOD_ISSUE = "-- Mod loading issue for: ";

    /** A log file or crash report, with when it was written and how large it is. */
    public record LogFile(Path path, Kind kind, FileTime modified, long size) {
        public LogFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(modified, "modified");
        }

        public String name() {
            return this.path.getFileName().toString();
        }
    }

    public enum Kind { LOG, CRASH_REPORT }

    public enum Level { WARN, ERROR, FATAL }

    /** A warning or error of a log: its level, time, thread and logger, its first line, all its lines, and where it starts. */
    public record LogEntry(Level level, String time, String thread, String logger, String message, String text, int offset) {
    }

    /**
     * A stack frame of a crash report: the class and method, the file and line, the mod it belongs to, or empty, and
     * where the line starts.
     */
    public record Frame(String className, String method, String source, String modId, int offset) {
        /** The class and method as a person reads them, such as {@code Minecraft.tick}. */
        public String shortName() {
            return this.className.substring(this.className.lastIndexOf('.') + 1) + "." + this.method;
        }
    }

    /**
     * An exception of a crash report: its first line, such as {@code java.lang.IllegalStateException: ...}, all its
     * lines, where it starts, and its frames.
     */
    public record Failure(String message, String text, int offset, List<Frame> frames) {
        public Failure {
            frames = List.copyOf(frames);
        }
    }

    /** A read log: its first warnings and errors, and how many of each it has in all. */
    public record Log(List<LogEntry> entries, long errors, long warnings) {
        public Log {
            entries = List.copyOf(entries);
        }

        /** Whether the log has more warnings and errors than {@link #MAX_ENTRIES}, which are left out of {@link #entries}. */
        public boolean truncated() {
            return this.errors + this.warnings > this.entries.size();
        }
    }

    /** Why a mod failed to load, as a crash report of failed mod loading names it, and where its section starts. */
    public record ModIssue(String modId, String message, int offset) {
    }

    /** A read crash report: when it happened, its description, its exceptions in order, and the mods that failed to load. */
    public record CrashReport(String time, String description, List<Failure> failures, List<ModIssue> modIssues) {
        public CrashReport {
            failures = List.copyOf(failures);
            modIssues = List.copyOf(modIssues);
        }
    }

    private GameLogs() {
    }

    /** Whether the game directory holds a current log or a crash report; it stops at the first. */
    public static boolean any(Path workspace) {
        if (workspace == null) return false;
        for (String name : List.of("latest.log", "debug.log")) {
            if (Files.isRegularFile(workspace.resolve("logs").resolve(name))) return true;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workspace.resolve("crash-reports"), "*.txt")) {
            for (Path report : entries) {
                if (Files.isRegularFile(report)) return true;
            }
        } catch (IOException noReports) {
            // Without the folder there is no crash report.
        }
        return false;
    }

    /** The game's current logs, then its crash reports, the newest first. None without a game directory. */
    public static List<LogFile> list(Path workspace) throws IOException {
        List<LogFile> files = new ArrayList<>();
        if (workspace == null) return files;
        for (String name : List.of("latest.log", "debug.log")) {
            Path log = workspace.resolve("logs").resolve(name);
            if (Files.isRegularFile(log)) files.add(new LogFile(log, Kind.LOG, Files.getLastModifiedTime(log), Files.size(log)));
        }
        Path crashes = workspace.resolve("crash-reports");
        if (Files.isDirectory(crashes)) {
            List<LogFile> reports = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(crashes, "*.txt")) {
                for (Path report : entries) {
                    if (Files.isRegularFile(report)) {
                        reports.add(new LogFile(report, Kind.CRASH_REPORT, Files.getLastModifiedTime(report), Files.size(report)));
                    }
                }
            }
            reports.sort(Comparator.comparing(LogFile::modified).reversed());
            files.addAll(reports);
        }
        return files;
    }

    /** Reads the warnings and errors of a log, each with the lines that follow it. */
    public static Log readLog(Path log) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        long errors = 0;
        long warnings = 0;
        Pending pending = null;
        try (Lines lines = new Lines(log)) {
            String line;
            while ((line = lines.next()) != null) {
                Matcher header = LOG_LINE.matcher(line);
                if (header.matches()) {
                    if (pending != null && entries.size() < MAX_ENTRIES) entries.add(pending.entry());
                    Level level = level(header.group(3));
                    if (level == Level.WARN) warnings++;
                    else if (level != null) errors++;
                    pending = level == null ? null : new Pending(level, header, lines.start());
                } else if (pending != null) {
                    pending.add(line);
                }
            }
        }
        if (pending != null && entries.size() < MAX_ENTRIES) entries.add(pending.entry());
        return new Log(entries, errors, warnings);
    }

    /** Reads a crash report's time, description, every exception with its frames, and the mods that failed to load. */
    public static CrashReport readCrashReport(Path report) throws IOException {
        String time = "";
        String description = "";
        List<Failure> failures = new ArrayList<>();
        StringBuilder message = null;
        int messageOffset = 0;
        List<Frame> frames = new ArrayList<>();
        List<ModIssue> issues = new ArrayList<>();
        String issueMod = null;
        int issueOffset = 0;
        boolean trace = false;
        try (Lines lines = new Lines(report)) {
            String line;
            while ((line = lines.next()) != null) {
                String stripped = line.strip();
                if (time.isEmpty() && line.startsWith("Time: ")) time = line.substring("Time: ".length()).strip();
                if (description.isEmpty() && line.startsWith("Description: ")) {
                    description = line.substring("Description: ".length()).strip();
                    trace = true;
                } else if (line.startsWith(MOD_ISSUE)) {
                    trace = false;
                    issueMod = line.substring(MOD_ISSUE.length()).replace("--", "").strip();
                    issueOffset = lines.start();
                } else if (issueMod != null && stripped.startsWith("Failure message: ")) {
                    issues.add(new ModIssue(issueMod, stripped.substring("Failure message: ".length()), issueOffset));
                    issueMod = null;
                } else if (trace) {
                    Matcher frame = FRAME.matcher(line);
                    if (frame.matches()) {
                        if (message != null) frames.add(frame(frame, lines.start()));
                    } else if (line.startsWith("A detailed walkthrough")) {
                        // The walkthrough repeats the trace; only mod loading issues are read after it.
                        trace = false;
                    } else if (stripped.isEmpty() || stripped.startsWith("...")) {
                        continue;
                    } else if (message == null || !frames.isEmpty() || EXCEPTION.matcher(stripped).matches()) {
                        // An exception or its cause starts on a line of its own, and its frames follow its message.
                        if (message != null) failures.add(failure(message, messageOffset, frames));
                        message = new StringBuilder(stripped);
                        messageOffset = lines.start();
                        frames = new ArrayList<>();
                    } else {
                        // A message that goes on over several lines, before the first frame.
                        message.append('\n').append(stripped);
                    }
                }
            }
        }
        if (message != null) failures.add(failure(message, messageOffset, frames));
        return new CrashReport(time, description, failures, issues);
    }

    private static Failure failure(StringBuilder text, int offset, List<Frame> frames) {
        String all = text.toString();
        int lineEnd = all.indexOf('\n');
        return new Failure(lineEnd < 0 ? all : all.substring(0, lineEnd), all, offset, frames);
    }

    private static Frame frame(Matcher frame, int offset) {
        String layer = frame.group(1);
        String module = frame.group(2);
        String method = frame.group(4);
        // TRANSFORMER/total_debug@2.0.0/... names the mod's module; java.base/... and MC-BOOTSTRAP/... are no mods.
        String modId = module != null && "TRANSFORMER".equals(layer) ? module.replaceFirst("@.*$", "") : "";
        // A mixin's handler runs in the class it was merged into, but belongs to the mod that owns the mixin.
        Matcher handler = MIXIN_HANDLER.matcher(method);
        if (!modId.isEmpty() && handler.matches()) modId = handler.group(1);
        return new Frame(frame.group(3), method, frame.group(5), modId, offset);
    }

    private static Level level(String name) {
        return switch (name) {
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "FATAL" -> Level.FATAL;
            default -> null;
        };
    }

    /**
     * The lines of a text file as the game writes it, in UTF-8, replacing what does not decode. Each line comes without
     * its line break, and {@link #start()} tells where it starts, counting {@code \r\n} as two characters as an editor
     * holding the file does.
     */
    private static final class Lines implements AutoCloseable {
        private final Reader reader;
        private final StringBuilder line = new StringBuilder();
        private int start;
        private int next;
        private boolean ended;

        Lines(Path file) throws IOException {
            this.reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16);
        }

        /** The next line, or null at the end. */
        String next() throws IOException {
            if (this.ended) return null;
            this.line.setLength(0);
            this.start = this.next;
            int read;
            while ((read = this.reader.read()) != -1) {
                this.next++;
                if (read == '\n') break;
                this.line.append((char) read);
            }
            if (read == -1) {
                this.ended = true;
                if (this.line.isEmpty()) return null;
            }
            int length = this.line.length();
            if (length > 0 && this.line.charAt(length - 1) == '\r') this.line.setLength(length - 1);
            return this.line.toString();
        }

        /** Where the line {@link #next()} returned last starts. */
        int start() {
            return this.start;
        }

        @Override
        public void close() throws IOException {
            this.reader.close();
        }
    }

    /** A warning or error still collecting the lines that follow it. */
    private static final class Pending {
        private final Level level;
        private final String time;
        private final String thread;
        private final String logger;
        private final String message;
        private final int offset;
        private final StringBuilder text;
        private int lines = 1;

        Pending(Level level, Matcher header, int offset) {
            this.level = level;
            this.time = header.group(1);
            this.thread = header.group(2);
            String logger = header.group(4);
            // A logger is written as name/marker; the marker is usually empty.
            int slash = logger.indexOf('/');
            this.logger = slash < 0 ? logger : logger.substring(0, slash);
            this.message = header.group(5);
            this.offset = offset;
            this.text = new StringBuilder(header.group(5));
        }

        void add(String line) {
            if (this.lines++ < MAX_ENTRY_LINES) this.text.append('\n').append(line);
        }

        LogEntry entry() {
            return new LogEntry(this.level, this.time, this.thread, this.logger, this.message, this.text.toString(), this.offset);
        }
    }
}
