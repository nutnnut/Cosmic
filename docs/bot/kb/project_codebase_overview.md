---
name: Codebase Overview
description: Cosmic v83 MapleStory emulator — repo structure, key classes, architecture
type: project
---

## Stack
- Java 21, Maven single-module, Netty 4.2, HikariCP + MySQL, GraalVM JS (NPC scripts), Liquibase

## Key Class Hierarchy
```
Character
  extends AbstractCharacterObject   (stats, hp/mp, map field)
    extends AbstractAnimatedMapObject  (stance, getIdleMovement())
      extends AbstractMapObject        (Point position, objectId)
        implements MapObject
```

## Client–Character relationship
- One `Client` (extends ChannelInboundHandlerAdapter) owns one `Character` via `client.player`
- `Character` back-references `Client` via `character.client`
- `Client.sendPacket()` → `ioChannel.writeAndFlush()` — NPEs if ioChannel is null (mock clients)
- `Client.createMock()` exists but has world=-123, channel=-123 and null ioChannel

## Admin Command System
- Prefix `!` for GM commands, `@` for player commands
- All params are lowercased before being passed to command execute()
- Add command: create class in `client/command/commands/gm[N]/`, register in `CommandsExecutor.registerLv[N]Commands()`
- Rank levels: gm0 (player), gm1–gm6 (ascending GM access)

## Character Creation Flow
- Account: SQL INSERT matching `LoginPasswordHandler` auto-register pattern (name, BCrypt password, DefaultDates.getBirthday(), DefaultDates.getTempban())
- Character: `CharacterFactory.createNewCharacter()` (protected) → use subclass in `client.creator` package
  - `Character.getDefault(client)` → set fields → `character.insertNewChar(recipe)` → `Server.createCharacterEntry()`
  - `MakeCharInfoValidator` validates client-sent equip IDs from WZ — skip for server-side creation
  - `CharacterFactoryRecipe` sets stats/items; top/bottom/shoes/weapon=0 skips equips

## Character Spawn Into Map (normal login flow)
1. `Character.loadCharFromDB(id, client, channelserver)` — false=light load, true=full with buffs/quests; sets `character.loggedIn=true` only when true
2. `character.newClient(client)` — sets `character.loggedIn=true`, loads map ref via `client.getChannelServer().getMapFactory().getMap(mapId)`, snaps to closest portal
3. `channel.addPlayer(chr)` — adds to channel PlayerStorage (REQUIRED before changeMap)
4. `world.addPlayer(chr)` — adds to world PlayerStorage
5. `character.setEnteredChannelWorld()` — marks awayFromWorld=false
6. `map.addPlayer(chr)` — adds to map, broadcasts SPAWN_PLAYER to all other players on map

## Map / Movement
- No server-side pathfinding exists — movement is fully client-driven
- `character.broadcastStance()` — server-initiated MOVE_PLAYER broadcast at current position
- `map.getPointBelow(Point)` — public; snaps X,Y to nearest foothold below (use for spawning)
- `map.calcPointBelow(Point)` — PRIVATE; use getPointBelow instead
- `character.forceChangeMap(MapleMap, Portal)` — map change without requiring client input; sends warp packet (no-op for BotClient)

## Key Methods / Gotchas
- `changeMapInternal` line 1758: checks `client.getChannelServer().getPlayerStorage().getCharacterById(id) != null` — must be in PlayerStorage before changeMap
- `Client.disconnect()` is **final** — cannot be overridden in subclasses
- `Client.updateLoginState()` writes to DB AND calls SessionCoordinator — must be no-op for headless clients
- `MapleMap.addPlayer()` calls `chr.sendPacket()` many times (clocks, pets, etc.) — all safe as no-ops
- `announcePlayerDiseases(client)` at end of addPlayer — adds to server tick list, processed once then cleared; safe with no-op sendPacket
