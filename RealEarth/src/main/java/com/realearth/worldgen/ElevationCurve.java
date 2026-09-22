package com.realearth.worldgen;

/**
 * Converts real-world elevation in metres to a Minecraft Y coordinate.
 *
 * <p>Minecraft 1.21.1 caps a dimension at 4064 blocks of height with {@code max_y <= 2032}, so the
 * dimension runs -2032..2032. Earth's real range is -10935 m (Challenger Deep) to +8849 m (Everest)
 * — 19784 m, nearly five times what fits. A single linear scale would either flatten every
 * mountain into a hill or lose the whole abyssal plain, so the mapping is piecewise:
 *
 * <ul>
 *   <li><b>-1000..+1500 m is exactly 1:1.</b> This band holds most inhabited land and every coast,
 *       so the part of the world you actually walk on is undistorted.</li>
 *   <li>Above +1500 m and below -1000 m the curve compresses with a power law, tapering so the
 *       gradient stays continuous at the join and no visible terrace forms at the boundary.</li>
 * </ul>
 *
 * <p>{@code reliefBoost} is the per-region knob: a value of 1.0 is the default compression, and
 * higher values spend more of the remaining headroom on that region. Nepal, the Caucasus and the
 * Alps are configured above 1.0 so they keep their relief, while plains stay at 1.0 and never pay
 * for it. This is what lets the world be tall only where tallness matters.
 */
public final class ElevationCurve {

    public static final int MIN_Y = -2032;
    public static final int MAX_Y = 2032;

    /** Sea level. Vanilla uses 63; keeping it there means vanilla structures and mobs behave. */
    public static final int SEA_LEVEL = 63;

    /** Top of the 1:1 band, in metres above sea level. */
    private static final double LINEAR_TOP_M = 1500.0;
    /** Bottom of the 1:1 band, in metres below sea level. */
    private static final double LINEAR_BOTTOM_M = -1000.0;

    private static final double EVEREST_M = 8849.0;
    private static final double CHALLENGER_M = -10935.0;

    /** Y headroom above the linear band, before the hard ceiling. Leaves room for build space. */
    private static final double UP_HEADROOM = (MAX_Y - 32) - (SEA_LEVEL + LINEAR_TOP_M);
    /** Y headroom below the linear band. */
    private static final double DOWN_HEADROOM = (SEA_LEVEL + LINEAR_BOTTOM_M) - (MIN_Y + 16);

    private ElevationCurve() {}

    /**
     * @param metres    real elevation, negative below sea level
     * @param reliefBoost 1.0 for the default curve; >1 keeps more relief in tall regions
     * @return Minecraft Y, clamped into the buildable range
     */
    public static double toY(double metres, double reliefBoost) {
        double y;
        if (metres <= LINEAR_TOP_M && metres >= LINEAR_BOTTOM_M) {
            y = SEA_LEVEL + metres;
        } else if (metres > LINEAR_TOP_M) {
            double over = metres - LINEAR_TOP_M;
            double span = EVEREST_M - LINEAR_TOP_M;
            y = SEA_LEVEL + LINEAR_TOP_M + UP_HEADROOM * taper(over / span, reliefBoost);
        } else {
            double under = LINEAR_BOTTOM_M - metres;
            double span = LINEAR_BOTTOM_M - CHALLENGER_M;
            y = SEA_LEVEL + LINEAR_BOTTOM_M - DOWN_HEADROOM * taper(under / span, 1.0);
        }
        return Math.max(MIN_Y + 1, Math.min(MAX_Y - 1, y));
    }

    public static int toBlockY(double metres, double reliefBoost) {
        return (int) Math.floor(toY(metres, reliefBoost));
    }

    /**
     * Maps t in [0,1] onto [0,1] as {@code 1 - (1-t)^k}, reaching exactly 1 at t=1 so the
     * extremes still land on the ceiling.
     *
     * <p>{@code boost} lowers k. At k=1.6 the curve is strongly concave: it spends most of the
     * headroom on the first kilometre above the linear band, which flatters foothills but leaves
     * Elbrus and Everest almost the same height. Lowering k straightens and then inverts the
     * curvature, spreading the headroom across the whole range so high peaks stay distinguishable
     * from each other. That is what a high-relief region such as Nepal, the Caucasus or the Alps
     * needs; plains never leave the linear band and are unaffected either way.
     */
    private static double taper(double t, double boost) {
        t = Math.max(0.0, Math.min(1.0, t));
        return 1.0 - Math.pow(1.0 - t, exponent(boost));
    }

    private static double exponent(double boost) {
        return 1.6 / Math.max(1.0, boost);
    }

    /** Inverse of {@link #toY}, for the HUD's "you are at N m above sea level" readout. */
    public static double toMetres(double y, double reliefBoost) {
        double rel = y - SEA_LEVEL;
        if (rel <= LINEAR_TOP_M && rel >= LINEAR_BOTTOM_M) {
            return rel;
        }
        if (rel > LINEAR_TOP_M) {
            double frac = Math.min(1.0, (rel - LINEAR_TOP_M) / UP_HEADROOM);
            double t = 1.0 - Math.pow(1.0 - frac, 1.0 / exponent(reliefBoost));
            return LINEAR_TOP_M + t * (EVEREST_M - LINEAR_TOP_M);
        }
        double frac = Math.min(1.0, (LINEAR_BOTTOM_M - rel) / DOWN_HEADROOM);
        double t = 1.0 - Math.pow(1.0 - frac, 1.0 / exponent(1.0));
        return LINEAR_BOTTOM_M - t * (LINEAR_BOTTOM_M - CHALLENGER_M);
    }
}
