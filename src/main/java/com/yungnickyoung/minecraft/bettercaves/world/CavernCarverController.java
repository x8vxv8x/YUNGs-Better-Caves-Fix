package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.enums.RegionSize;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseUtils;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverNoiseRange;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarverBuilder;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

import java.util.ArrayList;
import java.util.List;

public class CavernCarverController {
    private World world;
    private FastNoise cavernRegionController;
    private List<CarverNoiseRange> noiseRanges = new ArrayList<>();

    // Vars from config
    private boolean isDebugViewEnabled;
    private boolean isOverrideSurfaceDetectionEnabled;
    private boolean isFloodedUndergroundEnabled;

    public CavernCarverController(World worldIn, ConfigHolder config) {
        this.world = worldIn;
        this.isDebugViewEnabled = config.debugVisualizer.get();
        this.isOverrideSurfaceDetectionEnabled = config.overrideSurfaceDetection.get();
        this.isFloodedUndergroundEnabled = config.enableFloodedUnderground.get();

        // Configure cavern region controller, which determines what type of cavern should be carved in any given region
        float cavernRegionSize = calcCavernRegionSize(config.cavernRegionSize.get(), config.cavernRegionCustomSize.get());
        this.cavernRegionController = new FastNoise();
        this.cavernRegionController.SetSeed((int)worldIn.getSeed() + 333);
        this.cavernRegionController.SetFrequency(cavernRegionSize);

        // Initialize all carvers using config options
        List<CavernCarver> carvers = new ArrayList<>();
        carvers.add(new CavernCarverBuilder(worldIn)
            .ofTypeFromConfig(CavernType.LIQUID, config)
            .debugVisualizerBlock(Blocks.REDSTONE_BLOCK.getDefaultState())
            .build()
        );
        carvers.add(new CavernCarverBuilder(worldIn)
            .ofTypeFromConfig(CavernType.FLOORED, config)
            .debugVisualizerBlock(Blocks.GOLD_BLOCK.getDefaultState())
            .build()
        );

        float spawnChance = config.cavernSpawnChance.get() / 100f;
        int totalPriority = carvers.stream().map(CavernCarver::getPriority).reduce(0, Integer::sum);

        BetterCaves.LOGGER.debug("CAVERN INFORMATION");
        BetterCaves.LOGGER.debug("--> SPAWN CHANCE SET TO: " + spawnChance);
        BetterCaves.LOGGER.debug("--> TOTAL PRIORITY: " + totalPriority);

        carvers.removeIf(carver -> carver.getPriority() == 0);
        float totalDeadzonePercent = 1 - spawnChance;
        float deadzonePercent = carvers.size() > 1
                ? totalDeadzonePercent / (carvers.size() - 1)
                : totalDeadzonePercent;

        BetterCaves.LOGGER.debug("--> DEADZONE PERCENT: " + deadzonePercent + "(" + totalDeadzonePercent + " TOTAL)");

        float currNoise = -1f;

        for (CavernCarver carver : carvers) {
            BetterCaves.LOGGER.debug("--> CARVER");
            float rangeCDFPercent = (float)carver.getPriority() / totalPriority * spawnChance;
            float topNoise = NoiseUtils.simplexNoiseOffsetByPercent(currNoise, rangeCDFPercent);
            CarverNoiseRange range = new CarverNoiseRange(currNoise, topNoise, carver);
            noiseRanges.add(range);

            // Offset currNoise for deadzone region
            currNoise = NoiseUtils.simplexNoiseOffsetByPercent(topNoise, deadzonePercent);

            BetterCaves.LOGGER.debug("    --> RANGE PERCENT LENGTH WANTED: " + rangeCDFPercent);
            BetterCaves.LOGGER.debug("    --> RANGE FOUND: " + range);
        }
    }

