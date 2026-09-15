package com.matterblueprint.blueprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

public final class Blueprint {

    public static final int FORMAT_VERSION = 1;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<BlueprintBlock> blocks;

    public Blueprint(int sizeX, int sizeY, int sizeZ, List<BlueprintBlock> blocks) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public List<BlueprintBlock> getBlocks() {
        return blocks;
    }

    public static final class BlueprintBlock {

        public final int x;
        public final int y;
        public final int z;
        public final String blockId;
        public final int metadata;
        public final NBTTagCompound tileData;

        public BlueprintBlock(int x, int y, int z, String blockId, int metadata, NBTTagCompound tileData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
            this.metadata = metadata;
            this.tileData = tileData == null ? null : (NBTTagCompound) tileData.copy();
        }
    }
}
