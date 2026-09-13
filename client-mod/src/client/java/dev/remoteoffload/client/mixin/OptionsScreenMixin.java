package dev.remoteoffload.client.mixin;

import dev.remoteoffload.client.RemoteProcessingConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Agrega el botón "Remote Processing" al menú de Opciones, posicionándolo en el
 * espacio vacío que dejan los botones adyacentes (debajo de la última fila de la
 * cuadrícula, alineado a la columna izquierda).
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    @Shadow
    private HeaderAndFooterLayout layout;

    private Button remoteOffloadButton;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "repositionElements", at = @At("TAIL"), require = 0, expect = 0)
    private void remoteOffload_repositionButton(CallbackInfo ci) {
        if (remoteOffloadButton == null || !this.children().contains(remoteOffloadButton)) {
            remoteOffloadButton = Button.builder(
                            Component.literal("Remote Processing"),
                            b -> Minecraft.getInstance().setScreen(new RemoteProcessingConfigScreen(this)))
                    .build();
            this.addRenderableWidget(remoteOffloadButton);
        }

        int footerTop = this.height - this.layout.getFooterHeight() - 8;
        int minX = Integer.MAX_VALUE;
        int maxBottom = this.layout.getHeaderHeight() + 4;
        int widthAtBottom = 150;
        for (GuiEventListener el : this.children()) {
            if (el == remoteOffloadButton || !(el instanceof AbstractWidget w)) {
                continue;
            }
            if (w.getY() >= footerTop) {
                continue;
            }
            minX = Math.min(minX, w.getX());
            int bottom = w.getY() + w.getHeight();
            if (bottom > maxBottom) {
                maxBottom = bottom;
                widthAtBottom = w.getWidth();
            }
        }

        int w = Math.max(120, Math.min(widthAtBottom, this.width - minX - 8));
        int h = 20;
        int x = Math.min(minX, Math.max(4, this.width - w - 8));
        int y = Math.min(maxBottom + 4, this.height - this.layout.getFooterHeight() - h - 8);

        remoteOffloadButton.setX(x);
        remoteOffloadButton.setY(y);
        remoteOffloadButton.setWidth(w);
    }
}