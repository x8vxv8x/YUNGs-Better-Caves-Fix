package com.yungnickyoung.minecraft.bettercaves.world.carver.cave;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.noise.ColumnNoiseBuffer;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseGen;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.ICarver;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;

import java.util.Arrays;

/**
 * BetterCaves Cave carver
 */
public class CaveCarver implements ICarver {
    private static final int MAX_WORLD_HEIGHT = 256;
    private static final int MASK_WORDS = 4;

    private CarverSettings settings;
    private NoiseGen noiseGen;
    private int seaLevel;

    /** Surface cutoff depth */
    private int surfaceCutoff;

    /** Cave bottom y-coordinate */
    private int bottomY;

    /* Cave bottom y-coordinate TODO */
    private int topY;

    /**
     * Set true to perform pre-processing on noise values, adjusting them to increase ...
     * ... headroom in the y direction.
     */
    private boolean enableYAdjust;

    /** Adjustment value for the block immediately above. Must be between 0 and 1.0 */
    private float yAdjustF1;

    /** Adjustment value for the block two blocks above. Must be between 0 and 1.0 */
    private float yAdjustF2;

    private final float[][] cachedThresholds = new float[MAX_WORLD_HEIGHT + 1][];
    private final long[] carveMask = new long[MASK_WORDS];
    private final long[] adjustedCarveMask = new long[MASK_WORDS];

    public CaveCarver(final CaveCarverBuilder builder) {
        settings = builder.getSettings();
        noiseGen = new NoiseGen(
                settings.getWorld(),
                settings.isFastNoise(),
                settings.getNoiseSettings(),
                settings.getNumGens(),
                settings.getyCompression(),
                settings.getXzCompression()
        );
        seaLevel = builder.getSettings().getWorld().getSeaLevel();
        surfaceCutoff = builder.getSurfaceCutoff();
        bottomY = builder.getBottomY();
        topY = builder.getTopY();
        enableYAdjust = builder.isEnableYAdjust();
        yAdjustF1 = builder.getyAdjustF1();
        yAdjustF2 = builder.getyAdjustF2();
        if (bottomY > topY) {
            BetterCaves.LOGGER.warn("Warning: Min altitude for caves should not be greater than max altitude.");
            BetterCaves.LOGGER.warn("Using default values...");
            this.bottomY = 1;
            this.topY = 80;
        }
    }

    public void carveColumn(ChunkPrimer primer, int localX, int localZ, int topY, ColumnNoiseBuffer noises, int noiseSlot, IBlockState liquidBlock, boolean flooded, Biome biome) {
        IBlockState airBlockState = Blocks.AIR.getDefaultState();
        IBlockState airState = Blocks.AIR.getDefaultState();
        IBlockState waterBlockState = Blocks.WATER.getDefaultState();
        IBlockState biomeTopState = biome.topBlock;
        Block biomeTopBlock = biomeTopState.getBlock();
        Block biomeFillerBlock = biome.fillerBlock.getBlock();
        int liquidAltitude = settings.getLiquidAltitude();
        int numGens = settings.getNumGens();

        if (bottomY < 0 || bottomY > 255)
            return;
        if (topY < 0 || topY > 255)
            return;
        if (topY < bottomY)
            return;

        int transitionBoundary = topY - surfaceCutoff;
        if (transitionBoundary < 1)
            transitionBoundary = 1;

        float[] thresholds = getThresholds(topY, bottomY, transitionBoundary);

        float[] noiseValues = noises.values();
        if (this.enableYAdjust) {
            carveColumnWithAdjustedMask(primer, localX, localZ, topY, bottomY, thresholds, noises, noiseSlot,
                noiseValues, numGens, liquidBlock, liquidAltitude, flooded, seaLevel, waterBlockState, airState,
                biomeTopState, biomeTopBlock, biomeFillerBlock);
            return;
        }

        for (int y = topY; y >= bottomY; y--) {
            if (y <= liquidAltitude && liquidBlock == null)
                break;

            boolean digBlock = true;
            float threshold = thresholds[y - bottomY];
            int noiseIndex = noises.firstIndex(noiseSlot, y);

            for (int i = 0; i < numGens; i++) {
                if (noiseValues[noiseIndex + i] < threshold) {
                    digBlock = false;
                    break;
                }
            }

            airBlockState = flooded && y < seaLevel ? waterBlockState : airState;

            if (settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlockLocal(primer, localX, y, localZ, settings.getDebugBlock(), digBlock);
            }
            else if (digBlock) {
                CarverUtils.digBlockLocal(primer, localX, y, localZ, biomeTopState, biomeTopBlock, biomeFillerBlock, airBlockState, liquidBlock, liquidAltitude, settings.isReplaceFloatingGravel());
            }
        }
    }

