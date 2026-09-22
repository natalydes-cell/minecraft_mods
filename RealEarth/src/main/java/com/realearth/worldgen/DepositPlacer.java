package com.realearth.worldgen;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.util.GeoProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Places the real mineral provinces on top of vanilla ore generation.
 *
 * <p><b>Additive, always.</b> Vanilla ore placement is never removed, moved or reduced, so a
 * modpack that expects normal copper and zinc distribution - Create Aeronautics above all - keeps
 * working exactly as it did. A deposit only adds extra veins inside its own footprint. Replacing
 * vanilla ore placement is the single fastest way to break a modpack, and this deliberately does
 * not do it.
 *
 * <p><b>Depths are real.</b> A vein is placed at the deposit's true geological depth, run through
 * {@link ElevationCurve}, so Ghawar oil sits under two kilometres of rock while Powder River coal
 * is near enough the surface to strip-mine. That is what gives prospecting a reason to exist.
 */
public final class DepositPlacer {

    private DepositPlacer() {}

    private static final List<Deposit> DEPOSITS = DepositRegistry.builtin();

    /** Blocks a vein may replace. Never touches air, water, or anything already placed. */
    private static boolean replaceable(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.TUFF);
    }

    public static void place(ChunkAccess chunk, GeoProjection proj) {
        if (!RealEarthConfig.ENABLE_DEPOSITS.get()) return;

        ChunkPos pos = chunk.getPos();
        double lat = proj.latitude(pos.getMiddleBlockZ());
        double lon = proj.longitude(pos.getMiddleBlockX());

        List<Deposit> here = DepositRegistry.at(DEPOSITS, lat, lon);
        if (here.isEmpty()) return;   // true for nearly the whole planet, and cheap to find out

        DepositBlocks blocks = RealEarth.depositBlocks();

        // Deterministic per chunk: the same province in the same chunk always yields the same
        // veins, on every machine, without consulting the world seed. A player who finds a seam
        // and logs out finds the same seam when they return.
        long seed = mix(pos.x, pos.z);

        for (Deposit deposit : here) {
            double density = deposit.densityAt(lat, lon);
            if (density <= 0) continue;

            BlockState ore = resolve(blocks, deposit, chunk);
            if (ore == null) continue;   // no block configured for this resource

            int veins = (int) Math.floor(deposit.richness() * density);
            // The fractional remainder becomes a chance, so a province edge thins out smoothly
            // instead of stepping down in whole veins.
            double remainder = deposit.richness() * density - veins;
            seed = next(seed);
            if (unit(seed) < remainder) veins++;

            for (int i = 0; i < veins; i++) {
                seed = next(seed);
                placeVein(chunk, deposit, ore, seed, proj);
            }
        }
    }

    private static void placeVein(ChunkAccess chunk, Deposit deposit, BlockState ore,
                                  long seed, GeoProjection proj) {
        int lx = (int) (unit(seed) * 16);
        int lz = (int) (unit(next(seed)) * 16);

        int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, lz);

        // Pick a depth inside the deposit's real band, then convert that metre depth into a
        // Minecraft Y through the same curve the terrain used. Below sea level the curve is
        // compressed, so a metre is worth less than a block down there and assuming otherwise
        // would put ocean-floor deposits hundreds of blocks too deep.
        double t = unit(next(next(seed)));
        double depthM = deposit.minDepthM() + t * (deposit.maxDepthM() - deposit.minDepthM());
        double surfaceM = ElevationCurve.toMetres(surface, 1.0);
        int y = ElevationCurve.toBlockY(surfaceM - depthM, 1.0);

        if (y <= chunk.getMinBuildHeight() + 2 || y >= surface) return;

        int size = 4 + (int) (unit(seed ^ 0x5DEECE66DL) * 8);
        blob(chunk, lx, y, lz, size, ore, seed);
    }

    /** A rough ellipsoid of ore, elongated horizontally the way a real seam is. */
    private static void blob(ChunkAccess chunk, int cx, int cy, int cz, int size,
                             BlockState ore, long seed) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long s = seed;

        int rx = size;
        int rz = size;
        int ry = Math.max(1, size / 3);   // seams are wide and thin, not spherical

        for (int dx = -rx; dx <= rx; dx++) {
            int x = cx + dx;
            if (x < 0 || x > 15) continue;
            for (int dz = -rz; dz <= rz; dz++) {
                int z = cz + dz;
                if (z < 0 || z > 15) continue;
                for (int dy = -ry; dy <= ry; dy++) {
                    int y = cy + dy;
                    if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) continue;

                    double n = (dx * dx) / (double) (rx * rx)
                             + (dy * dy) / (double) (ry * ry)
                             + (dz * dz) / (double) (rz * rz);
                    if (n > 1.0) continue;

                    s = next(s);
                    // Ragged edge: the outer shell is patchy rather than a clean ellipsoid.
                    if (n > 0.55 && unit(s) < (n - 0.55) / 0.45) continue;

                    if (!replaceable(chunk.getBlockState(cursor.set(x, y, z)))) continue;
                    chunk.setBlockState(cursor.set(x, y, z), ore, false);
                }
            }
        }
    }

    /**
     * Picks the ore block for this deposit, preferring the deepslate variant deep down.
     *
     * <p>Returns null when nothing in the configured list exists, which is the normal case for
     * oil and the other resources vanilla has no block for. The caller skips silently; the
     * startup report is where the operator finds out.
     */
    private static BlockState resolve(DepositBlocks blocks, Deposit deposit, ChunkAccess chunk) {
        // The common ore tag first. NeoForge packs agree on c:ores/<material>, so a pack with
        // any tin mod at all answers "what is tin ore here" without anyone editing a config.
        // This is what makes the modded half of the deposit table work out of the box instead
        // of needing a hand-written id per modpack.
        BlockState tagged = resolveTag(deposit.resource());
        if (tagged != null) return tagged;

        List<String> ids = blocks.candidatesFor(deposit.resource());
        if (ids.isEmpty()) return null;

        // Deep provinces get the second id when one is listed - that is the deepslate variant.
        boolean deep = deposit.minDepthM() > 300 && ids.size() > 1;
        List<String> order = deep ? List.of(ids.get(1), ids.get(0)) : ids;

        for (String id : order) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) continue;
            Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (block != null && block != Blocks.AIR) return block.defaultBlockState();
        }
        return null;
    }

    /**
     * Looks the resource up as {@code c:ores/<material>}, the convention every NeoForge ore mod
     * follows. Returns null for resources that have no such tag - oil and gas are fluids in every
     * mod that has them, so there is nothing tagged as an ore to find.
     */
    private static BlockState resolveTag(Deposit.Resource resource) {
        String material = switch (resource) {
            case NICKEL -> "nickel";
            case TIN -> "tin";
            case BAUXITE -> "aluminum";
            case URANIUM -> "uranium";
            case POTASH, SALT -> "salt";
            // Vanilla resources are left to the explicit id list: the tag would also match every
            // modded variant, and coal should be coal, not whatever a tech mod added last.
            default -> null;
        };
        if (material == null) return null;

        TagKey<Block> tag = TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("c", "ores/" + material));
        return BuiltInRegistries.BLOCK.getTag(tag)
                .flatMap(holders -> holders.stream().findFirst())
                .map(holder -> holder.value().defaultBlockState())
                .orElse(null);
    }

    // Deterministic scalar PRNG, same approach as RealisticCaves: identical output for identical
    // coordinates on every machine, independent of world seed.

    private static long mix(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 27);
    }

    private static long next(long s) {
        s ^= s << 13;
        s ^= s >>> 7;
        s ^= s << 17;
        return s;
    }

    private static double unit(long s) {
        return ((s >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
    }
}
