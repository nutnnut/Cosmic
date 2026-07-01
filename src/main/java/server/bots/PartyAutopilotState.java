/*
    This file is part of the Cosmic (AI-companion-bot fork).

    Single source of truth for a bot party's shared autopilot plan. One instance per game
    party (keyed by party id in BotAutopilotManager); the party leader is the only writer, every
    member reads. Before this object the plan was copied onto every member's BotEntry and the code
    fought to keep N copies in sync — followers drifted onto stale picks and the crew split up.
    Now there is one copy: members refresh their per-tick destination cache from here, so drift is
    impossible by construction.

    Stage 1 scope: owns only the shared destination + per-member objective text. The decision clock,
    cohesion hold state, and follow wiring still live on BotEntry; later stages fold them in (see
    docs/bot/party-autopilot-redesign.md). Keep this class to fields that are actually read today —
    do not add speculative state ahead of the stage that needs it.
*/
package server.bots;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PartyAutopilotState {
    /** The group's shared grind-destination map id, or -1 before the first plan. SSOT. */
    volatile int mapId = -1;
    /** Human-readable destination name for chat/botdebug, mirrored to members. */
    volatile String destinationName = "";
    /** Char id of the member that owns the decision (the cohesion/decide leader). */
    volatile int leaderCharId = -1;
    /** Per-member objective summary ("farm X for me" / "back the party up"), keyed by bot char id. */
    final Map<Integer, String> objectiveByCharId = new ConcurrentHashMap<>();
}
