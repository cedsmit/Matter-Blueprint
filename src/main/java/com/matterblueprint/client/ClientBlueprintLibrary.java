package com.matterblueprint.client;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import com.matterblueprint.blueprint.Blueprint;
import com.matterblueprint.blueprint.BlueprintStore;
import com.matterblueprint.network.BlueprintNetwork;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

public final class ClientBlueprintLibrary {

    private static String loadedBlueprint;
    private static Blueprint loadedData;
    private static boolean undoAvailable;

    private ClientBlueprintLibrary() {}

    public static void requestSave(String name) {
        try {
            BlueprintStore.validateName(name);
            BlueprintNetwork.requestCapture(name);
            notifyPlayer("Requesting blueprint capture...");
        } catch (IOException exception) {
            notifyPlayer(exception.getMessage());
        }
    }

    public static void load(String name) {
        try {
            BlueprintStore.validateName(name);
            loadedData = BlueprintStore.decodeCompressed(BlueprintStore.load(getMinecraftDirectory(), name));
            loadedBlueprint = name;
            BlueprintNetwork.setBlueprintBounds(loadedData.getSizeX(), loadedData.getSizeY(), loadedData.getSizeZ());
            Messages.MarkPaste.sendToServer();
            notifyPlayer("Loaded local blueprint '" + name + "'; right-click to set its position");
        } catch (IOException exception) {
            notifyPlayer("Could not load blueprint: " + exception.getMessage());
        }
    }

    public static List<String> getAvailableBlueprints() {
        try {
            return BlueprintStore.list(getMinecraftDirectory());
        } catch (IOException exception) {
            notifyPlayer("Could not list blueprints: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public static void pasteLoaded() {
        if (loadedBlueprint == null) {
            notifyPlayer("Load a local blueprint first");
            return;
        }

        try {
            byte[] data = BlueprintStore.load(getMinecraftDirectory(), loadedBlueprint);
            BlueprintNetwork.uploadForPaste(loadedBlueprint, data);
            notifyPlayer("Uploading '" + loadedBlueprint + "' for paste...");
        } catch (IOException exception) {
            notifyPlayer("Could not read blueprint: " + exception.getMessage());
        }
    }

    public static void receiveCapture(String name, byte[] data) {
        try {
            BlueprintStore.decodeCompressed(data);
            BlueprintStore.save(getMinecraftDirectory(), name, data);
            loadedBlueprint = name;
            loadedData = BlueprintStore.decodeCompressed(data);
            notifyPlayer("Saved local blueprint '" + name + "'");
        } catch (IOException exception) {
            notifyPlayer("Could not save blueprint: " + exception.getMessage());
        }
    }

    public static Blueprint getLoadedData() {
        return loadedData;
    }

    public static boolean hasUndo() {
        return undoAvailable;
    }

    public static void setUndoAvailable(boolean available) {
        undoAvailable = available;
    }

    private static File getMinecraftDirectory() {
        return Minecraft.getMinecraft().mcDataDir;
    }

    private static void notifyPlayer(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("Matter Blueprint: " + message));
        }
    }
}
