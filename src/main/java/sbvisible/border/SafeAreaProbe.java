package sbvisible.border;

import jackiecrazy.softborder.capability.BorderData;
import jackiecrazy.softborder.capability.IBorderTracker;
import jackiecrazy.softborder.config.BorderConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tileentity.BeaconTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the local player's safe-area set by reading Soft Borders' <b>public</b> API plus vanilla
 * beacons. No reflection, no Mixin, no access transformer, and no change to Soft Borders itself.
 * <p>
 * What this reads and why it is available client side:
 * <ul>
 *   <li><b>Border radius</b> - {@code BorderData.getCap(player)} is {@code public static} and
 *       {@code IBorderTracker#getBorderFor(World)} is public. Soft Borders already syncs that
 *       capability to the client on login, respawn, dimension change and after a
 *       {@code /softborder} radius change, so the value is the server's.</li>
 *   <li><b>Whether beacons protect</b> - {@code BorderConfig.beacon} is a {@code public static}
 *       field, and because Soft Borders registers that spec as {@code ModConfig.Type.SERVER},
 *       Forge syncs it to the client at login. So this is the server's setting, not a local
 *       guess.</li>
 *   <li><b>Beacon positions and levels</b> - ordinary vanilla tile entities in loaded chunks.</li>
 * </ul>
 * This is read-only and purely cosmetic. The server still decides every penalty with its own
 * {@code BorderUtils}; nothing here can grant or remove protection, so a stale or wrong local
 * reading can only ever draw a slightly wrong wall, never change the rules.
 * <p>
 * Contains no client-only types, so it is harmless if this jar is ever dropped on a server.
 */
public final class SafeAreaProbe {

    /** Most beacons to track at once, nearest first. */
    public static final int MAX_BEACONS = 16;

    private SafeAreaProbe() {
    }

    /**
     * True once Soft Borders' server config has actually been baked locally. Before that,
     * {@code BorderConfig.size} is still the uninitialised static {@code 0}, and calling
     * {@code getBorderFor} would insert that bogus default into the client's own radius map. Callers
     * must check this first.
     */
    public static boolean ready() {
        return BorderConfig.size > 0;
    }

    /**
     * @param searchMargin how far beyond a beacon's protection surface to still track it, in blocks
     */
    public static SafeAreaSet probe(PlayerEntity player, double searchMargin) {
        if (player == null) return SafeAreaSet.EMPTY;
        World world = player.world;
        if (world == null) return SafeAreaSet.EMPTY;

        int radius = readRadius(player);
        if (radius < 1) return SafeAreaSet.EMPTY;

        List<SafeArea> beacons = new ArrayList<>();
        if (BorderConfig.beacon) {
            collectBeacons(player, world, radius, searchMargin, beacons);
        }
        return SafeAreaSet.of(radius, beacons);
    }

    /** The server-synced radius for the player's current dimension. */
    private static int readRadius(PlayerEntity player) {
        Optional<IBorderTracker> cap = BorderData.getCap(player);
        if (cap.isPresent()) {
            return cap.get().getBorderFor(player.world);
        }
        return BorderConfig.size;
    }

    private static void collectBeacons(PlayerEntity player, World world, int mainRadius,
                                       double searchMargin, List<SafeArea> out) {
        double px = player.getPosX(), py = player.getPosY(), pz = player.getPosZ();

        List<Candidate> found = new ArrayList<>();
        List<TileEntity> list = world.loadedTileEntityList;
        if (list == null) return;
        // Indexed walk re-reading size() each step: the client's tile-entity list is mutated by
        // chunk and block updates, and this cannot throw ConcurrentModificationException.
        for (int i = 0; i < list.size(); i++) {
            TileEntity t;
            try {
                t = list.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (!(t instanceof BeaconTileEntity)) continue;
            BeaconTileEntity beacon = (BeaconTileEntity) t;
            if (beacon.isRemoved()) continue;
            // Soft Borders' own emitter test: no pyramid, no protection.
            int levels = beacon.getLevels();
            if (levels == 0) continue;

            int coverage = (levels + 1) * 10;
            BlockPos pos = beacon.getPos();
            double dx = px - pos.getX();
            double dy = py - pos.getY();
            double dz = pz - pos.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d - coverage > searchMargin) continue;

            found.add(new Candidate(d, area(pos, coverage, mainRadius)));
        }

        found.sort(Comparator.comparingDouble(c -> c.distance));
        for (int i = 0; i < found.size() && i < MAX_BEACONS; i++) {
            out.add(found.get(i).area);
        }
    }

    /**
     * Mirrors Soft Borders' two radii: {@code coverage} drives emitter selection, while the penalty
     * edge comes from {@code getMaxBorderSize}, which returns {@code max(playerBorder, coverage)}
     * for a beacon standing on the world axis.
     */
    public static SafeArea area(BlockPos pos, int coverage, int mainRadius) {
        int edge = (pos.getX() == 0 && pos.getZ() == 0) ? Math.max(mainRadius, coverage) : coverage;
        return SafeArea.beacon(pos, coverage, edge);
    }

    private static final class Candidate {
        final double distance;
        final SafeArea area;

        Candidate(double distance, SafeArea area) {
            this.distance = distance;
            this.area = area;
        }
    }
}
