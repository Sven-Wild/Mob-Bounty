package com.mobbounty;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the bounty cycle: the 1-second "selection" ceremony, the 5-minute
 * hunt, target death/survival handling and mob targeting restrictions.
 */
public final class BountyManager {

	private static final int SELECTION_DURATION_TICKS = 20; // 1 second
	private static final int BOUNTY_DURATION_TICKS = 20 * 60 * 5; // 5 minutes
	private static final int STARTUP_DELAY_TICKS = 100; // 5 seconds, lets players finish logging in

	private static final String GLOW_TEAM_NAME = "mobbounty_glow";
	private static final Identifier MAX_HEALTH_MODIFIER_ID = Identifier.of(MobBounty.MOD_ID, "heart_loss");
	private static final int MAX_HEARTS_LOST = 9; // always leaves at least 1 heart (2 HP)
	private static final String GOLD = "§6";

	private enum Phase {
		WAITING,
		SELECTION,
		BOUNTY
	}

	private final MinecraftServer server;
	private final BountyData data;

	private Phase phase = Phase.WAITING;
	private int timer = STARTUP_DELAY_TICKS;
	private UUID targetUuid;

	public BountyManager(MinecraftServer server) {
		this.server = server;
		this.data = BountyData.load(server);
	}

	public void onServerStarted() {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			addToGlowTeam(player);
			applyHeartLoss(player);
		}
	}

	public void onServerStopping() {
		data.save(server);
	}

	public void onPlayerJoin(ServerPlayerEntity player) {
		addToGlowTeam(player);
		applyHeartLoss(player);
	}

	public void onPlayerRespawn(ServerPlayerEntity player) {
		addToGlowTeam(player);
		applyHeartLoss(player);
	}

	public void tick() {
		if (phase == Phase.BOUNTY) {
			enforceMobTargeting();
		}

		if (timer > 0) {
			timer--;
			return;
		}

		switch (phase) {
			case WAITING -> startSelectionPhase();
			case SELECTION -> startBountyPhase();
			case BOUNTY -> onTargetSurvived();
		}
	}

	public void onPlayerDeath(ServerPlayerEntity player) {
		if (!isTarget(player)) {
			return;
		}

		String name = player.getGameProfile().getName();
		broadcast(GOLD + "[BOUNTY] " + GOLD + name + GOLD + " has been eliminated!");
		broadcast(GOLD + "[BOUNTY] Selecting next bounty...");

		data.incrementHeartsLost(player.getUuid());
		data.setImmunePlayer(player.getUuid());
		data.save(server);
		applyHeartLoss(player);

		targetUuid = null;
		startSelectionPhase();
	}

	public boolean canDamagePlayer(ServerPlayerEntity player, DamageSource source) {
		if (phase != Phase.BOUNTY || isTarget(player)) {
			return true;
		}

		Entity attacker = source.getAttacker();
		return !(attacker instanceof Monster);
	}

	private void startSelectionPhase() {
		phase = Phase.SELECTION;
		timer = SELECTION_DURATION_TICKS;

		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		for (ServerPlayerEntity player : players) {
			applySelectionEffects(player);
		}

		targetUuid = pickNewTarget(players);
	}

	private void startBountyPhase() {
		phase = Phase.BOUNTY;
		timer = BOUNTY_DURATION_TICKS;
	}

	private void onTargetSurvived() {
		ServerPlayerEntity target = getTargetPlayer();
		if (target != null) {
			broadcast(GOLD + "[BOUNTY] " + GOLD + target.getGameProfile().getName() + GOLD + " has survived as target!");
		}

		data.setImmunePlayer(null);
		data.save(server);

		startSelectionPhase();
	}

	private UUID pickNewTarget(List<ServerPlayerEntity> players) {
		if (players.isEmpty()) {
			return null;
		}

		UUID immune = data.getImmunePlayer();
		List<ServerPlayerEntity> candidates = new ArrayList<>();
		for (ServerPlayerEntity player : players) {
			if (!player.getUuid().equals(immune)) {
				candidates.add(player);
			}
		}
		if (candidates.isEmpty()) {
			// Everyone online is immune (e.g. solo server) - the round must still continue.
			candidates.addAll(players);
		}

		ServerPlayerEntity chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		return chosen.getUuid();
	}

	private void applySelectionEffects(ServerPlayerEntity player) {
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, SELECTION_DURATION_TICKS, 0, false, true, true));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, SELECTION_DURATION_TICKS, 0, false, false, false));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, SELECTION_DURATION_TICKS, 9, false, false, false));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, SELECTION_DURATION_TICKS, 4, false, false, false));
	}

	private void enforceMobTargeting() {
		ServerPlayerEntity target = getTargetPlayer();

		for (ServerWorld world : server.getWorlds()) {
			boolean targetInThisWorld = target != null && target.getWorld() == world;

			for (Entity entity : world.iterateEntities()) {
				if (!(entity instanceof Monster) || !(entity instanceof MobEntity mob)) {
					continue;
				}

				if (targetInThisWorld) {
					if (mob.getTarget() != target) {
						mob.setTarget(target);
					}
				} else if (mob.getTarget() instanceof ServerPlayerEntity) {
					mob.setTarget(null);
				}
			}
		}
	}

	private void applyHeartLoss(ServerPlayerEntity player) {
		int lost = Math.min(data.getHeartsLost(player.getUuid()), MAX_HEARTS_LOST);

		EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (instance == null) {
			return;
		}

		instance.removeModifier(MAX_HEALTH_MODIFIER_ID);
		if (lost > 0) {
			instance.addPersistentModifier(new EntityAttributeModifier(
					MAX_HEALTH_MODIFIER_ID, -2.0 * lost, EntityAttributeModifier.Operation.ADD_VALUE));
		}

		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	private void addToGlowTeam(ServerPlayerEntity player) {
		Scoreboard scoreboard = server.getScoreboard();
		Team team = scoreboard.getTeam(GLOW_TEAM_NAME);
		if (team == null) {
			team = scoreboard.addTeam(GLOW_TEAM_NAME);
			team.setColor(Formatting.RED);
		}
		scoreboard.addScoreHolderToTeam(player.getGameProfile().getName(), team);
	}

	private boolean isTarget(ServerPlayerEntity player) {
		return targetUuid != null && targetUuid.equals(player.getUuid());
	}

	private ServerPlayerEntity getTargetPlayer() {
		return targetUuid == null ? null : server.getPlayerManager().getPlayer(targetUuid);
	}

	private void broadcast(String rawMessage) {
		server.getPlayerManager().broadcast(Text.literal(rawMessage), false);
		MobBounty.LOGGER.info(rawMessage.replace(GOLD, ""));
	}
}
