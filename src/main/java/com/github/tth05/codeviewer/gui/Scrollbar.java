package com.github.tth05.codeviewer.gui;

public class Scrollbar {

    public static final float DEFAULT_STEP_SIZE = 0.06f;

    private int min;
    private int max;

    /**
     * a value between 0 and 1 that defines how much of the available range should be added each scroll
     * event to the offset. Basically the speed of the scrollbar.
     */
    private float stepSize = DEFAULT_STEP_SIZE;

    private float currentOffset;

    /**
     * @param min      the minimum
     * @param max      the maximum
     */
    public Scrollbar(int min, int max) {
        this.min = min;
        this.max = max;

        this.currentOffset = min;
    }

    public void mouseWheel(int delta) {
        if (delta < 0) {
            this.currentOffset = Math.min(max, this.currentOffset + (max - min) * stepSize);
        } else if (delta > 0) {
            this.currentOffset = Math.max(min, this.currentOffset - (max - min) * stepSize);
        }
    }

    public int getOffset() {
        return (int) currentOffset;
    }

    public void setMin(int min) {
        this.min = min;
    }

    public void setMax(int max) {
        this.max = max;

        if (this.currentOffset > max)
            this.currentOffset = max;
    }

    public void setStepSize(float stepSize) {
        this.stepSize = stepSize;
    }
}
