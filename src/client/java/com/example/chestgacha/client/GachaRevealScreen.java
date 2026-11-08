package com.example.chestgacha.client;

import java.util.ArrayList;
import java.util.Collections;
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
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.example.chestgacha.GachaTier;
import com.example.chestgacha.net.AnimationDone;

/**
 * The gacha animation (slot-machine style):
 *  1. Up to 8 reels stand side by side. Each reel shows ONLY items from the chest / loot table and
 *     lands on one of the chest's most valuable items. The reels start one after another, left to right.
 *  2. After the last reel has stopped: flash in the rarity colour, light beam, glow, confetti and the
 *     best item big in the middle.
 *  3. The screen closes -> removed() tells the server "done" -> the chest opens.
 *
 * Click / Space / Enter = skip to the reveal, then end the animation. ESC = end immediately.
 */
public class GachaRevealScreen extends Screen {
	/** Maximum number of reels. The most valuable items get a reel first. */
	private static final int MAX_REELS = 8;

	// ---- timeline in milliseconds ----
	private static final long FADE_IN = 200;
	private static final long SPIN_START = 150;
	/** Delay between the start of two neighbouring reels (left -> right). */
	private static final long STAGGER = 100;
	/** How long one reel spins. */
	private static final long SPIN_DUR = 1300;
	/** Pause between the last reel stopping and the big reveal. */
	private static final long HOLD = 450;

	// ---- reels ----
	private static final int GAP = 6;
	private static final int STRIP_LEN = 40;
	private static final int TARGET = STRIP_LEN - 5; // each reel stops here

	private final BlockPos pos;
	private final int reels;
	private final ItemStack[][] strips;
	private final GachaTier[][] stripTiers;
	private final GachaTier[] reelTiers;
	private final boolean[] stopped;

	private final ItemStack winner;
	private final GachaTier tier;
	private final long revealStart;
	private final long closeAt;

	private final float[] confVx;
	private final float[] confVy;
	private final int[] confColor;
	private final int[] confSize;

	private long startMs;
	private int lastTickReel = -1;
	private int lastTickIdx = -1;
	private boolean skipped;
	private boolean revealSoundPlayed;
	private boolean done;

