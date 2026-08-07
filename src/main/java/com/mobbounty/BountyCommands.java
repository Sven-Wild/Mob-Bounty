package com.mobbounty;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * OP-only admin/testing commands for driving the bounty cycle by hand -
 * forcing targets, skipping waits, tweaking hearts and durations, and
 * handing out the troll item kit.
 */
public final class BountyCommands {

	private BountyCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("bounty")
				.then(CommandManager.literal("target")
						.requires(BountyCommands::isOp)
						.executes(BountyCommands::runTarget))
				.then(CommandManager.literal("settarget")
						.requires(BountyCommands::isOp)
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(BountyCommands::runSetTarget)))
				.then(CommandManager.literal("skip")
						.requires(BountyCommands::isOp)
						.executes(BountyCommands::runSkip))
				.then(CommandManager.literal("setduration")
						.requires(BountyCommands::isOp)
						.then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
								.executes(BountyCommands::runSetDuration)))
				.then(CommandManager.literal("giveheart")
						.requires(BountyCommands::isOp)
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(BountyCommands::runGiveHeart)))
				.then(CommandManager.literal("setheartslost")
						.requires(BountyCommands::isOp)
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
										.executes(BountyCommands::runSetHeartsLost))))
				.then(CommandManager.literal("wave")
						.requires(BountyCommands::isOp)
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(context -> runWave(context, 3))
								.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 10))
										.executes(context -> runWave(context, IntegerArgumentType.getInteger(context, "count"))))))
				.then(CommandManager.literal("pause")
						.requires(BountyCommands::isOp)
						.executes(context -> runSetPaused(context, true)))
				.then(CommandManager.literal("resume")
						.requires(BountyCommands::isOp)
						.executes(context -> runSetPaused(context, false)))
				.then(CommandManager.literal("trollkit")
						.requires(BountyCommands::isOp)
						.executes(BountyCommands::runTrollKit)));
	}

	private static boolean isOp(ServerCommandSource source) {
		return source.hasPermissionLevel(2);
	}

	private static int runTarget(CommandContext<ServerCommandSource> context) {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		context.getSource().sendFeedback(manager::getTargetStatusText, false);
		return 1;
	}

	private static int runSetTarget(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
		manager.forceSetTarget(player);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Target forced to " + player.getGameProfile().getName() + "."), true);
		return 1;
	}

	private static int runSkip(CommandContext<ServerCommandSource> context) {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		manager.skipPhase();
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Phase skipped."), true);
		return 1;
	}

	private static int runSetDuration(CommandContext<ServerCommandSource> context) {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		int seconds = IntegerArgumentType.getInteger(context, "seconds");
		manager.setBountyDurationSeconds(seconds);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Bounty duration set to " + seconds + "s."), true);
		return 1;
	}

	private static int runGiveHeart(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
		manager.giveHeartBack(player);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Restored a heart to " + player.getGameProfile().getName() + "."), true);
		return 1;
	}

	private static int runSetHeartsLost(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
		int amount = IntegerArgumentType.getInteger(context, "amount");
		manager.setHeartsLost(player, amount);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] " + player.getGameProfile().getName()
				+ " now has " + amount + " permanent heart(s) lost."), true);
		return 1;
	}

	private static int runWave(CommandContext<ServerCommandSource> context, int count) throws CommandSyntaxException {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
		manager.spawnWaveOn(player, count);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Spawned a wave of " + count + " on " + player.getGameProfile().getName() + "."), true);
		return 1;
	}

	private static int runSetPaused(CommandContext<ServerCommandSource> context, boolean paused) {
		BountyManager manager = MobBounty.getManager();
		if (manager == null) {
			return fail(context, "Not running yet.");
		}
		manager.setPaused(paused);
		context.getSource().sendFeedback(() -> Text.literal(paused ? "[MobBounty] Cycle paused." : "[MobBounty] Cycle resumed."), true);
		return 1;
	}

	private static int runTrollKit(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		if (player == null) {
			return fail(context, "Only a player can receive the troll kit.");
		}
		TrollItems.giveKit(player);
		context.getSource().sendFeedback(() -> Text.literal("[MobBounty] Troll kit given."), true);
		return 1;
	}

	private static int fail(CommandContext<ServerCommandSource> context, String message) {
		context.getSource().sendError(Text.literal("[MobBounty] " + message));
		return 0;
	}
}
