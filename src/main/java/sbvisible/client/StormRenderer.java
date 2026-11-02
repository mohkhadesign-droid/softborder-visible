package sbvisible.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeArea;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;

/**
 * Draws the storm: the boundary of the combined safe area, rendered as a layered violet cloud wall
 * rather than a flat tinted cylinder.
 * <p>
 * <b>Geometry, taken from Soft Borders' own maths rather than assumed</b>
 * <ul>
 *   <li><b>Main border</b> - a vertical cylinder of the player's own radius around the world axis
 *       at x = 0, z = 0. Not a sphere, and not centred on world spawn: the original rebuilds the
 *       emitter every tick at {@code (0, floor(playerY), 0)}, which cancels the vertical term of
 *       the distance. The cylinder is therefore <b>unbounded in y</b>, so the wall is drawn as a
 *       tall band centred on the camera that fades out softly at the top and bottom instead of
 *       being capped - a ceiling there would falsely imply protection ends at some height.</li>
 *   <li><b>Beacon shelter</b> - a true sphere of {@code (levels + 1) * 10} blocks around the beacon
 *       block, because Soft Borders measures 3D distance. Protection genuinely <i>does</i> end
 *       above the beacon, so a closed dome is the honest shape here, and its ceiling is not a lie.
 *       This is <b>not</b> the vanilla beacon effect volume, which is a box with a stretched y
 *       range.</li>
 * </ul>
 * <b>The storm fills the dangerous region</b>, so what is drawn is the outer surface of the safe
 * union: any patch of wall lying inside another safe area is dropped, which is what makes a beacon
 * read as a clear pocket carved out of the storm. See {@link SafeAreaSet#insideAnyExcept}.
 * <p>
 * Purely visual: it never moves a player, never changes a penalty, writes no depth, and blocks
 * nothing.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class StormRenderer {

    /** Vanilla's cloud sheet: real cloud shapes with transparent gaps, so the world shows through. */
    private static final ResourceLocation CLOUDS = new ResourceLocation("textures/environment/clouds.png");
    /** Vanilla's beacon beam: vertical streaks, used for the flowing energy layer. */
    private static final ResourceLocation BEAM = new ResourceLocation("textures/entity/beacon_beam.png");

    private static final double SEGMENT_LENGTH = 3.0D;
    private static final int MAX_ARC_SEGMENTS = 128;
    private static final int MIN_ARC_SEGMENTS = 8;
    private static final int SPHERE_LON = 32;
    private static final int SPHERE_LAT = 16;

    /**
     * One pass of cloud over the boundary surface. Several of these at different scales, drifting
     * at different speeds and in opposite directions, is what produces apparent depth and swirl
     * from flat geometry - a single pass would just look like a moving wallpaper.
     */
    private static final class Layer {
        final ResourceLocation tex;
        /** World blocks per texture repeat, horizontally and vertically. */
        final double tileU, tileV;
        /** Texture units per second. */
        final double scrollU, scrollV;
        final float alpha;
        /** How much of the accent colour is mixed in, 0..1. */
        final float accent;
        /** Additive blending, for layers that should glow rather than occlude. */
        final boolean additive;

        Layer(ResourceLocation tex, double tileU, double tileV,
              double scrollU, double scrollV, float alpha, float accent, boolean additive) {
            this.tex = tex;
            this.tileU = tileU;
            this.tileV = tileV;
            this.scrollU = scrollU;
            this.scrollV = scrollV;
            this.alpha = alpha;
            this.accent = accent;
            this.additive = additive;
        }
    }

    private static final Layer[] LAYERS = {
            // Slow, large cloud mass: the body of the storm.
            new Layer(CLOUDS, 96.0D, 64.0D, 0.004D, 0.0015D, 0.55F, 0.15F, false),
            // Mid clouds drifting the other way: crossing the first layer is what reads as swirl.
            new Layer(CLOUDS, 42.0D, 30.0D, -0.011D, -0.006D, 0.50F, 0.35F, false),
            // Fine mist, fastest of the cloud layers.
            new Layer(CLOUDS, 19.0D, 14.0D, 0.019D, -0.013D, 0.28F, 0.60F, false),
            // Energy streaking upward, additive so it glows through the clouds instead of hiding them.
            new Layer(BEAM, 10.0D, 40.0D, 0.003D, -0.075D, 0.30F, 0.95F, true),
    };
    /** Reduced-effects mode keeps the cloud body and the energy glow only. */
    private static final int[] FULL_LAYERS = {0, 1, 2, 3};
    private static final int[] REDUCED_LAYERS = {0, 3};

    private static final double[] LON_COS = new double[SPHERE_LON + 1];
    private static final double[] LON_SIN = new double[SPHERE_LON + 1];
    private static final double[] LAT_COS = new double[SPHERE_LAT + 1];
    private static final double[] LAT_SIN = new double[SPHERE_LAT + 1];

    static {
        for (int i = 0; i <= SPHERE_LON; i++) {
            double a = (Math.PI * 2.0D) * i / SPHERE_LON;
            LON_COS[i] = Math.cos(a);
            LON_SIN[i] = Math.sin(a);
        }
        for (int j = 0; j <= SPHERE_LAT; j++) {
            double a = Math.PI * j / SPHERE_LAT;
            LAT_COS[j] = Math.cos(a);
            LAT_SIN[j] = Math.sin(a);
        }
    }

    private StormRenderer() {
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!VisualConfig.rendersAnything()) return;
        if (!ClientSafeAreas.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        SafeAreaSet set = ClientSafeAreas.areas();
        if (set.isEmpty()) return;

        Vector3d view = mc.gameRenderer.getActiveRenderInfo().getProjectedView();
        double camX = view.getX(), camY = view.getY(), camZ = view.getZ();

        // Decide up front whether anything is in range, so a player deep inside a huge border pays
        // two square roots and an early return.
        SafeArea main = set.main();
        float mainAlpha = main == null ? 0.0F : fade(mainSurfaceDistance(main, camX, camZ));
        boolean anyBeacon = false;
        for (int i = 0; i < set.size(); i++) {
            SafeArea a = set.get(i);
            if (a.isMain()) continue;
            if (fade(a.distanceToSurface(camX, camY, camZ)) > 0.0F) {
                anyBeacon = true;
                break;
            }
        }
        if (mainAlpha <= 0.0F && !anyBeacon) return;

        MatrixStack ms = event.getMatrixStack();
        Matrix4f matrix = ms.getLast().getMatrix();
        // Wall-clock seconds, scaled by the configured speed. Independent of tick rate, so the
        // storm keeps drifting smoothly even if the server stutters.
        double t = (System.currentTimeMillis() % 3600000L) / 1000.0D * VisualConfig.cloudSpeed;

        int[] active = VisualConfig.reduced ? REDUCED_LAYERS : FULL_LAYERS;

        beginState();
        try {
            for (int li = 0; li < active.length; li++) {
                Layer layer = LAYERS[active[li]];
                mc.getTextureManager().bindTexture(layer.tex);
                if (layer.additive) {
                    // src*alpha + dst: glows without darkening what is behind it.
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
                } else {
                    RenderSystem.defaultBlendFunc();
                }

                Tessellator tess = Tessellator.getInstance();
                BufferBuilder buf = tess.getBuffer();
                boolean started = false;

                if (mainAlpha > 0.0F && main != null) {
                    started = emitMainWall(matrix, buf, started, set, main, layer,
                            camX, camY, camZ, t, mainAlpha);
                }
                if (anyBeacon) {
                    for (int i = 0; i < set.size(); i++) {
                        SafeArea a = set.get(i);
                        if (a.isMain()) continue;
                        float alpha = fade(a.distanceToSurface(camX, camY, camZ))
                                * ClientSafeAreas.areaFade(a);
                        if (alpha <= 0.0F) continue;
                        started = emitSphere(matrix, buf, started, set, a, layer,
                                camX, camY, camZ, t, alpha);
                    }
                }
                if (started) tess.draw();
            }

            StormLightning.render(matrix, camX, camY, camZ);
        } finally {
            // Always restore, so one bad frame cannot leave GL state broken for other renderers.
            endState();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /** Horizontal distance from the camera to the main cylinder's surface. */
    static double mainSurfaceDistance(SafeArea main, double camX, double camZ) {
        return Math.abs(main.edgeRadius - Math.sqrt(camX * camX + camZ * camZ));
    }

    /**
     * Opacity for a surface whose nearest point is {@code d} blocks away. Zero beyond the hard
     * cutoff, rising toward the configured opacity as the player approaches. The curve is
     * deliberately gentle so the storm is clearly visible from well inside safety, which is the
     * whole point of it.
     */
    static float fade(double d) {
        double max = VisualConfig.maxRenderDistance;
        if (d >= max) return 0.0F;
        double start = Math.min(VisualConfig.fadeDistance, max);
        if (start <= 0.0D) return 1.0F;
        if (d >= start) return 0.0F;
        double tt = 1.0D - (d / start);
        return (float) Math.pow(tt, 1.2D);
    }

    /**
     * Cheap two-sine pseudo-noise standing in for turbulence. Deterministic, allocation free, and
     * continuous in angle and height so neighbouring patches never seam.
     */
    private static float swirl(double a, double y, double t) {
        double s1 = Math.sin(a * 3.1D + y * 0.07D + t * 0.6D);
        double s2 = Math.sin(a * 7.3D - y * 0.11D - t * 0.37D);
        return (float) (0.62D + 0.38D * (0.5D * s1 + 0.5D * s2));
    }

    /** Smooth 0 at the very top and bottom of the drawn band, 1 through the middle. */
    private static float verticalFade(double p) {
        double f = Math.min(p / 0.30D, (1.0D - p) / 0.30D);
        if (f <= 0.0D) return 0.0F;
        if (f >= 1.0D) return 1.0F;
        return (float) (f * f * (3.0D - 2.0D * f));
    }

    private static void emit(Matrix4f m, BufferBuilder buf, double x, double y, double z,
                             float r, float g, float bl, float a, float u, float v) {
        buf.pos(m, (float) x, (float) y, (float) z).color(r, g, bl, a).tex(u, v).endVertex();
    }

    // ---------------------------------------------------------------------------------------------
    // Main border: an arc of a vertical cylinder about x = 0, z = 0
    // ---------------------------------------------------------------------------------------------

    private static boolean emitMainWall(Matrix4f m, BufferBuilder buf, boolean started,
                                        SafeAreaSet set, SafeArea main, Layer layer,
                                        double camX, double camY, double camZ,
                                        double t, float globalAlpha) {
        double r = main.edgeRadius;
        if (r <= 0.0D) return started;

        double max = VisualConfig.maxRenderDistance;
        double arcHalf = Math.min(Math.PI, max / r);
        double centerAngle = Math.atan2(camZ, camX);

        int segments = (int) Math.ceil((2.0D * arcHalf * r) / SEGMENT_LENGTH);
        if (segments < MIN_ARC_SEGMENTS) segments = MIN_ARC_SEGMENTS;
        int cap = VisualConfig.reduced ? MAX_ARC_SEGMENTS / 2 : MAX_ARC_SEGMENTS;
        if (segments > cap) segments = cap;

        int bands = VisualConfig.reduced ? 5 : 10;
        double half = VisualConfig.wallHeight * 0.5D;
        double yBottom = camY - half;
        double bandHeight = VisualConfig.wallHeight / bands;

        boolean checkOverlap = set.size() > 1;
        double step = (2.0D * arcHalf) / segments;

        float[] primary = VisualConfig.stormColor;
        float[] accent = VisualConfig.accentColor;
        float baseAlpha = (float) (VisualConfig.opacity * VisualConfig.cloudDensity)
                * layer.alpha * globalAlpha;
        if (baseAlpha <= 0.0F) return started;

        float uScroll = (float) (t * layer.scrollU);
        float vScroll = (float) (t * layer.scrollV);

        for (int i = 0; i < segments; i++) {
            double a0 = centerAngle - arcHalf + step * i;
            double a1 = a0 + step;
            double x0 = r * Math.cos(a0), z0 = r * Math.sin(a0);
            double x1 = r * Math.cos(a1), z1 = r * Math.sin(a1);

            // u follows arc length, so the cloud never stretches whatever the radius.
            float u0 = (float) ((a0 * r) / layer.tileU) + uScroll;
            float u1 = (float) ((a1 * r) / layer.tileU) + uScroll;

            // Fade the ends of the arc out instead of cutting them off.
            double midX = (x0 + x1) * 0.5D, midZ = (z0 + z1) * 0.5D;
            double dxm = midX - camX, dzm = midZ - camZ;
            double horiz = Math.sqrt(dxm * dxm + dzm * dzm);
            float arcFade = (float) Math.max(0.0D, 1.0D - (horiz / max));
            if (arcFade <= 0.0F) continue;

            for (int bnd = 0; bnd < bands; bnd++) {
                double yb = yBottom + bandHeight * bnd;
                double yt = yb + bandHeight;

                if (checkOverlap) {
                    // Drop patches inside a neighbouring safe area: that stretch is interior to the
                    // union, so a storm wall there would mark a boundary that separates nothing.
                    if (set.insideAnyExcept(main, midX, (yb + yt) * 0.5D, midZ)) continue;
                }

                float fb = verticalFade((bnd) / (double) bands);
                float ft = verticalFade((bnd + 1) / (double) bands);
                if (fb <= 0.0F && ft <= 0.0F) continue;

                float sb = swirl(a0, yb, t);
                float st = swirl(a1, yt, t);

                float v0 = (float) (-yb / layer.tileV) + vScroll;
                float v1 = (float) (-yt / layer.tileV) + vScroll;

                float mixB = layer.accent * (0.45F + 0.55F * sb);
                float mixT = layer.accent * (0.45F + 0.55F * st);
                float rb = primary[0] + (accent[0] - primary[0]) * mixB;
                float gb = primary[1] + (accent[1] - primary[1]) * mixB;
                float bb = primary[2] + (accent[2] - primary[2]) * mixB;
                float rt = primary[0] + (accent[0] - primary[0]) * mixT;
                float gt = primary[1] + (accent[1] - primary[1]) * mixT;
                float bt = primary[2] + (accent[2] - primary[2]) * mixT;

                float ab = baseAlpha * fb * sb * arcFade;
                float at = baseAlpha * ft * st * arcFade;

                if (!started) {
                    buf.begin(7, DefaultVertexFormats.POSITION_COLOR_TEX);
                    started = true;
                }
                emit(m, buf, x0 - camX, yb - camY, z0 - camZ, rb, gb, bb, ab, u0, v0);
                emit(m, buf, x1 - camX, yb - camY, z1 - camZ, rb, gb, bb, ab, u1, v0);
                emit(m, buf, x1 - camX, yt - camY, z1 - camZ, rt, gt, bt, at, u1, v1);
                emit(m, buf, x0 - camX, yt - camY, z0 - camZ, rt, gt, bt, at, u0, v1);
            }
        }
        return started;
    }

    // ---------------------------------------------------------------------------------------------
    // Beacon shelter: a sphere about the beacon block, tinted toward the shelter accent
    // ---------------------------------------------------------------------------------------------

    private static boolean emitSphere(Matrix4f m, BufferBuilder buf, boolean started,
                                      SafeAreaSet set, SafeArea area, Layer layer,
                                      double camX, double camY, double camZ,
                                      double t, float globalAlpha) {
        double r = area.edgeRadius;
        if (r <= 0.0D) return started;

        double cx = area.origin.getX(), cy = area.origin.getY(), cz = area.origin.getZ();
        double max = VisualConfig.maxRenderDistance;
        double maxSq = max * max;
        boolean checkOverlap = set.size() > 1;
        int stride = VisualConfig.reduced ? 2 : 1;

        float[] primary = VisualConfig.stormColor;
        float[] shelter = VisualConfig.beaconColor;
        float baseAlpha = (float) (VisualConfig.opacity * VisualConfig.cloudDensity)
                * layer.alpha * globalAlpha;
        if (baseAlpha <= 0.0F) return started;

        float uScroll = (float) (t * layer.scrollU);
        float vScroll = (float) (t * layer.scrollV);

        for (int j = 0; j < SPHERE_LAT; j += stride) {
            int j2 = Math.min(j + stride, SPHERE_LAT);
            double ring0 = r * LAT_SIN[j], ring1 = r * LAT_SIN[j2];
            double y0 = cy + r * LAT_COS[j], y1 = cy + r * LAT_COS[j2];
            float v0 = (float) ((Math.PI * j / SPHERE_LAT) * r / layer.tileV) + vScroll;
            float v1 = (float) ((Math.PI * j2 / SPHERE_LAT) * r / layer.tileV) + vScroll;

            for (int i = 0; i < SPHERE_LON; i += stride) {
                int i2 = Math.min(i + stride, SPHERE_LON);
                double x00 = cx + ring0 * LON_COS[i], z00 = cz + ring0 * LON_SIN[i];
                double x10 = cx + ring0 * LON_COS[i2], z10 = cz + ring0 * LON_SIN[i2];
                double x01 = cx + ring1 * LON_COS[i], z01 = cz + ring1 * LON_SIN[i];
                double x11 = cx + ring1 * LON_COS[i2], z11 = cz + ring1 * LON_SIN[i2];

                double mx = (x00 + x10 + x01 + x11) * 0.25D;
                double my = (y0 + y1) * 0.5D;
                double mz = (z00 + z10 + z01 + z11) * 0.25D;

                double dx = mx - camX, dy = my - camY, dz = mz - camZ;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq > maxSq) continue;

                // A shelter entirely inside the main border is entirely interior to the union, so
                // every patch fails this and nothing is drawn for it - which is correct: there is
                // no storm there to carve a pocket out of.
                if (checkOverlap && set.insideAnyExcept(area, mx, my, mz)) continue;

                float patchFade = (float) Math.max(0.0D, 1.0D - (Math.sqrt(dSq) / max));
                if (patchFade <= 0.0F) continue;

                double ang = (Math.PI * 2.0D) * i / SPHERE_LON;
                float s = swirl(ang, my, t);

                // The shelter wall stays storm-coloured but carries the accent, so it reads as a
                // different kind of boundary from the main wall without becoming a solid cyan ball.
                float mix = 0.35F + 0.30F * s + 0.25F * layer.accent;
                if (mix > 1.0F) mix = 1.0F;
                float cr = primary[0] + (shelter[0] - primary[0]) * mix;
                float cg = primary[1] + (shelter[1] - primary[1]) * mix;
                float cb = primary[2] + (shelter[2] - primary[2]) * mix;
                float al = baseAlpha * s * patchFade;

                float u0 = (float) (((Math.PI * 2.0D) * i / SPHERE_LON) * r / layer.tileU) + uScroll;
                float u1 = (float) (((Math.PI * 2.0D) * i2 / SPHERE_LON) * r / layer.tileU) + uScroll;

                if (!started) {
                    buf.begin(7, DefaultVertexFormats.POSITION_COLOR_TEX);
                    started = true;
                }
                emit(m, buf, x00 - camX, y0 - camY, z00 - camZ, cr, cg, cb, al, u0, v0);
                emit(m, buf, x10 - camX, y0 - camY, z10 - camZ, cr, cg, cb, al, u1, v0);
                emit(m, buf, x11 - camX, y1 - camY, z11 - camZ, cr, cg, cb, al, u1, v1);
                emit(m, buf, x01 - camX, y1 - camY, z01 - camZ, cr, cg, cb, al, u0, v1);
            }
        }
        return started;
    }

    // ---------------------------------------------------------------------------------------------
    // GL state
    // ---------------------------------------------------------------------------------------------

    private static void beginState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableTexture();
        // Two-sided: the storm reads the same from inside safety and from out in it.
        RenderSystem.disableCull();
        if (VisualConfig.seeThroughTerrain) {
            RenderSystem.disableDepthTest();
        } else {
            // Terrain and buildings occlude the storm, as they do the vanilla world border.
            RenderSystem.enableDepthTest();
        }
        // Never write depth: the storm must not occlude anything drawn after it.
        RenderSystem.depthMask(false);
        RenderSystem.polygonOffset(-3.0F, -3.0F);
        RenderSystem.enablePolygonOffset();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void endState() {
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
