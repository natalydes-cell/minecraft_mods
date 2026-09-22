package com.realearth.fauna;

import com.realearth.climate.KoppenClass;
import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops animals spawning outside their real range.
 *
 * <p>Works by denial rather than by adding spawns, and that is the important design choice. The
 * biome source already puts taiga where taiga belongs, so vanilla's spawn tables offer roughly the
 * right animals in roughly the right places on their own. What vanilla cannot know is that snowy
 * is not the same as Arctic: it will put a polar bear on a Himalayan ridge two thousand kilometres
 * from any sea, and a parrot in the African jungles that have none. Cancelling those is a small,
 * safe change. Injecting new spawn entries would fight every other mob mod in the pack.
 *
 * <p>Only natural spawns are filtered. Spawn eggs, breeding, spawners and commands all pass
 * through untouched - if a player wants a penguin in the desert, that is their business.
 */
@EventBusSubscriber(modid = RealEarth.MODID)
public final class HabitatFilter {

    private HabitatFilter() {}

    private static final List<Habitat> HABITATS = HabitatRegistry.builtin();

    /** Entity id to habitat, built once. Entities with no entry are never filtered. */
    private static final Map<String, Habitat> BY_ENTITY = new HashMap<>();
    static {
        for (Habitat h : HABITATS) BY_ENTITY.put(h.entityId(), h);
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        // Natural and chunk-generation spawns only. Everything deliberate is left alone.
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;

        Entity entity = event.getEntity();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Habitat habitat = BY_ENTITY.get(id.toString());
        if (habitat == null) return;   // not an animal we have a range for

        LevelAccessor level = event.getLevel();
        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();

        double lat = proj.latitude(event.getZ());
        double lon = proj.longitude(event.getX());
        double metres = data.elevation(lat, lon);
        KoppenClass climate = data.koppen(lat, lon);

        if (!habitat.allows(lat, lon, metres, climate, nearCoast(data, lat, lon))) {
            event.setSpawnCancelled(true);
        }
    }

    /**
     * Whether open sea is within reach, for the animals that need it.
     *
     * <p>Sampled at roughly 50 km in each direction rather than block by block. A polar bear needs
     * to be on the coast, not on the exact shoreline block, and probing eight points is cheap
     * enough to run on every spawn attempt.
     */
    private static boolean nearCoast(EarthData data, double lat, double lon) {
        final double step = 0.45;   // degrees, about 50 km
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            double dLat = Math.sin(angle) * step;
            double dLon = Math.cos(angle) * step / Math.max(0.2, Math.cos(Math.toRadians(lat)));
            if (data.elevation(lat + dLat, lon + dLon) < 0) return true;
        }
        return data.elevation(lat, lon) < 0;
    }

    /** Exposed for the debug command and for tests. */
    public static List<Habitat> habitats() {
        return HABITATS;
    }
}
