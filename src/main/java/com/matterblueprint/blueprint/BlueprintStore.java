package com.matterblueprint.blueprint;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.matterblueprint.blueprint.Blueprint.BlueprintBlock;

public final class BlueprintStore {

    public static final String EXTENSION = ".mbp";

    private BlueprintStore() {}

    public static File ensureDirectory(File minecraftDirectory) throws IOException {
        File directory = new File(minecraftDirectory, "matter-blueprints").getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create blueprint directory: " + directory);
        }
        return directory;
    }

    public static void save(File minecraftDirectory, String name, byte[] compressedBlueprint) throws IOException {
        File target = resolve(minecraftDirectory, name);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");

        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
            output.write(compressedBlueprint);
        }

        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] load(File minecraftDirectory, String name) throws IOException {
        File source = resolve(minecraftDirectory, name);
        if (!source.isFile()) {
            throw new IOException("Blueprint does not exist: " + name);
        }
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    public static Blueprint decodeCompressed(byte[] data) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            return decode(CompressedStreamTools.readCompressed(input));
        }
    }

    public static byte[] encodeCompressed(Blueprint blueprint) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CompressedStreamTools.writeCompressed(encode(blueprint), output);
            return output.toByteArray();
        }
    }

    public static List<String> list(File minecraftDirectory) throws IOException {
        String[] files = ensureDirectory(minecraftDirectory)
            .list((directory, filename) -> filename.endsWith(EXTENSION));
        if (files == null) return Collections.emptyList();

        Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
        List<String> names = new ArrayList<>(files.length);
        for (String file : files) {
            names.add(file.substring(0, file.length() - EXTENSION.length()));
        }
        return names;
    }

    public static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("Use 1-64 letters, numbers, dots, dashes, or underscores");
        }
    }

    private static File resolve(File minecraftDirectory, String name) throws IOException {
        validateName(name);
        File directory = ensureDirectory(minecraftDirectory);
        File file = new File(directory, name + EXTENSION).getCanonicalFile();
        if (
            !file.getParentFile()
                .equals(directory)
        ) {
            throw new IOException("Invalid blueprint path");
        }
        return file;
    }

    private static NBTTagCompound encode(Blueprint blueprint) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("format", Blueprint.FORMAT_VERSION);
        root.setInteger("sizeX", blueprint.getSizeX());
        root.setInteger("sizeY", blueprint.getSizeY());
        root.setInteger("sizeZ", blueprint.getSizeZ());

        NBTTagList blocks = new NBTTagList();
        for (BlueprintBlock block : blueprint.getBlocks()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", block.x);
            entry.setInteger("y", block.y);
            entry.setInteger("z", block.z);
            entry.setString("id", block.blockId);
            entry.setInteger("meta", block.metadata);
            if (block.tileData != null) entry.setTag("tile", block.tileData.copy());
            blocks.appendTag(entry);
        }
        root.setTag("blocks", blocks);
        return root;
    }

    private static Blueprint decode(NBTTagCompound root) throws IOException {
        int format = root.getInteger("format");
        if (format != Blueprint.FORMAT_VERSION) {
            throw new IOException("Unsupported blueprint format: " + format);
        }

        NBTTagList entries = root.getTagList("blocks", 10);
        List<BlueprintBlock> blocks = new ArrayList<>(entries.tagCount());
        for (int index = 0; index < entries.tagCount(); index++) {
            NBTTagCompound entry = entries.getCompoundTagAt(index);
            NBTTagCompound tile = entry.hasKey("tile", 10) ? entry.getCompoundTag("tile") : null;
            blocks.add(
                new BlueprintBlock(
                    entry.getInteger("x"),
                    entry.getInteger("y"),
                    entry.getInteger("z"),
                    entry.getString("id"),
                    entry.getInteger("meta"),
                    tile));
        }

        return new Blueprint(root.getInteger("sizeX"), root.getInteger("sizeY"), root.getInteger("sizeZ"), blocks);
    }
}
