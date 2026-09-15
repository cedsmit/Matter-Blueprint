package com.matterblueprint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.matterblueprint.network.BlueprintNetwork;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = MatterBlueprint.MODID,
    name = MatterBlueprint.NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:matter-manipulator@[0.1.55-GTNH,)")
public final class MatterBlueprint {

    public static final String MODID = "matter-blueprint";
    public static final String NAME = "Matter Blueprint";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        BlueprintNetwork.initialize();
        LOG.info("Loading {} {}", NAME, Tags.VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("Matter Manipulator extension initialized");
    }
}
