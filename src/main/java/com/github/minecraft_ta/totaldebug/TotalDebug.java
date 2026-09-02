package com.github.minecraft_ta.totaldebug;

import com.mojang.logging.LogUtils;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.network.TotalDebugNetwork;
import com.github.minecraft_ta.totaldebug.runtime.PreparedRuntimeSources;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceMaterializer;
import com.github.minecraft_ta.totaldebug.server.script.ServerScriptService;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import io.github.classgraph.ClassGraph;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Objects;

@Mod(TotalDebug.MOD_ID)
public final class TotalDebug {
    public static final String MOD_ID = "total_debug";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static TotalDebug instance;

    private final String version;
    private final TickTaskScheduler tickTaskScheduler;
    private final TotalDebugNetwork network;
    private final ServerScriptService serverScripts;
    private PreparedRuntimeSources runtimeSources;

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
        this.serverScripts = new ServerScriptService(this.tickTaskScheduler);
        this.network = new TotalDebugNetwork(Objects.requireNonNull(modEventBus, "modEventBus"));
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

    public TotalDebugNetwork network() {
        return this.network;
    }

    public ServerScriptService serverScripts() {
        return this.serverScripts;
    }

    public synchronized PreparedRuntimeSources runtimeSources() throws IOException {
        if (this.runtimeSources == null) {
            this.runtimeSources = RuntimeSourceMaterializer.prepare(
                    RuntimeSourceInventory.discover(TotalDebug.class, Block.class, ClassGraph.class),
                    com.github.minecraft_ta.totaldebug.storage.InstancePaths.forGame(FMLPaths.GAMEDIR.get()).sources()
            );
        }
        return this.runtimeSources;
    }
}
