package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.enums.RegionSize;
import com.yungnickyoung.minecraft.bettercaves.noise.ColumnNoiseBuffer;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverNoiseRange;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarverBuilder;
import net.minecraft.init.Blocks;
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
        carvers.removeIf(carver -> carver.getPriority() == 0);
        if (carvers.isEmpty()) {
            return;
        }
        int totalPriority = carvers.stream().map(CavernCarver::getPriority).reduce(0, Integer::sum);

        BetterCaves.LOGGER.debug("CAVERN INFORMATION");
        BetterCaves.LOGGER.debug("--> SPAWN CHANCE SET TO: " + spawnChance);
        BetterCaves.LOGGER.debug("--> TOTAL PRIORITY: " + totalPriority);

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

    public void carveChunk(ChunkCaveContext chunkContext) {
        // Prevent unnecessary computation if caverns are disabled
        if (noiseRanges.isEmpty()) {
            return;
        }

        ChunkPrimer primer = chunkContext.getPrimer();
        boolean flooded = false;
        float smoothAmpFactor = 1;

        int subChunkCount = 16 / BCSettings.SUB_CHUNK_SIZE;
        SubChunkColumnFields fields = new SubChunkColumnFields();
        fields.resetRanges(noiseRanges.size());
        ColumnNoiseBuffer[] noiseBuffers = new ColumnNoiseBuffer[noiseRanges.size()];

        for (int subX = 0; subX < subChunkCount; subX++) {
            for (int subZ = 0; subZ < subChunkCount; subZ++) {
                int startX = subX * BCSettings.SUB_CHUNK_SIZE;
                int startZ = subZ * BCSettings.SUB_CHUNK_SIZE;
                int endX = startX + BCSettings.SUB_CHUNK_SIZE - 1;
                int endZ = startZ + BCSettings.SUB_CHUNK_SIZE - 1;

                fields.clear();
                fields.resetRanges(noiseRanges.size());

                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int columnIndex = offsetX * BCSettings.SUB_CHUNK_SIZE + offsetZ;
                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        int blockX = chunkContext.getBlockX(localX);
                        int blockZ = chunkContext.getBlockZ(localZ);
                        fields.clearColumn(columnIndex);

                        if (isFloodedUndergroundEnabled && !isDebugViewEnabled) {
                            flooded = chunkContext.isOceanColumn(localX, localZ);
                            smoothAmpFactor = chunkContext.getFloodedBoundaryFactor(localX, localZ);
                            if (smoothAmpFactor <= 0) { // Wall between flooded and normal caves.
                                continue; // Continue to prevent unnecessary noise calculation
                            }
                        }

                        int surfaceAltitude = chunkContext.getSurfaceAltitude(localX, localZ);

                        // Get noise values used to determine cavern region
                        float cavernRegionNoise = cavernRegionController.GetNoise(blockX, blockZ);

                        // Carve cavern using matching carver
                        for (int rangeIndex = 0; rangeIndex < noiseRanges.size(); rangeIndex++) {
                            CarverNoiseRange range = noiseRanges.get(rangeIndex);
                            if (!range.contains(cavernRegionNoise)) {
                                continue;
                            }
                            CavernCarver carver = (CavernCarver)range.getCarver();
                            int bottomY = carver.getBottomY();
                            int topY = isDebugViewEnabled ? carver.getTopY() : Math.min(surfaceAltitude, carver.getTopY());
                            if (isOverrideSurfaceDetectionEnabled) {
                                topY = carver.getTopY();
                            }
                            if (topY < bottomY) {
                                continue;
                            }
                            float smoothAmp = range.getSmoothAmp(cavernRegionNoise) * smoothAmpFactor;
                            fields.activeRanges[columnIndex] = range;
                            fields.topYs[columnIndex] = topY;
                            fields.smoothAmps[columnIndex] = smoothAmp;
                            fields.flooded[columnIndex] = flooded;
                            fields.addActiveColumn(columnIndex, rangeIndex);
                            break;
                        }
                    }
                }

                for (int rangeIndex = 0; rangeIndex < noiseRanges.size(); rangeIndex++) {
                    CarverNoiseRange range = noiseRanges.get(rangeIndex);
                    CavernCarver carver = (CavernCarver) range.getCarver();
                    int neededMaxHeight = Integer.MIN_VALUE;
                    int rangeCount = fields.getRangeCount(rangeIndex);
                    int[] rangeColumns = fields.getRangeColumns(rangeIndex);
                    for (int i = 0; i < rangeCount; i++) {
                        int columnIndex = rangeColumns[i];
                        neededMaxHeight = Math.max(neededMaxHeight, fields.topYs[columnIndex]);
                    }
                    if (neededMaxHeight == Integer.MIN_VALUE) {
                        continue;
                    }

                    ColumnNoiseBuffer noiseBuffer = noiseBuffers[rangeIndex];
                    if (noiseBuffer == null) {
                        noiseBuffer = new ColumnNoiseBuffer();
                        noiseBuffers[rangeIndex] = noiseBuffer;
                    }
                    carver.getNoiseGen().interpolateNoiseColumns(
                        noiseBuffer,
                        chunkContext.getBlockX(startX),
                        chunkContext.getBlockX(endX),
                        chunkContext.getBlockZ(startZ),
                        chunkContext.getBlockZ(endZ),
                        carver.getBottomY(),
                        neededMaxHeight,
                        rangeColumns,
                        rangeCount
                    );
                }

                if (fields.activeCount == 0) {
                    continue;
                }

                for (int i = 0; i < fields.activeCount; i++) {
                    int columnIndex = fields.activeColumns[i];
                    int offsetX = columnIndex / BCSettings.SUB_CHUNK_SIZE;
                    int offsetZ = columnIndex % BCSettings.SUB_CHUNK_SIZE;
                    CarverNoiseRange activeRange = fields.activeRanges[columnIndex];
                    int localX = startX + offsetX;
                    int localZ = startZ + offsetZ;

                    CavernCarver carver = (CavernCarver) activeRange.getCarver();
                    carver.carveColumn(
                        primer,
                        localX,
                        localZ,
                        fields.topYs[columnIndex],
                        fields.smoothAmps[columnIndex],
                        noiseBuffers[fields.rangeIndices[columnIndex]],
                        fields.noiseSlots[columnIndex],
                        chunkContext.getLiquidBlock(localX, localZ),
                        fields.flooded[columnIndex],
                        chunkContext.getBiome(localX, localZ)
                    );
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

    public boolean needsBiomeCache() {
        return hasWork();
    }

    public int getMaxSurfaceSearchY() {
        int maxTopY = 0;
        for (CarverNoiseRange range : noiseRanges) {
            maxTopY = Math.max(maxTopY, range.getCarver().getTopY());
        }
        return maxTopY;
    }
}
