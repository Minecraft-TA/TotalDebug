package com.github.minecraft_ta.totaldebug.block;

import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public class TickBlock extends Block {

    public TickBlock() {
        super(Material.IRON);
        setCreativeTab(CreativeTabs.REDSTONE);
    }

    @Override
    public TileEntity createTileEntity(@Nonnull World world, @Nonnull IBlockState state) {
        return new TickBlockTile();
    }

    @Override
    public boolean hasTileEntity(@Nonnull IBlockState state) {
        return true;
    }

    @Override
    public boolean onBlockActivated(World worldIn, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull EntityPlayer playerIn, @Nonnull EnumHand hand, @Nonnull EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote || hand != EnumHand.MAIN_HAND)
            return false;

        TileEntity te = worldIn.getTileEntity(pos);
        if (te != null) {
            ((TickBlockTile) te).resetAverage();
        }

        return false;
    }
}
