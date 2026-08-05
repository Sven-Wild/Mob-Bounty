package com.mobbounty;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

	private BountyManager bountyManager;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			bountyManager = new BountyManager(server);
			bountyManager.onServerStarted();

			String startupMessage = "[MobBounty/INFO] Mod loaded - Bounty cycle starting";
			LOGGER.info(startupMessage);
			server.getPlayerManager().broadcast(Text.literal(startupMessage), false);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (bountyManager != null) {
				bountyManager.onServerStopping();
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (bountyManager != null) {
				bountyManager.tick();
			}
		});

		ServerPlayerEvents.JOIN.register(player -> {
			if (bountyManager != null) {
				bountyManager.onPlayerJoin(player);
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (bountyManager != null) {
				bountyManager.onPlayerRespawn(newPlayer);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (bountyManager != null && entity instanceof ServerPlayerEntity player) {
				bountyManager.onPlayerDeath(player);
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (bountyManager == null || !(entity instanceof ServerPlayerEntity player)) {
				return true;
			}
			return bountyManager.canDamagePlayer(player, source);
		});
	}
}
