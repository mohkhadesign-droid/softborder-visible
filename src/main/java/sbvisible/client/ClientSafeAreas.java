package sbvisible.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeAreaProbe;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;

/**
 * Holds the local player's safe areas, refreshed on a timer rather than per frame.
 * <p>
 * This is the whole difference between the client-only add-on and a both-sides build: instead of
 * receiving a packet, the client re-reads Soft Borders' synced capability and the loaded beacons
 * every {@value #REFRESH_TICKS} ticks. The radius itself is still the server's value - Soft Borders
 * syncs it - so the main wall is exact and updates the moment a {@code /softborder} change arrives.
 * <p>
 * Beacons are the part that is locally derived, and the cost is up to half a second of lag after a
 * pyramid is built or broken. For a cosmetic boundary that is a fair trade for not having to touch
 * the server.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class ClientSafeAreas {

    /** 10 ticks = half a second. Cheap, because it is a walk of one in-memory list. */
    public static final int REFRESH_TICKS = 10;
    /** Track beacons a bit beyond the draw distance so walls are ready before they fade in. */
    public static final double SEARCH_MARGIN = 96.0D;

    private static SafeAreaSet areas = SafeAreaSet.EMPTY;
    private static int counter;
    /**
     * Set if reading Soft Borders ever throws, which would mean a future release changed the API
     * this add-on depends on. Rendering then stays off for the session instead of throwing from a
     * tick handler every tick.
     */
    private static boolean disabled;

    private ClientSafeAreas() {
    }

    public static SafeAreaSet areas() {
        return areas;
    }

    public static boolean hasData() {
        return !areas.isEmpty();
    }

    public static void clear() {
        areas = SafeAreaSet.EMPTY;
        counter = 0;
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
        // Nothing to refresh for if neither the walls nor the HUD are on.
        if (!VisualConfig.rendersAnything() && !VisualConfig.hud) {
            if (!areas.isEmpty()) clear();
            return;
        }
        if (counter++ % REFRESH_TICKS != 0) return;

        // Before Soft Borders' server config has been synced and baked locally its statics are
        // still zero. Probing then would both give a wrong radius and poison the client's own
        // radius map with a bogus default, so wait instead.
        if (!SafeAreaProbe.ready()) {
            if (!areas.isEmpty()) clear();
            return;
        }

        double margin = VisualConfig.maxRenderDistance + SEARCH_MARGIN;
        try {
            areas = SafeAreaProbe.probe(player, margin);
        } catch (Throwable t) {
            // If a future Soft Borders release changes the capability shape, degrade to drawing
            // nothing rather than throwing from a tick handler every tick.
            disabled = true;
            clear();
            SoftBorderVisible.LOGGER.warn("sbvisible: could not read Soft Borders state, "
                    + "boundary rendering disabled for this session: " + t);
        }
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
