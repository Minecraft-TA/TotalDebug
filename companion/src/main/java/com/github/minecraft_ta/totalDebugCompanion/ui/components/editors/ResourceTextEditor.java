package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourcePaths;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A text resource of the pack, edited in place (see {@link PackResourceEditor}); Save checks the text as the game reads it. */
final class ResourceTextEditor extends PackResourceEditor<String> {
    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    private final EditableTextPanel text;

    ResourceTextEditor(String path, String origin, Path pack, LoadedResource.Text content, ResourceEdits edits) {
        super(path, origin, pack, content.value(), edits);
        this.text = new EditableTextPanel(content.syntaxStyle(), this::changed, this::save);
        this.text.load(content.value());
        start(this.text);
    }

    EditableTextPanel textPanel() {
        return this.text;
    }

    @Override
    protected String shown() {
        return this.text.text();
    }

    @Override
    protected boolean same(String first, String second) {
        return first.equals(second);
    }

    @Override
    protected void load(String content) {
        this.text.load(content);
    }

    @Override
    protected void markSaved(String content) {
        this.text.markSaved(content);
    }

    @Override
    protected boolean modified() {
        return this.text.modified();
    }

    @Override
    protected String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] encode(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected String none() {
        return "";
    }

    @Override
    protected String noun() {
        return "text";
    }

    /** Checks the text as the game parses it, and moves to the line a problem names. */
    @Override
    protected Optional<String> check(String content) {
        Optional<String> problem = ResourcePaths.check(path(), content);
        problem.map(LINE::matcher).filter(Matcher::find).ifPresent(line -> this.text.goToLine(Integer.parseInt(line.group(1))));
        return problem;
    }

    @Override
    void dispose() {
        super.dispose();
        this.text.dispose();
    }
}
