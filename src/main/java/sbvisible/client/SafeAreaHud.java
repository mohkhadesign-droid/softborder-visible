package sbvisible.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeArea;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Small text indicator: whether you are protected, and by what.
 * <p>
 * It is drawn from the synced safe areas using {@link SafeAreaSet#select} and
 * {@link SafeArea#overreach}, which are line-for-line the same decisions
 * {@code BorderUtils#getClosestBorderEmitter} and {@code getOverreachPercentage} make server side,
 * so the readout agrees with the penalties the player is actually taking.
 * <p>
 * Drawn once per frame on the overlay pass. Nothing is written to chat.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class SafeAreaHud {

    private static final int COLOR_SAFE_MAIN = 0x8CE99A;
    private static final int COLOR_SAFE_BEACON = 0x66D9EF;
    private static final int COLOR_UNSAFE = 0xFF8787;

    private SafeAreaHud() {
    }

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!VisualConfig.hud) return;
        if (!ClientSafeAreas.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        // Stay out of the way of the F3 screen and of screenshot mode.
        if (mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo) return;

        SafeAreaSet set = ClientSafeAreas.areas();
        if (set.isEmpty()) return;

        double px = player.getPosX(), py = player.getPosY(), pz = player.getPosZ();
        SafeArea sel = set.select(px, py, pz);
        if (sel == null) return;

        double over = sel.overreach(px, py, pz);
        boolean safe = over < 0.0D;

        String text;
        int color;
        if (!safe) {
            text = I18n.format("sbvisible.hud.outside");
            color = COLOR_UNSAFE;
        } else if (sel.isMain()) {
            text = I18n.format("sbvisible.hud.inside");
            color = COLOR_SAFE_MAIN;
        } else {
            text = I18n.format("sbvisible.hud.beacon");
            color = COLOR_SAFE_BEACON;
        }

        if (VisualConfig.hudDistance) {
            // |overreach| * size is exactly the straight-line distance to the surface of the safe
            // area Soft Borders is currently measuring this player against, in blocks.
            double distance = Math.abs(over) * sel.edgeRadius;
            String key = safe ? "sbvisible.hud.distance.in" : "sbvisible.hud.distance.out";
            text = text + " " + I18n.format(key, formatBlocks(distance));
        }

        MatrixStack ms = event.getMatrixStack();
        mc.fontRenderer.drawString(ms, text, VisualConfig.hudX, VisualConfig.hudY, color);
    }

    /** One decimal below 100 blocks, whole blocks above, so the readout stops jittering at range. */
    private static String formatBlocks(double d) {
        if (d < 100.0D) {
            return String.format("%.1f", d);
        }
        return Long.toString(Math.round(d));
    }
}
