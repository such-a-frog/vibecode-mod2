package com.example.chestgacha.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.chestgacha.ChestGacha;

/** Client -> server: "The animation is over, you can open the chest now." */
public record AnimationDone(BlockPos pos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AnimationDone> TYPE = new CustomPacketPayload.Type<>(ChestGacha.id("animation_done"));

	public static final StreamCodec<RegistryFriendlyByteBuf, AnimationDone> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			AnimationDone::pos,
			AnimationDone::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
