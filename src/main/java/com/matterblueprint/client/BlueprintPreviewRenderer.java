package com.matterblueprint.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import org.joml.Vector3f;
import org.joml.Vector3i;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.matterblueprint.blueprint.Blueprint;
import com.matterblueprint.blueprint.Blueprint.BlueprintBlock;
import com.recursive_pineapple.matter_manipulator.GlobalMMConfig.RenderingConfig;
import com.recursive_pineapple.matter_manipulator.client.rendering.BoxRenderer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.RenderHints;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import cpw.mods.fml.common.registry.GameRegistry;

public final class BlueprintPreviewRenderer {

    private static final short[] TINT = { 229, 242, 255, 255 };

    private BlueprintPreviewRenderer() {}

    public static void renderLoadedBlueprint(float partialTicks) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        Blueprint blueprint = ClientBlueprintLibrary.getLoadedData();
        if (player == null || blueprint == null) return;

        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemMatterManipulator)) return;

        MMState state = ItemMatterManipulator.getState(held);

        World world = player.worldObj;
        Location markedPaste = state.config.coordC;
        Vector3i origin = markedPaste != null && markedPaste.isInWorld(world) ? markedPaste.toVec()
            : MMUtils.getLookingAtLocation(player);
        Transform transform = state.getTransform();
        int minX = state.config.coordA == null || state.config.coordB == null ? 0
            : Math.max(0, Math.min(state.config.coordA.x, state.config.coordB.x));
        int minY = state.config.coordA == null || state.config.coordB == null ? 0
            : Math.max(0, Math.min(state.config.coordA.y, state.config.coordB.y));
        int minZ = state.config.coordA == null || state.config.coordB == null ? 0
            : Math.max(0, Math.min(state.config.coordA.z, state.config.coordB.z));
        int maxX = state.config.coordA == null || state.config.coordB == null ? blueprint.getSizeX() - 1
            : Math.min(blueprint.getSizeX() - 1, Math.max(state.config.coordA.x, state.config.coordB.x));
        int maxY = state.config.coordA == null || state.config.coordB == null ? blueprint.getSizeY() - 1
            : Math.min(blueprint.getSizeY() - 1, Math.max(state.config.coordA.y, state.config.coordB.y));
        int maxZ = state.config.coordA == null || state.config.coordB == null ? blueprint.getSizeZ() - 1
            : Math.min(blueprint.getSizeZ() - 1, Math.max(state.config.coordA.z, state.config.coordB.z));
        if (minX > maxX || minY > maxY || minZ > maxZ) return;
        int spanX = state.config.arraySpan == null ? 0 : state.config.arraySpan.x;
        int spanY = state.config.arraySpan == null ? 0 : state.config.arraySpan.y;
        int spanZ = state.config.arraySpan == null ? 0 : state.config.arraySpan.z;
        int hints = 0;
        RenderHints hintsRenderer = RenderHints.INSTANCE;
        hintsRenderer.start();
        hintsRenderer.setDepthTest(true);
        transform.cacheRotation();
        try {
            preview: for (int stackY = Math.min(spanY, 0); stackY <= Math.max(spanY, 0); stackY++) {
                for (int stackZ = Math.min(spanZ, 0); stackZ <= Math.max(spanZ, 0); stackZ++) {
                    for (int stackX = Math.min(spanX, 0); stackX <= Math.max(spanX, 0); stackX++) {
                        Vector3i stackOffset = transform.apply(
                            new Vector3i(
                                stackX * (maxX - minX + 1),
                                stackY * (maxY - minY + 1),
                                stackZ * (maxZ - minZ + 1)));
                        Vector3i copyOrigin = new Vector3i(origin).add(stackOffset);
                        drawBounds(copyOrigin, transform, partialTicks, minX, minY, minZ, maxX, maxY, maxZ);
                        for (BlueprintBlock source : blueprint.getBlocks()) {
                            if (hints >= RenderingConfig.maxHints) break preview;
                            if (
                                source.x < minX || source.x > maxX
                                    || source.y < minY
                                    || source.y > maxY
                                    || source.z < minZ
                                    || source.z > maxZ
                            ) continue;

                            Vector3i target = transform.apply(new Vector3i(source.x, source.y, source.z))
                                .add(copyOrigin);
                            if (target.y < 0 || target.y > 255) continue;

                            Block block = resolveBlock(source.blockId);
                            if (block == null) continue;
                            if (block == Blocks.air) {
                                if (!world.isAirBlock(target.x, target.y, target.z)) {
                                    hintsRenderer.addHint(
                                        target.x,
                                        target.y,
                                        target.z,
                                        StructureLibAPI.getBlockHint(),
                                        StructureLibAPI.HINT_BLOCK_META_ERROR,
                                        TINT);
                                    hints++;
                                }
                            } else {
                                hintsRenderer.addHint(target.x, target.y, target.z, block, source.metadata, TINT);
                                hints++;
                            }
                        }
                    }
                }
            }
        } finally {
            transform.uncacheRotation();
            hintsRenderer.finish();
        }
    }

    private static void drawBounds(Vector3i origin, Transform transform, float partialTicks, int minX, int minY,
        int minZ, int maxX, int maxY, int maxZ) {
        int minWorldX = Integer.MAX_VALUE;
        int minWorldY = Integer.MAX_VALUE;
        int minWorldZ = Integer.MAX_VALUE;
        int maxWorldX = Integer.MIN_VALUE;
        int maxWorldY = Integer.MIN_VALUE;
        int maxWorldZ = Integer.MIN_VALUE;

        for (int x : new int[] { minX, maxX }) {
            for (int y : new int[] { minY, maxY }) {
                for (int z : new int[] { minZ, maxZ }) {
                    Vector3i corner = transform.apply(new Vector3i(x, y, z))
                        .add(origin);
                    minWorldX = Math.min(minWorldX, corner.x);
                    minWorldY = Math.min(minWorldY, corner.y);
                    minWorldZ = Math.min(minWorldZ, corner.z);
                    maxWorldX = Math.max(maxWorldX, corner.x);
                    maxWorldY = Math.max(maxWorldY, corner.y);
                    maxWorldZ = Math.max(maxWorldZ, corner.z);
                }
            }
        }

        AxisAlignedBB bounds = AxisAlignedBB
            .getBoundingBox(minWorldX, minWorldY, minWorldZ, maxWorldX + 1, maxWorldY + 1, maxWorldZ + 1);
        BoxRenderer.INSTANCE.start(partialTicks);
        BoxRenderer.INSTANCE.drawAround(bounds, new Vector3f(0.2f, 0.85f, 1.0f));
        BoxRenderer.INSTANCE.finish();
    }

    private static Block resolveBlock(String id) {
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) return null;
        return GameRegistry.findBlock(id.substring(0, separator), id.substring(separator + 1));
    }
}
