# Mob Bounty

A Fabric server mod for Minecraft 1.21.1. Every 5 minutes a random online
player is secretly marked as the **bounty target**. During that round every
hostile mob hunts only the target — everyone else is invisible to them, but
non-targets can still fight mobs freely. Die as the target and you lose a
heart's worth of max health **permanently**; survive and the round repeats.

## Building

Requires JDK 21 and internet access to Fabric's Maven repositories
(`maven.fabricmc.net`), since Gradle needs to download Minecraft, Yarn
mappings, Fabric Loader and Fabric API on first run.

```
./gradlew build
```

The output jar is written to `build/libs/`. Drop it into a Fabric server's
`mods/` folder alongside [Fabric API](https://modrinth.com/mod/fabric-api)
for Minecraft 1.21.1.

> **Note:** this project was written in a sandboxed environment without
> access to `maven.fabricmc.net`, so the build could not be compiled here to
> verify it end-to-end. The code follows standard 1.21.1 Yarn mappings and
> Fabric API conventions throughout. If `./gradlew build` reports a missing
> method on your machine, the most likely spots (given API churn around the
> 1.20.5/1.21 attribute rework) are the `EntityAttributeInstance.removeModifier`
> / `addPersistentModifier` calls in `BountyManager.applyHeartLoss` — those
> are one-line fixes if the exact overload name differs slightly.

## How it works

**Selection ceremony (1 second, every 5 minutes or immediately after a
target dies):**
- All online players get Glowing (dyed red via a scoreboard team), Darkness,
  and a heavy Slowness + Resistance combo (the "Turtle Master" effect) so
  nobody can move or take damage for 1 second.
- A random player is secretly chosen as the new target. Whoever died as the
  previous target (if any) is excluded from this draw.
- Effects expire naturally after the 1 second.

**Bounty phase (5 minutes):**
- Every hostile mob (anything implementing `Monster`) is forced onto the
  target as its attack target and is prevented from targeting anyone else.
- Damage from hostile mobs to non-target players is cancelled outright, so
  mobs "can't hit" bystanders even if they clip one while pathing.
- Non-target players take no special treatment when attacking mobs — normal
  combat, normal loot.
- This is done with a per-tick target enforcement loop + damage-cancel event
  rather than mixins into mob AI internals, so it's resilient to API
  changes: mobs may briefly glance at a non-target while pathing, but they
  are forced off that target and dealt no damage the same tick, so in
  practice non-targets are never chased or hurt.
- If the target survives the full 5 minutes: `§6[BOUNTY] §6<name>§6 has
  survived as target!` and a new ceremony starts immediately.
- If the target dies: `§6[BOUNTY] §6<name>§6 has been eliminated!` +
  `§6[BOUNTY] Selecting next bounty...`, the target permanently loses 1
  heart (2 max HP, floor of 1 heart remaining), becomes immune from the next
  draw, and a new target is picked immediately (no waiting for the timer).

Heart loss and the "immune next round" flag are stored in
`<world>/mobbounty_data.json` so they survive server restarts.

On server start: `[MobBounty/INFO] Mod loaded - Bounty cycle starting` is
logged and broadcast to chat.

Works with any number of players — with only one player online, the
immunity rule is bypassed so the cycle keeps running instead of stalling.

## About "Essentials" compatibility

Essentials is a Bukkit/Spigot/Paper **plugin**; it cannot run on a Fabric
server at all, since Fabric and Bukkit/Paper are separate, incompatible
server implementations (different plugin/mod loaders, different APIs).
There's no such thing as running Essentials alongside a Fabric mod on the
same server. If your setup actually runs Paper, you'd need this reimplemented
as a Bukkit/Paper plugin instead of a Fabric mod — happy to do that as a
separate project if that's what you need.

What this mod *does* do to stay a good citizen regardless of what else is
installed: it doesn't override any vanilla commands, doesn't touch
permissions, doesn't replace the scoreboard objectives/teams other than one
dedicated `mobbounty_glow` team it manages itself, and only ever adds a
capped, clearly-namespaced attribute modifier (`mobbounty:heart_loss`) to
a player's max health rather than mutating other attributes.