	public GachaRevealScreen(BlockPos pos, List<ItemStack> items) {
		super(Component.empty());
		this.pos = pos;
		Random rnd = new Random();

		// 1) Merge identical stacks (12 stacks of bread -> one entry), items from the chest only
		List<ItemStack> pool = new ArrayList<>();

		for (ItemStack stack : items) {
			if (stack.isEmpty()) {
				continue;
			}

			boolean merged = false;

			for (ItemStack existing : pool) {
				if (ItemStack.isSameItemSameComponents(existing, stack)) {
					existing.grow(stack.getCount());
					merged = true;
					break;
				}
			}

			if (!merged) {
				pool.add(stack.copy());
			}
		}

		// 2) The most valuable items get a reel (max 8). Shown from least to most valuable,
		//    so the best one lands last, on the right.
		List<ItemStack> chosen = new ArrayList<>(pool);
		chosen.sort((a, b) -> Integer.compare(GachaTier.of(b).ordinal(), GachaTier.of(a).ordinal()));

		if (chosen.size() > MAX_REELS) {
			chosen = new ArrayList<>(chosen.subList(0, MAX_REELS));
		}

		Collections.reverse(chosen);

		this.reels = chosen.size();
		this.strips = new ItemStack[reels][STRIP_LEN];
		this.stripTiers = new GachaTier[reels][STRIP_LEN];
		this.reelTiers = new GachaTier[reels];
		this.stopped = new boolean[reels];

		// 3) Fill every reel with items from the pool only (no random decoys)
		for (int r = 0; r < reels; r++) {
			ItemStack target = chosen.get(r);
			GachaTier targetTier = GachaTier.of(target);
			reelTiers[r] = targetTier;

			for (int i = 0; i < STRIP_LEN; i++) {
				strips[r][i] = pool.get(rnd.nextInt(pool.size())).copy();
			}

			strips[r][TARGET] = target.copy();

			// "So close" trick: a better item from the same chest often sits right next to the winner
			List<ItemStack> better = new ArrayList<>();

			for (ItemStack candidate : pool) {
				if (GachaTier.of(candidate).ordinal() > targetTier.ordinal()) {
					better.add(candidate);
				}
			}

			if (!better.isEmpty() && rnd.nextFloat() < 0.5f) {
				strips[r][TARGET + (rnd.nextBoolean() ? 1 : -1)] = better.get(rnd.nextInt(better.size())).copy();
			}

			for (int i = 0; i < STRIP_LEN; i++) {
				stripTiers[r][i] = GachaTier.of(strips[r][i]);
			}
		}

		// 4) The big reveal shows the best item (= the last reel)
		this.winner = reels > 0 ? chosen.get(reels - 1).copy() : ItemStack.EMPTY;
		this.tier = reels > 0 ? reelTiers[reels - 1] : GachaTier.COMMON;

		this.revealStart = reels > 0 ? startOf(reels - 1) + SPIN_DUR + HOLD : 0;
		this.closeAt = reels > 0 ? revealStart + 650L + 250L * tier.stars : 0;

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

	private static long startOf(int reel) {
		return SPIN_START + reel * STAGGER;
	}

	private long elapsed() {
		return System.currentTimeMillis() - startMs;
	}

	// ------------------------------------------------------------------ input

	private void advance() {
		long t = elapsed();

		if (t < revealStart) {
			skipped = true;
			startMs -= (revealStart - t); // jump straight to the reveal
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
		if (reels == 0) {
			return;
		}

		long t = elapsed();
		int cx = width / 2;
		int cy = height / 2;

		float fade = Mth.clamp(t / (float) FADE_IN, 0f, 1f);
		graphics.fill(0, 0, width, height, argb(fade * 0.78f, 0x000000));

		if (t < revealStart) {
			extractReels(graphics, t, cx, cy);
		} else {
			extractReveal(graphics, t - revealStart, cx, cy);
		}
	}

	private static float easeOut(float p) {
		return 1f - (float) Math.pow(1f - p, 4); // fast start, slow finish
	}

	private void extractReels(GuiGraphicsExtractor g, long t, int cx, int cy) {
		int cell = Mth.clamp((width - 24 - (reels - 1) * GAP) / reels, 24, 40);
		int windowH = cell * 3; // three cells visible, the middle one is the result
		int totalW = reels * cell + (reels - 1) * GAP;
		int left0 = cx - totalW / 2;
		int top = cy - windowH / 2;
		float itemScale = (cell - 8) / 16f;
		float finalOffset = TARGET * cell + cell / 2f - windowH / 2f;

		g.centeredText(font, Component.translatable("gui.chestgacha.rolling"), cx, top - 26, 0xFFFFFFFF);

		// Click sound: only for the reel that started last and is still spinning
		int tickReel = -1;

		for (int r = 0; r < reels; r++) {
			long s = startOf(r);

			if (t >= s && t < s + SPIN_DUR) {
				tickReel = r;
			}
		}

		if (tickReel >= 0) {
			float p = Mth.clamp((t - startOf(tickReel)) / (float) SPIN_DUR, 0f, 1f);
			float eased = easeOut(p);
			int idx = (int) ((finalOffset * eased + windowH / 2f) / cell);

			if (tickReel == lastTickReel && idx != lastTickIdx && !skipped) {
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f + 0.7f * eased));
			}

			lastTickReel = tickReel;
			lastTickIdx = idx;
		}

		// Pass 1: panels and cells
		for (int r = 0; r < reels; r++) {
			int x = left0 + r * (cell + GAP);
			float p = Mth.clamp((t - startOf(r)) / (float) SPIN_DUR, 0f, 1f);
			float offset = finalOffset * easeOut(p);

			if (p >= 1f && !stopped[r]) {
				stopped[r] = true;

				if (!skipped) {
					playStopSound(reelTiers[r]);
				}
			}

			g.fill(x - 2, top - 2, x + cell + 2, top + windowH + 2, 0xCC101018);

			g.enableScissor(x, top, x + cell, top + windowH);
			int first = Math.max(0, (int) Math.floor(offset / cell));
			int last = Math.min(STRIP_LEN - 1, (int) Math.floor((offset + windowH) / cell));

			for (int i = first; i <= last; i++) {
				int y = top + Math.round(i * cell - offset);
				GachaTier cellTier = stripTiers[r][i];

				g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, argb(0.30f, cellTier.rgb));
				g.fill(x + 1, y + cell - 4, x + cell - 1, y + cell - 1, cellTier.argb());

				g.pose().pushMatrix();
				g.pose().translate(x + 4f, y + 3f);
				g.pose().scale(itemScale, itemScale);
				g.item(strips[r][i], 0, 0);
				g.pose().popMatrix();
			}

			g.disableScissor();
		}

		// Pass 2: overlays (own stratum so they always draw above the items)
		g.nextStratum();

		for (int r = 0; r < reels; r++) {
			int x = left0 + r * (cell + GAP);

			// Dim the top and bottom row so the middle row stands out
			g.fillGradient(x, top, x + cell, top + cell, 0xB0000000, 0x00000000);
			g.fillGradient(x, top + 2 * cell, x + cell, top + windowH, 0x00000000, 0xB0000000);

			// Frame: grey while spinning, rarity colour once stopped
			g.outline(x - 2, top - 2, cell + 4, windowH + 4, stopped[r] ? reelTiers[r].argb() : 0xFF5A5A6E);

			// Highlight lines around the middle row
			g.fill(x, top + cell, x + cell, top + cell + 1, 0xAAFFFFFF);
			g.fill(x, top + 2 * cell - 1, x + cell, top + 2 * cell, 0xAAFFFFFF);

			// Short flash in the rarity colour when this reel locks in
			if (stopped[r]) {
				float since = (t - (startOf(r) + SPIN_DUR)) / 350f;
				float a = Mth.clamp(1f - since, 0f, 1f) * 0.55f;

				if (a > 0.01f) {
					g.fill(x, top + cell, x + cell, top + 2 * cell, argb(a, reelTiers[r].rgb));
				}
			}
		}

		g.centeredText(font, Component.translatable("gui.chestgacha.skip"), cx, top + windowH + 14, 0xFF8A8A99);
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

	/** Small feedback sound when a single reel locks in. */
	private void playStopSound(GachaTier reelTier) {
		var sounds = minecraft.getSoundManager();
		sounds.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.6f + 0.12f * reelTier.stars));

		if (reelTier.ordinal() >= GachaTier.RARE.ordinal()) {
			sounds.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f + 0.15f * reelTier.stars));
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
