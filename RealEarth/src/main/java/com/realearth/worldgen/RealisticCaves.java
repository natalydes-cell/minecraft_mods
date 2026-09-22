package com.realearth.worldgen;

import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Carves caves whose shape follows the rock actually under your feet.
 *
 * <p>Real caves are not one phenomenon. Limestone dissolves in slightly acidic groundwater and
 * produces long, near-horizontal galleries following the water table - Mammoth Cave, Postojna,
 * the whole karst world. Granite does not dissolve at all, so its voids are short, angular
 * fractures. Basalt gets lava tubes: smooth, round, and steeply following the old flow direction.
 * Vanilla's carvers produce the same worm everywhere and cannot express any of that.
 *
 * <p>The rock type comes from the geology raster. Where the operator has supplied no geology data
 * it is inferred from elevation and terrain age instead, which gets the common cases right:
 * lowland sedimentary basins are usually limestone, high eroded ranges are usually crystalline.
 */
public final class RealisticCaves {

    private RealisticCaves() {}

    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();

    /** Rock families, in the order the geology raster indexes them. */
    public enum RockType {
        /** Dissolves: long horizontal galleries, sinkholes, large chambers. */
        LIMESTONE,
        /** Crystalline: short angular fracture voids only. */
        GRANITE,
        /** Volcanic: lava tubes, round and steeply dipping. */
        BASALT,
        /** Soft sediment: few caves, and the ones that exist collapse into rubble. */
        SANDSTONE,
        /** Deep metamorphic: almost no voids at all. */
        SCHIST
    }

