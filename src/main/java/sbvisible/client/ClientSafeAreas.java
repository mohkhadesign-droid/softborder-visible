package sbvisible.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeArea;
import sbvisible.border.SafeAreaProbe;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * The local player's safe areas, plus the derived storm state everything else reads.
 * <p>
 * Refreshed on a timer rather than per frame. The radius is the server's own value - Soft Borders
 * syncs its capability on login, respawn, dimension change and after a {@code /softborder} change -
 * so the main wall is exact and moves the moment a change arrives. Beacons are re-read from loaded
 * tile entities, which costs up to half a second of lag after a pyramid changes.
 * <p>
 * One safe/unsafe calculation feeds the wall, the fog, the particles, the sound and the HUD: they
 * all go through {@link SafeAreaSet#select} and {@link SafeArea#overreach}, which are the same
 * decisions the server makes in {@code BorderUtils}.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class ClientSafeAreas {

    /** 10 ticks = half a second. Cheap: one walk of an in-memory list, never a chunk scan. */
    public static final int REFRESH_TICKS = 10;
    /** Track beacons a little beyond the draw distance so shelters are ready before they fade in. */
    public static final double SEARCH_MARGIN = 96.0D;
    /** Seconds for a newly protecting beacon's shelter wall to fade in. */
    private static final double SHELTER_FADE_SECONDS = 0.6D;

    private static SafeAreaSet areas = SafeAreaSet.EMPTY;
    private static int counter;
    private static boolean disabled;

    /** 0 = fully sheltered, 1 = deep in the storm. Smoothed, so fog and tint never snap. */
    private static float exposure;
    private static float exposureTarget;
    private static boolean sheltered = true;

    /** First time each beacon shelter was seen, for the fade-in. Keyed by emitter position. */
    private static final Map<BlockPos, Long> shelterSeen = new HashMap<>();

    private ClientSafeAreas() {
    }

    public static SafeAreaSet areas() {
        return areas;
    }

    public static boolean hasData() {
        return !areas.isEmpty();
    }

    /** Smoothed storm exposure, 0..1. */
    public static float exposure() {
        return exposure;
    }

    /** True when the player is inside the combined safe area, by the server's own maths. */
    public static boolean sheltered() {
        return sheltered;
    }

    /**
     * Opacity multiplier for one area's wall, so a beacon shelter fades in rather than popping.
     * <p>
     * Only the <i>opacity</i> animates - the shelter is drawn at its true radius from the first
     * frame, so the visual never claims a smaller protected region than the server is enforcing.
     * Removal is deliberately instant: a shelter wall lingering after protection ended would imply
     * safety that no longer exists.
     */
    public static float areaFade(SafeArea area) {
        if (area.isMain()) return 1.0F;
        Long since = shelterSeen.get(area.origin);
        if (since == null) return 1.0F;
        double age = (System.currentTimeMillis() - since) / 1000.0D;
        if (age >= SHELTER_FADE_SECONDS) return 1.0F;
        return (float) Math.max(0.0D, age / SHELTER_FADE_SECONDS);
    }

    public static void clear() {
        areas = SafeAreaSet.EMPTY;
        counter = 0;
        exposure = 0.0F;
        exposureTarget = 0.0F;
        sheltered = true;
        shelterSeen.clear();
        StormLightning.reset();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (disabled) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.world == null) {
            if (!areas.isEmpty()) clear();
            return;
        }
        if (!VisualConfig.rendersAnything() && !VisualConfig.hud
                && !VisualConfig.fog && !VisualConfig.particles) {
            if (!areas.isEmpty()) clear();
            return;
        }

        if (counter++ % REFRESH_TICKS == 0) {
            // Before Soft Borders' server config has been synced and baked locally its statics are
            // still zero. Probing then would give a wrong radius and would also insert that bogus
            // default into the client's own radius map, so wait instead.
            if (!SafeAreaProbe.ready()) {
                if (!areas.isEmpty()) clear();
                return;
            }
            try {
                areas = SafeAreaProbe.probe(player, VisualConfig.maxRenderDistance + SEARCH_MARGIN);
                trackShelters();
            } catch (Throwable t) {
                disabled = true;
                clear();
                SoftBorderVisible.LOGGER.warn("sbvisible: could not read Soft Borders state, "
                        + "storm rendering disabled for this session: " + t);
                return;
            }
        }

        updateExposure(player);
    }

    /** Remembers when each shelter appeared, and forgets ones that are gone. */
    private static void trackShelters() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < areas.size(); i++) {
            SafeArea a = areas.get(i);
            if (a.isMain()) continue;
            if (!shelterSeen.containsKey(a.origin)) {
                shelterSeen.put(a.origin, now);
            }
        }
        if (shelterSeen.size() > areas.size()) {
            shelterSeen.keySet().removeIf(pos -> {
                for (int i = 0; i < areas.size(); i++) {
                    SafeArea a = areas.get(i);
                    if (!a.isMain() && a.origin.equals(pos)) return false;
                }
                return true;
            });
        }
    }

    /**
     * Recomputes how deep in the storm the player is, then eases {@link #exposure} toward it.
     * <p>
     * The target is driven by {@code overreach}, the very value Soft Borders scales its penalties
     * by, so the atmosphere thickens in step with the damage multiplier rather than on a guess.
     */
    private static void updateExposure(ClientPlayerEntity player) {
        if (areas.isEmpty()) {
            exposureTarget = 0.0F;
            sheltered = true;
        } else {
            SafeArea sel = areas.select(player.getPosX(), player.getPosY(), player.getPosZ());
            if (sel == null) {
                exposureTarget = 0.0F;
                sheltered = true;
            } else {
                double over = sel.overreach(player.getPosX(), player.getPosY(), player.getPosZ());
                sheltered = over < 0.0D;
                // Full atmosphere once 25% past the edge; that is already a heavy penalty zone.
                exposureTarget = sheltered ? 0.0F : (float) Math.min(1.0D, over / 0.25D);
            }
        }
        // Ease about 8% of the remaining gap per tick: roughly a second to settle, no snapping.
        exposure += (exposureTarget - exposure) * 0.08F;
        if (Math.abs(exposureTarget - exposure) < 0.002F) exposure = exposureTarget;
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        clear();
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof World && ((World) event.getWorld()).isRemote) {
            clear();
        }
    }
}
