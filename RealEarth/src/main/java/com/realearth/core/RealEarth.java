package com.realearth.core;

import com.mojang.serialization.MapCodec;
import com.realearth.data.EarthData;
import com.realearth.region.RegionRegistry;
import com.realearth.util.GeoProjection;
import com.realearth.worldgen.EarthBiomeSource;
import com.realearth.worldgen.DepositBlocks;
import com.realearth.worldgen.EarthChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mod entry point and the holder of the two things everything else needs: the geodata and the
 * projection.
 *
 * <p>Both are static. That is a deliberate exception to the usual rule against global state: the
 * chunk generator is constructed by the dimension codec, which gives no route to pass dependencies
 * in, and every worker thread needs the same instance. They are initialised once on mod
 * construction and never replaced while a world is loaded.
 */
@Mod(RealEarth.MODID)
public final class RealEarth {

    public static final String MODID = "realearth";
    public static final Logger LOG = LoggerFactory.getLogger("RealEarth");

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MODID);

    static {
        CHUNK_GENERATORS.register("earth", () -> EarthChunkGenerator.CODEC);
        BIOME_SOURCES.register("earth", () -> EarthBiomeSource.CODEC);
    }

    private static volatile EarthData data;
    private static volatile GeoProjection projection;
    private static volatile Executor terrainExecutor;
    private static volatile RegionRegistry regions;
    private static volatile DepositBlocks depositBlocks;

    public RealEarth(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, RealEarthConfig.SPEC, "realearth.toml");

        CHUNK_GENERATORS.register(modEventBus);
        BIOME_SOURCES.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(RealEarth::initShared);
    }

    /**
     * Builds the projection, opens the geodata and starts the terrain pool.
     *
     * <p>Runs once, after config is available. A failure to read the geodata is fatal rather than
     * silently degraded: a world generated against missing tiles is not the world the operator
     * asked for, and discovering that after generating a few thousand chunks is much worse than
     * failing at startup.
     */
    public static void initShared() {
        double scale = RealEarthConfig.METRES_PER_BLOCK.get();
        projection = new GeoProjection(scale);
        LOG.info("RealEarth scale 1:{} - world is {} x {} blocks",
                (int) scale, projection.worldHalfWidth() * 2, projection.worldHalfHeight() * 2);

        Path configDir = FMLPaths.CONFIGDIR.get().resolve(MODID);
        try {
            data = EarthData.load(configDir);
            regions = RegionRegistry.load(configDir);
            LOG.info("RealEarth gazetteer: {} named regions", regions.all().size());
            depositBlocks = DepositBlocks.load(configDir);
        } catch (IOException e) {
            throw new UncheckedIOException("RealEarth could not read its geodata in " + configDir, e);
        }

        int threads = RealEarthConfig.generatorThreads();
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "RealEarth-terrain-" + counter.incrementAndGet());
            t.setDaemon(true);
            // Below the server thread. Terrain that arrives a tick later is invisible; a server
            // tick that arrives late is not.
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        terrainExecutor = Executors.newFixedThreadPool(threads, factory);
        LOG.info("RealEarth terrain pool: {} threads", threads);
    }

    public static EarthData data() {
        EarthData d = data;
        if (d == null) throw new IllegalStateException("RealEarth geodata accessed before setup");
        return d;
    }

    public static GeoProjection projection() {
        GeoProjection p = projection;
        if (p == null) throw new IllegalStateException("RealEarth projection accessed before setup");
        return p;
    }

    public static RegionRegistry regions() {
        RegionRegistry r = regions;
        if (r == null) throw new IllegalStateException("RealEarth gazetteer accessed before setup");
        return r;
    }

    public static DepositBlocks depositBlocks() {
        DepositBlocks d = depositBlocks;
        if (d == null) throw new IllegalStateException("RealEarth deposits accessed before setup");
        return d;
    }

    public static Executor terrainExecutor() {
        Executor e = terrainExecutor;
        // Falling back to the calling thread is correct here rather than throwing: it keeps
        // single-threaded tooling and datagen working without a live server.
        return e == null ? Runnable::run : e;
    }
}
