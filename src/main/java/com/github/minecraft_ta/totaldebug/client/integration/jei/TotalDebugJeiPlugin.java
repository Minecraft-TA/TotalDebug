package com.github.minecraft_ta.totaldebug.client.integration.jei;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

@JeiPlugin
public final class TotalDebugJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_ID = ResourceLocation.fromNamespaceAndPath(
            TotalDebug.MOD_ID,
            "integration"
    );

    private JeiHoveredItemResolver installedResolver;

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        if (this.installedResolver != null) {
            throw new IllegalStateException("JEI runtime became available without releasing the previous runtime");
        }

        var resolver = new JeiHoveredItemResolver(Objects.requireNonNull(jeiRuntime, "jeiRuntime"));
        TotalDebugClient.get().codeViewInput().installScreenItemResolver(resolver);
        this.installedResolver = resolver;
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiHoveredItemResolver resolver = this.installedResolver;
        if (resolver == null) {
            return;
        }

        TotalDebugClient.get().codeViewInput().removeScreenItemResolver(resolver);
        this.installedResolver = null;
    }
}
