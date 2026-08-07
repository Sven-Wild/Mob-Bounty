package com.mobbounty;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every 5 minutes a random player is secretly marked as the "bounty".
 * During that round hostile mobs hunt only the bounty target; everyone
 * else is invisible to them. Dying as the target permanently costs a heart.
 */
public final class MobBounty implements ModInitializer {

	public static final String MOD_ID = "mobbounty";
	public static final Logger LOGGER = LoggerFactory.getLogger("MobBounty");

	private static BountyManager manager;

	public static BountyManager getManager() {
		return manager;
	}

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			manager = new BountyManager(server);
			manager.onServerStarted();

			String startupMessage = "[MobBounty/INFO] Mod loaded - Bounty cycle starting";
			LOGGER.info(startupMessage);
			server.getPlayerManager().broadcast(Text.literal(startupMessage), false);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (manager != null) {
				manager.onServerStopping();
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (manager != null) {
				manager.tick();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (manager != null) {
				manager.onPlayerJoin(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (manager != null) {
				manager.onPlayerLeave(handler.getPlayer());
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (manager != null) {
				manager.onPlayerRespawn(newPlayer);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (manager != null && entity instanceof ServerPlayerEntity player) {
				manager.onPlayerDeath(player);
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (manager == null || !(entity instanceof ServerPlayerEntity player)) {
				return true;
			}
			return manager.canDamagePlayer(player, source);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				BountyCommands.register(dispatcher));

		TrollItems.register();
	}
}
