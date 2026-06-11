package com.yungnickyoung.minecraft.bettercaves.world.carver.cave;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseCube;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseGen;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.ICarver;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;

/**
 * BetterCaves Cave carver
 */
public class CaveCarver implements ICarver {
    private CarverSettings settings;
    private NoiseGen noiseGen;
    private World world;

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
        world = builder.getSettings().getWorld();
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

    public void carveColumn(ChunkPrimer primer, BlockPos colPos, int topY, NoiseCube noises, int noiseX, int noiseZ, IBlockState liquidBlock, boolean flooded) {
        int worldX = colPos.getX();
        int worldZ = colPos.getZ();
        int localX = BetterCavesUtils.getLocal(colPos.getX());
        int localZ = BetterCavesUtils.getLocal(colPos.getZ());
        Biome biome = world.getBiome(colPos);
        IBlockState airBlockState = Blocks.AIR.getDefaultState();
        IBlockState airState = Blocks.AIR.getDefaultState();
        IBlockState waterBlockState = Blocks.WATER.getDefaultState();

        // Validate vars
        if (localX < 0 || localX > 15)
            return;
        if (localZ < 0 || localZ > 15)
            return;
        if (bottomY < 0 || bottomY > 255)
            return;
        if (topY < 0 || topY > 255)
            return;

        // Altitude at which caves start closing off so they aren't all open to the surface
        int transitionBoundary = topY - surfaceCutoff;

        // Validate transition boundary
        if (transitionBoundary < 1)
            transitionBoundary = 1;

        // Pre-compute thresholds to ensure accuracy during pre-processing
        float[] thresholds = generateThresholds(topY, bottomY, transitionBoundary);

        // Do some pre-processing on the noises to facilitate better cave generation.
        // Basically this makes caves taller to give players more headroom.
        // See the javadoc for the function for more info.
        if (this.enableYAdjust)
            preprocessCaveNoiseCol(noises, noiseX, noiseZ, topY, bottomY, thresholds, settings.getNumGens());

        /* =============== Dig out caves and caverns in this column, based on noise values =============== */
        for (int y = topY; y >= bottomY; y--) {
            if (y <= settings.getLiquidAltitude() && liquidBlock == null)
                break;

            boolean digBlock = true;
            float threshold = thresholds[y - bottomY];

            for (int i = 0; i < noises.getNumGenerators(); i++) {
                if (noises.get(noiseX, noiseZ, y, i) < threshold) {
                    digBlock = false;
                    break;
                }
            }

            airBlockState = flooded && y < world.getSeaLevel() ? waterBlockState : airState;

            // Dig out the block if it passed the threshold check, using the debug visualizer if enabled
            if (settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlock(primer, worldX, y, worldZ, settings.getDebugBlock(), digBlock);
            }
            else if (digBlock) {
                CarverUtils.digBlock(settings.getWorld(), primer, worldX, y, worldZ, biome, airBlockState, liquidBlock, settings.getLiquidAltitude(), settings.isReplaceFloatingGravel());
            }
        }
    }

    /**
     * Preprocessing performed on a column of noise to adjust its values before comparing them to the threshold.
     * This function adjusts the noise value of blocks based on the noise values of blocks below.
     * This has the effect of raising the ceilings of caves, giving the player more headroom.
     * Big shoutouts to the guys behind Worley's Caves for this great idea.
     * @param noises The cube containing all interpolated noise values for this subchunk.
     * @param noiseX Local x index in the noise cube.
     * @param noiseZ Local z index in the noise cube.
     * @param topY Top y-coordinate of the noise column
     * @param bottomY Bottom y-coordinate of the noise column
     * @param thresholds Array of y-coordinate noise thresholds, indexed by y - bottomY.
     * @param numGens Number of noise values to create per block.
     */
    private void preprocessCaveNoiseCol(NoiseCube noises, int noiseX, int noiseZ, int topY, int bottomY, float[] thresholds, int numGens) {
        /* Adjust simplex noise values based on blocks above in order to give the player more headroom */
        for (int realY = topY; realY >= bottomY; realY--) {
            float threshold = thresholds[realY - bottomY];

            boolean valid = true;
            for (int i = 0; i < numGens; i++) {
                if (noises.get(noiseX, noiseZ, realY, i) < threshold) {
                    valid = false;
                    break;
                }
            }

            // Adjust noise values of blocks above to give the player more head room
            if (valid) {
                float f1 = yAdjustF1;
                float f2 = yAdjustF2;

                // Adjust block one above
                if (realY < topY) {
                    for (int i = 0; i < numGens; i++) {
                        double noise = noises.get(noiseX, noiseZ, realY, i);
                        double noiseAbove = noises.get(noiseX, noiseZ, realY + 1, i);
                        noises.set(noiseX, noiseZ, realY + 1, i, ((1 - f1) * noiseAbove) + (f1 * noise));
                    }
                }

                // Adjust block two above
                if (realY < topY - 1) {
                    for (int i = 0; i < numGens; i++) {
                        double noise = noises.get(noiseX, noiseZ, realY, i);
                        double noiseTwoAbove = noises.get(noiseX, noiseZ, realY + 2, i);
                        noises.set(noiseX, noiseZ, realY + 2, i, ((1 - f2) * noiseTwoAbove) + (f2 * noise));
                    }
                }
            }
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
        for (int realY = bottomY; realY <= topY; realY++) {
            float noiseThreshold = settings.getNoiseThreshold();
            if (realY >= transitionBoundary)
                noiseThreshold *= (1 + .3f * ((float)(realY - transitionBoundary) / (topY - transitionBoundary)));
            thresholds[realY - bottomY] = noiseThreshold;
        }

        return thresholds;
    }

    public NoiseGen getNoiseGen() {
        return noiseGen;
    }

    public CarverSettings getSettings() {
        return settings;
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
