package com.example.chestgacha;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import com.example.chestgacha.net.AnimationDone;
import com.example.chestgacha.net.PeekResult;

/** Everything that happens on the (logical) server. */
public final class ServerLogic {
	/** After this many ticks an animation that never finished counts as abandoned (10 s). */
	private static final long STALE_TICKS = 200;

	private record Pending(BlockPos pos, InteractionHand hand, BlockHitResult hit, long gameTime) { }

	/** Players whose chest opening is waiting for the animation to finish. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/** Players we are currently letting through ourselves (prevents catching our own re-open). */
	private static final Set<UUID> BYPASS = new HashSet<>();

	private ServerLogic() { }

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PeekResult.TYPE, PeekResult.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AnimationDone.TYPE, AnimationDone.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(AnimationDone.TYPE,
				(payload, context) -> onAnimationDone(payload, context.player()));

		// Run after other mods (claims, protection) so we don't animate chests the player may not open.
		Identifier late = ChestGacha.id("late");
		UseBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, late);
		UseBlockCallback.EVENT.register(late, ServerLogic::onUseBlock);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			PENDING.remove(id);
			BYPASS.remove(id);
		});
	}

	/** Steps 1+2: catch the right-click, look inside, start the animation on the client. */
	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS; // client side: let vanilla continue, the server decides
		}

		if (!(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}

		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}

		UUID id = serverPlayer.getUUID();

		if (BYPASS.contains(id)) {
			return InteractionResult.PASS;
		}

		// Sneaking = open without animation
		if (serverPlayer.isShiftKeyDown() || serverPlayer.isSpectator()) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hit.getBlockPos();
		BlockState state = serverLevel.getBlockState(pos);

		if (!isSupported(state)) {
			return InteractionResult.PASS;
		}

		long now = serverLevel.getGameTime();
		Pending existing = PENDING.get(id);

		if (existing != null && now - existing.gameTime() < STALE_TICKS) {
			return InteractionResult.SUCCESS; // animation already running: swallow extra clicks
		}

		List<ItemStack> items = collect(serverPlayer, serverLevel, pos, state);

		if (items.isEmpty()) {
			return InteractionResult.PASS; // nothing to pull -> open normally
		}

		PENDING.put(id, new Pending(pos.immutable(), hand, hit, now));
		ServerPlayNetworking.send(serverPlayer, new PeekResult(pos.immutable(), items));
		return InteractionResult.SUCCESS;
	}

	/** Step 4: the client finished the animation -> really open the chest now. */
	private static void onAnimationDone(AnimationDone payload, ServerPlayer player) {
		Pending pending = PENDING.remove(player.getUUID());

		if (pending == null || !pending.pos().equals(payload.pos())) {
			return;
		}

		if (!player.isAlive() || player.isSpectator()) {
			return;
		}

		if (player.distanceToSqr(Vec3.atCenterOf(pending.pos())) > 64.0) {
			return; // walked away during the animation
		}

		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		BYPASS.add(player.getUUID());

		try {
			// The same call vanilla makes on right-click; our callback lets it through now.
			player.gameMode.useItemOn(player, level, player.getItemInHand(pending.hand()), pending.hand(), pending.hit());
		} finally {
			BYPASS.remove(player.getUUID());
		}
	}

	private static boolean isSupported(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
	}

	/** Collects all items (including the other half of a double chest). Empty list = no animation. */
	private static List<ItemStack> collect(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		// A blocked chest (block above, cat on top, ...) can't be opened -> no animation either
		if (state.getMenuProvider(level, pos) == null) {
			return List.of();
		}

		List<BlockPos> parts = new ArrayList<>(2);
		parts.add(pos);

		if (state.getBlock() instanceof ChestBlock
				&& state.hasProperty(ChestBlock.TYPE)
				&& state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
		}

		boolean hadLootTable = false;
		List<ItemStack> result = new ArrayList<>();

		for (BlockPos part : parts) {
			BlockEntity blockEntity = level.getBlockEntity(part);

			// Unopened loot chest: roll the loot now (vanilla does this on open anyway)
			if (blockEntity instanceof RandomizableContainer loot && loot.getLootTable() != null) {
				hadLootTable = true;
				loot.unpackLootTable(player);
			}

			if (blockEntity instanceof Container container) {
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);

					if (!stack.isEmpty()) {
						result.add(stack.copy());
					}
				}
			}
		}

		if (Config.onlyLootChests() && !hadLootTable) {
			return List.of();
		}

		return result;
	}
}
