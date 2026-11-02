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
 * to anything, never damages a player or a mob, never charges a creeper, and sends nothing to the
 * server. It is a bright ribbon of geometry on the wall surface plus, if sound is enabled, a quiet
 * low-pitched rumble.
 * <p>
 * Bolts are <b>aimed where the player is looking</b>. Spawning them anywhere in the rendered arc
 * sounds fair but is not: at a 150-block radius the visible arc spans about 73 degrees, wider than
 * the field of view, so most flashes happened off-screen and the effect read as "no lightning at
 * all". The strike angle is now biased toward the view direction and clamped to stay inside the
 * drawn stretch of wall.
 * <p>
 * Each bolt's geometry is generated once on spawn and reused for its whole life, so flashes cost
 * nothing per frame. At most {@value #MAX_BOLTS} exist at a time, the configured interval is
 * floored at 1.5 seconds, and the envelope is a single smooth rise-and-decay - repeated on/off
 * within one flash is exactly what reads as strobing.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class StormLightning {

    /** Long enough to actually register when you glance at it, short enough to stay a flash. */
    private static final long LIFETIME_MS = 420L;
    /** Half-width of the ribbon in blocks. */
    private static final double RIBBON_BLOCKS = 1.7D;
    /** Concurrent bolts. Two reads as an active storm; more starts to strobe. */
    private static final int MAX_BOLTS = 2;

    private static final Random RANDOM = new Random();

    /** One flash: segments of {angle0, y0, angle1, y1} on the cylinder, plus its radius and start. */
    private static final class Bolt {
        final List<double[]> segments;
        final double radius;
        final long start;

        Bolt(List<double[]> segments, double radius, long start) {
            this.segments = segments;
            this.radius = radius;
            this.start = start;
        }
    }

    private static final List<Bolt> BOLTS = new ArrayList<>(MAX_BOLTS);
    private static long nextBoltAt;

    private StormLightning() {
    }

    public static void reset() {
        BOLTS.clear();
        nextBoltAt = 0L;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!VisualConfig.rendersAnything() || !VisualConfig.lightning
                || VisualConfig.reduced || VisualConfig.lightningIntensity <= 0.0D) {
            if (!BOLTS.isEmpty()) BOLTS.clear();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.world == null) {
            if (!BOLTS.isEmpty()) BOLTS.clear();
            return;
        }

        long now = System.currentTimeMillis();
        BOLTS.removeIf(b -> now - b.start > LIFETIME_MS);

        if (nextBoltAt == 0L) {
            nextBoltAt = now + (long) (VisualConfig.lightningInterval * 1000.0D);
            return;
        }
        if (now < nextBoltAt || BOLTS.size() >= MAX_BOLTS) return;

        SafeAreaSet set = ClientSafeAreas.areas();
        SafeArea main = set.main();
        if (main == null) {
            nextBoltAt = now + 1000L;
            return;
        }
        // Only strike where the wall is actually being drawn; otherwise wait and try again shortly.
        double surface = StormRenderer.mainSurfaceDistance(main, player.getPosX(), player.getPosZ());
        if (StormRenderer.fade(surface) <= 0.0F) {
            nextBoltAt = now + 1000L;
            return;
        }

        BOLTS.add(buildBolt(main, player, now));
        // Jitter so flashes never fall into a rhythm.
        double interval = VisualConfig.lightningInterval * (0.65D + RANDOM.nextDouble() * 0.8D);
        nextBoltAt = now + (long) (interval * 1000.0D);

        if (VisualConfig.soundVolume > 0.0D && RANDOM.nextFloat() < 0.6F) {
            // Muffled: quiet and low-pitched, played at the listener so it reads as a distant
            // rumble rather than a crack overhead.
            float vol = (float) (VisualConfig.soundVolume * 0.45D);
            float pitch = 0.45F + RANDOM.nextFloat() * 0.25F;
            player.world.playSound(player.getPosX(), player.getPosY(), player.getPosZ(),
                    SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, vol, pitch, false);
        }
    }

    /** Wraps an angle difference into -PI..PI. */
    private static double wrap(double a) {
        while (a > Math.PI) a -= Math.PI * 2.0D;
        while (a < -Math.PI) a += Math.PI * 2.0D;
        return a;
    }

    /** Builds a branching polyline on the cylinder surface, biased toward the player's view. */
    private static Bolt buildBolt(SafeArea main, ClientPlayerEntity player, long now) {
        double r = main.edgeRadius;

        double bearing = Math.atan2(player.getPosZ(), player.getPosX());
        // Where the player is looking, projected onto the cylinder.
        Vector3d look = player.getLookVec();
        double aimAngle = Math.atan2(player.getPosZ() + look.z * 80.0D,
                player.getPosX() + look.x * 80.0D);

        double spread = Math.min(Math.PI, VisualConfig.maxRenderDistance / Math.max(1.0D, r));
        double maxDev = spread * 0.5D;
        // Bias toward the view, but never outside the stretch of wall actually being drawn.
        double dev = wrap(aimAngle - bearing);
        if (dev > maxDev) dev = maxDev;
        if (dev < -maxDev) dev = -maxDev;
        // A little scatter so repeated flashes do not stack in one spot.
        dev += (RANDOM.nextDouble() - 0.5D) * maxDev * 0.7D;

        double a = bearing + dev;
        double y = player.getPosY() + 22.0D + RANDOM.nextDouble() * 38.0D;

        List<double[]> out = new ArrayList<>(32);
        // Angular step matching a few blocks of arc, so jitter looks the same at any radius: a
        // fixed angle would be kilometres of wobble on a 5000-block border.
        double angStep = 2.5D / Math.max(1.0D, r);
        trace(out, a, y, angStep, 12 + RANDOM.nextInt(7), 1);
        return new Bolt(out, r, now);
    }

    private static void trace(List<double[]> out, double a, double y,
                              double angStep, int steps, int depth) {
        for (int i = 0; i < steps; i++) {
            double na = a + (RANDOM.nextDouble() - 0.5D) * angStep * 4.0D;
            double ny = y - (3.0D + RANDOM.nextDouble() * 5.0D);
            out.add(new double[]{a, y, na, ny});
            a = na;
            y = ny;
            // One or two short forks, bounded so the segment count cannot explode.
            if (depth < 3 && out.size() < 52 && RANDOM.nextFloat() < 0.26F) {
                trace(out, a, y, angStep, 2 + RANDOM.nextInt(4), depth + 1);
            }
        }
    }

    /** Quick rise then smooth decay. Deliberately a single pulse, to avoid strobing. */
    private static float envelope(double p) {
        if (p < 0.0D || p > 1.0D) return 0.0F;
        double rise = Math.min(1.0D, p / 0.10D);
        double decay = Math.pow(1.0D - p, 1.25D);
        return (float) (rise * decay);
    }

    /** Called by {@link StormRenderer} inside its render state, after the cloud layers. */
    static void render(Matrix4f m, double camX, double camY, double camZ) {
        if (BOLTS.isEmpty() || VisualConfig.lightningIntensity <= 0.0D) return;

        float intensity = (float) Math.min(2.0D, VisualConfig.lightningIntensity);
        float[] accent = VisualConfig.accentColor;
        // Accent pushed most of the way to white, so the core reads as a hot discharge.
        float cr = accent[0] + (1.0F - accent[0]) * 0.70F;
        float cg = accent[1] + (1.0F - accent[1]) * 0.70F;
        float cb = accent[2] + (1.0F - accent[2]) * 0.70F;

        long now = System.currentTimeMillis();

        RenderSystem.disableTexture();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        boolean started = false;

        for (int bi = 0; bi < BOLTS.size(); bi++) {
            Bolt bolt = BOLTS.get(bi);
            float env = envelope((now - bolt.start) / (double) LIFETIME_MS);
            if (env <= 0.0F) continue;
            float alpha = Math.min(1.0F, env * intensity);
            double r = bolt.radius;
            double halfAng = RIBBON_BLOCKS / Math.max(1.0D, r);

            for (int i = 0; i < bolt.segments.size(); i++) {
                double[] s = bolt.segments.get(i);
                double a0 = s[0], y0 = s[1], a1 = s[2], y1 = s[3];

                double x0l = r * Math.cos(a0 - halfAng), z0l = r * Math.sin(a0 - halfAng);
                double x0r = r * Math.cos(a0 + halfAng), z0r = r * Math.sin(a0 + halfAng);
                double x1l = r * Math.cos(a1 - halfAng), z1l = r * Math.sin(a1 - halfAng);
                double x1r = r * Math.cos(a1 + halfAng), z1r = r * Math.sin(a1 + halfAng);

                if (!started) {
                    buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
                    started = true;
                }
                buf.pos(m, (float) (x0l - camX), (float) (y0 - camY), (float) (z0l - camZ))
                        .color(cr, cg, cb, alpha).endVertex();
                buf.pos(m, (float) (x0r - camX), (float) (y0 - camY), (float) (z0r - camZ))
                        .color(cr, cg, cb, alpha).endVertex();
                buf.pos(m, (float) (x1r - camX), (float) (y1 - camY), (float) (z1r - camZ))
                        .color(cr, cg, cb, alpha).endVertex();
                buf.pos(m, (float) (x1l - camX), (float) (y1 - camY), (float) (z1l - camZ))
                        .color(cr, cg, cb, alpha).endVertex();
            }
        }
        if (started) tess.draw();

        // Hand the state back the way the cloud layers expect to find it.
        RenderSystem.enableTexture();
        RenderSystem.defaultBlendFunc();
    }
}
