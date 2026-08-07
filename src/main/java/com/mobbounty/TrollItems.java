package com.mobbounty;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Harmless troll tools for the OP kit. These are plain vanilla items with a
 * custom display name (no textures/registration needed) - their behaviour
 * is looked up by matching that name when right-clicked.
 */
public final class TrollItems {

	private static final String CURSE_STICK = "Curse Stick";
	private static final String SWAP_STICK = "Swap Stick";
	private static final String SNITCH_SCROLL = "Snitch Scroll";
	private static final String COMPASS_OF_JUDGMENT = "Compass of Judgment";
	private static final String RANDOM_CURSE_WAND = "Random Curse Wand";

	private static final List<StatusEffectInstance> CURSE_POOL = List.of(
			new StatusEffectInstance(StatusEffects.LEVITATION, 60, 1),
			new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0),
			new StatusEffectInstance(StatusEffects.JUMP_BOOST, 200, 9),
			new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 5),
			new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0),
			new StatusEffectInstance(StatusEffects.WEAKNESS, 200, 3)
	);

	private TrollItems() {
	}

	public static void register() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity user) || !(entity instanceof ServerPlayerEntity clicked)) {
				return ActionResult.PASS;
			}

			String name = displayName(player.getStackInHand(hand));
			switch (name) {
				case CURSE_STICK -> {
					BountyManager manager = MobBounty.getManager();
					if (manager == null) {
						return ActionResult.PASS;
					}
					manager.forceSetTarget(clicked);
					user.sendMessage(Text.literal("[MobBounty] Cursed " + clicked.getGameProfile().getName()
							+ " as the new target.").formatted(Formatting.RED), false);
					return ActionResult.SUCCESS;
				}
				case SWAP_STICK -> {
					swapPositions(user, clicked);
					return ActionResult.SUCCESS;
				}
				case RANDOM_CURSE_WAND -> {
					applyRandomCurse(clicked);
					user.sendMessage(Text.literal("[MobBounty] Cursed " + clicked.getGameProfile().getName() + "...")
							.formatted(Formatting.DARK_PURPLE), false);
					return ActionResult.SUCCESS;
				}
				default -> {
					return ActionResult.PASS;
				}
			}
		});

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack stack = player.getStackInHand(hand);
			if (world.isClient || !(player instanceof ServerPlayerEntity user)) {
				return TypedActionResult.pass(stack);
			}

			String name = displayName(stack);
			if (SNITCH_SCROLL.equals(name)) {
				BountyManager manager = MobBounty.getManager();
				if (manager == null) {
					return TypedActionResult.pass(stack);
				}
				manager.revealTargetPublicly();
				stack.decrement(1);
				return TypedActionResult.success(stack);
			}
			if (COMPASS_OF_JUDGMENT.equals(name)) {
				BountyManager manager = MobBounty.getManager();
				if (manager == null) {
					return TypedActionResult.pass(stack);
				}
				manager.sendTargetDirection(user);
				return TypedActionResult.success(stack);
			}
			return TypedActionResult.pass(stack);
		});
	}

	public static void giveKit(ServerPlayerEntity player) {
		player.giveItemStack(namedItem(Items.STICK, CURSE_STICK, Formatting.RED));
		player.giveItemStack(namedItem(Items.BLAZE_ROD, SWAP_STICK, Formatting.LIGHT_PURPLE));
		player.giveItemStack(namedItem(Items.PAPER, SNITCH_SCROLL, Formatting.YELLOW));
		player.giveItemStack(namedItem(Items.COMPASS, COMPASS_OF_JUDGMENT, Formatting.AQUA));
		player.giveItemStack(namedItem(Items.END_ROD, RANDOM_CURSE_WAND, Formatting.DARK_PURPLE));
	}

	private static ItemStack namedItem(Item item, String name, Formatting color) {
		ItemStack stack = new ItemStack(item);
		stack.setCustomName(Text.literal(name).formatted(color));
		return stack;
	}

	private static String displayName(ItemStack stack) {
		return stack.hasCustomName() ? stack.getName().getString() : "";
	}

	private static void swapPositions(ServerPlayerEntity a, ServerPlayerEntity b) {
		if (a.getWorld() != b.getWorld()) {
			a.sendMessage(Text.literal("[MobBounty] Can't swap across dimensions.").formatted(Formatting.GRAY), false);
			return;
		}
		double ax = a.getX();
		double ay = a.getY();
		double az = a.getZ();
		double bx = b.getX();
		double by = b.getY();
		double bz = b.getZ();
		a.requestTeleport(bx, by, bz);
		b.requestTeleport(ax, ay, az);
	}

	private static void applyRandomCurse(ServerPlayerEntity target) {
		StatusEffectInstance chosen = CURSE_POOL.get(ThreadLocalRandom.current().nextInt(CURSE_POOL.size()));
		target.addStatusEffect(new StatusEffectInstance(chosen));
	}
}
