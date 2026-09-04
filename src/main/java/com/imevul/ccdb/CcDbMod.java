package com.imevul.ccdb;

import dan200.computercraft.api.ComputerCraftAPI;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CcDbMod.MOD_ID)
public final class CcDbMod {
	public static final String MOD_ID = "ccdb";
	private static final Logger LOGGER = LoggerFactory.getLogger(CcDbMod.class);

	public CcDbMod(IEventBus modBus) {
		modBus.addListener(this::onCommonSetup);
		NeoForge.EVENT_BUS.addListener(this::onServerStarted);
		NeoForge.EVENT_BUS.addListener(this::onServerStopping);
	}

	private void onCommonSetup(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			ComputerCraftAPI.registerAPIFactory(CcdbAPI::new);
			LOGGER.info("Registered ComputerCraft ccdb API");
		});
	}

	private void onServerStarted(ServerStartedEvent event) {
		var dir = event.getServer().getWorldPath(LevelResource.ROOT).resolve("computercraft").resolve("ccdb");
		try {
			Database.get().open(dir);
			LOGGER.info("Opened ccdb at {}", dir.resolve("main.sqlite"));
		} catch (Exception e) {
			LOGGER.error("Failed to open ccdb", e);
		}
	}

	private void onServerStopping(ServerStoppingEvent event) {
		Database.get().close();
		LOGGER.info("Closed ccdb");
	}
}
