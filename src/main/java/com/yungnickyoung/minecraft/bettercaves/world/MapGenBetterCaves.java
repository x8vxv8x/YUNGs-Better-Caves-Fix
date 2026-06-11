package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.config.io.ConfigLoader;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.bedrock.FlattenBedrock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraftforge.event.terraingen.InitMapGenEvent;

import javax.annotation.Nonnull;

/**
 * Class that overrides vanilla cave gen with Better Caves gen.
 * Combines multiple types of caves and caverns using different types of noise to create a
 * novel underground experience.
 */
public class MapGenBetterCaves extends MapGenCaves {
    // Vanilla cave gen if user sets config to use it
    private MapGenBase defaultCaveGen;

    // Region Controllers
    public WaterRegionController waterRegionController;
    private CaveCarverController caveCarverController;
    private CavernCarverController cavernCarverController;

    // Config holder for options specific to this carver
    public ConfigHolder config;

    public MapGenBetterCaves(InitMapGenEvent event) {
        this.defaultCaveGen = event.getOriginalGen();
    }

    /**
     * Function for generating Better Caves in a single chunk. This overrides the vanilla cave generation, which is
     * ordinarily performed by the MapGenCaves class.
     * This function is called for every new chunk that is generated in a world.
     * @param worldIn The Minecraft world
     * @param chunkX The chunk's x-coordinate (on the chunk grid, not the block grid)
     * @param chunkZ The chunk's z-coordinate (on the chunk grid, not the block grid)
     * @param primer The chunk's ChunkPrimer
     */
    @Override
    public synchronized void generate(World worldIn, int chunkX, int chunkZ, @Nonnull ChunkPrimer primer) {
        // Only operate on whitelisted dimensions.
        if (!BetterCavesUtils.isDimensionWhitelisted(worldIn.provider.getDimension())) {
            defaultCaveGen.generate(worldIn, chunkX, chunkZ, primer);
            return;
        }

        if (world == null || world != worldIn) { // Lazy initialization of all controllers and config
            this.initialize(worldIn);
        }

        // Flatten bedrock, if enabled
        if (config.flattenBedrock.get())
            FlattenBedrock.flattenBedrock(primer, config.bedrockWidth.get());

        boolean caveHasWork = caveCarverController.hasWork();
        boolean cavernHasWork = cavernCarverController.hasWork();
        boolean needsSurfaceAltitudes = (caveHasWork && caveCarverController.needsSurfaceAltitudes())
            || (cavernHasWork && cavernCarverController.needsSurfaceAltitudes());
        boolean needsOceanMask = (caveHasWork && caveCarverController.needsOceanMask())
            || (cavernHasWork && cavernCarverController.needsOceanMask());
        boolean needsBiomeDistanceFactors = cavernHasWork && cavernCarverController.needsOceanMask();
        boolean needsLiquidBlocks = caveHasWork || cavernHasWork;
        boolean needsBiomeCache = (caveHasWork && caveCarverController.needsBiomeCache())
            || (cavernHasWork && cavernCarverController.needsBiomeCache());
        int surfaceSearchTopY = 255;
        if (needsSurfaceAltitudes) {
            surfaceSearchTopY = Math.max(
                caveHasWork && caveCarverController.needsSurfaceAltitudes() ? caveCarverController.getMaxSurfaceSearchY() : 0,
                cavernHasWork && cavernCarverController.needsSurfaceAltitudes() ? cavernCarverController.getMaxSurfaceSearchY() : 0
            );
        }

        ChunkCaveContext chunkContext = new ChunkCaveContext(
            worldIn,
            primer,
            chunkX,
            chunkZ,
            config,
            waterRegionController,
            needsSurfaceAltitudes,
            needsOceanMask,
            needsBiomeDistanceFactors,
            needsLiquidBlocks,
            needsBiomeCache,
            surfaceSearchTopY
        );

        // Carve chunk
        if (caveHasWork) {
            caveCarverController.carveChunk(chunkContext);
        }
        if (cavernHasWork) {
            cavernCarverController.carveChunk(chunkContext);
        }
    }

    /**
     * Initialize Better Caves carvers and controllers for this dimension.
     * @param worldIn The minecraft world
     */
    private void initialize(World worldIn) {
        // Extract world information
        this.world = worldIn;

        // Load config for this dimension
        this.config = ConfigLoader.loadConfigFromFileForDimension(world.provider.getDimension());

        // Initialize controllers
        this.waterRegionController = new WaterRegionController(world, config);
        this.caveCarverController = new CaveCarverController(world, config);
        this.cavernCarverController = new CavernCarverController(worldIn, config);
    }
}
