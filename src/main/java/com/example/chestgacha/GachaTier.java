package com.example.chestgacha;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The five gacha tiers (1 to 5 stars) and the rules for which item lands in which tier. */
public enum GachaTier {
	COMMON(1, 0x9AA0A6),
	UNCOMMON(2, 0x55D66B),
	RARE(3, 0x4DA3FF),
	EPIC(4, 0xB45CFF),
	LEGENDARY(5, 0xFFC83D);

	public final int stars;
	/** RGB without alpha. */
	public final int rgb;

	GachaTier(int stars, int rgb) {
		this.stars = stars;
		this.rgb = rgb;
	}

	public int argb() {
		return 0xFF000000 | rgb;
	}

	public Component label() {
		return Component.translatable("tier." + ChestGacha.MOD_ID + "." + name().toLowerCase(Locale.ROOT));
	}

	/** Tier of an item: the higher of vanilla rarity and our own list below. */
	public static GachaTier of(ItemStack stack) {
		if (stack.isEmpty()) {
			return COMMON;
		}

		GachaTier byRarity = switch (stack.getRarity()) {
			case UNCOMMON -> UNCOMMON;
			case RARE -> RARE;
			case EPIC -> EPIC;
			default -> COMMON;
		};
		GachaTier byItem = Overrides.MAP.getOrDefault(stack.getItem(), COMMON);
		return byRarity.ordinal() >= byItem.ordinal() ? byRarity : byItem;
	}

	/** Index of the most valuable stack (first one on a tie). */
	public static int indexOfBest(List<ItemStack> stacks) {
		int best = 0;
		GachaTier bestTier = COMMON;

		for (int i = 0; i < stacks.size(); i++) {
			GachaTier tier = of(stacks.get(i));

			if (i == 0 || tier.ordinal() > bestTier.ordinal()) {
				best = i;
				bestTier = tier;
			}
		}

		return best;
	}

	/** Edit this to change what counts as rare. */
	private static final class Overrides {
		static final Map<Item, GachaTier> MAP = new HashMap<>();

		private static void put(GachaTier tier, Item... items) {
			for (Item item : items) {
				MAP.put(item, tier);
			}
		}

		static {
			put(LEGENDARY,
					Items.ENCHANTED_GOLDEN_APPLE, Items.NETHER_STAR, Items.ELYTRA, Items.TOTEM_OF_UNDYING,
					Items.DRAGON_EGG, Items.BEACON, Items.TRIDENT, Items.HEART_OF_THE_SEA,
					Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK);
			put(EPIC,
					Items.DIAMOND_BLOCK, Items.NETHERITE_SCRAP, Items.ANCIENT_DEBRIS,
					Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND_HORSE_ARMOR, Items.SHULKER_SHELL,
					Items.ECHO_SHARD);
			put(RARE,
					Items.DIAMOND, Items.EMERALD, Items.GOLDEN_APPLE, Items.ENCHANTED_BOOK, Items.ENDER_EYE,
					Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL,
					Items.DIAMOND_HOE, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
					Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
			put(UNCOMMON,
					Items.IRON_INGOT, Items.GOLD_INGOT, Items.LAPIS_LAZULI, Items.REDSTONE, Items.NAME_TAG,
					Items.SADDLE, Items.IRON_HORSE_ARMOR, Items.GOLDEN_HORSE_ARMOR, Items.ENDER_PEARL,
					Items.OBSIDIAN);
		}
	}
}
