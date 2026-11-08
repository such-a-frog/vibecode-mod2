package com.example.chestgacha.client;

import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.example.chestgacha.GachaTier;
import com.example.chestgacha.net.AnimationDone;

/**
 * The gacha animation:
 *  1. A reel spins and slows down (ease-out), with a click sound for every cell
 *  2. Flash in the rarity colour, light beam, glow, confetti, big item
 *  3. The screen closes -> removed() tells the server "done" -> the chest opens
 *
 * Click / Space / Enter = skip the reel, then end the animation. ESC = end immediately.
 */
public class GachaRevealScreen extends Screen {
	// ---- timeline in milliseconds ----
	private static final long FADE_IN = 200;
	private static final long SPIN_START = 150;
	private static final long SPIN_END = 1950;

	// ---- reel ----
	private static final int CELL = 40;
	private static final int STRIP_LEN = 60;
	private static final int TARGET = STRIP_LEN - 7; // the reel stops here

	/** Decoy items for the reel, roughly sorted from common to rare. */
	private static final Item[] DECOYS = {
			Items.COBBLESTONE, Items.STICK, Items.BREAD, Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT,
			Items.REDSTONE, Items.LAPIS_LAZULI, Items.ENDER_PEARL, Items.DIAMOND, Items.EMERALD,
			Items.GOLDEN_APPLE, Items.ENCHANTED_BOOK, Items.TOTEM_OF_UNDYING, Items.NETHER_STAR,
			Items.ENCHANTED_GOLDEN_APPLE
	};

	private final BlockPos pos;
	private final ItemStack[] strip = new ItemStack[STRIP_LEN];
	private final ItemStack winner;
	private final GachaTier tier;
	private final long closeAt;

	private final float[] confVx;
	private final float[] confVy;
	private final int[] confColor;
	private final int[] confSize;

	private long startMs;
	private int lastTickIdx = -1;
	private boolean revealSoundPlayed;
	private boolean done;

	public GachaRevealScreen(BlockPos pos, List<ItemStack> items) {
		super(Component.empty());
		this.pos = pos;

		Random rnd = new Random();
		this.winner = items.get(GachaTier.indexOfBest(items)).copy();
		this.tier = GachaTier.of(winner);
		this.closeAt = SPIN_END + 650L + 250L * tier.stars;

		// Fill the reel: a mix of the real chest contents and decoys
		for (int i = 0; i < STRIP_LEN; i++) {
			if (rnd.nextFloat() < 0.65f) {
				strip[i] = items.get(rnd.nextInt(items.size())).copy();
			} else {
				int idx = (int) (Math.pow(rnd.nextDouble(), 1.7) * DECOYS.length);
				strip[i] = new ItemStack(DECOYS[Math.min(idx, DECOYS.length - 1)]);
			}
		}

		strip[TARGET] = winner.copy();

		// The classic "so close" trick: something better often sits next to the winner
		if (tier.ordinal() < GachaTier.LEGENDARY.ordinal() && rnd.nextFloat() < 0.5f) {
			strip[TARGET + (rnd.nextBoolean() ? 1 : -1)] = new ItemStack(Items.NETHER_STAR);
		}

		// Pre-compute confetti
		int n = 8 + 10 * tier.stars;
		confVx = new float[n];
		confVy = new float[n];
		confColor = new int[n];
		confSize = new int[n];
		int[] palette = {tier.rgb, tier.rgb, 0xFFFFFF, 0xFFC83D};

		for (int i = 0; i < n; i++) {
			confVx[i] = (rnd.nextFloat() - 0.5f) * (200f + 80f * tier.stars);
			confVy[i] = -(80f + rnd.nextFloat() * (160f + 40f * tier.stars));
			confColor[i] = palette[rnd.nextInt(palette.length)];
			confSize[i] = 2 + rnd.nextInt(3);
		}

		this.startMs = System.currentTimeMillis();
	}

	private long elapsed() {
		return System.currentTimeMillis() - startMs;
	}

	// ------------------------------------------------------------------ input

	private void advance() {
		long t = elapsed();

		if (t < SPIN_END) {
			startMs -= (SPIN_END - t); // skip the reel
		} else {
			onClose();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		advance();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isSelection()) { // space / enter
			advance();
			return true;
		}

		return super.keyPressed(event); // ESC -> onClose()
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (elapsed() >= closeAt) {
			onClose();
		}
	}