    public void carveChunk(ChunkPrimer primer, int chunkX, int chunkZ, int[][] surfaceAltitudes, IBlockState[][] liquidBlocks, boolean[][] oceanMask) {
        // Prevent unnecessary computation if caverns are disabled
        if (noiseRanges.size() == 0) {
            return;
        }

        boolean flooded = false;
        float smoothAmpFactor = 1;

        BlockPos.MutableBlockPos colPos = new BlockPos.MutableBlockPos();
        int subChunkCount = 16 / BCSettings.SUB_CHUNK_SIZE;
        CarverNoiseRange[] activeRanges = new CarverNoiseRange[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];
        int[] activeTopYs = new int[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];
        float[] activeSmoothAmps = new float[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];
        boolean[] activeFlooded = new boolean[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];

        for (int subX = 0; subX < subChunkCount; subX++) {
            for (int subZ = 0; subZ < subChunkCount; subZ++) {
                int startX = subX * BCSettings.SUB_CHUNK_SIZE;
                int startZ = subZ * BCSettings.SUB_CHUNK_SIZE;
                int endX = startX + BCSettings.SUB_CHUNK_SIZE - 1;
                int endZ = startZ + BCSettings.SUB_CHUNK_SIZE - 1;

                noiseRanges.forEach(range -> range.setNoiseCube(null));
                int activeColumnCount = 0;

                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int columnIndex = offsetX * BCSettings.SUB_CHUNK_SIZE + offsetZ;
                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        colPos.setPos(chunkX * 16 + localX, 1, chunkZ * 16 + localZ);
                        activeRanges[columnIndex] = null;
                        activeTopYs[columnIndex] = 0;
                        activeSmoothAmps[columnIndex] = 0;
                        activeFlooded[columnIndex] = false;

                        if (isFloodedUndergroundEnabled && !isDebugViewEnabled && oceanMask != null) {
                            flooded = oceanMask[localX + 2][localZ + 2];
                            smoothAmpFactor = BetterCavesUtils.biomeDistanceFactor(localX, localZ, 2, oceanMask, !flooded);
                            if (smoothAmpFactor <= 0) { // Wall between flooded and normal caves.
                                continue; // Continue to prevent unnecessary noise calculation
                            }
                        }

                        int surfaceAltitude = surfaceAltitudes[localX][localZ];
                        IBlockState liquidBlock = liquidBlocks[localX][localZ];

                        // Get noise values used to determine cavern region
                        float cavernRegionNoise = cavernRegionController.GetNoise(colPos.getX(), colPos.getZ());

                        // Carve cavern using matching carver
                        for (CarverNoiseRange range : noiseRanges) {
                            if (!range.contains(cavernRegionNoise)) {
                                continue;
                            }
                            CavernCarver carver = (CavernCarver)range.getCarver();
                            int bottomY = carver.getBottomY();
                            int topY = isDebugViewEnabled ? carver.getTopY() : Math.min(surfaceAltitude, carver.getTopY());
                            if (isOverrideSurfaceDetectionEnabled) {
                                topY = carver.getTopY();
                            }
                            float smoothAmp = range.getSmoothAmp(cavernRegionNoise) * smoothAmpFactor;
                            activeRanges[columnIndex] = range;
                            activeTopYs[columnIndex] = topY;
                            activeSmoothAmps[columnIndex] = smoothAmp;
                            activeFlooded[columnIndex] = flooded;
                            activeColumnCount++;
                            break;
                        }
                    }
                }

                for (CarverNoiseRange range : noiseRanges) {
                    CavernCarver carver = (CavernCarver) range.getCarver();
                    int neededMaxHeight = Integer.MIN_VALUE;
                    for (int i = 0; i < activeRanges.length; i++) {
                        if (activeRanges[i] == range) {
                            neededMaxHeight = Math.max(neededMaxHeight, activeTopYs[i]);
                        }
                    }
                    if (neededMaxHeight == Integer.MIN_VALUE) {
                        continue;
                    }

                    range.setNoiseCube(carver.getNoiseGen().interpolateNoiseCube(
                        chunkX * 16 + startX,
                        chunkX * 16 + endX,
                        chunkZ * 16 + startZ,
                        chunkZ * 16 + endZ,
                        carver.getBottomY(),
                        neededMaxHeight
                    ));
                }

                if (activeColumnCount == 0) {
                    continue;
                }

                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int columnIndex = offsetX * BCSettings.SUB_CHUNK_SIZE + offsetZ;
                        CarverNoiseRange activeRange = activeRanges[columnIndex];
                        if (activeRange == null) {
                            continue;
                        }

                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        colPos.setPos(chunkX * 16 + localX, 1, chunkZ * 16 + localZ);

                        CavernCarver carver = (CavernCarver) activeRange.getCarver();
                        carver.carveColumn(
                            primer,
                            colPos,
                            activeTopYs[columnIndex],
                            activeSmoothAmps[columnIndex],
                            activeRange.getNoiseCube(),
                            offsetX,
                            offsetZ,
                            liquidBlocks[localX][localZ],
                            activeFlooded[columnIndex]
                        );
                    }
                }
            }
        }
    }

    /**
     * @return frequency value for cavern region controller
     */
    private float calcCavernRegionSize(RegionSize cavernRegionSize, float cavernRegionCustomSize) {
        switch (cavernRegionSize) {
            case Small:
                return .01f;
            case Large:
                return .005f;
            case ExtraLarge:
                return .001f;
            case Custom:
                return cavernRegionCustomSize;
            default: // Medium
                return .007f;
        }
    }

    public boolean hasWork() {
        return !noiseRanges.isEmpty();
    }

    public boolean needsSurfaceAltitudes() {
        return hasWork();
    }

    public boolean needsOceanMask() {
        return isFloodedUndergroundEnabled && hasWork();
    }
}
