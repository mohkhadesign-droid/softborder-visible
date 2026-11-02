package sbvisible.border;

import net.minecraft.util.math.BlockPos;

/**
 * One safe area, in the exact geometry Soft Borders uses.
 * <p>
 * This class contains no Minecraft client references and is safe to load on a dedicated server.
 * <p>
 * There are deliberately <b>two</b> radii here, because {@code BorderUtils} uses two different
 * numbers for two different jobs:
 * <ul>
 *   <li>{@link #selectRadius} - the radius used by
 *       {@code BorderUtils#getClosestBorderEmitter}, which picks the emitter minimising
 *       {@code distanceSq / radius^2}. For a beacon that is always {@code (levels + 1) * 10}.</li>
 *   <li>{@link #edgeRadius} - the radius used by {@code BorderUtils#getMaxBorderSize}, which is
 *       what {@code getOverreachPercentage} actually divides by, and therefore what decides where
 *       penalties begin. It differs from {@code selectRadius} only for a beacon standing on the
 *       world axis (x == 0 and z == 0), where {@code getMaxBorderSize} returns
 *       {@code max(playerBorder, (levels + 1) * 10)}.</li>
 * </ul>
 * Rendering and the HUD use {@link #edgeRadius}, because that is the surface where protection
 * actually stops.
 */
public final class SafeArea {

    /** The player's own border: centred on the world axis at x = 0, z = 0. */
    public static final int TYPE_MAIN = 0;
    /** An active beacon acting as a border emitter. */
    public static final int TYPE_BEACON = 1;

    public final int type;
    /** Emitter block position. {@link BlockPos#ZERO} for {@link #TYPE_MAIN}. */
    public final BlockPos origin;
    public final double selectRadius;
    public final double edgeRadius;

    public SafeArea(int type, BlockPos origin, double selectRadius, double edgeRadius) {
        this.type = type;
        this.origin = origin;
        this.selectRadius = selectRadius;
        this.edgeRadius = edgeRadius;
    }

    public static SafeArea main(double radius) {
        return new SafeArea(TYPE_MAIN, BlockPos.ZERO, radius, radius);
    }

    public static SafeArea beacon(BlockPos pos, double coverage, double edge) {
        return new SafeArea(TYPE_BEACON, pos, coverage, edge);
    }

    public boolean isMain() {
        return type == TYPE_MAIN;
    }

    /**
     * Squared distance normalised by {@link #selectRadius}, replicating
     * {@code BorderUtils#getClosestBorderEmitter} exactly.
     * <p>
     * For the main border that method builds {@code BlockPos.ZERO.add(0, posY, 0)} and calls
     * {@code BlockPos#distanceSq(x, y, z, false)}, i.e. corner coordinates, so the y term is only
     * the fractional part of the player's y and the shape is a vertical cylinder.
     * <p>
     * For a beacon it uses {@code Vector3d.copyCentered(pos)}, i.e. block-centre coordinates.
     */
    public double selectionScore(double px, double py, double pz) {
        double dx, dy, dz;
        if (type == TYPE_MAIN) {
            dx = px;
            dy = Math.floor(py) - py;
            dz = pz;
        } else {
            dx = px - (origin.getX() + 0.5D);
            dy = py - (origin.getY() + 0.5D);
            dz = pz - (origin.getZ() + 0.5D);
        }
        double r = selectRadius;
        return (dx * dx + dy * dy + dz * dz) / (r * r);
    }

    /**
     * Distance from the emitter as {@code BorderUtils#getOverreachPercentage} measures it:
     * {@code Entity#getDistanceSq(closest.getX(), closest.getY(), closest.getZ())}, i.e. raw
     * integer corner coordinates on both sides.
     */
    public double distanceFromEmitter(double px, double py, double pz) {
        double dx, dy, dz;
        if (type == TYPE_MAIN) {
            dx = px;
            dy = Math.floor(py) - py;
            dz = pz;
        } else {
            dx = px - origin.getX();
            dy = py - origin.getY();
            dz = pz - origin.getZ();
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * {@code (distance - size) / size}: the same value {@code getOverreachPercentage} returns when
     * this area is the selected emitter. Negative means inside.
     */
    public double overreach(double px, double py, double pz) {
        double d = distanceFromEmitter(px, py, pz);
        return (d - edgeRadius) / edgeRadius;
    }

    /**
     * Whether a point is inside the shape that gets drawn.
     * <p>
     * The main border is treated as a true vertical cylinder of radius {@link #edgeRadius}. The
     * real penalty surface is a sphere recentred on the player's own y every tick, so its
     * horizontal radius is {@code sqrt(r^2 - frac(y)^2)} - at most {@code 1/(2r)} blocks tighter
     * than the cylinder (0.05 blocks at r = 10, 0.0001 blocks at r = 5000). Drawing the cylinder
     * keeps the wall still instead of breathing with the player's y.
     */
    public boolean containsRendered(double x, double y, double z) {
        double r = edgeRadius;
        if (type == TYPE_MAIN) {
            return x * x + z * z < r * r;
        }
        double dx = x - origin.getX();
        double dy = y - origin.getY();
        double dz = z - origin.getZ();
        return dx * dx + dy * dy + dz * dz < r * r;
    }

    /** Shortest distance from a point to the drawn surface, always positive. */
    public double distanceToSurface(double x, double y, double z) {
        if (type == TYPE_MAIN) {
            return Math.abs(edgeRadius - Math.sqrt(x * x + z * z));
        }
        double dx = x - origin.getX();
        double dy = y - origin.getY();
        double dz = z - origin.getZ();
        return Math.abs(edgeRadius - Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SafeArea)) return false;
        SafeArea other = (SafeArea) o;
        return type == other.type
                && origin.equals(other.origin)
                && Double.compare(selectRadius, other.selectRadius) == 0
                && Double.compare(edgeRadius, other.edgeRadius) == 0;
    }

    @Override
    public int hashCode() {
        int h = type;
        h = 31 * h + origin.hashCode();
        h = 31 * h + Double.hashCode(selectRadius);
        h = 31 * h + Double.hashCode(edgeRadius);
        return h;
    }

    @Override
    public String toString() {
        return (type == TYPE_MAIN ? "main" : "beacon") + "@" + origin + " r=" + edgeRadius;
    }
}
