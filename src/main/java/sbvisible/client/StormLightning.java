package sbvisible.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeArea;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cosmetic lightning inside the storm wall.
 * <p>
 * <b>This is purely a drawing.</b> It never spawns a {@code LightningBoltEntity}, never sets fire
 * to anything, never damages a player or a mob, never converts a pig or charges a creeper, and
 * sends nothing to the server. It is a bright ribbon of geometry on the wall surface plus, if the
 * player has sound enabled, a quiet low-pitched rumble.
 * <p>
 * A bolt's geometry is generated once when it spawns and reused for its whole ~quarter-second life,
 * so flashes cost nothing per frame. At most one bolt exists at a time, and the configured interval
 * is floored at 1.5 seconds with a smooth single-decay envelope - no strobing.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class StormLightning {

    private static final long LIFETIME_MS = 280L;
    /** Angular half-width of the ribbon, scaled by radius so it looks the same at any border size. */
    private static final double RIBBON_BLOCKS = 0.9D;

    private static final Random RANDOM = new Random();

    /** Each entry is {angle0, y0, angle1, y1} on the cylinder surface. */
    private static List<double[]> segments = null;
    private static double boltRadius;
    private static long boltStart;
    private static long nextBoltAt;

    private StormLightning() {
    }

    public static void reset() {
        segments = null;
        nextBoltAt = 0L;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!VisualConfig.rendersAnything() || !VisualConfig.lightning
                || VisualConfig.reduced || VisualConfig.lightningIntensity <= 0.0D) {
            segments = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.world == null) {
            segments = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (segments != null && now - boltStart > LIFETIME_MS) {
            segments = null;
        }
        if (nextBoltAt == 0L) {
            nextBoltAt = now + (long) (VisualConfig.lightningInterval * 1000.0D);
            return;
        }
        if (now < nextBoltAt || segments != null) return;

        SafeAreaSet set = ClientSafeAreas.areas();
        SafeArea main = set.main();
        if (main == null) {
            nextBoltAt = now + 1000L;
            return;
        }
        // Only strike where the player can actually see the wall; otherwise wait and try again.
        double surface = StormRenderer.mainSurfaceDistance(main, player.getPosX(), player.getPosZ());
        if (StormRenderer.fade(surface) <= 0.0F) {
            nextBoltAt = now + 1000L;
            return;
        }

        spawnBolt(main, player);
        // Jitter the next one so flashes never fall into a rhythm.
        double interval = VisualConfig.lightningInterval * (0.7D + RANDOM.nextDouble() * 0.8D);
        nextBoltAt = now + (long) (interval * 1000.0D);

        if (VisualConfig.soundVolume > 0.0D && RANDOM.nextFloat() < 0.65F) {
            // Muffled: low volume, low pitch, played at the listener so it reads as a distant
            // rumble rather than a crack overhead.
            float vol = (float) (VisualConfig.soundVolume * 0.45D);
            float pitch = 0.45F + RANDOM.nextFloat() * 0.25F;
            player.world.playSound(player.getPosX(), player.getPosY(), player.getPosZ(),
                    SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, vol, pitch, false);
        }
    }

    /** Builds a branching polyline on the cylinder surface near the player's angle. */
    private static void spawnBolt(SafeArea main, ClientPlayerEntity player) {
        double r = main.edgeRadius;
        boltRadius = r;
        boltStart = System.currentTimeMillis();

        // Start somewhere on the visible stretch of wall, near but not exactly at the player's
        // bearing, and high above their eye level so the bolt travels down into view.
        double centerAngle = Math.atan2(player.getPosZ(), player.getPosX());
        double spread = Math.min(Math.PI, VisualConfig.maxRenderDistance / Math.max(1.0D, r));
        double a = centerAngle + (RANDOM.nextDouble() - 0.5D) * spread;
        double y = player.getPosY() + 25.0D + RANDOM.nextDouble() * 40.0D;

        List<double[]> out = new ArrayList<>(24);
        // Angular step that corresponds to a few blocks of arc, so jitter looks consistent at any
        // radius (a fixed angle would be kilometres of wobble on a 5000-block border).
        double angStep = 2.5D / Math.max(1.0D, r);

        traceBolt(out, a, y, angStep, 10 + RANDOM.nextInt(6), 1);
        segments = out;
    }

    private static void traceBolt(List<double[]> out, double a, double y,
                                  double angStep, int steps, int depth) {
        for (int i = 0; i < steps; i++) {
            double na = a + (RANDOM.nextDouble() - 0.5D) * angStep * 4.0D;
            double ny = y - (3.0D + RANDOM.nextDouble() * 5.0D);
            out.add(new double[]{a, y, na, ny});
            a = na;
            y = ny;
            // One or two short forks, never recursing far enough to explode the segment count.
            if (depth < 3 && out.size() < 40 && RANDOM.nextFloat() < 0.22F) {
                traceBolt(out, a, y, angStep, 2 + RANDOM.nextInt(3), depth + 1);
            }
        }
    }

    /**
     * Brightness envelope: a quick rise then a smooth decay. Deliberately a single pulse, because
     * repeated on/off within one flash is exactly what reads as strobing.
     */
    private static float envelope(double p) {
        if (p < 0.0D || p > 1.0D) return 0.0F;
        double rise = Math.min(1.0D, p / 0.12D);
        double decay = Math.pow(1.0D - p, 1.3D);
        return (float) (rise * decay);
    }

    /** Called by {@link StormRenderer} inside its render state, after the cloud layers. */
    static void render(Matrix4f m, double camX, double camY, double camZ) {
        List<double[]> segs = segments;
        if (segs == null || segs.isEmpty()) return;
        if (VisualConfig.lightningIntensity <= 0.0D) return;

        double p = (System.currentTimeMillis() - boltStart) / (double) LIFETIME_MS;
        float env = envelope(p);
        if (env <= 0.0F) return;

        float intensity = (float) Math.min(2.0D, VisualConfig.lightningIntensity);
        float alpha = Math.min(1.0F, env * intensity);

        // Accent colour pushed most of the way to white, so the core reads as a hot discharge.
        float[] accent = VisualConfig.accentColor;
        float cr = accent[0] + (1.0F - accent[0]) * 0.65F;
        float cg = accent[1] + (1.0F - accent[1]) * 0.65F;
        float cb = accent[2] + (1.0F - accent[2]) * 0.65F;

        double r = boltRadius;
        double halfAng = RIBBON_BLOCKS / Math.max(1.0D, r);

        RenderSystem.disableTexture();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);

        for (int i = 0; i < segs.size(); i++) {
            double[] s = segs.get(i);
            double a0 = s[0], y0 = s[1], a1 = s[2], y1 = s[3];

            double x0l = r * Math.cos(a0 - halfAng), z0l = r * Math.sin(a0 - halfAng);
            double x0r = r * Math.cos(a0 + halfAng), z0r = r * Math.sin(a0 + halfAng);
            double x1l = r * Math.cos(a1 - halfAng), z1l = r * Math.sin(a1 - halfAng);
            double x1r = r * Math.cos(a1 + halfAng), z1r = r * Math.sin(a1 + halfAng);

            buf.pos(m, (float) (x0l - camX), (float) (y0 - camY), (float) (z0l - camZ))
                    .color(cr, cg, cb, alpha).endVertex();
            buf.pos(m, (float) (x0r - camX), (float) (y0 - camY), (float) (z0r - camZ))
                    .color(cr, cg, cb, alpha).endVertex();
            buf.pos(m, (float) (x1r - camX), (float) (y1 - camY), (float) (z1r - camZ))
                    .color(cr, cg, cb, alpha).endVertex();
            buf.pos(m, (float) (x1l - camX), (float) (y1 - camY), (float) (z1l - camZ))
                    .color(cr, cg, cb, alpha).endVertex();
        }
        tess.draw();

        // Hand the state back the way the cloud layers expect to find it.
        RenderSystem.enableTexture();
        RenderSystem.defaultBlendFunc();
    }
}
