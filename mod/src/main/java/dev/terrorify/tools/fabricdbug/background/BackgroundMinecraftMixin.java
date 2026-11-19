package dev.terrorify.tools.fabricdbug.background;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** An opt-in automation instance must never show or activate its native window. */
@Mixin(Minecraft.class)
abstract class BackgroundMinecraftMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false))
    private void fabricdbug$showUnlessBackground(long handle) {
        if (!Boolean.getBoolean("fabricdbug.background")) {
            GLFW.glfwShowWindow(handle);
        }
    }
}
