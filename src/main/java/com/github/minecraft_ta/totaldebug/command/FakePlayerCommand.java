package com.github.minecraft_ta.totaldebug.command;

import com.github.minecraft_ta.totaldebug.fake.FakeEntityPlayerMP;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

public class FakePlayerCommand extends CommandBase {

    @Override
    public String getName() {
        return "player";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.total_debug.player.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayer player = ((EntityPlayer) sender);
        FakeEntityPlayerMP.spawn(args[0], server, player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch, player.dimension);
    }
}
