package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.catalog.KeyBindingOwners;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * The mod's entry on the client, constructed with {@link TotalDebug}: what only a client sets up while mods are
 * constructed. Outside the client part, no class refers to the client (see {@code docs/MOD_SIDES.md}).
 */
@Mod(value = TotalDebug.MOD_ID, dist = Dist.CLIENT)
public final class TotalDebugClientMod {
    public TotalDebugClientMod() {
        KeyBindingOwners.install();
    }
}
