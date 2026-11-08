package com.example.chestgacha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

/**
 * Chest Gacha - plays a gacha animation before a chest opens.
 *
 * Flow:
 *  1. Player right-clicks a chest or barrel.
 *  2. The SERVER holds the opening back, looks inside (rolling loot if needed)
 *     and sends the contents to the client.
 *  3. The CLIENT plays the reel animation (GachaRevealScreen).
 *  4. When the screen closes, the client reports "done" and the SERVER opens the chest normally.
 */
public class ChestGacha implements ModInitializer {
	public static final String MOD_ID = "chestgacha";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Config.load();
		ServerLogic.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
