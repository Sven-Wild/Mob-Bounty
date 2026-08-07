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
> access to `maven.fabricmc.net`, so most of it could not be compiled here to
> verify it end-to-end (two real builds on a normal machine already caught
> and fixed two bad API references — see git log). The code follows standard
> 1.21.1 Yarn mappings and Fabric API conventions throughout. If
> `./gradlew build` reports another missing method/field, paste the error —
> these are one-line mapping fixes, not design problems. The highest-risk
> spots in the latest batch of changes are `ItemStack.setCustomName` and the
> `new StatusEffectInstance(StatusEffectInstance)` copy constructor in
> `TrollItems.java` — both are long-standing convenience methods, but the
> 1.20.5+ item-component rework touched adjacent APIs.

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

## Content/streaming features

Added on top of the base mechanic specifically to make rounds watchable:

- **Ceremony title card**: a bold red "⚠ BOUNTY SELECTION ⚠" title + an
  ominous Wither-spawn-style sound plays for everyone during the 1-second
  freeze.
- **Hunt-start strike effect**: the moment the 5-minute hunt begins, a
  lightning-style particle burst + thunder sound fires at the target's feet
  — visible to anyone nearby, without announcing who it is in chat. If you
  happen to be standing next to someone when the sky flashes... suspicious.
- **Boss bar countdown**: a red "⚔ BOUNTY HUNT — 04:32 remaining" bar is
  shown to everyone for the whole 5-minute round, ticking down live.
- **Reinforcement waves**: instead of relying on ambient mob spawns, a wave
  of 1–3 hostile mobs is summoned near the target every 15 seconds, using
  tougher mobs (adding Skeletons and Creepers to the pool) in the back half
  of the round — so the hunt has guaranteed, escalating action on camera.
- **Elimination/survival spectacle**: matching title cards, sounds
  (Wither-death boom for eliminations, level-up chime for survivals) and
  particle bursts (a big explosion cloud / a firework + totem shower) fire
  at the moment of death or survival, on top of the required chat messages.
- **`/bounty target` command** (requires OP / permission level 2): privately
  tells whoever runs it who the current target is and how much time is
  left, without telling anyone else. Meant for the streamer/host to
  narrate with dramatic irony, or for editors to check later. The target is
  also logged (server console/log only, never chat) every time a new one is
  picked, so you have a searchable record for editing without spoiling
  anything for players.

## Admin/testing commands

All of these require OP (permission level 2):

| Command | What it does |
|---|---|
| `/bounty target` | Privately shows you the current target + time left. |
| `/bounty settarget <player>` | Forces that player to become the target immediately, skipping the RNG. |
| `/bounty skip` | Instantly ends the current phase (ceremony or hunt) and moves to the next — no waiting 5 real minutes while testing. |
| `/bounty setduration <seconds>` | Changes the hunt length live; shortens an already-running hunt if the new value is smaller. |
| `/bounty giveheart <player>` | Restores one permanently-lost heart. |
| `/bounty setheartslost <player> <amount>` | Sets a player's permanent hearts-lost count directly. |
| `/bounty wave <player> [count]` | Manually drops a wave of hostile mobs (default 3, max 10) near that player right now. |
| `/bounty pause` / `/bounty resume` | Freezes/unfreezes the whole cycle — timers, mob targeting, boss bar, everything. Handy before explaining the rules to friends. |
| `/bounty trollkit` | Gives the executing player the troll item kit (see below). |

## Troll items (`/bounty trollkit`)

Plain vanilla items with a custom name — no resource pack or texture needed,
their behavior is triggered by matching that name on right-click:

- **Curse Stick** (Stick) — right-click a player to instantly make them the
  target, mid-round, no waiting for the next ceremony.
- **Swap Stick** (Blaze Rod) — right-click a player to instantly
  teleport-swap positions with them (same dimension only).
- **Snitch Scroll** (Paper, one-time use) — broadcasts the current secret
  target's name to all chat. Blows the round wide open for a dramatic
  finish; consumed on use.
- **Compass of Judgment** (Compass) — right-click (or just "use") to
  privately see the current target's distance and direction from you.
  Doesn't announce anything to anyone else.
- **Random Curse Wand** (End Rod) — right-click a player for a random goofy
  effect: Levitation, Nausea, Jump Boost X, Slowness VI, Blindness, or
  Weakness. Pure chaos, nothing lethal.

Hand these out to whoever's hosting/testing — they're not tied to being the
bounty target or anything, just OP-gated via the command that grants them.

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
