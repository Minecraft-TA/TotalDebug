package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ResourceViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

public final class ResourceView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    @Override public RuntimeBinding runtimeBinding() { return runtimeBinding; }


    private final ContentSource source;
    private final ResourceFileType fileType;
    private final ResourceViewPanel panel;

    public ResourceView(ContentSource source, RuntimeBinding runtimeBinding) {
        this.runtimeBinding = runtimeBinding;
        this.source = Objects.requireNonNull(source, "source");
        this.fileType = FileTypeResolver.resolve(source.displayName());
        this.panel = new ResourceViewPanel(source, this.fileType);
    }

    public ContentSource source() {
        return this.source;
    }

    @Override
    public String getTitle() {
        return this.source.displayName();
    }

    @Override
    public String getTooltip() {
        return this.source.tooltip();
    }

    @Override
    public Icon getIcon() {
        return this.fileType.icon();
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public EditorLocation getLocation() {
        if (this.source instanceof ArchiveEntrySource archiveEntry) {
            return EditorLocation.forArchiveEntry(archiveEntry.archivePath(), archiveEntry.entryName());
        }
        if (this.source instanceof LocalFileSource localFile) {
            return EditorLocation.forFile(localFile.path(), CompanionApp.getWorkspaceDirectory());
        }
        return new EditorLocation(this.source.displayName(), java.util.List.of(), this.source.tooltip());
    }

    @Override
    public BottomInformationBar getInformationBar() {
        return this.panel.getBottomInformationBar();
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return switch (this.source) {
            case ArchiveEntrySource archive -> new NavigationTarget.ArchiveEntry(
                    archive.archivePath(),
                    archive.entryName()
            );
            case LocalFileSource file -> new NavigationTarget.LocalFile(file.path());
            default -> null;
        };
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
