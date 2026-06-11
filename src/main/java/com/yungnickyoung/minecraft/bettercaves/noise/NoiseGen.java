package com.yungnickyoung.minecraft.bettercaves.noise;

import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import net.minecraft.world.World;

/**
 * Class used to generate noise values for blocks.
 * This class serves as an interface between Better Caves and FastNoise.
 */
public class NoiseGen {
    /** Noise generation seed. Minecraft world seed should be used for reproducibility. */
    private long seed;

    /** Number of FastNoise functions to use. This will be the number of noise values per block. Recommended: 2 */
    private int numGenerators;

    /** Primary noise function parameters */
    private NoiseSettings noiseSettings;

    /** Determines how steep and tall caves are */
    private float yCompression;
    /** Determines how horizontally large and stretched out caves are */
    private float xzCompression;

    /** Primary noise generators, one for each octave */
    private final INoiseLibrary[] noiseGens;

    /**
     * @param world World this generaton function will be used in
     * @param isFastNoise true if FastNoise, false if OpenSimplex2S
     * @param noiseSettings Primary noise function parameters
     * @param numGenerators Number of noise values to calculate per block. This number will be the number of noise
     *                      values stored per block. Increasing this will impact performance.
     * @param yComp y-compression factor
     * @param xzComp xz-compression factor
     */
    public NoiseGen(World world, boolean isFastNoise, NoiseSettings noiseSettings,
                    int numGenerators, float yComp, float xzComp) {
        this.seed = world.getSeed();
        this.noiseSettings = noiseSettings;
        this.numGenerators = numGenerators;
        this.yCompression = yComp;
        this.xzCompression = xzComp;
        this.noiseGens = new INoiseLibrary[numGenerators];
        initializeNoiseGens(isFastNoise);
    }

    public ColumnNoiseBuffer interpolateNoiseColumns(ColumnNoiseBuffer buffer, int startX, int endX, int startZ, int endZ,
                                                    int minHeight, int maxHeight, int[] activeColumns, int activeColumnCount) {
        int subChunkSize = endX - startX + 1;
        float noiseStartX = startX * xzCompression;
        float noiseEndX = endX * xzCompression;
        float noiseStartZ = startZ * xzCompression;
        float noiseEndZ = endZ * xzCompression;
        buffer.reset(activeColumnCount, minHeight, maxHeight, numGenerators);

        int verticalStep = Math.max(1, BCSettings.NOISE_VERTICAL_SAMPLE_STEP);
        int sampleCount = buffer.resetVerticalSamples(minHeight, maxHeight, verticalStep, numGenerators);
        float[] corners = buffer.getSampledCornerValues();
        int[] sampleYs = buffer.getSampleYs();
        float[] values = buffer.values();
        int height = buffer.getHeight();
        int cornerStride = sampleCount * numGenerators;

        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            int y = sampleYs[sampleIndex];
            float noiseY = y * yCompression;
            int yOffset = sampleIndex * numGenerators;
            for (int i = 0; i < numGenerators; i++) {
                INoiseLibrary noiseGen = noiseGens[i];
                int index = yOffset + i;
                corners[index] = noiseGen.GetNoise(noiseStartX, noiseY, noiseStartZ);
                corners[cornerStride + index] = noiseGen.GetNoise(noiseStartX, noiseY, noiseEndZ);
                corners[cornerStride * 2 + index] = noiseGen.GetNoise(noiseEndX, noiseY, noiseStartZ);
                corners[cornerStride * 3 + index] = noiseGen.GetNoise(noiseEndX, noiseY, noiseEndZ);
            }
        }

