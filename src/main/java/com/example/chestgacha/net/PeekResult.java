package com.example.chestgacha.net;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import com.example.chestgacha.ChestGacha;

/** Server -> client: "Play the animation for this chest. This is what's inside:" */
public record PeekResult(BlockPos pos, List<ItemStack> items) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PeekResult> TYPE = new CustomPacketPayload.Type<>(ChestGacha.id("peek_result"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PeekResult> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			PeekResult::pos,
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
			PeekResult::items,
			PeekResult::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
