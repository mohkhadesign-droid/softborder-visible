package sbvisible.client;

import sbvisible.SoftBorderVisible;
import sbvisible.config.VisualConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles the boundary toggle key. Flips a session-only flag, so the config file keeps whatever the
 * player set as their startup default. Feedback goes to the action bar rather than chat.
 */
@Mod.EventBusSubscriber(modid = SoftBorderVisible.MODID, value = Dist.CLIENT)
public final class ClientInput {

    private ClientInput() {
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        KeyBinding key = ClientSetup.toggleBorders;
        if (key == null) return;

        boolean toggled = false;
        // isPressed() pops one queued click at a time; drain so a fast double tap is not swallowed.
        while (key.isPressed()) {
            VisualConfig.toggledOn = !VisualConfig.toggledOn;
            toggled = true;
        }
        if (!toggled) return;

        ClientPlayerEntity player = Minecraft.getInstance().player;
        if (player == null) return;
        player.sendStatusMessage(new TranslationTextComponent(
                VisualConfig.toggledOn ? "sbvisible.toggle.on" : "sbvisible.toggle.off"), true);
    }
}
