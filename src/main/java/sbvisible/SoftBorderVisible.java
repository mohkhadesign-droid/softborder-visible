package sbvisible;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sbvisible.config.VisualConfig;

/**
 * Soft Borders Visible - draws the edge of Soft Borders' safe areas.
 * <p>
 * This is a <b>client-side</b> add-on. It is designed to be installed on clients only, leaving the
 * Soft Borders jar on the server completely untouched: no protocol change, no server restart, and no
 * requirement that other players install anything.
 * <p>
 * It registers no network channel and no registry objects, so it cannot cause a Forge handshake
 * mismatch when connecting to a server that does not have it.
 * <p>
 * This class deliberately references nothing from {@code net.minecraft.client}, so that if the jar
 * is dropped into a dedicated server's mods folder by mistake it loads, does nothing, and does not
 * crash. Every client-only type lives in {@code sbvisible.client}, behind
 * {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)}.
 */
@Mod(SoftBorderVisible.MODID)
public class SoftBorderVisible {
    public static final String MODID = "sbvisible";
    public static final Logger LOGGER = LogManager.getLogger();

    public SoftBorderVisible() {
        // VisualConfig holds no client-only types, so registering it is safe on either side.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, VisualConfig.CONFIG_SPEC);
    }
}
