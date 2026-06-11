package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverNoiseRange;

/**
 * Scratch fields for columns inside one interpolated sub-chunk.
 */
public class SubChunkColumnFields {
    public static final int COLUMN_COUNT = BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE;

    public final CarverNoiseRange[] activeRanges = new CarverNoiseRange[COLUMN_COUNT];
    public final int[] topYs = new int[COLUMN_COUNT];
    public final float[] smoothAmps = new float[COLUMN_COUNT];
    public final boolean[] flooded = new boolean[COLUMN_COUNT];
    public final int[] activeColumns = new int[COLUMN_COUNT];
    public final int[] noiseSlots = new int[COLUMN_COUNT];
    public final int[] rangeIndices = new int[COLUMN_COUNT];
    private int[][] rangeColumns;
    private int[] rangeCounts;
    public int activeCount;

    public void resetRanges(int rangeCount) {
        if (rangeColumns == null || rangeColumns.length < rangeCount) {
            rangeColumns = new int[rangeCount][COLUMN_COUNT];
            rangeCounts = new int[rangeCount];
        }
        for (int i = 0; i < rangeCount; i++) {
            rangeCounts[i] = 0;
        }
    }

    public void clear() {
        activeCount = 0;
    }

    public void clearColumn(int columnIndex) {
        activeRanges[columnIndex] = null;
        topYs[columnIndex] = 0;
        smoothAmps[columnIndex] = 0;
        flooded[columnIndex] = false;
        noiseSlots[columnIndex] = 0;
        rangeIndices[columnIndex] = 0;
    }

    public void addActiveColumn(int columnIndex, int rangeIndex) {
        int noiseSlot = rangeCounts[rangeIndex];
        activeColumns[activeCount++] = columnIndex;
        rangeColumns[rangeIndex][rangeCounts[rangeIndex]++] = columnIndex;
        noiseSlots[columnIndex] = noiseSlot;
        rangeIndices[columnIndex] = rangeIndex;
    }

    public int[] getRangeColumns(int rangeIndex) {
        return rangeColumns[rangeIndex];
    }

    public int getRangeCount(int rangeIndex) {
        return rangeCounts[rangeIndex];
    }
}
