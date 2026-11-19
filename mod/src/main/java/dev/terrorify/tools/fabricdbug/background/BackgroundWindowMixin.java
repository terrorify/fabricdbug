package dev.terrorify.tools.fabricdbug.background;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
abstract class BackgroundWindowMixin {
    @Shadow private boolean focused;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabricdbug$initializeBackgroundFocus(CallbackInfo ci) {
        if (Boolean.getBoolean("fabricdbug.background")) {
            // A never-shown window receives no OS focus callback to clear vanilla's initial true.
            focused = false;
        }
    }
}
