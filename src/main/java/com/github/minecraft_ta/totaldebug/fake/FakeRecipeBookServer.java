package com.github.minecraft_ta.totaldebug.fake;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.stats.RecipeBookServer;

public class FakeRecipeBookServer extends RecipeBookServer {

    @Override
    public void init(EntityPlayerMP player) {
        //NO OP
    }
}
