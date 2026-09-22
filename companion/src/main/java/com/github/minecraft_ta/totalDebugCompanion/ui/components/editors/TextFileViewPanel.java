package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import java.util.function.Consumer;

/** Read-only syntax-highlighted text without JDT parsing or Java navigation actions. */
public final class TextFileViewPanel extends AbstractTextViewPanel {

    TextFileViewPanel(
            LoadedResource.Text content,
            ResourceFileType fileType,
            Consumer<String> metadata
    ) {
        super();
        this.editorPane.setEditable(false);
        setSyntaxStyle(content.syntaxStyle());
        this.editorPane.setText(content.value());
        this.editorPane.setCaretPosition(0);
        enableSearch();
        metadata.accept(
                fileType.description() + "  |  " + content.charsetName() + "  |  " + formatBytes(content.byteCount())
        );
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.1f MiB".formatted(bytes / (1024d * 1024d));
        }
        if (bytes >= 1024) {
            return "%.1f KiB".formatted(bytes / 1024d);
        }
        return bytes + " B";
    }
}
