package sbvisible.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import sbvisible.SoftBorderVisible;
import sbvisible.border.SafeArea;
import sbvisible.border.SafeAreaSet;
import sbvisible.config.VisualConfig;
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

/**
 * Draws the edge of the combined safe area as a translucent wall, in the same spirit as the vanilla
 * world border.
 * <p>
 * Shapes, taken from {@code BorderUtils} rather than assumed:
 * <ul>
 *   <li><b>Main border</b> - a vertical cylinder of the player's own radius around the world axis
 *       at x = 0, z = 0. It is <i>not</i> centred on world spawn, and it is not a sphere: the
 *       original recentres the emitter on the player's own y every tick, which cancels the vertical
 *       term and leaves a cylinder.</li>
 *   <li><b>Beacon</b> - a sphere of {@code (levels + 1) * 10} blocks around the beacon block, which
 *       is not the same shape as the vanilla beacon effect box.</li>
 * </ul>
 * Only the outer edge of the <i>union</i> is drawn: any patch of wall that lies inside another safe
 * area is dropped, so overlapping areas never show an interior "danger" line.
 * <p>
 * Purely visual. It never moves a player, never changes a penalty, and writes no depth.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class SafeAreaRenderer {

    /** Vanilla's world-border texture: a faint scrolling grid, already in every client's assets. */
    private static final ResourceLocation FORCEFIELD = new ResourceLocation("textures/misc/forcefield.png");

    /** World units per texture repeat. Matches the scale vanilla uses for the border wall. */
    private static final double TILE = 8.0D;
    /** Target length of one wall segment, in blocks. */
    private static final double SEGMENT_LENGTH = 2.0D;
    private static final int MAX_ARC_SEGMENTS = 256;
    private static final int MIN_ARC_SEGMENTS = 8;
    /** Vertical bands on the main wall, used only when a beacon could overlap it. */
    private static final int OVERLAP_BANDS = 8;

    // Static unit-sphere tables, so beacon spheres cost no trigonometry and no allocation per frame.
    private static final int SPHERE_LON = 32;
    private static final int SPHERE_LAT = 16;
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

    private SafeAreaRenderer() {
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

        // Decide up front whether anything is visible, so a player sitting in the middle of a huge
        // border pays almost nothing: a couple of square roots and an early return.
        boolean anyMain = VisualConfig.renderMain && mainAlpha(set, camX, camZ) > 0.0F;
        boolean anyBeacon = false;
        if (VisualConfig.renderBeacons) {
            for (int i = 0; i < set.size(); i++) {
                SafeArea a = set.get(i);
                if (a.isMain()) continue;
                if (beaconAlpha(a, camX, camY, camZ) > 0.0F) {
                    anyBeacon = true;
                    break;
                }
            }
        }
        if (!anyMain && !anyBeacon) return;

        MatrixStack ms = event.getMatrixStack();
        Matrix4f matrix = ms.getLast().getMatrix();

        float scroll = VisualConfig.animate
                ? (float) (System.currentTimeMillis() % 3000L) / 3000.0F
                : 0.0F;

        beginWallState(mc);
        try {
            if (anyMain) {
                SafeArea main = set.main();
                if (main != null) {
                    RenderSystem.color4f(VisualConfig.mainR, VisualConfig.mainG,
                            VisualConfig.mainB, mainAlpha(set, camX, camZ));
                    drawMainWall(matrix, set, main, camX, camY, camZ, scroll);
                }
            }
            if (anyBeacon) {
                for (int i = 0; i < set.size(); i++) {
                    SafeArea a = set.get(i);
                    if (a.isMain()) continue;
                    float alpha = beaconAlpha(a, camX, camY, camZ);
                    if (alpha <= 0.0F) continue;
                    RenderSystem.color4f(VisualConfig.beaconR, VisualConfig.beaconG,
                            VisualConfig.beaconB, alpha);
                    drawBeaconSphere(matrix, set, a, camX, camY, camZ, scroll);
                }
            }
        } finally {
            // Always restore, even if geometry building threw, so one bad frame cannot leave the
            // GL state broken for every other renderer.
            endWallState();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fade
    // ---------------------------------------------------------------------------------------------

    /**
     * Opacity for a surface whose nearest point is {@code surfaceDistance} blocks away. Zero beyond
     * the fade distance or the hard cutoff, rising quadratically to the configured opacity as the
     * player closes in.
     */
    private static float fade(double surfaceDistance) {
        double max = VisualConfig.maxRenderDistance;
        if (surfaceDistance >= max) return 0.0F;
        double fadeStart = Math.min(VisualConfig.fadeDistance, max);
        if (fadeStart <= 0.0D) return (float) VisualConfig.opacity;
        if (surfaceDistance >= fadeStart) return 0.0F;
        double t = 1.0D - (surfaceDistance / fadeStart);
        return (float) (VisualConfig.opacity * t * t);
    }

    private static float mainAlpha(SafeAreaSet set, double camX, double camZ) {
        SafeArea main = set.main();
        if (main == null) return 0.0F;
        double d = Math.sqrt(camX * camX + camZ * camZ);
        return fade(Math.abs(main.edgeRadius - d));
    }

    private static float beaconAlpha(SafeArea a, double camX, double camY, double camZ) {
        return fade(a.distanceToSurface(camX, camY, camZ));
    }

    // ---------------------------------------------------------------------------------------------
    // Main border: an arc of a vertical cylinder about x = 0, z = 0
    // ---------------------------------------------------------------------------------------------

    private static void drawMainWall(Matrix4f matrix, SafeAreaSet set, SafeArea main,
                                     double camX, double camY, double camZ, float scroll) {
        double r = main.edgeRadius;
        if (r <= 0.0D) return;

        double max = VisualConfig.maxRenderDistance;
        // Half-angle wide enough that the arc reaches the cutoff along the wall in both directions.
        double arcHalf = Math.min(Math.PI, max / r);
        double centerAngle = Math.atan2(camZ, camX);

        double arcLength = 2.0D * arcHalf * r;
        int segments = (int) Math.ceil(arcLength / SEGMENT_LENGTH);
        if (segments < MIN_ARC_SEGMENTS) segments = MIN_ARC_SEGMENTS;
        if (segments > MAX_ARC_SEGMENTS) segments = MAX_ARC_SEGMENTS;

        double half = VisualConfig.wallHeight * 0.5D;
        double yBottom = camY - half;
        double yTop = camY + half;

        // Vertical subdivision only buys accuracy where another safe area can eat into the wall.
        int bands = set.size() > 1 ? OVERLAP_BANDS : 1;
        boolean checkOverlap = set.size() > 1;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        // Begin lazily: if every patch turns out to be interior to another safe area there is
        // nothing to upload, and an empty draw is wasted work.
        boolean started = false;

        double step = (2.0D * arcHalf) / segments;
        double bandHeight = (yTop - yBottom) / bands;

        for (int i = 0; i < segments; i++) {
            double a0 = centerAngle - arcHalf + step * i;
            double a1 = a0 + step;

            double x0 = r * Math.cos(a0), z0 = r * Math.sin(a0);
            double x1 = r * Math.cos(a1), z1 = r * Math.sin(a1);

            // u follows arc length so the grid never stretches, whatever the radius.
            float u0 = (float) ((a0 * r) / TILE) + scroll * 0.5F;
            float u1 = (float) ((a1 * r) / TILE) + scroll * 0.5F;

            for (int b = 0; b < bands; b++) {
                double yb = yBottom + bandHeight * b;
                double yt = yb + bandHeight;

                if (checkOverlap) {
                    // Drop this patch if it sits inside a neighbouring safe area: that stretch is
                    // interior to the union, so a wall there would be a boundary that does not
                    // actually separate safe from unsafe.
                    double mx = (x0 + x1) * 0.5D;
                    double mz = (z0 + z1) * 0.5D;
                    double my = (yb + yt) * 0.5D;
                    if (set.insideAnyExcept(main, mx, my, mz)) continue;
                }

                float vb = (float) (-yb / TILE) - scroll;
                float vt = (float) (-yt / TILE) - scroll;

                if (!started) {
                    buf.begin(7, DefaultVertexFormats.POSITION_TEX);
                    started = true;
                }
                emit(matrix, buf, x0 - camX, yb - camY, z0 - camZ, u0, vb);
                emit(matrix, buf, x1 - camX, yb - camY, z1 - camZ, u1, vb);
                emit(matrix, buf, x1 - camX, yt - camY, z1 - camZ, u1, vt);
                emit(matrix, buf, x0 - camX, yt - camY, z0 - camZ, u0, vt);
            }
        }

        if (started) tess.draw();
    }

    // ---------------------------------------------------------------------------------------------
    // Beacon: a sphere about the beacon block
    // ---------------------------------------------------------------------------------------------

    private static void drawBeaconSphere(Matrix4f matrix, SafeAreaSet set, SafeArea area,
                                         double camX, double camY, double camZ, float scroll) {
        double r = area.edgeRadius;
        if (r <= 0.0D) return;

        double cx = area.origin.getX();
        double cy = area.origin.getY();
        double cz = area.origin.getZ();

        double max = VisualConfig.maxRenderDistance;
        double maxSq = max * max;
        boolean checkOverlap = set.size() > 1;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        // A beacon fully inside the main border has every patch culled, so nothing is begun.
        boolean started = false;

        for (int j = 0; j < SPHERE_LAT; j++) {
            double ringR0 = r * LAT_SIN[j];
            double ringR1 = r * LAT_SIN[j + 1];
            double y0 = cy + r * LAT_COS[j];
            double y1 = cy + r * LAT_COS[j + 1];

            float v0 = (float) ((Math.PI * j / SPHERE_LAT) * r / TILE) - scroll;
            float v1 = (float) ((Math.PI * (j + 1) / SPHERE_LAT) * r / TILE) - scroll;

            for (int i = 0; i < SPHERE_LON; i++) {
                double x00 = cx + ringR0 * LON_COS[i], z00 = cz + ringR0 * LON_SIN[i];
                double x10 = cx + ringR0 * LON_COS[i + 1], z10 = cz + ringR0 * LON_SIN[i + 1];
                double x01 = cx + ringR1 * LON_COS[i], z01 = cz + ringR1 * LON_SIN[i];
                double x11 = cx + ringR1 * LON_COS[i + 1], z11 = cz + ringR1 * LON_SIN[i + 1];

                double mx = (x00 + x10 + x01 + x11) * 0.25D;
                double my = (y0 + y1) * 0.5D;
                double mz = (z00 + z10 + z01 + z11) * 0.25D;

                // Per-patch distance cull: only the stretch of sphere near the player is built.
                double dx = mx - camX, dy = my - camY, dz = mz - camZ;
                if (dx * dx + dy * dy + dz * dz > maxSq) continue;

                // A beacon entirely inside the main border is entirely interior to the union, so
                // every one of its patches fails this test and nothing is drawn for it.
                if (checkOverlap && set.insideAnyExcept(area, mx, my, mz)) continue;

                float u0 = (float) (((Math.PI * 2.0D) * i / SPHERE_LON) * r / TILE) + scroll * 0.5F;
                float u1 = (float) (((Math.PI * 2.0D) * (i + 1) / SPHERE_LON) * r / TILE) + scroll * 0.5F;

                if (!started) {
                    buf.begin(7, DefaultVertexFormats.POSITION_TEX);
                    started = true;
                }
                emit(matrix, buf, x00 - camX, y0 - camY, z00 - camZ, u0, v0);
                emit(matrix, buf, x10 - camX, y0 - camY, z10 - camZ, u1, v0);
                emit(matrix, buf, x11 - camX, y1 - camY, z11 - camZ, u1, v1);
                emit(matrix, buf, x01 - camX, y1 - camY, z01 - camZ, u0, v1);
            }
        }

        if (started) tess.draw();
    }

    private static void emit(Matrix4f matrix, BufferBuilder buf,
                             double x, double y, double z, float u, float v) {
        buf.pos(matrix, (float) x, (float) y, (float) z).tex(u, v).endVertex();
    }

    // ---------------------------------------------------------------------------------------------
    // GL state
    // ---------------------------------------------------------------------------------------------

    private static void beginWallState(Minecraft mc) {
        RenderSystem.enableBlend();
        // Standard src-alpha / one-minus-src-alpha: the world stays visible through the wall.
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableTexture();
        mc.getTextureManager().bindTexture(FORCEFIELD);
        // Two-sided, so the wall reads the same from inside and outside.
        RenderSystem.disableCull();
        // Depth test on so terrain still occludes the wall; depth mask off so the wall never
        // occludes anything drawn after it.
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        // Nudge toward the camera to stop the wall z-fighting with blocks it is flush against.
        RenderSystem.polygonOffset(-3.0F, -3.0F);
        RenderSystem.enablePolygonOffset();
    }

    private static void endWallState() {
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
