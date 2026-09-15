package com.matterblueprint.blueprint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.matterblueprint.blueprint.Blueprint.BlueprintBlock;

public class BlueprintStoreTest {

    @Test
    public void compressedBlueprintRoundTripsBlockAndTileData() throws IOException {
        NBTTagCompound tileData = new NBTTagCompound();
        tileData.setString("customData", "preserved");
        Blueprint source = new Blueprint(
            3,
            4,
            5,
            Collections.singletonList(new BlueprintBlock(1, 2, 3, "minecraft:chest", 4, tileData)));

        Blueprint decoded = BlueprintStore.decodeCompressed(BlueprintStore.encodeCompressed(source));

        assertEquals(3, decoded.getSizeX());
        assertEquals(4, decoded.getSizeY());
        assertEquals(5, decoded.getSizeZ());
        assertEquals(
            1,
            decoded.getBlocks()
                .size());
        BlueprintBlock block = decoded.getBlocks()
            .get(0);
        assertEquals("minecraft:chest", block.blockId);
        assertEquals(4, block.metadata);
        assertEquals("preserved", block.tileData.getString("customData"));
    }

    @Test
    public void saveLoadAndListUseTheBlueprintDirectory() throws IOException {
        File minecraftDirectory = Files.createTempDirectory("matter-blueprint-test")
            .toFile();
        byte[] contents = { 1, 2, 3 };

        BlueprintStore.save(minecraftDirectory, "factory-floor", contents);

        assertArrayEquals(contents, BlueprintStore.load(minecraftDirectory, "factory-floor"));
        assertEquals(Collections.singletonList("factory-floor"), BlueprintStore.list(minecraftDirectory));
    }

    @Test
    public void invalidNamesAreRejected() {
        assertThrows(IOException.class, () -> BlueprintStore.validateName("../outside"));
        assertThrows(IOException.class, () -> BlueprintStore.validateName("contains spaces"));
        assertThrows(IOException.class, () -> BlueprintStore.validateName(""));
    }
}