        for (int columnSlot = 0; columnSlot < activeColumnCount; columnSlot++) {
            int columnIndex = activeColumns[columnSlot];
            int noiseX = columnIndex / BCSettings.SUB_CHUNK_SIZE;
            int noiseZ = columnIndex % BCSettings.SUB_CHUNK_SIZE;
            float startCoeffX = BCSettings.START_COEFFS[noiseX];
            float endCoeffX = BCSettings.END_COEFFS[noiseX];
            float startCoeffZ = BCSettings.START_COEFFS[noiseZ];
            float endCoeffZ = BCSettings.END_COEFFS[noiseZ];
            int columnBase = columnSlot * height * numGenerators;

            for (int sampleIndex = 0; sampleIndex < sampleCount - 1; sampleIndex++) {
                int yStart = sampleYs[sampleIndex];
                int yEnd = sampleYs[sampleIndex + 1];
                int segmentHeight = yEnd - yStart;
                int fillEndY = sampleIndex == sampleCount - 2 ? yEnd : yEnd - 1;
                int sampleOffsetStart = sampleIndex * numGenerators;
                int sampleOffsetEnd = (sampleIndex + 1) * numGenerators;

                for (int y = yStart; y <= fillEndY; y++) {
                    float yCoeff = segmentHeight == 0 ? 0 : (float)(y - yStart) / segmentHeight;
                    int yOffset = (y - minHeight) * numGenerators;
                    for (int i = 0; i < numGenerators; i++) {
                        int startIndex = sampleOffsetStart + i;
                        int endIndex = sampleOffsetEnd + i;
                        float c00 = corners[startIndex] + (corners[endIndex] - corners[startIndex]) * yCoeff;
                        float c01 = corners[cornerStride + startIndex] + (corners[cornerStride + endIndex] - corners[cornerStride + startIndex]) * yCoeff;
                        float c10 = corners[cornerStride * 2 + startIndex] + (corners[cornerStride * 2 + endIndex] - corners[cornerStride * 2 + startIndex]) * yCoeff;
                        float c11 = corners[cornerStride * 3 + startIndex] + (corners[cornerStride * 3 + endIndex] - corners[cornerStride * 3 + startIndex]) * yCoeff;
                        float zStart = c00 * startCoeffX + c10 * endCoeffX;
                        float zEnd = c01 * startCoeffX + c11 * endCoeffX;
                        values[columnBase + yOffset + i] = zStart * startCoeffZ + zEnd * endCoeffZ;
                    }
                }
            }

            if (sampleCount == 1) {
                for (int i = 0; i < numGenerators; i++) {
                    float zStart = corners[i] * startCoeffX + corners[cornerStride * 2 + i] * endCoeffX;
                    float zEnd = corners[cornerStride + i] * startCoeffX + corners[cornerStride * 3 + i] * endCoeffX;
                    values[columnBase + i] = zStart * startCoeffZ + zEnd * endCoeffZ;
                }
            }
        }

        return buffer;
    }

    /* ------------------------- Private Methods -------------------------*/
    /**
     * Initialize fractal noise generators.
     */
    private void initializeNoiseGens(boolean isFastNoise) {
        if (isFastNoise) {
            for (int i = 0; i < numGenerators; i++) {
                FastNoise noiseGen = new FastNoise();
                noiseGen.SetSeed((int) (seed) + (1111 * (i + 1)));
                noiseGen.SetFractalType(noiseSettings.getFractalType());
                noiseGen.SetNoiseType(noiseSettings.getNoiseType());
                noiseGen.SetFractalOctaves(noiseSettings.getOctaves());
                noiseGen.SetFractalGain(noiseSettings.getGain());
                noiseGen.SetFrequency(noiseSettings.getFrequency());
                noiseGens[i] = noiseGen;
            }
        }
        else {
            for (int i = 0; i < numGenerators; i++) {
                OpenSimplex2S noiseGen = new OpenSimplex2S(seed + (1111 * (i + 1)));
                noiseGen.setGain(noiseSettings.getGain());
                noiseGen.setOctaves(noiseSettings.getOctaves());
                noiseGen.setFrequency(noiseSettings.getFrequency());
                noiseGen.setLacunarity(2.0);
                noiseGens[i] = noiseGen;
            }
        }
    }
}
