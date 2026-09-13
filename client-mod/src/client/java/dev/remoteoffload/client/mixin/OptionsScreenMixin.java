package dev.remoteoffload.client.mixin;

import dev.remoteoffload.client.RemoteProcessingConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {

    @Inject(method = "init", at = @At("TAIL"), require = 0, expect = 0)
    private void remoteOffload_addButton(CallbackInfo ci) {
        OptionsScreen self = (OptionsScreen) (Object) this;
        self.addRenderableWidget(Button.builder(
                Component.literal("Remote Processing"),
                b -> Minecraft.getInstance().setScreen(new RemoteProcessingConfigScreen(self)))
                .bounds(self.width / 2 - 155, 6, 150, 20)
                .build());
    }
}