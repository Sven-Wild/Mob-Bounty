package com.mobbounty;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the bounty cycle: the 1-second "selection" ceremony, the 5-minute
 * hunt (with escalating reinforcement waves and a boss bar countdown),
 * target death/survival handling and mob targeting restrictions.
 */
public final class BountyManager {

	private static final int SELECTION_DURATION_TICKS = 20; // 1 second
	private static final int BOUNTY_DURATION_TICKS = 20 * 60 * 5; // 5 minutes
	private static final int STARTUP_DELAY_TICKS = 100; // 5 seconds, lets players finish logging in
	private static final int REINFORCEMENT_INTERVAL_TICKS = 20 * 15; // every 15 seconds
	private static final int REINFORCEMENT_INITIAL_DELAY_TICKS = 20 * 5; // grace period at hunt start

	private static final String GLOW_TEAM_NAME = "mobbounty_glow";
	private static final Identifier MAX_HEALTH_MODIFIER_ID = Identifier.of(MobBounty.MOD_ID, "heart_loss");
	private static final int MAX_HEARTS_LOST = 9; // always leaves at least 1 heart (2 HP)
	private static final String GOLD = "§6";

	private static final List<EntityType<? extends HostileEntity>> EARLY_MOB_POOL =
			List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.HUSK);
	private static final List<EntityType<? extends HostileEntity>> LATE_MOB_POOL =
			List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.HUSK);

	private enum Phase {
		WAITING,
		SELECTION,
		BOUNTY
	}

	private final MinecraftServer server;
	private final BountyData data;
	private final ServerBossBar bossBar =
			new ServerBossBar(Text.literal("Bounty Hunt"), BossBar.Color.RED, BossBar.Style.NOTCHED_10);

	private Phase phase = Phase.WAITING;
	private int timer = STARTUP_DELAY_TICKS;
	private int reinforcementTimer;
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
		if (phase == Phase.BOUNTY) {
			bossBar.addPlayer(player);
		}
	}

	public void onPlayerLeave(ServerPlayerEntity player) {
		bossBar.removePlayer(player);
	}

	public void onPlayerRespawn(ServerPlayerEntity player) {
		addToGlowTeam(player);
		applyHeartLoss(player);
		if (phase == Phase.BOUNTY) {
			bossBar.addPlayer(player);
		}
	}

	public void tick() {
		if (phase == Phase.BOUNTY) {
			ServerPlayerEntity target = getTargetPlayer();
			enforceMobTargeting(target);
			if (target != null) {
				tickReinforcements(target);
			}
			updateBossBar();
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
		sendTitleToAll("§4§l☠ BOUNTY ELIMINATED ☠", "§f" + name + "§7 could not survive the hunt.", 5, 40, 10);
		playSoundToAll(SoundEvents.ENTITY_WITHER_DEATH, 0.7f, 1.4f);

		if (player.getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, player.getX(), player.getY() + 1.0, player.getZ(), 1, 0, 0, 0, 0);
		}

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

	public Text getTargetStatusText() {
		ServerPlayerEntity target = getTargetPlayer();
		if (phase != Phase.BOUNTY || target == null) {
			return Text.literal("[MobBounty] No active bounty target right now.");
		}
		return Text.literal("[MobBounty] Current target: " + target.getGameProfile().getName()
				+ " (" + formatTime(timer / 20) + " remaining)");
	}

	private void startSelectionPhase() {
		phase = Phase.SELECTION;
		timer = SELECTION_DURATION_TICKS;
		bossBar.clearPlayers();

		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		for (ServerPlayerEntity player : players) {
			applySelectionEffects(player);
		}

		sendTitleToAll("§4§l⚠ BOUNTY SELECTION ⚠", "§7Choosing the next target...", 5, 15, 10);
		playSoundToAll(SoundEvents.ENTITY_WITHER_SPAWN, 0.6f, 1.3f);

		targetUuid = pickNewTarget(players);
		ServerPlayerEntity chosen = getTargetPlayer();
		if (chosen != null) {
			MobBounty.LOGGER.info("[MobBounty] (hidden from players) New bounty target: {}", chosen.getGameProfile().getName());
		}
	}

	private void startBountyPhase() {
		phase = Phase.BOUNTY;
		timer = BOUNTY_DURATION_TICKS;
		reinforcementTimer = REINFORCEMENT_INITIAL_DELAY_TICKS;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			bossBar.addPlayer(player);
		}
		updateBossBar();

		ServerPlayerEntity target = getTargetPlayer();
		if (target != null) {
			strikeStartEffect(target);
		}
	}

	private void onTargetSurvived() {
		ServerPlayerEntity target = getTargetPlayer();
		if (target != null) {
			String name = target.getGameProfile().getName();
			broadcast(GOLD + "[BOUNTY] " + GOLD + name + GOLD + " has survived as target!");
			sendTitleToAll("§6§l🏆 TARGET SURVIVED 🏆", "§f" + name + "§7 made it through the hunt!", 5, 40, 10);
			playSoundToAll(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
			celebrationParticles(target);
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

	private void enforceMobTargeting(ServerPlayerEntity target) {
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

	private void tickReinforcements(ServerPlayerEntity target) {
		reinforcementTimer--;
		if (reinforcementTimer > 0) {
			return;
		}
		reinforcementTimer = REINFORCEMENT_INTERVAL_TICKS;

		if (!(target.getWorld() instanceof ServerWorld world)) {
			return;
		}

		double elapsedFraction = 1.0 - ((double) timer / BOUNTY_DURATION_TICKS);
		int waveSize = 1 + (int) Math.floor(Math.min(elapsedFraction, 1.0) * 2.0); // 1..3, grows over the round
		List<EntityType<? extends HostileEntity>> pool = elapsedFraction < 0.5 ? EARLY_MOB_POOL : LATE_MOB_POOL;

		for (int i = 0; i < waveSize; i++) {
			EntityType<? extends HostileEntity> type = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
			spawnMobNear(world, target, type);
		}
	}

	private void spawnMobNear(ServerWorld world, ServerPlayerEntity target, EntityType<? extends HostileEntity> type) {
		int radius = 8 + ThreadLocalRandom.current().nextInt(9); // 8-16 blocks out
		double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
		int x = target.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
		int z = target.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

		HostileEntity mob = type.create(world);
		if (mob == null) {
			return;
		}
		mob.refreshPositionAndAngles(x + 0.5, y, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f, 0f);
		world.spawnEntity(mob);
	}

	private void updateBossBar() {
		float percent = Math.max(0f, Math.min(1f, (float) timer / BOUNTY_DURATION_TICKS));
		bossBar.setPercent(percent);
		bossBar.setName(Text.literal("§c§l⚔ BOUNTY HUNT §r§7- §f" + formatTime(timer / 20) + " remaining"));
	}

	private void strikeStartEffect(ServerPlayerEntity target) {
		if (!(target.getWorld() instanceof ServerWorld world)) {
			return;
		}
		double x = target.getX();
		double y = target.getY();
		double z = target.getZ();

		world.spawnParticles(ParticleTypes.FLASH, x, y + 1.0, z, 1, 0, 0, 0, 0);
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 1.0, z, 40, 0.5, 1.0, 0.5, 0.3);
		world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1.0f, 1.0f);
	}

	private void celebrationParticles(ServerPlayerEntity player) {
		if (!(player.getWorld() instanceof ServerWorld world)) {
			return;
		}
		world.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 1.0, player.getZ(), 80, 0.6, 1.0, 0.6, 0.15);
		world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.5, 0.8, 0.5, 0.2);
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

	private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
			player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
		}
	}

	private void playSoundToAll(SoundEvent sound, float volume, float pitch) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.playSoundToPlayer(sound, SoundCategory.MASTER, volume, pitch);
		}
	}

	private static String formatTime(int totalSeconds) {
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}
}
