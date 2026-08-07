package com.mobbounty;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small flat-file store for the state that must survive server restarts:
 * how many hearts each player has permanently lost, and who is currently
 * immune from being picked as the next target.
 */
public final class BountyData {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "mobbounty_data.json";

	private final Map<String, Integer> heartsLost = new HashMap<>();
	private String immunePlayer;

	private BountyData() {
	}

	public static BountyData load(MinecraftServer server) {
		Path path = getPath(server);
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				Stored stored = GSON.fromJson(reader, Stored.class);
				BountyData data = new BountyData();
				if (stored != null) {
					if (stored.heartsLost != null) {
						data.heartsLost.putAll(stored.heartsLost);
					}
					data.immunePlayer = stored.immunePlayer;
				}
				return data;
			} catch (IOException e) {
				MobBounty.LOGGER.warn("[MobBounty] Failed to load bounty data, starting fresh.", e);
			}
		}
		return new BountyData();
	}

	public void save(MinecraftServer server) {
		Path path = getPath(server);
		Stored stored = new Stored();
		stored.heartsLost = new HashMap<>(heartsLost);
		stored.immunePlayer = immunePlayer;
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(stored, writer);
			}
		} catch (IOException e) {
			MobBounty.LOGGER.warn("[MobBounty] Failed to save bounty data.", e);
		}
	}

	public int getHeartsLost(UUID uuid) {
		return heartsLost.getOrDefault(uuid.toString(), 0);
	}

	public void incrementHeartsLost(UUID uuid) {
		heartsLost.merge(uuid.toString(), 1, Integer::sum);
	}

	public void decrementHeartsLost(UUID uuid) {
		int current = getHeartsLost(uuid);
		if (current > 0) {
			heartsLost.put(uuid.toString(), current - 1);
		}
	}

	public void setHeartsLost(UUID uuid, int hearts) {
		if (hearts <= 0) {
			heartsLost.remove(uuid.toString());
		} else {
			heartsLost.put(uuid.toString(), hearts);
		}
	}

	public UUID getImmunePlayer() {
		return immunePlayer == null ? null : UUID.fromString(immunePlayer);
	}

	public void setImmunePlayer(UUID uuid) {
		this.immunePlayer = uuid == null ? null : uuid.toString();
	}

	private static Path getPath(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
	}

	private static final class Stored {
		Map<String, Integer> heartsLost;
		String immunePlayer;
	}
}
