package sbvisible.border;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The set of safe areas that currently apply to one player, in one dimension.
 * <p>
 * Index 0 is always the main border. Beacons follow. No Minecraft client references: this is used
 * by the server to decide what to send, and by the client to decide what to draw.
 */
public final class SafeAreaSet {

    public static final SafeAreaSet EMPTY = new SafeAreaSet(Collections.emptyList());

    private final List<SafeArea> areas;

    public SafeAreaSet(List<SafeArea> areas) {
        this.areas = areas;
    }

    public static SafeAreaSet of(double mainRadius, List<SafeArea> beacons) {
        List<SafeArea> list = new ArrayList<>(beacons.size() + 1);
        list.add(SafeArea.main(mainRadius));
        list.addAll(beacons);
        return new SafeAreaSet(list);
    }

    public List<SafeArea> areas() {
        return areas;
    }

    public int size() {
        return areas.size();
    }

    public SafeArea get(int i) {
        return areas.get(i);
    }

    public boolean isEmpty() {
        return areas.isEmpty();
    }

    public SafeArea main() {
        for (int i = 0; i < areas.size(); i++) {
            SafeArea a = areas.get(i);
            if (a.isMain()) return a;
        }
        return null;
    }

    /**
     * The emitter Soft Borders would pick, replicating {@code BorderUtils#getClosestBorderEmitter}:
     * the smallest {@code distanceSq / radius^2}. Ties resolve to the earlier entry, and the main
     * border is first, which matches the original's "start from the main border, only replace on a
     * strictly smaller score" loop.
     */
    public SafeArea select(double px, double py, double pz) {
        SafeArea best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < areas.size(); i++) {
            SafeArea a = areas.get(i);
            double score = a.selectionScore(px, py, pz);
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    /**
     * The overreach percentage the server would compute at this position, i.e. the same number
     * {@code BorderUtils#getOverreachPercentage} returns. Negative means protected.
     */
    public double overreach(double px, double py, double pz) {
        SafeArea sel = select(px, py, pz);
        if (sel == null) return 0.0D;
        return sel.overreach(px, py, pz);
    }

    /** True if any area's drawn shape contains the point. */
    public boolean insideAny(double x, double y, double z) {
        for (int i = 0; i < areas.size(); i++) {
            if (areas.get(i).containsRendered(x, y, z)) return true;
        }
        return false;
    }

    /**
     * True if any area <i>other than</i> {@code exclude} contains the point. Used to drop the parts
     * of a wall that sit inside a neighbouring safe area, so only the outer edge of the combined
     * safe area is ever drawn.
     */
    public boolean insideAnyExcept(SafeArea exclude, double x, double y, double z) {
        for (int i = 0; i < areas.size(); i++) {
            SafeArea a = areas.get(i);
            if (a == exclude) continue;
            if (a.containsRendered(x, y, z)) return true;
        }
        return false;
    }
}
