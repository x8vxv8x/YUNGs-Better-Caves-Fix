package com.yungnickyoung.minecraft.bettercaves.noise;

public class NoiseCube {
    private final double[] values;
    private final int length;
    private final int minY;
    private final int maxY;
    private final int height;
    private final int numGenerators;

    public NoiseCube(int edgeLength, int minY, int maxY, int numGenerators) {
        this.length = edgeLength;
        this.minY = minY;
        this.maxY = maxY;
        this.height = maxY - minY + 1;
        this.numGenerators = numGenerators;
        this.values = new double[edgeLength * edgeLength * height * numGenerators];
    }

    public double get(int x, int z, int y, int generator) {
        return values[index(x, z, y, generator)];
    }

    public void set(int x, int z, int y, int generator, double value) {
        values[index(x, z, y, generator)] = value;
    }

    public double getUnchecked(int x, int z, int y, int generator) {
        return values[uncheckedIndex(x, z, y, generator)];
    }

    public void setUnchecked(int x, int z, int y, int generator, double value) {
        values[uncheckedIndex(x, z, y, generator)] = value;
    }

    public int getNumGenerators() {
        return numGenerators;
    }

    private int index(int x, int z, int y, int generator) {
        if (x < 0 || x >= length)
            throw new IndexOutOfBoundsException("No corresponding noise value in NoiseCube for x-index: " + x);
        if (z < 0 || z >= length)
            throw new IndexOutOfBoundsException("No corresponding noise value in NoiseCube for z-index: " + z);
        if (y < minY || y > maxY)
            throw new IndexOutOfBoundsException("No corresponding noise value in NoiseCube for y-value: " + y);
        if (generator < 0 || generator >= numGenerators)
            throw new IndexOutOfBoundsException("No corresponding noise value in NoiseCube for generator-index: " + generator);

        return (((x * length + z) * height + (y - minY)) * numGenerators) + generator;
    }

    private int uncheckedIndex(int x, int z, int y, int generator) {
        return (((x * length + z) * height + (y - minY)) * numGenerators) + generator;
    }
}
