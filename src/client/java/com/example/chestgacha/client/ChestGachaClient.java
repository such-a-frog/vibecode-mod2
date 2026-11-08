package com.example.chestgacha.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.example.chestgacha.net.PeekResult;

public class ChestGachaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(PeekResult.TYPE, (payload, context) -> {
			if (payload.items().isEmpty()) {
				return;
			}

			context.client().gui.setScreen(new GachaRevealScreen(payload.pos(), payload.items()));
		});
	}
}
