package sbvisible.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;
import sbvisible.SoftBorderVisible;

/**
 * Purely visual, purely client-side settings, written to {@code config/sbvisible-client.toml}.
 * Soft Borders' own {@code softborder-server.toml} is never read or written by this add-on.
 * <p>
 * Nothing in here affects protection, penalties, commands or saved data, so changing any of it is
 * safe on a server other people play on, and none of it is ever transmitted.
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

    // ---- baked storm values, read every frame ----
    public static boolean storm, reduced, seeThroughTerrain;
    public static double opacity, fadeDistance, maxRenderDistance, wallHeight;
    public static double cloudSpeed, cloudDensity;
    /** {r,g,b} in 0..1. */
    public static final float[] stormColor = {0.48F, 0.18F, 0.75F};
    public static final float[] accentColor = {0.29F, 0.42F, 0.94F};
    public static final float[] beaconColor = {0.20F, 0.88F, 1.00F};

    // ---- lightning ----
    public static boolean lightning;
    public static double lightningInterval, lightningIntensity;

    // ---- atmosphere ----
    public static boolean fog, particles;
    public static double fogStrength, screenTint, particleDensity, soundVolume;

    // ---- hud ----
    public static boolean hud, hudDistance;
    public static int hudX, hudY;

    /**
     * Runtime on/off driven by the toggle key. Not persisted: the {@code storm enabled} value is the
     * state at startup, and the key flips this for the session only.
     */
    public static boolean toggledOn = true;

    private final ForgeConfigSpec.BooleanValue _storm, _reduced, _seeThrough, _lightning, _fog, _particles, _hud, _hudDistance;
    private final ForgeConfigSpec.DoubleValue _opacity, _fade, _maxDist, _height, _cloudSpeed, _cloudDensity,
            _lightInterval, _lightIntensity, _fogStrength, _tint, _particleDensity, _volume;
    private final ForgeConfigSpec.IntValue _hudX, _hudY;
    private final ForgeConfigSpec.ConfigValue<String> _stormColor, _accentColor, _beaconColor;

    public VisualConfig(ForgeConfigSpec.Builder b) {
        b.comment("Visual and audio only. Nothing here changes protection, penalties or commands.").push("storm");
        _storm = b.comment("master switch for the storm wall.")
                .define("storm enabled", true);
        _reduced = b.comment("reduced effects mode for weaker machines: fewer cloud layers, coarser geometry, no lightning, far fewer particles. Overrides the detail settings below.")
                .define("reduced effects", false);
        _opacity = b.comment("peak opacity of the storm wall once you are close enough for it to be fully faded in. Lower it if the storm hides too much of the world beyond.")
                .defineInRange("storm opacity", 0.55D, 0.0D, 1.0D);
        _fade = b.comment("distance from the boundary, in blocks, at which the storm starts to fade in. This is how far away you can see the storm from inside safety.")
                .defineInRange("visibility distance", 96.0D, 0.0D, 1024.0D);
        _maxDist = b.comment("hard cutoff in blocks. No storm geometry is built beyond this, and only the stretch of boundary nearest you is drawn. Lower this first if you need frames back.")
                .defineInRange("max render distance", 192.0D, 8.0D, 1024.0D);
        _height = b.comment("total height of the main storm wall in blocks, centred on your eye level, fading out softly at the top and bottom. The main border has no ceiling in Soft Borders, so this is a viewing window onto an unbounded wall rather than its real extent.")
                .defineInRange("wall height", 160.0D, 8.0D, 1024.0D);
        _cloudSpeed = b.comment("how fast the cloud and energy layers drift. 0 freezes the storm completely.")
                .defineInRange("cloud animation speed", 1.0D, 0.0D, 5.0D);
        _cloudDensity = b.comment("thickness of the cloud layers. Higher is murkier and hides more of the world behind the storm.")
                .defineInRange("cloud density", 1.0D, 0.0D, 2.0D);
        _seeThrough = b.comment("draw the storm through terrain and buildings. Off by default: the storm is occluded by the world like the vanilla world border is.")
                .define("see through terrain", false);
        _stormColor = b.comment("main storm colour, RRGGBB hex. Default is a deep violet.")
                .define("storm colour", "7B2FBE");
        _accentColor = b.comment("secondary colour woven through the storm and used for its energy streaks, RRGGBB hex. Default is a storm blue.")
                .define("storm accent colour", "4A6CF0");
        _beaconColor = b.comment("accent along a beacon shelter's boundary, RRGGBB hex. Default is cyan. The storm around it stays the main colour.")
                .define("beacon shelter accent colour", "33E0FF");
        b.pop();

        b.push("lightning");
        _lightning = b.comment("cosmetic lightning inside the storm. Purely visual: it never spawns a lightning entity, never starts fires, and never damages anything.")
                .define("lightning enabled", true);
        _lightInterval = b.comment("average seconds between flashes. The floor of 1.5 is deliberate, to keep the effect from strobing.")
                .defineInRange("lightning interval seconds", 6.0D, 1.5D, 120.0D);
        _lightIntensity = b.comment("brightness and size of a flash. 0 is invisible.")
                .defineInRange("lightning intensity", 1.0D, 0.0D, 2.0D);
        b.pop();

        b.push("atmosphere");
        _fog = b.comment("thicken fog while you are out in the storm.")
                .define("fog enabled", true);
        _fogStrength = b.comment("how heavy the storm fog gets. Capped internally so you can always see far enough to move and fight.")
                .defineInRange("fog strength", 1.0D, 0.0D, 2.0D);
        _tint = b.comment("strength of the purple screen-edge tint while out in the storm. 0 disables it. Soft Borders draws its own darkening vignette as you go further out; this is in addition to that.")
                .defineInRange("screen edge tint", 0.5D, 0.0D, 1.0D);
        _particles = b.comment("drifting storm particles while you are out in the storm. Never spawned inside a safe area.")
                .define("particles enabled", true);
        _particleDensity = b.comment("how many storm particles to spawn. Internally capped per tick.")
                .defineInRange("particle density", 1.0D, 0.0D, 2.0D);
        _volume = b.comment("volume of the storm wind and the muffled thunder. 0 mutes all sound from this mod.")
                .defineInRange("storm sound volume", 0.4D, 0.0D, 1.0D);
        b.pop();

        b.push("hud");
        _hud = b.comment("show a small text indicator for whether you are sheltered.")
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
        storm = CONFIG._storm.get();
        reduced = CONFIG._reduced.get();
        seeThroughTerrain = CONFIG._seeThrough.get();
        opacity = CONFIG._opacity.get();
        fadeDistance = CONFIG._fade.get();
        maxRenderDistance = CONFIG._maxDist.get();
        wallHeight = CONFIG._height.get();
        cloudSpeed = CONFIG._cloudSpeed.get();
        cloudDensity = CONFIG._cloudDensity.get();

        lightning = CONFIG._lightning.get();
        lightningInterval = CONFIG._lightInterval.get();
        lightningIntensity = CONFIG._lightIntensity.get();

        fog = CONFIG._fog.get();
        fogStrength = CONFIG._fogStrength.get();
        screenTint = CONFIG._tint.get();
        particles = CONFIG._particles.get();
        particleDensity = CONFIG._particleDensity.get();
        soundVolume = CONFIG._volume.get();

        hud = CONFIG._hud.get();
        hudDistance = CONFIG._hudDistance.get();
        hudX = CONFIG._hudX.get();
        hudY = CONFIG._hudY.get();

        parseColor(CONFIG._stormColor.get(), 0x7B2FBE, stormColor);
        parseColor(CONFIG._accentColor.get(), 0x4A6CF0, accentColor);
        parseColor(CONFIG._beaconColor.get(), 0x33E0FF, beaconColor);
    }

    /** Accepts {@code RRGGBB}, {@code #RRGGBB} and {@code 0xRRGGBB}; falls back rather than crashing. */
    private static void parseColor(String s, int fallback, float[] out) {
        int rgb = fallback;
        if (s != null) {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
            try {
                rgb = Integer.parseInt(t, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                SoftBorderVisible.LOGGER.warn("sbvisible: '" + s + "' is not an RRGGBB colour, using the default instead.");
            }
        }
        out[0] = ((rgb >> 16) & 0xFF) / 255.0F;
        out[1] = ((rgb >> 8) & 0xFF) / 255.0F;
        out[2] = (rgb & 0xFF) / 255.0F;
    }

    /** True when the storm wall should be drawn at all: config switch plus the session toggle key. */
    public static boolean rendersAnything() {
        return storm && toggledOn;
    }

    /** Cloud layers to draw. Reduced mode keeps the base mass and the energy streaks only. */
    public static int cloudLayers() {
        return reduced ? 2 : 4;
    }

    @SubscribeEvent
    public static void loadConfig(ModConfig.ModConfigEvent e) {
        if (e.getConfig().getSpec() == CONFIG_SPEC) {
            SoftBorderVisible.LOGGER.debug("loading sbvisible visual config!");
            bake();
        }
    }
}
