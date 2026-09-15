package com.matterblueprint.blueprint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.joml.Vector3i;

import com.matterblueprint.blueprint.Blueprint.BlueprintBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

public final class BlueprintWorldIO {

    private BlueprintWorldIO() {}

    public static Blueprint capture(World world, Location first, Location second) throws IOException {
        if (first == null || second == null || !first.isInWorld(world) || !second.isInWorld(world)) {
            throw new IOException("Matter Manipulator coordinates A and B must be in this world");
        }

        int minX = Math.min(first.x, second.x);
        int minY = Math.min(first.y, second.y);
        int minZ = Math.min(first.z, second.z);
        int maxX = Math.max(first.x, second.x);
        int maxY = Math.max(first.y, second.y);
        int maxZ = Math.max(first.z, second.z);
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > Integer.MAX_VALUE) throw new IOException("Selection is too large to encode");

        List<BlueprintBlock> blocks = new ArrayList<>((int) volume);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    Block block = world.getBlock(x, y, z);
                    UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(block);
                    if (id == null) throw new IOException("Unregistered block at " + x + ", " + y + ", " + z);

                    TileEntity tile = world.getTileEntity(x, y, z);
                    NBTTagCompound tileData = null;
                    if (tile != null) {
                        tileData = new NBTTagCompound();
                        tile.writeToNBT(tileData);
                        tileData.removeTag("x");
                        tileData.removeTag("y");
                        tileData.removeTag("z");
                    }

