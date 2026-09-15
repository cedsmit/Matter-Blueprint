package com.matterblueprint.mixins;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.modularui.api.screen.ModularUIContext;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularUIContainer;
import com.matterblueprint.client.ClientBlueprintLibrary;
import com.matterblueprint.network.BlueprintNetwork;
import com.recursive_pineapple.matter_manipulator.client.gui.RadialMenuBuilder;
import com.recursive_pineapple.matter_manipulator.client.gui.RadialMenuBuilder.RadialMenuOptionBuilderBranch;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

import cpw.mods.fml.common.FMLCommonHandler;

@Mixin(value = ItemMatterManipulator.class, remap = false)
public abstract class MixinItemMatterManipulator {

    private static final String QUICK_SLOT = "quick-slot";
    private static final int BLUEPRINTS_PER_PAGE = 6;

    @Inject(method = "addCommonOptions", at = @At("RETURN"))
    private void matterBlueprint$addBlueprintOptions(RadialMenuBuilder builder, UIBuildContext buildContext,
        MMState state, CallbackInfo callback) {
        RadialMenuOptionBuilderBranch<RadialMenuBuilder> blueprintMenu = builder.branch()
            .label(StatCollector.translateToLocal("matterblueprint.gui.blueprints"));
        blueprintMenu.option()
            .label(StatCollector.translateToLocal("matterblueprint.gui.save_quick"))
            .onClicked(() -> ClientBlueprintLibrary.requestSave(QUICK_SLOT))
            .done();

        RadialMenuOptionBuilderBranch<?> loadMenu = blueprintMenu.branch()
            .label(StatCollector.translateToLocal("matterblueprint.gui.load_quick"));
        matterBlueprint$addBlueprintPage(loadMenu, ClientBlueprintLibrary.getAvailableBlueprints(), 0);
        loadMenu.done();

        blueprintMenu.option()
            .label(
                StatCollector.translateToLocal(
                    state.config.coordC == null ? "matterblueprint.gui.lock" : "matterblueprint.gui.unlock"))
            .onClicked(() -> {
                if (state.config.coordC == null) {
                    Messages.MarkPaste.sendToServer();
                } else {
                    BlueprintNetwork.unlockBlueprint();
                }
            })
            .done();
        blueprintMenu.option()
            .label(StatCollector.translateToLocal("matterblueprint.gui.edit"))
            .onClicked((menu, option, mouseButton, doubleClicked) -> matterBlueprint$openEditor(buildContext))
            .done();
        blueprintMenu.option()
            .label(StatCollector.translateToLocal("matterblueprint.gui.paste_loaded"))
            .onClicked(ClientBlueprintLibrary::pasteLoaded)
            .done();
        blueprintMenu.option()
            .label(StatCollector.translateToLocal("matterblueprint.gui.undo"))
            .hidden(!ClientBlueprintLibrary.hasUndo())
            .onClicked(BlueprintNetwork::requestUndo)
            .done();
        blueprintMenu.done();
    }

    private static void matterBlueprint$addBlueprintPage(RadialMenuOptionBuilderBranch<?> page, List<String> blueprints,
        int pageIndex) {
        if (blueprints.isEmpty()) {
            page.option()
                .label(StatCollector.translateToLocal("matterblueprint.gui.no_blueprints"))
                .onClicked(() -> {})
                .done();
            return;
        }

        int first = pageIndex * BLUEPRINTS_PER_PAGE;
        int last = Math.min(first + BLUEPRINTS_PER_PAGE, blueprints.size());
        for (int index = first; index < last; index++) {
            String name = blueprints.get(index);
            page.option()
                .label(name)
                .onClicked(() -> ClientBlueprintLibrary.load(name))
                .done();
        }

        if (last < blueprints.size()) {
            int pageCount = (blueprints.size() + BLUEPRINTS_PER_PAGE - 1) / BLUEPRINTS_PER_PAGE;
            RadialMenuOptionBuilderBranch<?> nextPage = page.branch()
                .label(
                    StatCollector
                        .translateToLocalFormatted("matterblueprint.gui.more_blueprints", pageIndex + 2, pageCount));
            matterBlueprint$addBlueprintPage(nextPage, blueprints, pageIndex + 1);
            nextPage.done();
        }
    }

    private void matterBlueprint$openEditor(UIBuildContext buildContext) {
        try {
            UIBuildContext editorContext = new UIBuildContext(buildContext.getPlayer());
            Class<?> editorClass = Class.forName(ItemMatterManipulator.class.getName() + "$TransformWindow");
            Constructor<?> constructor = editorClass
                .getDeclaredConstructor(ItemMatterManipulator.class, UIBuildContext.class);
            constructor.setAccessible(true);
            Object editor = constructor.newInstance((ItemMatterManipulator) (Object) this, editorContext);
            Method buildCopyMode = editorClass.getDeclaredMethod("buildCopyMode");
            buildCopyMode.setAccessible(true);
            buildCopyMode.invoke(editor);
            Method build = editorClass.getDeclaredMethod("build");
            build.setAccessible(true);
            ModularWindow window = (ModularWindow) build.invoke(editor);
            GuiScreen screen = new ModularGui(
                new ModularUIContainer(new ModularUIContext(editorContext, null, true), window));
            FMLCommonHandler.instance()
                .showGuiScreen(screen);
        } catch (ReflectiveOperationException exception) {
            buildContext.getPlayer()
                .addChatMessage(new ChatComponentText("Matter Blueprint: Could not open the blueprint editor"));
        }
    }

}
