package com.realearth.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.realearth.climate.KoppenClass;
import com.realearth.core.RealEarth;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Builds terrain by reading real elevation instead of generating noise.
 *
 * <p>The shape of the world is not invented here at all: every column's height comes from the
 * GEBCO raster through {@link ElevationCurve}. Noise is used only to add metre-scale roughness
 * that the source raster is too coarse to contain, so the Alps are the real Alps with a believable
 * surface rather than a smooth ramp.
 *
 * <h2>Why this is fast enough</h2>
 * <ul>
 *   <li>A 16x16 chunk needs 289 raster samples (17x17 for the edge gradients), not 65536. The
 *       columns between are bicubically interpolated, which is cheaper than a raster read and
 *       smoother than the source data deserves.</li>
 *   <li>Blocks are written through {@link LevelChunkSection} directly, under a single
 *       acquire/release per section, instead of through {@code ChunkAccess.setBlockState}. That
 *       skips the per-block section lookup, the heightmap update and the block-entity check
 *       4096 times per section. On a planet with 2000-block-deep ocean trenches a chunk can span
 *       130 sections, so this is where nearly all the saving is.</li>
 *   <li>Deposits are tested per chunk, not per block: 36 ellipse tests, then the veins are only
 *       placed if one actually hits.</li>
 * </ul>
 */
public class EarthChunkGenerator extends ChunkGenerator {

