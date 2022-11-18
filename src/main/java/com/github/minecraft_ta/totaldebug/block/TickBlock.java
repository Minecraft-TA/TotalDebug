package com.github.minecraft_ta.totaldebug.block;

import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class TickBlock extends Block {

    public TickBlock() {
        super(Material.iron);
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        return new TickBlockTile();
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return true;
    }

    @Override
    public boolean onBlockActivated(World p_149727_1_, int p_149727_2_, int p_149727_3_, int p_149727_4_, EntityPlayer p_149727_5_, int p_149727_6_, float p_149727_7_, float p_149727_8_, float p_149727_9_) {
        if (p_149727_1_.isRemote)
            return false;

        TileEntity te = p_149727_1_.getTileEntity(p_149727_2_, p_149727_3_, p_149727_4_);
        if (te != null) {
            ((TickBlockTile) te).resetAverage();
        }

        return false;
    }
}
