package com.github.minecraft_ta.totaldebug;

import com.mojang.logging.LogUtils;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Objects;

@Mod(TotalDebug.MOD_ID)
public final class TotalDebug {
    public static final String MOD_ID = "total_debug";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static TotalDebug instance;

    private final String version;
    private final TickTaskScheduler tickTaskScheduler;

    public TotalDebug(IEventBus modEventBus, ModContainer modContainer) {
        if (instance != null) {
            throw new IllegalStateException("TotalDebug was initialized more than once");
        }

        instance = this;
        this.version = Objects.requireNonNull(modContainer, "modContainer")
                .getModInfo()
                .getVersion()
                .toString();
        this.tickTaskScheduler = new TickTaskScheduler();
        TotalDebugConfig.register(modContainer);

        LOGGER.info("Initializing TotalDebug {}", this.version);
    }

    public static TotalDebug get() {
        if (instance == null) {
            throw new IllegalStateException("TotalDebug has not been initialized yet");
        }
        return instance;
    }

    public String version() {
        return this.version;
    }

    public TickTaskScheduler tickTasks() {
        return this.tickTaskScheduler;
    }
}
