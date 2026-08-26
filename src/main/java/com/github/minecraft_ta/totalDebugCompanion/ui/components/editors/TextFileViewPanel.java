package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

/** Read-only syntax-highlighted text without JDT parsing or Java navigation actions. */
public final class TextFileViewPanel extends AbstractTextViewPanel {

    public TextFileViewPanel(LoadedResource.Text content, ResourceFileType fileType) {
        this(content, fileType, new BottomInformationBar());
    }

    TextFileViewPanel(
            LoadedResource.Text content,
            ResourceFileType fileType,
            BottomInformationBar informationBar
    ) {
        super(informationBar);
        this.editorPane.setEditable(false);
        setSyntaxStyle(content.syntaxStyle());
        this.editorPane.setText(content.value());
        this.editorPane.setCaretPosition(0);
        enableSearch();
        this.bottomInformationBar.setMutedInfoText(
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