                    blocks.add(
                        new BlueprintBlock(
                            x - minX,
                            y - minY,
                            z - minZ,
                            id.modId + ":" + id.name,
                            world.getBlockMetadata(x, y, z),
                            tileData));
                }
            }
        }
        return new Blueprint(sizeX, sizeY, sizeZ, blocks);
    }

    public static void paste(EntityPlayerMP player, Blueprint blueprint, Location origin, Location first,
        Location second, Vector3i arraySpan, Transform transform, Consumer<UndoSnapshot> undoRecorder)
        throws IOException {
        World world = player.worldObj;
        if (origin == null || !origin.isInWorld(world)) {
            throw new IOException("Could not determine a paste position in this world");
        }

        List<ResolvedBlock> resolved = new ArrayList<>(
            blueprint.getBlocks()
                .size());
        BlueprintBounds bounds = BlueprintBounds.from(blueprint, first, second);
        int spanX = arraySpan == null ? 0 : arraySpan.x;
        int spanY = arraySpan == null ? 0 : arraySpan.y;
        int spanZ = arraySpan == null ? 0 : arraySpan.z;
        transform.cacheRotation();
        try {
            for (int stackY = Math.min(spanY, 0); stackY <= Math.max(spanY, 0); stackY++) {
                for (int stackZ = Math.min(spanZ, 0); stackZ <= Math.max(spanZ, 0); stackZ++) {
                    for (int stackX = Math.min(spanX, 0); stackX <= Math.max(spanX, 0); stackX++) {
                        Vector3i stackOffset = transform.apply(
                            new Vector3i(stackX * bounds.sizeX(), stackY * bounds.sizeY(), stackZ * bounds.sizeZ()));
                        for (BlueprintBlock source : blueprint.getBlocks()) {
                            if (!bounds.contains(source)) continue;
                            Vector3i offset = transform.apply(new Vector3i(source.x, source.y, source.z))
                                .add(stackOffset);
                            int targetX = origin.x + offset.x;
                            int targetY = origin.y + offset.y;
                            int targetZ = origin.z + offset.z;
                            if (targetY < 0 || targetY > 255)
                                throw new IOException("Blueprint extends outside the world height");

                            Block block = resolveBlock(source.blockId);
                            if (block == null) throw new IOException("Missing block: " + source.blockId);
                            resolved.add(new ResolvedBlock(source, block, targetX, targetY, targetZ));
                        }
                    }
                }
            }
        } finally {
            transform.uncacheRotation();
        }

        UndoSnapshot undo = UndoSnapshot.capture(world, resolved);
        undoRecorder.accept(undo);
        for (ResolvedBlock entry : resolved) {
            world.setBlock(entry.x, entry.y, entry.z, Blocks.air, 0, 2);
        }
        for (ResolvedBlock entry : resolved) {
            if (entry.block != Blocks.air) {
                world.setBlock(entry.x, entry.y, entry.z, entry.block, entry.source.metadata, 2);
            }
        }
        for (ResolvedBlock entry : resolved) {
            restoreTile(world, entry.x, entry.y, entry.z, entry.source.tileData);
        }
        for (ResolvedBlock entry : resolved) {
            notifyChanged(world, entry.x, entry.y, entry.z, entry.block);
        }
    }

    private static Block resolveBlock(String id) {
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) return null;
        return GameRegistry.findBlock(id.substring(0, separator), id.substring(separator + 1));
    }

    private static final class BlueprintBounds {

        private final int minX, minY, minZ, maxX, maxY, maxZ;

        private BlueprintBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private static BlueprintBounds from(Blueprint blueprint, Location first, Location second) throws IOException {
            int minX = first == null || second == null ? 0 : Math.max(0, Math.min(first.x, second.x));
            int minY = first == null || second == null ? 0 : Math.max(0, Math.min(first.y, second.y));
            int minZ = first == null || second == null ? 0 : Math.max(0, Math.min(first.z, second.z));
            int maxX = first == null || second == null ? blueprint.getSizeX() - 1
                : Math.min(blueprint.getSizeX() - 1, Math.max(first.x, second.x));
            int maxY = first == null || second == null ? blueprint.getSizeY() - 1
                : Math.min(blueprint.getSizeY() - 1, Math.max(first.y, second.y));
            int maxZ = first == null || second == null ? blueprint.getSizeZ() - 1
                : Math.min(blueprint.getSizeZ() - 1, Math.max(first.z, second.z));
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IOException("Copy A/B do not overlap the blueprint");
            }
            return new BlueprintBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private boolean contains(BlueprintBlock block) {
            return block.x >= minX && block.x <= maxX
                && block.y >= minY
                && block.y <= maxY
                && block.z >= minZ
                && block.z <= maxZ;
        }

        private int sizeX() {
            return maxX - minX + 1;
        }

        private int sizeY() {
            return maxY - minY + 1;
        }

        private int sizeZ() {
            return maxZ - minZ + 1;
        }
    }

    private static NBTTagCompound captureTile(World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) return null;
        NBTTagCompound data = new NBTTagCompound();
        tile.writeToNBT(data);
        return data;
    }

    private static void restoreTile(World world, int x, int y, int z, NBTTagCompound sourceData) throws IOException {
        if (sourceData == null) return;

        NBTTagCompound tileData = (NBTTagCompound) sourceData.copy();
        tileData.setInteger("x", x);
        tileData.setInteger("y", y);
        tileData.setInteger("z", z);

        try {
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile == null) {
                tile = TileEntity.createAndLoadEntity(tileData);
                if (tile == null) {
                    throw new IOException("Could not create tile entity at " + x + ", " + y + ", " + z);
                }
                world.setTileEntity(x, y, z, tile);
            } else {
                tile.readFromNBT(tileData);
            }
            tile.markDirty();
        } catch (RuntimeException exception) {
            throw new IOException("Could not restore tile entity at " + x + ", " + y + ", " + z, exception);
        }
    }

    private static void notifyChanged(World world, int x, int y, int z, Block block) {
        world.markBlockForUpdate(x, y, z);
        world.notifyBlocksOfNeighborChange(x, y, z, block);
    }

    public static final class UndoSnapshot {

        private final List<SnapshotBlock> blocks;
        private final int worldId;

        private UndoSnapshot(int worldId, List<SnapshotBlock> blocks) {
            this.worldId = worldId;
            this.blocks = blocks;
        }

        private static UndoSnapshot capture(World world, List<ResolvedBlock> targets) {
            List<SnapshotBlock> blocks = new ArrayList<>(targets.size());
            for (ResolvedBlock target : targets) {
                blocks.add(
                    new SnapshotBlock(
                        target.x,
                        target.y,
                        target.z,
                        world.getBlock(target.x, target.y, target.z),
                        world.getBlockMetadata(target.x, target.y, target.z),
                        captureTile(world, target.x, target.y, target.z)));
            }
            return new UndoSnapshot(world.provider.dimensionId, blocks);
        }

        public void restore(World world) throws IOException {
            if (world.provider.dimensionId != worldId) {
                throw new IOException("Return to the dimension where the paste was made before undoing it");
            }
            for (SnapshotBlock entry : blocks) {
                world.setBlock(entry.x, entry.y, entry.z, Blocks.air, 0, 2);
            }
            for (SnapshotBlock entry : blocks) {
                if (entry.block != Blocks.air) {
                    world.setBlock(entry.x, entry.y, entry.z, entry.block, entry.metadata, 2);
                }
            }
            for (SnapshotBlock entry : blocks) {
                restoreTile(world, entry.x, entry.y, entry.z, entry.tileData);
            }
            for (SnapshotBlock entry : blocks) {
                notifyChanged(world, entry.x, entry.y, entry.z, entry.block);
            }
        }
    }

    private static final class SnapshotBlock {

        private final int x;
        private final int y;
        private final int z;
        private final Block block;
        private final int metadata;
        private final NBTTagCompound tileData;

        private SnapshotBlock(int x, int y, int z, Block block, int metadata, NBTTagCompound tileData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.metadata = metadata;
            this.tileData = tileData;
        }
    }

    private static final class ResolvedBlock {

        private final BlueprintBlock source;
        private final Block block;
        private final int x;
        private final int y;
        private final int z;

        private ResolvedBlock(BlueprintBlock source, Block block, int x, int y, int z) {
            this.source = source;
            this.block = block;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
