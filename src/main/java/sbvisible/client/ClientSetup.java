package sbvisible.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import sbvisible.SoftBorderVisible;

/**
 * Client-only setup. Registers the boundary toggle key.
 * <p>
 * Marked {@code value = Dist.CLIENT} so FML reads that from the mod file scan and never loads this
 * class on a dedicated server, which matters because {@code KeyBinding} and {@code ClientRegistry}
 * are both {@code @OnlyIn(Dist.CLIENT)}.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    /**
     * Unbound by default, so it cannot collide with anything in a modpack. Bind it under
     * Options -> Controls -> Soft Borders Visible.
     */
    public static KeyBinding toggleBorders;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void clientSetup(final FMLClientSetupEvent event) {
        toggleBorders = new KeyBinding(
                "key.sbvisible.toggle_borders",
                KeyConflictContext.IN_GAME,
                // GLFW_KEY_UNKNOWN is -1, the code vanilla uses for "not bound".
                InputMappings.Type.KEYSYM.getOrMakeInput(GLFW.GLFW_KEY_UNKNOWN),
                "key.categories.sbvisible");
        ClientRegistry.registerKeyBinding(toggleBorders);
    }
}
