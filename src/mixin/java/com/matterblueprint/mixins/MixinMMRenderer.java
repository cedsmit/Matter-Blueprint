package com.matterblueprint.mixins;

import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.matterblueprint.client.BlueprintPreviewRenderer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMRenderer;

@Mixin(value = MMRenderer.class, remap = false)
public abstract class MixinMMRenderer {

    @Inject(method = "renderSelection", at = @At("RETURN"))
    private static void matterBlueprint$renderPreview(RenderWorldLastEvent event, CallbackInfo callback) {
        BlueprintPreviewRenderer.renderLoadedBlueprint(event.partialTicks);
    }
}
