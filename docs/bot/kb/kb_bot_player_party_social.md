---
name: kb_bot_player_party_social
description: "Player<->bot dynamic party triggers (Flow1 ask-then-yes / Flow2 unprompted / Flow3 player asks \"pt\"), stay-online-while-partied QoL guard, and bot-player familiarity tracking"
metadata: 
  node_type: memory
  type: project
  originSessionId: 4438702b-33f7-42ca-ad4d-33d775b6a7de
---

Four party-social surfaces between bots and real players (all in `server.bots`):

**Flow 2 (bot invites unprompted)** + **Flow 1 (bot asks, invites on "yes")** — `BotSocialManager.offerToPlayer` (proactive tick path). Personality-driven split: roll vs `sociability*chattiness` → high = Flow 1 (say line, set `BotEntry.pendingPartyAskPlayerId`/`pendingPartyAskUntilMs` ~15s, NO invite yet); low = Flow 2 (`sendPartyInvite` now). Extracted `sendPartyInvite(bot,player)` SSOT (party create/reuse + `InviteCoordinator.createInvite` + `partyInvite` packet + `scheduleLonePartyCleanup`) shared by all flows.

**Flow 3 (player asks bot to party)** — player types "pt"/"party"/"invite me"/"lfp"/... `BotSocialManager.maybeHandlePartyChat(speaker,message)` is the non-owner chokepoint, called from `BotManager.handleChat` BEFORE the owner-scoped `bots.get(owner.getId())` return (that's why non-owners reached nothing before). `handlePlayerPartyRequest` picks nearest eligible self-owned autopilot bot (party room <6, level window, off 8s cooldown via `nextPlayerPartyReplyAtMs`), decides via `BotSocialMath.respondToOffer` (ACCEPT→invite / DECLINE→speak / IGNORE→silent). Patterns `PARTY_REQUEST_PATTERN`/`AFFIRMATIVE_PATTERN` exposed as testable `isPartyRequest`/`isAffirmative`.

**Player invites bot via UI** — ALREADY existed (`PartyOperationHandler.java:96-104` → `BotManager.acceptsPartyInvite` → `BotSocialManager.acceptsInvite`: owned=owner only, self-owned=personality+level gap). Left untouched.

**Stay-online QoL** — `BotManager.partyHasRealPlayer(bot)` (online non-bot party member). Guards: `BotScheduler` solo logout (:182), crew logout (:246), `logOutExcess` thinning (:479), `BotBreakManager.maybeStartBreak`. careerEnded NOT guarded (only retires OFFLINE bots). Owner `logout`/`away` chat commands unaffected (explicit).

**Familiarity tracking** — `BotFamiliarityManager` presence-diff sampler (30s TimerManager tick registered in `BotScheduler.start()`, ungated). Diffs live (bot,human) party pairs vs in-memory `sessionStart`: new pair→`party_count++`, ongoing→accrue `total_ms`, vanished→flush+drop. Self-heals on kick/disband/logout (sample sees changed membership; no event hooks). Table `bot_player_familiarity` (Liquibase cs30, `030-bot-familiarity.sql`). Getter `familiarity(botId,playerId)` for FUTURE greet-as-known system (NOT built yet — data only).

Tests: `BotSocialPartyChatTest` (alias matching + key roundtrip). Gated by `SOCIAL_PARTY_ENABLED`/`SOCIAL_INVITE_PLAYERS`. See [[project_bot_independence_infra.md]], [[kb_bot_self_owned_owner_assumptions.md]].
