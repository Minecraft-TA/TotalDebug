package com.github.minecraft_ta.totaldebug.client.companion;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Objects;

public final class CompanionProgressActionBar {
    private CompanionProgressActionBar() {
    }

    public static void show(Minecraft minecraft, CompanionStartupProgress progress) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(progress, "progress");
        minecraft.execute(() -> minecraft.gui.setOverlayMessage(messageFor(progress), false));
    }

    static Component messageFor(CompanionStartupProgress progress) {
        return switch (progress.stage()) {
            case DOWNLOADING -> progress.hasDeterminateProgress()
                    ? Component.translatable(
                            "companion_app.download_progress",
                            progress.detail(),
                            progress.percentage()
                    )
                    : Component.translatable("companion_app.download_start", progress.detail());
            case STARTING -> Component.translatable("companion_app.starting");
            case CONNECTING -> Component.translatable("companion_app.connecting");
            case READY -> Component.translatable("companion_app.connection_success");
            case FAILED -> Component.translatable("companion_app.startup_fail").withStyle(ChatFormatting.RED);
        };
    }
}
