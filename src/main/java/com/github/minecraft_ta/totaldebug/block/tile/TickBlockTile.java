package com.github.minecraft_ta.totaldebug.block.tile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.TicksPerSecondMessage;
import com.github.minecraft_ta.totaldebug.util.BlockPos;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.tileentity.TileEntity;

public class TickBlockTile extends TileEntity {

    private int ticksPerSecond;
    private long timeStampSecond;

    private long totalTicks;
    private long counter;

    private int average;

    public TickBlockTile() {
        this.ticksPerSecond = 0;
        this.timeStampSecond = System.currentTimeMillis();
    }

    @Override
    public void updateEntity() {
        if (worldObj.isRemote)
            return;

        ticksPerSecond++;

        if ((System.currentTimeMillis() - timeStampSecond) >= 1000) {
            TotalDebug.INSTANCE.network.sendToAllAround(
                    new TicksPerSecondMessage(
                            new BlockPos(this.xCoord, this.yCoord, this.zCoord),
                            this.ticksPerSecond,
                            this.counter == 0 ? 0 : (int) Math.round(this.totalTicks / (double) this.counter)
                    ),
                    new NetworkRegistry.TargetPoint(
                            this.worldObj.provider.dimensionId,
                            this.xCoord,
                            this.yCoord,
                            this.zCoord,
                            25
                    )
            );

            totalTicks += ticksPerSecond;
            counter++;

            ticksPerSecond = 0;
            timeStampSecond = System.currentTimeMillis();
        }
    }

    public void updateData(int average, int tps) {
        if (worldObj.isRemote) {
            this.average = average;
            this.ticksPerSecond = tps;
        }
    }

    public void resetAverage() {
        this.counter = 0;
        this.totalTicks = 0;
    }

    public int getTicksPerSecond() {
        return ticksPerSecond;
    }

    public int getAverage() {
        return average;
    }
}
