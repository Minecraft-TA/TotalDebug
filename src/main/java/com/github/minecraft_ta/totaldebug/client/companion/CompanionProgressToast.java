package com.github.minecraft_ta.totaldebug.client.companion;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public final class CompanionProgressToast implements Toast {
    private static final Object TOKEN = new Object();
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/tutorial");
    private static final int BAR_LEFT = 3;
    private static final int BAR_RIGHT = 157;
    private static final int BAR_Y = 28;
    private static final long COMPLETION_DISPLAY_MILLIS = 2_500L;
    private static final long FAILURE_DISPLAY_MILLIS = 5_000L;

    private Component message;
    private float progress;
    private boolean showProgress;
    private boolean terminal;
    private long terminalSince = -1L;
    private long terminalDisplayMillis;

    private CompanionProgressToast(CompanionStartupProgress progress) {
        update(progress);
    }

    public static void show(Minecraft minecraft, CompanionStartupProgress progress) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(progress, "progress");
        minecraft.execute(() -> {
            ToastComponent toasts = minecraft.getToasts();
            CompanionProgressToast toast = toasts.getToast(CompanionProgressToast.class, TOKEN);
            if (toast == null) {
                toasts.addToast(new CompanionProgressToast(progress));
            } else {
                toast.update(progress);
            }
        });
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent toasts, long visibleTime) {
        graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
        graphics.drawString(
                toasts.getMinecraft().font,
                Component.translatable("companion_app.toast.title"),
                12,
                7,
                -11534256,
                false
        );
        graphics.drawString(toasts.getMinecraft().font, this.message, 12, 18, -16777216, false);

        if (this.showProgress) {
            graphics.fill(BAR_LEFT, BAR_Y, BAR_RIGHT, BAR_Y + 1, -1);
            int progressRight = BAR_LEFT + Math.round((BAR_RIGHT - BAR_LEFT) * this.progress);
            graphics.fill(BAR_LEFT, BAR_Y, progressRight, BAR_Y + 1, -16755456);
        }

        if (!this.terminal) {
            return Visibility.SHOW;
        }
        if (this.terminalSince < 0L) {
            this.terminalSince = visibleTime;
        }
        return visibleTime - this.terminalSince < this.terminalDisplayMillis
                ? Visibility.SHOW
                : Visibility.HIDE;
    }

    @Override
    public Object getToken() {
        return TOKEN;
    }

    void update(CompanionStartupProgress progress) {
        this.message = messageFor(progress);
        this.showProgress = progress.hasDeterminateProgress();
        this.progress = progress.fraction();
        this.terminal = progress.stage() == CompanionStartupProgress.Stage.READY
                || progress.stage() == CompanionStartupProgress.Stage.FAILED;
        this.terminalSince = -1L;
        this.terminalDisplayMillis = progress.stage() == CompanionStartupProgress.Stage.FAILED
                ? FAILURE_DISPLAY_MILLIS
                : COMPLETION_DISPLAY_MILLIS;
    }

    private static Component messageFor(CompanionStartupProgress progress) {
        return switch (progress.stage()) {
            case INDEXING -> Component.translatable("companion_app.start_indexing");
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
            case FAILED -> Component.translatable("companion_app.connection_fail").withStyle(ChatFormatting.RED);
        };
    }
}
