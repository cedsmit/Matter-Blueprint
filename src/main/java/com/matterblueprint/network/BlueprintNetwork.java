package com.matterblueprint.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import com.matterblueprint.blueprint.Blueprint;
import com.matterblueprint.blueprint.BlueprintStore;
import com.matterblueprint.blueprint.BlueprintWorldIO;
import com.matterblueprint.blueprint.BlueprintWorldIO.UndoSnapshot;
import com.matterblueprint.client.ClientBlueprintLibrary;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PendingAction;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public final class BlueprintNetwork {

    private static final int CHUNK_SIZE = 24 * 1024;
    private static final int MAX_TRANSFER_SIZE = 64 * 1024 * 1024;
    private static final int MAX_CHUNKS = MAX_TRANSFER_SIZE / CHUNK_SIZE + 1;
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("matter_blueprint");
    private static final ConcurrentLinkedQueue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> CLIENT_TASKS = new ConcurrentLinkedQueue<>();
    private static final TransferAssembler CLIENT_TRANSFERS = new TransferAssembler();
    private static final TransferAssembler SERVER_TRANSFERS = new TransferAssembler();
    private static final Map<UUID, UndoSnapshot> UNDO_SNAPSHOTS = new ConcurrentHashMap<>();
    private static boolean initialized;

    private BlueprintNetwork() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(CaptureRequestHandler.class, CaptureRequest.class, 0, Side.SERVER);
        CHANNEL.registerMessage(CapturedChunkHandler.class, CapturedChunk.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(PasteChunkHandler.class, PasteChunk.class, 2, Side.SERVER);
        CHANNEL.registerMessage(UndoRequestHandler.class, UndoRequest.class, 3, Side.SERVER);
        CHANNEL.registerMessage(SetBoundsRequestHandler.class, SetBoundsRequest.class, 4, Side.SERVER);
        CHANNEL.registerMessage(MoveBlueprintRequestHandler.class, MoveBlueprintRequest.class, 5, Side.SERVER);
        CHANNEL.registerMessage(UnlockBlueprintRequestHandler.class, UnlockBlueprintRequest.class, 6, Side.SERVER);
        CHANNEL.registerMessage(UndoAvailabilityHandler.class, UndoAvailability.class, 7, Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(new TickHandler());
    }

    public static void requestCapture(String name) {
        CHANNEL.sendToServer(new CaptureRequest(name));
    }

    public static void uploadForPaste(String name, byte[] data) throws IOException {
        validateTransfer(data);
        String transferId = UUID.randomUUID()
            .toString();
        int total = chunkCount(data);
        for (int index = 0; index < total; index++) {
            CHANNEL.sendToServer(new PasteChunk(transferId, name, index, total, chunk(data, index)));
        }
    }

    public static void requestUndo() {
        CHANNEL.sendToServer(new UndoRequest());
    }

    public static void setBlueprintBounds(int sizeX, int sizeY, int sizeZ) {
        CHANNEL.sendToServer(new SetBoundsRequest(sizeX, sizeY, sizeZ));
    }

    public static void moveBlueprint(int x, int y, int z) {
        CHANNEL.sendToServer(new MoveBlueprintRequest(x, y, z));
    }

    public static void unlockBlueprint() {
        CHANNEL.sendToServer(new UnlockBlueprintRequest());
    }

    private static void capture(EntityPlayerMP player, String name) {
        try {
            requirePermission(player);
            BlueprintStore.validateName(name);
            MMState state = getManipulatorState(player);
            Blueprint blueprint = BlueprintWorldIO.capture(player.worldObj, state.config.coordA, state.config.coordB);
            byte[] data = BlueprintStore.encodeCompressed(blueprint);
            validateTransfer(data);

            String transferId = UUID.randomUUID()
                .toString();
            int total = chunkCount(data);
            for (int index = 0; index < total; index++) {
                CHANNEL.sendTo(new CapturedChunk(transferId, name, index, total, chunk(data, index)), player);
            }
        } catch (IOException | IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static void paste(EntityPlayerMP player, String name, byte[] data) {
        try {
            requirePermission(player);
            BlueprintStore.validateName(name);
            Blueprint blueprint = BlueprintStore.decodeCompressed(data);
            MMState state = getManipulatorState(player);
            Location origin = state.config.coordC;
            if (origin == null || !origin.isInWorld(player.worldObj)) {
                origin = new Location(player.worldObj, MMUtils.getLookingAtLocation(player));
            }
            BlueprintWorldIO.paste(
                player,
                blueprint,
                origin,
                state.config.coordA,
                state.config.coordB,
                state.config.arraySpan,
                state.getTransform(),
                snapshot -> UNDO_SNAPSHOTS.put(player.getUniqueID(), snapshot));
            CHANNEL.sendTo(new UndoAvailability(true), player);
            notifyPlayer(player, "Pasted '" + name + "'");
        } catch (IOException | IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static void undo(EntityPlayerMP player) {
        try {
            requirePermission(player);
            getManipulatorState(player);
            UndoSnapshot snapshot = UNDO_SNAPSHOTS.get(player.getUniqueID());
            if (snapshot == null) throw new IllegalStateException("There is no paste to undo");
            snapshot.restore(player.worldObj);
            UNDO_SNAPSHOTS.remove(player.getUniqueID(), snapshot);
            CHANNEL.sendTo(new UndoAvailability(false), player);
            notifyPlayer(player, "Restored the world state from before the last paste");
        } catch (IOException | IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static void setBounds(EntityPlayerMP player, int sizeX, int sizeY, int sizeZ) {
        try {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalStateException("Invalid blueprint dimensions");
            }
            ItemStack held = player.getHeldItem();
            MMState state = getManipulatorState(player);
            state.config.coordA = new Location(player.worldObj, 0, 0, 0);
            state.config.coordB = new Location(player.worldObj, sizeX - 1, sizeY - 1, sizeZ - 1);
            ItemMatterManipulator.setState(held, state);
        } catch (IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static void moveBlueprint(EntityPlayerMP player, int x, int y, int z) {
        try {
            ItemStack held = player.getHeldItem();
            MMState state = getManipulatorState(player);
            Location position = state.config.coordC;
            if (position == null || !position.isInWorld(player.worldObj)) {
                position = new Location(player.worldObj, MMUtils.getLookingAtLocation(player));
            }
            int targetY = position.y + y;
            if (targetY < 0 || targetY > 255)
                throw new IllegalStateException("Blueprint position is outside the world");
            state.config.coordC = new Location(player.worldObj, position.x + x, targetY, position.z + z);
            ItemMatterManipulator.setState(held, state);
        } catch (IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static void unlockBlueprint(EntityPlayerMP player) {
        try {
            ItemStack held = player.getHeldItem();
            MMState state = getManipulatorState(player);
            state.config.coordC = null;
            state.config.action = PendingAction.MARK_PASTE;
            ItemMatterManipulator.setState(held, state);
        } catch (IllegalStateException exception) {
            notifyPlayer(player, exception.getMessage());
        }
    }

    private static MMState getManipulatorState(EntityPlayerMP player) {
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemMatterManipulator)) {
            throw new IllegalStateException("Hold a Matter Manipulator first");
        }
        return ItemMatterManipulator.getState(held);
    }

    private static void requirePermission(EntityPlayerMP player) {
        if (!player.canCommandSenderUseCommand(2, "matterblueprint")) {
            throw new IllegalStateException("Operator permission is required");
        }
    }

    private static void notifyPlayer(EntityPlayerMP player, String message) {
        player.addChatMessage(new ChatComponentText("Matter Blueprint: " + message));
    }

    private static void validateTransfer(byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_TRANSFER_SIZE) {
            throw new IOException("Blueprint transfer must be between 1 byte and 64 MiB");
        }
    }

    private static int chunkCount(byte[] data) {
        return (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    private static byte[] chunk(byte[] data, int index) {
        int offset = index * CHUNK_SIZE;
        int length = Math.min(CHUNK_SIZE, data.length - offset);
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

    private static abstract class TransferChunk implements IMessage {

        protected String transferId;
        protected String name;
        protected int index;
        protected int total;
        protected byte[] data;

        protected TransferChunk() {}

        protected TransferChunk(String transferId, String name, int index, int total, byte[] data) {
            this.transferId = transferId;
            this.name = name;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            transferId = ByteBufUtils.readUTF8String(buffer);
            name = ByteBufUtils.readUTF8String(buffer);
            index = buffer.readInt();
            total = buffer.readInt();
            int length = buffer.readUnsignedShort();
            data = new byte[length];
            buffer.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            ByteBufUtils.writeUTF8String(buffer, transferId);
            ByteBufUtils.writeUTF8String(buffer, name);
            buffer.writeInt(index);
            buffer.writeInt(total);
            buffer.writeShort(data.length);
            buffer.writeBytes(data);
        }
    }

    public static final class CaptureRequest implements IMessage {

        private String name;

        public CaptureRequest() {}

        private CaptureRequest(String name) {
            this.name = name;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            name = ByteBufUtils.readUTF8String(buffer);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            ByteBufUtils.writeUTF8String(buffer, name);
        }
    }

    public static final class CapturedChunk extends TransferChunk {

        public CapturedChunk() {}

        private CapturedChunk(String transferId, String name, int index, int total, byte[] data) {
            super(transferId, name, index, total, data);
        }
    }

    public static final class PasteChunk extends TransferChunk {

        public PasteChunk() {}

        private PasteChunk(String transferId, String name, int index, int total, byte[] data) {
            super(transferId, name, index, total, data);
        }
    }

    public static final class UndoRequest implements IMessage {

        public UndoRequest() {}

        @Override
        public void fromBytes(ByteBuf buffer) {}

        @Override
        public void toBytes(ByteBuf buffer) {}
    }

    public static final class SetBoundsRequest implements IMessage {

        private int sizeX;
        private int sizeY;
        private int sizeZ;

        public SetBoundsRequest() {}

        private SetBoundsRequest(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            sizeX = buffer.readInt();
            sizeY = buffer.readInt();
            sizeZ = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(sizeX);
            buffer.writeInt(sizeY);
            buffer.writeInt(sizeZ);
        }
    }

    public static final class MoveBlueprintRequest implements IMessage {

        private int x;
        private int y;
        private int z;

        public MoveBlueprintRequest() {}

        private MoveBlueprintRequest(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            x = buffer.readInt();
            y = buffer.readInt();
            z = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
        }
    }

    public static final class UnlockBlueprintRequest implements IMessage {

        public UnlockBlueprintRequest() {}

        @Override
        public void fromBytes(ByteBuf buffer) {}

        @Override
        public void toBytes(ByteBuf buffer) {}
    }

    public static final class UndoAvailability implements IMessage {

        private boolean available;

        public UndoAvailability() {}

        private UndoAvailability(boolean available) {
            this.available = available;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            available = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeBoolean(available);
        }
    }

    public static final class CaptureRequestHandler implements IMessageHandler<CaptureRequest, IMessage> {

        @Override
        public IMessage onMessage(CaptureRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> capture(player, message.name));
            return null;
        }
    }

    public static final class CapturedChunkHandler implements IMessageHandler<CapturedChunk, IMessage> {

        @Override
        public IMessage onMessage(CapturedChunk message, MessageContext context) {
            CLIENT_TASKS.add(() -> {
                byte[] complete = CLIENT_TRANSFERS.accept(message);
                if (complete != null) ClientBlueprintLibrary.receiveCapture(message.name, complete);
            });
            return null;
        }
    }

    public static final class PasteChunkHandler implements IMessageHandler<PasteChunk, IMessage> {

        @Override
        public IMessage onMessage(PasteChunk message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> {
                byte[] complete = SERVER_TRANSFERS.accept(message);
                if (complete != null) paste(player, message.name, complete);
            });
            return null;
        }
    }

    public static final class UndoRequestHandler implements IMessageHandler<UndoRequest, IMessage> {

        @Override
        public IMessage onMessage(UndoRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> undo(player));
            return null;
        }
    }

    public static final class SetBoundsRequestHandler implements IMessageHandler<SetBoundsRequest, IMessage> {

        @Override
        public IMessage onMessage(SetBoundsRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> setBounds(player, message.sizeX, message.sizeY, message.sizeZ));
            return null;
        }
    }

    public static final class MoveBlueprintRequestHandler implements IMessageHandler<MoveBlueprintRequest, IMessage> {

        @Override
        public IMessage onMessage(MoveBlueprintRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> moveBlueprint(player, message.x, message.y, message.z));
            return null;
        }
    }

    public static final class UnlockBlueprintRequestHandler
        implements IMessageHandler<UnlockBlueprintRequest, IMessage> {

        @Override
        public IMessage onMessage(UnlockBlueprintRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_TASKS.add(() -> unlockBlueprint(player));
            return null;
        }
    }

    public static final class UndoAvailabilityHandler implements IMessageHandler<UndoAvailability, IMessage> {

        @Override
        public IMessage onMessage(UndoAvailability message, MessageContext context) {
            CLIENT_TASKS.add(() -> ClientBlueprintLibrary.setUndoAvailable(message.available));
            return null;
        }
    }

    public static final class TickHandler {

        @SubscribeEvent
        public void serverTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) drain(SERVER_TASKS);
        }

        @SubscribeEvent
        public void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) drain(CLIENT_TASKS);
        }

        private static void drain(ConcurrentLinkedQueue<Runnable> tasks) {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    private static final class TransferAssembler {

        private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

        private synchronized byte[] accept(TransferChunk chunk) {
            if (
                chunk.total <= 0 || chunk.total > MAX_CHUNKS
                    || chunk.index < 0
                    || chunk.index >= chunk.total
                    || chunk.data.length > CHUNK_SIZE
            ) {
                transfers.remove(chunk.transferId);
                return null;
            }

            Transfer transfer = transfers.computeIfAbsent(chunk.transferId, ignored -> new Transfer(chunk.total));
            if (transfer.chunks.length != chunk.total || transfer.chunks[chunk.index] != null) return null;
            transfer.chunks[chunk.index] = chunk.data;
            transfer.received++;
            if (transfer.received != chunk.total) return null;

            transfers.remove(chunk.transferId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] part : transfer.chunks) output.write(part, 0, part.length);
            byte[] complete = output.toByteArray();
            return complete.length <= MAX_TRANSFER_SIZE ? complete : null;
        }
    }

    private static final class Transfer {

        private final byte[][] chunks;
        private int received;

        private Transfer(int total) {
            chunks = new byte[total][];
        }
    }
}
