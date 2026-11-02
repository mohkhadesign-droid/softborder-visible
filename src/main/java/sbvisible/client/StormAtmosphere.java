package sbvisible.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import jackiecrazy.softborder.SoftBorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.particles.BasicParticleType;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;

import java.util.Random;

/**
 * What being out in the storm feels like: thicker violet fog, a screen-edge tint, drifting
 * particles and wind.
 * <p>
 * All of it is driven by {@link ClientSafeAreas#exposure()}, which comes from {@code overreach} -
 * the exact value Soft Borders scales its penalties by - eased over about a second. So the
 * atmosphere thickens in step with the damage multiplier and clears smoothly on the way back in,
 * and it is never on inside a protected region.
 * <p>
 * <b>Border Anchor takes priority.</b> Soft Borders' own {@code ClientBorder} drives fog while that
 * effect is active, and that fog is a gameplay signal. This class checks for the effect and for an
 * already-cancelled event, and keeps its hands off in either case.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class StormAtmosphere {

    /** Hard ceiling on fog density, so you can always see far enough to move and fight. */
    private static final float MAX_FOG_DENSITY = 0.06F;
    private static final int MAX_PARTICLES_PER_TICK = 12;
    private static final int WIND_INTERVAL_TICKS = 60;
    /** Subdivisions per axis for the screen tint. Fine enough that no cell boundary is visible. */
    private static final int TINT_GRID = 24;
    /** Radial distance where the tint starts, and where it reaches full. 1.0 is an edge midpoint. */
    private static final double TINT_INNER = 0.55D;
    private static final double TINT_OUTER = 1.30D;

    private static final Random RANDOM = new Random();
    private static int tickCounter;
    /** Set if Soft Borders' effect cannot be read, so we stop trying every frame. */
    private static boolean anchorUnreadable;

    private StormAtmosphere() {
    }

    /**
     * True while Soft Borders' Border Anchor is on the player. {@code SoftBorder.BORDER_ANCHOR} is
     * a public static field, but {@code RegistryObject.get()} throws if the effect is somehow not
     * registered, so this degrades to false rather than throwing from a render event.
     */
    private static boolean anchorActive(ClientPlayerEntity player) {
        if (anchorUnreadable) return false;
        try {
            return player.isPotionActive(SoftBorder.BORDER_ANCHOR.get());
        } catch (Throwable t) {
            anchorUnreadable = true;
            SoftBorderVisible.LOGGER.warn("sbvisible: could not read Border Anchor, storm fog will "
                    + "no longer defer to it: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fog
    // ---------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fogDensity(EntityViewRenderEvent.FogDensity event) {
        // LOW priority means Soft Borders has already had its say; if it cancelled, Border Anchor
        // fog is in control and we leave it alone.
        if (event.isCanceled()) return;
        if (!VisualConfig.fog || !VisualConfig.storm) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || anchorActive(player)) return;

        float exposure = ClientSafeAreas.exposure();
        if (exposure <= 0.001F) return;

        float d = (float) (0.004D + 0.035D * exposure * VisualConfig.fogStrength);
        if (d > MAX_FOG_DENSITY) d = MAX_FOG_DENSITY;
        event.setDensity(d);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fogColors(EntityViewRenderEvent.FogColors event) {
        if (!VisualConfig.fog || !VisualConfig.storm) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || anchorActive(player)) return;

        float exposure = ClientSafeAreas.exposure();
        if (exposure <= 0.001F) return;

        float[] storm = VisualConfig.stormColor;
        float[] accent = VisualConfig.accentColor;
        // A darkened blend of the two storm colours, eased in from whatever the world's fog was,
        // so dawn, dusk and the Nether all still read correctly underneath.
        float tr = (storm[0] * 0.5F + accent[0] * 0.5F) * 0.55F;
        float tg = (storm[1] * 0.5F + accent[1] * 0.5F) * 0.55F;
        float tb = (storm[2] * 0.5F + accent[2] * 0.5F) * 0.60F;
        float k = Math.min(1.0F, exposure);
        event.setRed(event.getRed() + (tr - event.getRed()) * k);
        event.setGreen(event.getGreen() + (tg - event.getGreen()) * k);
        event.setBlue(event.getBlue() + (tb - event.getBlue()) * k);
    }

    // ---------------------------------------------------------------------------------------------
    // Screen-edge tint
    // ---------------------------------------------------------------------------------------------

    /**
     * A vignette built entirely from vertex colours, on a fine grid with a smooth radial falloff.
     * <p>
     * The first version of this used a 3x3 grid. It was mathematically continuous - neighbouring
     * cells agreed exactly on their shared edges - but the <i>gradient direction</i> changed
     * abruptly at each cell boundary, and the eye reads that derivative discontinuity as a hard
     * line. The result was a visible rectangular outline at 30% and 70% of each axis. Hence
     * {@value #TINT_GRID} subdivisions and an alpha that is a smooth function of radial distance:
     * no straight boundaries exist to band along.
     * <p>
     * Still no texture, so there is nothing to load or tint wrongly, and the centre stays fully
     * clear for combat.
     */
    @SubscribeEvent
    public static void screenTint(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!VisualConfig.storm || VisualConfig.screenTint <= 0.0D) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.gameSettings.hideGUI) return;

        float exposure = ClientSafeAreas.exposure();
        if (exposure <= 0.001F) return;

        float alpha = (float) (VisualConfig.screenTint * 0.55D * exposure);
        if (alpha <= 0.002F) return;

        int w = event.getWindow().getScaledWidth();
        int h = event.getWindow().getScaledHeight();
        float[] c = VisualConfig.stormColor;
        float[] a2 = VisualConfig.accentColor;
        float cr = c[0] * 0.6F + a2[0] * 0.4F;
        float cg = c[1] * 0.6F + a2[1] * 0.4F;
        float cb = c[2] * 0.6F + a2[2] * 0.4F;

        Matrix4f m = event.getMatrixStack().getLast().getMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);

        final int n = TINT_GRID;
        for (int gx = 0; gx < n; gx++) {
            float x0 = w * gx / (float) n;
            float x1 = w * (gx + 1) / (float) n;
            for (int gy = 0; gy < n; gy++) {
                float y0 = h * gy / (float) n;
                float y1 = h * (gy + 1) / (float) n;

                float a00 = alpha * tintAt(gx, gy, n);
                float a10 = alpha * tintAt(gx + 1, gy, n);
                float a11 = alpha * tintAt(gx + 1, gy + 1, n);
                float a01 = alpha * tintAt(gx, gy + 1, n);
                if (a00 <= 0.0F && a10 <= 0.0F && a11 <= 0.0F && a01 <= 0.0F) continue;

                buf.pos(m, x0, y0, 0.0F).color(cr, cg, cb, a00).endVertex();
                buf.pos(m, x0, y1, 0.0F).color(cr, cg, cb, a01).endVertex();
                buf.pos(m, x1, y1, 0.0F).color(cr, cg, cb, a11).endVertex();
                buf.pos(m, x1, y0, 0.0F).color(cr, cg, cb, a10).endVertex();
            }
        }
        tess.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Vignette weight at grid node (gx, gy): a smoothstep on distance from the screen centre, so
     * the falloff is radial and has no straight contour anywhere on screen.
     */
    private static float tintAt(int gx, int gy, int n) {
        // Normalised to -1..1 on each axis, so an edge midpoint is 1 and a corner is about 1.414.
        double nx = (2.0D * gx / n) - 1.0D;
        double ny = (2.0D * gy / n) - 1.0D;
        double r = Math.sqrt(nx * nx + ny * ny);
        double t = (r - TINT_INNER) / (TINT_OUTER - TINT_INNER);
        if (t <= 0.0D) return 0.0F;
        if (t >= 1.0D) return 1.0F;
        return (float) (t * t * (3.0D - 2.0D * t));
    }

    // ---------------------------------------------------------------------------------------------
    // Particles and wind
    // ---------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!VisualConfig.storm) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.world == null) return;

        float exposure = ClientSafeAreas.exposure();
        if (exposure <= 0.02F) return;

        tickCounter++;
        if (VisualConfig.particles && VisualConfig.particleDensity > 0.0D) {
            spawnParticles(player, exposure);
        }
        if (VisualConfig.soundVolume > 0.0D && tickCounter % WIND_INTERVAL_TICKS == 0) {
            // ITEM_ELYTRA_FLYING is vanilla's wind-rush sample. Pitched down and quiet it reads as
            // storm wind without needing a custom sound asset, which would mean registering a
            // SoundEvent and risking a registry mismatch for a client-only mod.
            float vol = (float) (VisualConfig.soundVolume * 0.35D * exposure);
            float pitch = 0.55F + RANDOM.nextFloat() * 0.15F;
            player.world.playSound(player.getPosX(), player.getPosY(), player.getPosZ(),
                    SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.MASTER, vol, pitch, false);
        }
    }

    private static void spawnParticles(ClientPlayerEntity player, float exposure) {
        World world = player.world;
        SafeAreaSet set = ClientSafeAreas.areas();

        int count = (int) Math.ceil(VisualConfig.particleDensity * exposure
                * (VisualConfig.reduced ? 2.0D : 7.0D));
        if (count > MAX_PARTICLES_PER_TICK) count = MAX_PARTICLES_PER_TICK;

        double px = player.getPosX(), py = player.getPosY(), pz = player.getPosZ();
        for (int i = 0; i < count; i++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0D;
            double dist = 5.0D + RANDOM.nextDouble() * 18.0D;
            double x = px + Math.cos(ang) * dist;
            double z = pz + Math.sin(ang) * dist;
            double y = py + (RANDOM.nextDouble() - 0.35D) * 14.0D;

            // Never let storm particles appear inside a protected region: inside a shelter the
            // world must look normal.
            if (!set.isEmpty() && set.insideAny(x, y, z)) continue;

            BasicParticleType type = ParticleTypes.PORTAL;
            float roll = RANDOM.nextFloat();
            if (roll > 0.92F) type = ParticleTypes.WITCH;
            else if (roll > 0.84F) type = ParticleTypes.DRAGON_BREATH;

            double vx = (RANDOM.nextDouble() - 0.5D) * 0.6D;
            double vy = -0.02D - RANDOM.nextDouble() * 0.06D;
            double vz = (RANDOM.nextDouble() - 0.5D) * 0.6D;
            world.addParticle(type, x, y, z, vx, vy, vz);
        }
    }
}