    public static void carve(WorldGenRegion level, ChunkAccess chunk, RandomState random,
                             GenerationStep.Carving step, EarthData data, GeoProjection proj) {
        // Only the air pass carves; the liquid pass is left to vanilla's aquifer handling.
        if (step != GenerationStep.Carving.AIR) return;

        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        double lat = proj.latitude(originZ + 8);
        double lon = proj.longitude(originX + 8);

        RockType rock = rockAt(data, lat, lon);
        CaveProfile profile = profileFor(rock);

        // One deterministic seed per chunk column, so caves are identical on server and client
        // and identical between two players generating the same chunk independently.
        long seed = mix(originX, originZ);

        int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, 8, 8);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int tunnel = 0; tunnel < profile.tunnelsPerChunk; tunnel++) {
            seed = nextSeed(seed);
            double r = unit(seed);
            if (r > profile.density) continue;

            // Tunnels sit in a depth band rather than anywhere: karst follows the water table,
            // lava tubes sit near the old surface, fractures go deep.
            int depth = profile.minDepth
                    + (int) (unit(nextSeed(seed)) * (profile.maxDepth - profile.minDepth));
            int y = surface - depth;
            if (y <= chunk.getMinBuildHeight() + 8) continue;

            carveTunnel(chunk, cursor, seed, y, profile);
        }
    }

    private static void carveTunnel(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                    long seed, int startY, CaveProfile profile) {
        double x = 8 + (unit(seed) - 0.5) * 12;
        double z = 8 + (unit(nextSeed(seed)) - 0.5) * 12;
        double y = startY;

        double headingXZ = unit(nextSeed(nextSeed(seed))) * Math.PI * 2;
        double headingY = (unit(seed ^ 0x5DEECE66DL) - 0.5) * profile.verticality;

        long s = seed;
        for (int stepIndex = 0; stepIndex < profile.length; stepIndex++) {
            s = nextSeed(s);
            headingXZ += (unit(s) - 0.5) * profile.wander;
            headingY += (unit(nextSeed(s)) - 0.5) * profile.wander * profile.verticality;
            headingY = Math.max(-1.0, Math.min(1.0, headingY));

            x += Math.cos(headingXZ);
            z += Math.sin(headingXZ);
            y += headingY;

            if (x < -4 || x > 20 || z < -4 || z > 20) break;
            if (y <= chunk.getMinBuildHeight() + 4) break;

            double radius = profile.minRadius
                    + unit(s >> 8) * (profile.maxRadius - profile.minRadius);
            sphere(chunk, cursor, x, y, z, radius);
        }
    }

    private static void sphere(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                               double cx, double cy, double cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            int lx = (int) Math.round(cx) + dx;
            if (lx < 0 || lx > 15) continue;
            for (int dz = -r; dz <= r; dz++) {
                int lz = (int) Math.round(cz) + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dy = -r; dy <= r; dy++) {
                    int ly = (int) Math.round(cy) + dy;
                    if (ly < chunk.getMinBuildHeight() || ly >= chunk.getMaxBuildHeight()) continue;
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    BlockState existing = chunk.getBlockState(cursor.set(lx, ly, lz));
                    // Never open a cave into water or through bedrock: one floods the system and
                    // the other punches a hole in the world floor.
                    if (existing.isAir() || existing.liquid()
                            || existing.is(Blocks.BEDROCK)) continue;
                    chunk.setBlockState(cursor.set(lx, ly, lz), AIR, false);
                }
            }
        }
    }

    /** Shape parameters for one rock family. */
    private record CaveProfile(int tunnelsPerChunk, double density, int length,
                               double minRadius, double maxRadius,
                               int minDepth, int maxDepth,
                               double wander, double verticality) {}

    private static CaveProfile profileFor(RockType rock) {
        return switch (rock) {
            // Karst: many long, wide, near-horizontal galleries in a band around the water table.
            case LIMESTONE -> new CaveProfile(6, 0.85, 110, 1.8, 4.5, 20, 220, 0.30, 0.18);
            // Fractures: short, narrow, steeply dipping, and they go deep.
            case GRANITE   -> new CaveProfile(3, 0.45, 32, 1.0, 2.0, 40, 700, 0.75, 0.85);
            // Lava tubes: long, round, consistent, following the old flow downhill.
            case BASALT    -> new CaveProfile(2, 0.55, 140, 2.2, 3.4, 10, 120, 0.12, 0.45);
            // Weak sediment: rare and small; most of it has already collapsed.
            case SANDSTONE -> new CaveProfile(2, 0.30, 45, 1.2, 2.4, 15, 140, 0.40, 0.25);
            case SCHIST    -> new CaveProfile(2, 0.20, 28, 0.9, 1.7, 60, 800, 0.70, 0.75);
        };
    }

    /**
     * Rock family here. Uses the geology raster if present, otherwise infers from elevation:
     * low flat ground is usually a sedimentary basin, high ground is usually crystalline
     * basement exposed by erosion.
     */
    public static RockType rockAt(EarthData data, double lat, double lon) {
        int glim = data.geology(lat, lon);
        if (glim >= 1 && glim <= 16) return fromGlim(glim);

        // No geology data here. Elevation is a decent proxy: low flat ground is usually a
        // sedimentary basin, high ground is usually crystalline basement exposed by erosion.
        double metres = data.elevation(lat, lon);
        if (metres > 2500) return RockType.GRANITE;
        if (metres > 900) return RockType.SCHIST;
        if (metres < 200) return RockType.LIMESTONE;
        return RockType.SANDSTONE;
    }

    /**
     * Maps a GLiM lithology class to a cave-forming rock family.
     *
     * <p>The codes are the ones in the dataset's own {@code Classnames.txt}, in its value order.
     * The grouping is by what the rock does to water, which is the only property that decides
     * what kind of void forms in it:
     *
     * <ul>
     *   <li><b>Carbonates and evaporites dissolve.</b> That is where real cave systems come from -
     *       Mammoth Cave, Postojna, the whole karst world. Evaporites dissolve faster than
     *       limestone does, which is why they are grouped with it rather than with sediments.</li>
     *   <li><b>Crystalline rock does not dissolve at all,</b> so plutonics and metamorphics only
     *       ever get short angular voids along fractures.</li>
     *   <li><b>Volcanics get lava tubes,</b> which are a completely different shape: round, smooth
     *       and following the old flow downhill.</li>
     *   <li><b>Loose and clastic sediment</b> barely holds a void open; what forms tends to
     *       collapse into rubble.</li>
     * </ul>
     */
    private static RockType fromGlim(int glim) {
        return switch (glim) {
            case 6 -> RockType.LIMESTONE;   // sc - carbonate sedimentary
            case 14 -> RockType.LIMESTONE;  // ev - evaporites, soluble and then some
            case 2 -> RockType.BASALT;      // vb - basic volcanic
            case 7 -> RockType.BASALT;      // va - acid volcanic
            case 10 -> RockType.BASALT;     // vi - intermediate volcanic
            case 12 -> RockType.BASALT;     // py - pyroclastics
            case 4 -> RockType.GRANITE;     // pb - basic plutonic
            case 9 -> RockType.GRANITE;     // pa - acid plutonic
            case 13 -> RockType.GRANITE;    // pi - intermediate plutonic
            case 8 -> RockType.SCHIST;      // mt - metamorphic
            case 1 -> RockType.SANDSTONE;   // su - unconsolidated sediment
            case 3 -> RockType.SANDSTONE;   // ss - siliciclastic sedimentary
            case 5 -> RockType.SANDSTONE;   // sm - mixed sedimentary
            // wb water, nd no data, ig ice and glaciers: nothing sensible to carve into.
            default -> RockType.SCHIST;
        };
    }

    // --- Deterministic scalar PRNG -------------------------------------------------------
    // Not java.util.Random: this must produce the same caves for the same coordinates on every
    // machine, independent of world seed, so that two clients generating the same chunk agree.

    private static long mix(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 27);
    }

    private static long nextSeed(long s) {
        s ^= s << 13;
        s ^= s >>> 7;
        s ^= s << 17;
        return s;
    }

    /** Uniform in [0, 1). */
    private static double unit(long s) {
        return ((s >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
    }
}
