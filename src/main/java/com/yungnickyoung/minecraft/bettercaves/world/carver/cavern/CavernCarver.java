package com.yungnickyoung.minecraft.bettercaves.world.carver.cavern;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
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

/**
 * BetterCaves Cavern carver.
 * Caverns are large openings generated at the bottom of the world.
 */
public class CavernCarver implements ICarver {
    private CarverSettings settings;
    private NoiseGen noiseGen;
    private int seaLevel;

    private CavernType cavernType;
    private int bottomY;
    private int topY;

    public CavernCarver(final CavernCarverBuilder builder) {
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
        cavernType = builder.getCavernType();
        bottomY = builder.getBottomY();
        topY = builder.getTopY();
        if (bottomY > topY) {
            BetterCaves.LOGGER.warn("Warning: Min altitude for caverns should not be greater than max altitude.");
            BetterCaves.LOGGER.warn("Using default values...");
            this.bottomY = 1;
            this.topY = 35;
        }
    }

    public void carveColumn(ChunkPrimer primer, int localX, int localZ, int topY, float smoothAmp, ColumnNoiseBuffer noises, int noiseSlot, IBlockState liquidBlock, boolean flooded, Biome biome) {
        IBlockState airState = Blocks.AIR.getDefaultState();
        IBlockState waterBlockState = Blocks.WATER.getDefaultState();
        IBlockState airBlockState;
        IBlockState biomeTopState = biome.topBlock;
        Block biomeTopBlock = biomeTopState.getBlock();
        Block biomeFillerBlock = biome.fillerBlock.getBlock();
        int liquidAltitude = settings.getLiquidAltitude();
        int numGens = settings.getNumGens();

        if (bottomY < 0 || bottomY > 255)
            return;
        if (topY < 0 || topY > 255)
            return;

        topY -= 2;
        if (topY < bottomY)
            return;

        int topTransitionBoundary = Math.max(topY - 6, 1);
        int bottomTransitionBoundary = bottomY + 3;
        if (cavernType == CavernType.FLOORED) {
            bottomTransitionBoundary = bottomY < liquidAltitude ? liquidAltitude + 8 : bottomY + 7;
        }
        bottomTransitionBoundary = Math.min(bottomTransitionBoundary, 255);

        float[] noiseValues = noises.values();
        int topTransitionHeight = topTransitionBoundary - topY;
        int bottomTransitionHeight = bottomTransitionBoundary - bottomY;
        for (int y = topY; y >= bottomY; y--) {
            if (y <= liquidAltitude && liquidBlock == null)
                break;

            boolean digBlock = false;
            float noise = 1;
            int noiseIndex = noises.firstIndex(noiseSlot, y);
            for (int i = 0; i < numGens; i++)
                noise *= noiseValues[noiseIndex + i];

            float noiseThreshold = settings.getNoiseThreshold();
            if (y >= topTransitionBoundary && topTransitionHeight != 0)
                noiseThreshold *= (float) (y - topY) / topTransitionHeight;
            if (y < bottomTransitionBoundary && bottomTransitionHeight != 0)
                noiseThreshold *= (float) (y - bottomY) / bottomTransitionHeight;
            if (smoothAmp < 1)
                noiseThreshold *= smoothAmp;

            if (noise < noiseThreshold)
                digBlock = true;

            airBlockState = flooded && y < seaLevel ? waterBlockState : airState;

            if (settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlockLocal(primer, localX, y, localZ, settings.getDebugBlock(), digBlock);
            } else if (digBlock) {
                CarverUtils.digBlockLocal(primer, localX, y, localZ, biomeTopState, biomeTopBlock, biomeFillerBlock, airBlockState, liquidBlock, liquidAltitude, settings.isReplaceFloatingGravel());
            }
        }
    }

    public NoiseGen getNoiseGen() {
        return noiseGen;
    }

    public int getPriority() {
        return settings.getPriority();
    }

    public int getBottomY() {
        return bottomY;
    }

    public int getTopY() {
        return topY;
    }
}
