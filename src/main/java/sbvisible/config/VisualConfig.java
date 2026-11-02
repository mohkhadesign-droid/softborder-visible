package sbvisible.config;

import sbvisible.SoftBorderVisible;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Purely visual, purely client-side settings, written to {@code config/sbvisible-client.toml}.
 * Soft Borders' own {@code softborder-server.toml} is never read or written by this add-on.
 * <p>
 * Nothing in here affects protection, penalties or any other gameplay value, so changing any of it
 * is safe on a server that other people play on, and none of it is ever sent anywhere.
 * <p>
 * <b>Deliberately contains no {@code net.minecraft.client} imports.</b> A {@code CLIENT} config
 * still gets registered on a dedicated server by Forge, so this class is loaded there and must stay
 * free of client-only types.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class VisualConfig {
    public static final VisualConfig CONFIG;
    public static final ForgeConfigSpec CONFIG_SPEC;

    static {
        final Pair<VisualConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(VisualConfig::new);
        CONFIG = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
    }

    // Baked values, read every frame by the renderer and the HUD.
    public static boolean render, renderMain, renderBeacons, hud, hudDistance, animate;
    public static double opacity, fadeDistance, maxRenderDistance, wallHeight;
    public static int hudX, hudY;
    public static float mainR, mainG, mainB;
    public static float beaconR, beaconG, beaconB;

    /**
     * Runtime on/off driven by the toggle key. Not persisted: the config value above is the state
     * at startup, and the key flips this for the session only.
     */
    public static boolean toggledOn = true;

    private final ForgeConfigSpec.BooleanValue _render, _renderMain, _renderBeacons, _hud, _hudDistance, _animate;
    private final ForgeConfigSpec.DoubleValue _opacity, _fade, _maxDist, _height;
    private final ForgeConfigSpec.IntValue _hudX, _hudY;
    private final ForgeConfigSpec.ConfigValue<String> _mainColor, _beaconColor;

    public VisualConfig(ForgeConfigSpec.Builder b) {
        b.comment("Visual only. None of these settings change protection, penalties or commands.").push("rendering");
        _render = b.comment("master switch for the boundary walls.")
                .define("render borders", true);
        _renderMain = b.comment("draw the edge of your own border.")
                .define("render main border", true);
        _renderBeacons = b.comment("draw the edge of nearby protecting beacons. Has no effect if the server has beacon sanctuary switched off.")
                .define("render beacon borders", true);
        _opacity = b.comment("peak opacity of a wall, once you are close enough for it to be fully faded in.")
                .defineInRange("wall opacity", 0.45D, 0.0D, 1.0D);
        _fade = b.comment("distance from a boundary, in blocks, at which its wall starts to fade in. Larger means you see it from further away.")
                .defineInRange("fade in distance", 48.0D, 0.0D, 1024.0D);
        _maxDist = b.comment("hard cutoff, in blocks. No wall geometry is built beyond this, and only the stretch of boundary nearest you is drawn. Lower this first if you need frames back.")
                .defineInRange("max render distance", 128.0D, 8.0D, 1024.0D);
        _height = b.comment("total height of the main border wall in blocks, centred on your eye level. The beacon spheres ignore this, since they are closed shapes.")
                .defineInRange("wall height", 64.0D, 4.0D, 512.0D);
        _animate = b.comment("scroll the wall texture. Turn off for a completely static wall.")
                .define("animate", true);
        _mainColor = b.comment("colour of your own border, RRGGBB hex. Default is amber.")
                .define("main border colour", "FFB000");
        _beaconColor = b.comment("colour of beacon safe areas, RRGGBB hex. Default is cyan.")
                .define("beacon border colour", "33CCFF");
        b.pop();

        b.push("hud");
        _hud = b.comment("show a small text indicator for whether you are protected.")
                .define("show hud", true);
        _hudDistance = b.comment("also show the distance to the edge of the safe area you are currently measured against.")
                .define("show distance", true);
        _hudX = b.comment("horizontal offset from the left edge of the screen, in scaled pixels.")
                .defineInRange("hud x", 4, -4096, 4096);
        _hudY = b.comment("vertical offset from the top edge of the screen, in scaled pixels.")
                .defineInRange("hud y", 4, -4096, 4096);
        b.pop();
    }

    private static void bake() {
        render = CONFIG._render.get();
        renderMain = CONFIG._renderMain.get();
        renderBeacons = CONFIG._renderBeacons.get();
        opacity = CONFIG._opacity.get();
        fadeDistance = CONFIG._fade.get();
        maxRenderDistance = CONFIG._maxDist.get();
        wallHeight = CONFIG._height.get();
        animate = CONFIG._animate.get();
        hud = CONFIG._hud.get();
        hudDistance = CONFIG._hudDistance.get();
        hudX = CONFIG._hudX.get();
        hudY = CONFIG._hudY.get();

        int main = parseColor(CONFIG._mainColor.get(), 0xFFB000);
        mainR = ((main >> 16) & 0xFF) / 255.0F;
        mainG = ((main >> 8) & 0xFF) / 255.0F;
        mainB = (main & 0xFF) / 255.0F;

        int bec = parseColor(CONFIG._beaconColor.get(), 0x33CCFF);
        beaconR = ((bec >> 16) & 0xFF) / 255.0F;
        beaconG = ((bec >> 8) & 0xFF) / 255.0F;
        beaconB = (bec & 0xFF) / 255.0F;
    }

    /** Accepts {@code RRGGBB}, {@code #RRGGBB} and {@code 0xRRGGBB}; falls back rather than crashing. */
    private static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            SoftBorderVisible.LOGGER.warn("sbvisible: '" + s + "' is not an RRGGBB colour, using the default instead.");
            return fallback;
        }
    }

    /** True when walls should be drawn at all: config switch plus the session toggle key. */
    public static boolean rendersAnything() {
        return render && toggledOn;
    }

    @SubscribeEvent
    public static void loadConfig(ModConfig.ModConfigEvent e) {
        if (e.getConfig().getSpec() == CONFIG_SPEC) {
            SoftBorderVisible.LOGGER.debug("loading visual config!");
            bake();
        }
    }
}