	/** Always called when the screen closes (no matter how) -> tell the server exactly once. */
	@Override
	public void removed() {
		super.removed();

		if (done) {
			return;
		}

		done = true;

		if (minecraft != null && minecraft.getConnection() != null) {
			ClientPlayNetworking.send(new AnimationDone(pos));
		}
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Deliberately no super call: we draw our own background instead of the vanilla one.
		long t = elapsed();
		int cx = width / 2;
		int cy = height / 2;

		float fade = Mth.clamp(t / (float) FADE_IN, 0f, 1f);
		graphics.fill(0, 0, width, height, argb(fade * 0.78f, 0x000000));

		if (t < SPIN_END) {
			extractReel(graphics, t, cx, cy);
		} else {
			extractReveal(graphics, t - SPIN_END, cx, cy);
		}
	}

	private void extractReel(GuiGraphicsExtractor g, long t, int cx, int cy) {
		int windowW = Math.min(width - 24, CELL * 7);
		int left = cx - windowW / 2;
		int top = cy - CELL / 2;

		float p = Mth.clamp((t - SPIN_START) / (float) (SPIN_END - SPIN_START), 0f, 1f);
		float eased = 1f - (float) Math.pow(1f - p, 4); // ease-out: fast start, slow finish
		float finalOffset = TARGET * CELL + CELL / 2f - windowW / 2f;
		float offset = finalOffset * eased;

		// Click sound whenever a new cell reaches the middle
		int centerIdx = (int) ((offset + windowW / 2f) / CELL);

		if (centerIdx != lastTickIdx) {
			if (lastTickIdx >= 0) {
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f + 0.7f * eased));
			}