    private void carveColumnWithAdjustedMask(ChunkPrimer primer, int localX, int localZ, int topY, int bottomY,
                                             float[] thresholds, ColumnNoiseBuffer noises, int noiseSlot,
                                             float[] noiseValues, int numGens, IBlockState liquidBlock,
                                             int liquidAltitude, boolean flooded, int seaLevel,
                                             IBlockState waterBlockState, IBlockState airState,
                                             IBlockState biomeTopState, Block biomeTopBlock,
                                             Block biomeFillerBlock) {
        Arrays.fill(carveMask, 0L);
        Arrays.fill(adjustedCarveMask, 0L);

        for (int y = topY; y >= bottomY; y--) {
            if (y <= liquidAltitude && liquidBlock == null) {
                break;
            }

            float threshold = thresholds[y - bottomY];
            int noiseIndex = noises.firstIndex(noiseSlot, y);
            boolean digBlock = true;

            for (int i = 0; i < numGens; i++) {
                if (noiseValues[noiseIndex + i] < threshold) {
                    digBlock = false;
                    break;
                }
            }

            if (digBlock) {
                setMaskBit(carveMask, y - bottomY);
            }
        }

        copyMask(carveMask, adjustedCarveMask);
        if (yAdjustF1 > 0) {
            orShiftedUp(carveMask, adjustedCarveMask, 1);
        }
        if (yAdjustF2 > 0) {
            orShiftedUp(carveMask, adjustedCarveMask, 2);
        }

        for (int y = topY; y >= bottomY; y--) {
            if (y <= liquidAltitude && liquidBlock == null) {
                break;
            }
            boolean digBlock = getMaskBit(adjustedCarveMask, y - bottomY);
            if (settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlockLocal(primer, localX, y, localZ, settings.getDebugBlock(), digBlock);
                continue;
            }
            if (!digBlock) {
                continue;
            }

            IBlockState airBlockState = flooded && y < seaLevel ? waterBlockState : airState;
            CarverUtils.digBlockLocal(primer, localX, y, localZ, biomeTopState, biomeTopBlock, biomeFillerBlock, airBlockState, liquidBlock, liquidAltitude, settings.isReplaceFloatingGravel());
        }
    }

    private static void copyMask(long[] source, long[] target) {
        for (int i = 0; i < MASK_WORDS; i++) {
            target[i] = source[i];
        }
    }

    private static void setMaskBit(long[] mask, int bitIndex) {
        mask[bitIndex >> 6] |= 1L << (bitIndex & 63);
    }

    private static boolean getMaskBit(long[] mask, int bitIndex) {
        return (mask[bitIndex >> 6] & (1L << (bitIndex & 63))) != 0;
    }

    private static void orShiftedUp(long[] source, long[] target, int shift) {
        long carry = 0L;
        for (int i = 0; i < MASK_WORDS; i++) {
            long current = source[i];
            target[i] |= current << shift | carry;
            carry = current >>> (64 - shift);
        }
    }

    /**
     * Generate a map of y-coordinates to thresholds for a column of blocks.
     * This is useful because the threshold will decrease near the surface, and it is useful (and more accurate)
     * to have a precomputed threshold value when doing y-adjustments for caves.
     * @param topY Top y-coordinate of the column
     * @param bottomY Bottom y-coordinate of the column
     * @param transitionBoundary The y-coordinate at which the caves start to close off
     * @return Array of y-coordinate noise thresholds, indexed by y - bottomY.
     */
    private float[] generateThresholds(int topY, int bottomY, int transitionBoundary) {
        float[] thresholds = new float[topY - bottomY + 1];
        int transitionHeight = topY - transitionBoundary;
        for (int realY = bottomY; realY <= topY; realY++) {
            float noiseThreshold = settings.getNoiseThreshold();
            if (realY >= transitionBoundary && transitionHeight > 0)
                noiseThreshold *= (1 + .3f * ((float)(realY - transitionBoundary) / transitionHeight));
            thresholds[realY - bottomY] = noiseThreshold;
        }

        return thresholds;
    }

    private float[] getThresholds(int topY, int bottomY, int transitionBoundary) {
        if (bottomY == this.bottomY && transitionBoundary == topY - surfaceCutoff) {
            float[] thresholds = cachedThresholds[topY];
            if (thresholds == null) {
                thresholds = generateThresholds(topY, bottomY, transitionBoundary);
                cachedThresholds[topY] = thresholds;
            }
            return thresholds;
        }

        return generateThresholds(topY, bottomY, transitionBoundary);
    }

    public NoiseGen getNoiseGen() {
        return noiseGen;
    }

    public int getPriority() {
        return settings.getPriority();
    }

    public int getBottomY() {
        return this.bottomY;
    }

    public int getTopY() {
        return this.topY;
    }
}
