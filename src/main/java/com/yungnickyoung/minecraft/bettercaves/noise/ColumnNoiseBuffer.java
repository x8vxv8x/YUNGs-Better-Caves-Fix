package com.yungnickyoung.minecraft.bettercaves.noise;

public class ColumnNoiseBuffer {
    private float[] values;
    private float[] cornerValues;
    private float[] sampledCornerValues;
    private int[] sampleYs;
    private int minY;
    private int height;
    private int numGenerators;

    public void reset(int columnCount, int minY, int maxY, int numGenerators) {
        this.minY = minY;
        this.height = maxY - minY + 1;
        this.numGenerators = numGenerators;
        int neededCapacity = columnCount * height * numGenerators;
        if (values == null || values.length < neededCapacity) {
            values = new float[neededCapacity];
        }

        int neededCornerCapacity = 4 * height * numGenerators;
        if (cornerValues == null || cornerValues.length < neededCornerCapacity) {
            cornerValues = new float[neededCornerCapacity];
        }
    }

    public int resetVerticalSamples(int minY, int maxY, int step, int numGenerators) {
        int height = maxY - minY + 1;
        int sampleCount = (height + step - 1) / step;
        int lastSampleY = minY + (sampleCount - 1) * step;
        if (lastSampleY < maxY) {
            sampleCount++;
        }

        int neededSampleCapacity = sampleCount;
        if (sampleYs == null || sampleYs.length < neededSampleCapacity) {
            sampleYs = new int[neededSampleCapacity];
        }

        for (int i = 0; i < sampleCount; i++) {
            int y = minY + i * step;
            sampleYs[i] = y > maxY ? maxY : y;
        }
        sampleYs[sampleCount - 1] = maxY;

        int neededCornerCapacity = 4 * sampleCount * numGenerators;
        if (sampledCornerValues == null || sampledCornerValues.length < neededCornerCapacity) {
            sampledCornerValues = new float[neededCornerCapacity];
        }

        return sampleCount;
    }

    public int firstIndex(int columnSlot, int y) {
        return ((columnSlot * height + (y - minY)) * numGenerators);
    }

    public float[] values() {
        return values;
    }

    float[] getCornerValues() {
        return cornerValues;
    }

    float[] getSampledCornerValues() {
        return sampledCornerValues;
    }

    int[] getSampleYs() {
        return sampleYs;
    }

    public int getHeight() {
        return height;
    }

}