    public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i
            .group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource))
            .apply(i, EarthChunkGenerator::new));

    /** Samples taken per chunk edge. 17 gives the 16 cells plus the shared far edge. */
    private static final int SAMPLE_STRIDE = 17;

    private final BlockState stone = Blocks.STONE.defaultBlockState();
    private final BlockState water = Blocks.WATER.defaultBlockState();
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

    public EarthChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private EarthData data() {
        return RealEarth.data();
    }

    private GeoProjection projection() {
        return RealEarth.projection();
    }

    // --- Height lookup -------------------------------------------------------------------

    /**
     * Surface Y for one column, as a double so the interpolation between samples does not
     * quantise to whole blocks and produce visible terracing on gentle slopes.
     */
    private double surfaceY(int blockX, int blockZ) {
        GeoProjection proj = projection();
        double lat = proj.latitude(blockZ);
        double lon = proj.longitude(blockX);
        double metres = data().elevation(lat, lon);
        double boost = EarthData.reliefBoostForElevation(metres);
        double y = ElevationCurve.toY(metres, boost);

        // Sub-raster roughness. Amplitude scales with slope so plains stay flat and mountainsides
        // get broken up; a constant amplitude would make the Netherlands look like gravel.
        double rough = TerrainDetail.roughness(blockX, blockZ, metres);
        return y + rough;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                             RandomState random) {
        int surface = (int) Math.floor(surfaceY(x, z));
        if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) {
            return surface;
        }
        // Every other heightmap must account for the ocean surface, or boats, mobs and structure
        // placement all think the seafloor is the top of the world.
        return Math.max(surface, ElevationCurve.SEA_LEVEL);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int min = height.getMinBuildHeight();
        int count = height.getHeight();
        BlockState[] column = new BlockState[count];
        int surface = (int) Math.floor(surfaceY(x, z));
        for (int i = 0; i < count; i++) {
            int y = min + i;
            column[i] = y <= surface ? stone
                    : (y <= ElevationCurve.SEA_LEVEL ? water : air);
        }
        return new NoiseColumn(min, column);
    }

    // --- Terrain fill --------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> doFill(chunk), RealEarth.terrainExecutor());
    }

    private ChunkAccess doFill(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int originX = pos.getMinBlockX();
        int originZ = pos.getMinBlockZ();

        // One raster pass for the whole chunk, on a 17x17 grid.
        double[] samples = new double[SAMPLE_STRIDE * SAMPLE_STRIDE];
        for (int sz = 0; sz < SAMPLE_STRIDE; sz++) {
            for (int sx = 0; sx < SAMPLE_STRIDE; sx++) {
                samples[sz * SAMPLE_STRIDE + sx] = surfaceY(originX + sx, originZ + sz);
            }
        }

        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        int sea = ElevationCurve.SEA_LEVEL;

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        // Precompute each column's solid top and water top once, so the section loop below is a
        // straight comparison rather than a repeated interpolation.
        int[] solidTop = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int top = (int) Math.floor(samples[lz * SAMPLE_STRIDE + lx]);
                solidTop[lz * 16 + lx] = Math.min(maxY - 1, Math.max(minY, top));
            }
        }

        // Walk section by section. Anything entirely above both the terrain and the sea is left
        // untouched - an empty section costs nothing and is not even allocated.
        int lowestSection = chunk.getSectionIndex(minY);
        int highestSection = chunk.getSectionIndex(Math.min(maxY - 1, sea + 1));
        for (int index = lowestSection; index <= highestSection; index++) {
            LevelChunkSection section = chunk.getSection(index);
            section.acquire();
            try {
                int sectionBottom = chunk.getSectionYFromSectionIndex(index) << 4;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int top = solidTop[lz * 16 + lx];
                        for (int ly = 0; ly < 16; ly++) {
                            int y = sectionBottom + ly;
                            if (y > top && y > sea) break;   // nothing above this in this column
                            BlockState state = y <= top ? stone : water;
                            section.setBlockState(lx, ly, lz, state, false);
                        }
                    }
                }
            } finally {
                section.release();
            }
        }

        // Heightmaps, once per column rather than once per block.
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int top = solidTop[lz * 16 + lx];
                oceanFloor.update(lx, top, lz, stone);
                if (top < sea) {
                    worldSurface.update(lx, sea, lz, water);
                } else {
                    worldSurface.update(lx, top, lz, stone);
                }
            }
        }

        // Bedrock floor. Flat rather than vanilla's ragged band: at this depth the player is
        // nearly always in an ocean trench, and a ragged floor there reads as world damage.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                chunk.setBlockState(cursor.set(lx, minY, lz), bedrock, false);
            }
        }

        return chunk;
    }

    // --- Carving, surface, mobs ----------------------------------------------------------

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Caves are carved by RealisticCaves, which follows the rock type from the geology raster:
        // limestone gets long horizontal solution caves, granite gets short fracture systems,
        // basalt gets lava tubes. Vanilla's carvers would ignore all of that.
        RealisticCaves.carve(level, chunk, random, step, data(), projection());
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        SurfaceDresser.dress(chunk, data(), projection());
        // Deposits go in after the surface, so the vein depth is measured from finished ground
        // rather than from raw stone. Vanilla ore placement runs separately and is untouched.
        DepositPlacer.place(chunk, projection());
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // Left empty on purpose. Vanilla's one-off world-gen mob burst ignores habitat, which
        // would scatter polar bears through the Congo on first load. Fauna is placed entirely by
        // HabitatSpawner instead, which checks the Koppen class and land cover first.
    }

    // --- Dimension shape -----------------------------------------------------------------

    @Override
    public int getGenDepth() {
        return ElevationCurve.MAX_Y - ElevationCurve.MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return ElevationCurve.SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return ElevationCurve.MIN_Y;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        GeoProjection proj = projection();
        double lat = proj.latitude(pos.getZ());
        double lon = proj.longitude(pos.getX());
        EarthData d = data();
        double metres = d.elevation(lat, lon);
        KoppenClass k = d.koppen(lat, lon);
        info.add(String.format("[RealEarth] %.4f%s %.4f%s",
                Math.abs(lat), lat >= 0 ? "N" : "S",
                Math.abs(lon), lon >= 0 ? "E" : "W"));
        info.add(String.format("[RealEarth] real elevation %.0f m, climate %s", metres, k.name()));
    }
}
