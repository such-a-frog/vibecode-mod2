package com.example.chestgacha;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Tiny config: config/chestgacha.properties */
public final class Config {
	private static boolean onlyLootChests = false;

	private Config() { }

	/** true = animation only on the FIRST opening of loot chests (dungeons etc.). */
	public static boolean onlyLootChests() {
		return onlyLootChests;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("chestgacha.properties");

		try {
			if (Files.exists(file)) {
				Properties props = new Properties();

				try (Reader reader = Files.newBufferedReader(file)) {
					props.load(reader);
				}

				onlyLootChests = Boolean.parseBoolean(props.getProperty("onlyLootChests", "false").trim());
			} else {
				Files.writeString(file, """
						# Chest Gacha
						# true  = animation only on the first opening of loot chests (dungeons, shipwrecks, ...)
						# false = animation on every chest/barrel that contains at least one item
						onlyLootChests=false
						""");
			}
		} catch (IOException e) {
			ChestGacha.LOGGER.warn("Could not read or write config file {}", file, e);
		}
	}
}