			lastTickIdx = centerIdx;
		}

		g.centeredText(font, Component.translatable("gui.chestgacha.rolling"), cx, top - 26, 0xFFFFFFFF);

		// Frame
		g.fill(left - 3, top - 3, left + windowW + 3, top + CELL + 3, 0xCC101018);
		g.outline(left - 3, top - 3, windowW + 6, CELL + 6, 0xFF5A5A6E);

		// Cells (clipped to the frame)
		g.enableScissor(left, top, left + windowW, top + CELL);
		int first = Math.max(0, (int) Math.floor(offset / CELL));
		int last = Math.min(STRIP_LEN - 1, (int) Math.floor((offset + windowW) / CELL));

		for (int i = first; i <= last; i++) {
			int x = left + Math.round(i * CELL - offset);
			ItemStack stack = strip[i];
			GachaTier cellTier = GachaTier.of(stack);

			g.fill(x + 1, top + 1, x + CELL - 1, top + CELL - 1, argb(0.30f, cellTier.rgb));
			g.fill(x + 1, top + CELL - 4, x + CELL - 1, top + CELL - 1, cellTier.argb());

			g.pose().pushMatrix();
			g.pose().translate(x + (CELL - 32) / 2f, top + 2f);
			g.pose().scale(2f, 2f);
			g.item(stack, 0, 0);
			g.pose().popMatrix();
		}

		g.disableScissor();

		// Marker in the middle (own stratum so it always draws above the items)
		g.nextStratum();
		g.fill(cx - 1, top - 7, cx + 1, top + CELL + 7, 0xFFFFFFFF);

		g.centeredText(font, Component.translatable("gui.chestgacha.skip"), cx, top + CELL + 16, 0xFF8A8A99);
	}

	private void extractReveal(GuiGraphicsExtractor g, long revealMs, int cx, int cy) {
		if (!revealSoundPlayed) {
			revealSoundPlayed = true;
			playRevealSound();
		}

		float r = revealMs / 1000f;
		int centerY = cy - 12;

		// Screen shake on epic / legendary
		float amp = tier.stars >= 4 ? 3.5f * Math.max(0f, 1f - r / 0.45f) : 0f;
		g.pose().pushMatrix();

		if (amp > 0f) {
			g.pose().translate(Mth.sin(r * 95f) * amp, Mth.cos(r * 120f) * amp);
		}

		// Light beam
		int bw = 10 + tier.stars * 5;
		float beamA = (0.12f + 0.05f * tier.stars) * Mth.clamp(r / 0.2f, 0f, 1f);
		g.fillGradient(cx - bw, 0, cx + bw, centerY, argb(0f, tier.rgb), argb(beamA, tier.rgb));
		g.fillGradient(cx - bw, centerY, cx + bw, height, argb(beamA, tier.rgb), argb(0f, tier.rgb));

		// Glow behind the item
		float pulse = 0.85f + 0.15f * Mth.sin(r * 7f);
		int glowR = (int) ((46 + tier.stars * 12) * pulse * Mth.clamp(r / 0.25f, 0f, 1f));
		drawGlow(g, cx, centerY, glowR, tier.rgb, 0.10f + 0.04f * tier.stars);

		// Confetti
		float life = 1f - Mth.clamp(r / 1.8f, 0f, 1f);

		if (life > 0.02f) {
			for (int i = 0; i < confVx.length; i++) {
				int px = (int) (cx + confVx[i] * r);
				int py = (int) (centerY + confVy[i] * r + 0.5f * 380f * r * r);
				g.fill(px, py, px + confSize[i], py + confSize[i], argb(life, confColor[i]));
			}
		}

		// Big item with a "pop"
		float pop = r < 0.3f ? easeOutBack(r / 0.3f) : 1f;
		float scale = Math.max(0.05f, 5f * pop);
		g.pose().pushMatrix();
		g.pose().translate(cx - 8f * scale, centerY - 8f * scale);
		g.pose().scale(scale, scale);
		g.item(winner, 0, 0);
		g.pose().popMatrix();

		// Texts fade in
		float textA = Mth.clamp((r - 0.2f) / 0.25f, 0f, 1f);

		if (textA > 0.05f) {
			MutableComponent name = Component.empty().append(winner.getHoverName());

			if (winner.getCount() > 1) {
				name.append(Component.literal(" \u00d7" + winner.getCount()));
			}

			drawScaled(g, tier.label(), cx, centerY - 74, 2f, argb(textA, tier.rgb));
			drawScaled(g, name, cx, centerY + 52, 1.5f, argb(textA, 0xFFFFFF));
			String stars = "\u2605".repeat(tier.stars) + "\u2606".repeat(5 - tier.stars);
			drawScaled(g, Component.literal(stars), cx, centerY + 70, 1.5f, argb(textA, tier.rgb));
		}

		g.pose().popMatrix();

		// Flash (own stratum so it covers everything, including the shake)
		float flash = Mth.clamp(1f - r / 0.5f, 0f, 1f);

		if (flash > 0f) {
			g.nextStratum();
			g.fill(0, 0, width, height, argb(flash * (0.45f + 0.08f * tier.stars), blend(tier.rgb, 0xFFFFFF, 0.55f)));
		}
	}

	private void playRevealSound() {
		var sounds = minecraft.getSoundManager();

		switch (tier) {
			case COMMON -> sounds.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f));
			case UNCOMMON -> sounds.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f));
			case RARE -> sounds.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
			case EPIC -> {
				sounds.play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f));
				sounds.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4f));
			}
			case LEGENDARY -> {
				sounds.play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f));
				sounds.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.6f));
				sounds.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f));
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Soft round glow: several translucent discs stacked on top of each other. */
	private static void drawGlow(GuiGraphicsExtractor g, int cx, int cy, int radius, int rgb, float alpha) {
		if (radius < 2) {
			return;
		}

		int steps = 6;
		int color = argb(alpha, rgb);

		for (int s = 0; s < steps; s++) {
			int rad = radius - (radius * s) / (steps + 1);

			for (int dy = -rad; dy <= rad; dy += 2) {
				int half = (int) Math.sqrt((double) rad * rad - (double) dy * dy);
				g.fill(cx - half, cy + dy, cx + half, cy + dy + 2, color);
			}
		}
	}

	private void drawScaled(GuiGraphicsExtractor g, Component text, int cx, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(cx, y);
		g.pose().scale(scale, scale);
		g.centeredText(font, text, 0, 0, color);
		g.pose().popMatrix();
	}

	private static float easeOutBack(float x) {
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		float k = x - 1f;
		return 1f + c3 * k * k * k + c1 * k * k;
	}

	private static int argb(float alpha, int rgb) {
		return (Mth.clamp((int) (alpha * 255f), 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static int blend(int rgbA, int rgbB, float t) {
		int r = (int) Mth.lerp(t, (rgbA >> 16) & 0xFF, (rgbB >> 16) & 0xFF);
		int g = (int) Mth.lerp(t, (rgbA >> 8) & 0xFF, (rgbB >> 8) & 0xFF);
		int b = (int) Mth.lerp(t, rgbA & 0xFF, rgbB & 0xFF);
		return (r << 16) | (g << 8) | b;
	}
}
