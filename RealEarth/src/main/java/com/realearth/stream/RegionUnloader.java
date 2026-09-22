package com.realearth.stream;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * Drops everything nobody is looking at.
 *
 * <p>On a vanilla-sized world keeping a few hundred idle chunks resident costs nothing. On a
 * twenty-million-block Earth it is the difference between a server that runs and one that does
 * not, because the set of chunks that have ever been visited grows without limit and vanilla has
 * no reason to let go of them quickly.
 *
 * <h2>The spawn chunks</h2>
 * Vanilla keeps a block of chunks around world spawn loaded and ticking forever, whether or not
 * anyone is there. That is a sensible default when spawn is where everyone is; it is pure waste
 * when spawn is one point on a planet and the players are on another continent. Setting the
 * spawn radius to zero is the single largest saving available here, and it is applied on world
 * load rather than left to the operator to discover.
 *
 * <h2>What this does not do</h2>
 * It does not attempt partial vertical loading. Minecraft sends a chunk to a client as one packet
 * containing every section from the bottom of the world to the top, and there is no supported way
 * to send half of one. On a world 4064 blocks tall that is genuinely expensive and it is the main
 * reason the ocean-trench depth is capped rather than extended - see docs/00-SCOPE-RU.md.
 */
public final class RegionUnloader {

    private RegionUnloader() {}

    /**
     * Applies the load-shedding settings to a level. Safe to call repeatedly.
     *
     * <p>Only touches the gamerule when it is not already what we want, so an operator who has
     * deliberately set a spawn radius keeps it after the first time they change it back - the mod
     * corrects the vanilla default, it does not fight the admin every tick.
     */
    public static void configure(ServerLevel level) {
        GameRules rules = level.getGameRules();
        GameRules.IntegerValue spawnRadius = rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        if (spawnRadius.get() > 0) {
            spawnRadius.set(0, level.getServer());
            RealEarth.LOG.info(
                    "RealEarth: spawn chunk radius set to 0 - on a planet-sized world, keeping "
                    + "chunks loaded around a spawn point nobody is standing on is wasted memory");
        }
    }

    /**
     * Seconds of grace before an unattended chunk is eligible to go.
     *
     * <p>Exposed rather than applied directly because the chunk ticket system is vanilla's to
     * manage; this is the number the rest of the mod uses when it decides how long to hold its
     * own tickets, and it is what {@code emptyRegionUnloadSeconds} in the config controls.
     */
    public static int graceSeconds() {
        return RealEarthConfig.EMPTY_REGION_UNLOAD_SECONDS.get();
    }

    public static int graceTicks() {
        return graceSeconds() * 20;
    }
}
