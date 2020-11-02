package com.github.minecraft_ta.totaldebug.block.tile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.TicksPerSecondMessage;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraftforge.fml.common.network.NetworkRegistry;

public class TickBlockTile extends TileEntity implements ITickable {

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
    public void update() {
        if (world.isRemote)
            return;

        ticksPerSecond++;

        if ((System.currentTimeMillis() - timeStampSecond) >= 1000) {
            TotalDebug.INSTANCE.network.sendToAllTracking(
                    new TicksPerSecondMessage(
                            this.getPos(),
                            this.ticksPerSecond,
                            this.counter == 0 ? 0 : (int) Math.round(this.totalTicks / (double) this.counter)
                    ),
                    new NetworkRegistry.TargetPoint(
                            this.world.provider.getDimension(),
                            this.getPos().getX(),
                            this.getPos().getY(),
                            this.getPos().getZ(),
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
        if (world.isRemote) {
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
