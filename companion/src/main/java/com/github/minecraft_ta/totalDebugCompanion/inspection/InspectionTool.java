package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project script used as an inspection tool. Its leading comment lines may declare the registry ids it applies to,
 * for example {@code // inspect: mekanism:*, minecraft:furnace}; matching tools run with every inspection of such a
 * subject. Any script can also be run once on a subject.
 */
public record InspectionTool(Path path, String name, String text, List<String> patterns) {
    public static final String FOLDER = "tools";
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*//\\s*inspect\\s*:\\s*(.+)$");

    public InspectionTool {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
        patterns = List.copyOf(patterns);
    }

    /** Reads every script under the project's Scripts root. Unreadable or unnameable scripts are skipped. */
    public static List<InspectionTool> load(ScriptFiles files) throws IOException {
        List<InspectionTool> tools = new ArrayList<>();
        for (String relative : files.listScripts()) {
            Path path = files.resolve(Path.of(relative));
            String fileName = path.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ScriptFiles.EXTENSION.length());
            if (!JavaSnippetSource.isValidClassName(name)) {
                continue;
            }
            String text;
            try {
                text = files.read(path).text();
            } catch (IOException unreadable) {
                continue;
            }
            tools.add(new InspectionTool(path, name, text, patterns(text)));
        }
        return List.copyOf(tools);
    }

    /** The registry id patterns declared in the script's leading comment lines. */
    static List<String> patterns(String text) {
        List<String> patterns = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            if (!line.strip().startsWith("//")) {
                break;
            }
            Matcher matcher = DIRECTIVE.matcher(line);
            if (matcher.matches()) {
                Arrays.stream(matcher.group(1).split(","))
                        .map(String::strip)
                        .filter(pattern -> !pattern.isEmpty())
                        .forEach(patterns::add);
            }
        }
        return patterns;
    }

    public boolean appliesTo(String registryId) {
        String id = registryId.toLowerCase(Locale.ROOT);
        for (String pattern : this.patterns) {
            if (glob(pattern.toLowerCase(Locale.ROOT)).matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** Source for a new tool applying to {@code registryId}. */
    public static String template(String registryId, String title) {
        return """
                // inspect: %1$s
                import com.github.minecraft_ta.totaldebug.script.ScriptTarget;

                // target() is the inspected block or entity; sections reported through facts() appear in its tab.
                switch (target()) {
                    case ScriptTarget.PlacedBlock block -> facts().section("%2$s")
                            .text("State", block.state())
                            .text("Block entity", block.blockEntity() == null ? "None" : block.blockEntity().getClass().getSimpleName());
                    case ScriptTarget.LiveEntity entity -> facts().section("%2$s")
                            .text("Position", entity.entity().position());
                    case ScriptTarget.SelectedStack selected -> facts().section("%2$s")
                            .text("Count", selected.stack().getCount());
                }
                return null;
                """.formatted(registryId, title);
    }
}
