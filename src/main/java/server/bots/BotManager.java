package server.bots;

import client.BotClient;
import config.YamlConfig;
import client.Character;
import client.Client;
import client.Disease;
import client.QuestStatus;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import client.keybind.KeyBinding;
import constants.game.CharacterStance;
import constants.inventory.ItemConstants;
import constants.string.CharsetConstants;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import server.TimerManager;
import server.bots.pq.BotPqHooks;
import server.life.Monster;
import server.life.MobSkill;
import server.maps.Foothold;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.quest.Quest;
import tools.PacketCreator;
import tools.Pair;

import java.awt.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotManager {
    private static final Logger log = LoggerFactory.getLogger(BotManager.class);
    private static final BotManager instance = new BotManager();

    /**
     * All tunable constants in one place. Fields are non-final so the class can
     * be hotswapped in debug mode without the JVM inlining the values.
     */
    public static class Config {
        public int   AI_TICK_MS       = 100;   // ms between heavier bot decision passes

        // Passive loot
        public int   LOOT_RADIUS         = 100;   // px; pickup items within this box radius
        public int   INV_FULL_WARN_CD_MS = 10_000;
        // Legacy convenience from the supervised-bots era: NX cards a bot loots get picked up
        // by the owner instead. Off by default - independent bots keep their own loot.
        public boolean REDIRECT_NX_CARDS_TO_OWNER = false;

        // Potion management
        public int   POT_LOW_WARN          = 100;   // warn on grind start below this count
        public int   POT_STOP              = 10;    // stop grinding below this HP pot count
        public int   POT_CHECK_INTERVAL_MS = 45_000;
        public int   POT_CHECK_RETRY_SOON_MS = 250;
        public int   MP_RECOVERY_INTERVAL_MS = 10_000;
        public int   BASE_HP_RECOVERY = 10;
        public int   BASE_MP_RECOVERY = 3;
        public float AUTOPOT_HP_THRESH = 0.7f; // use HP pot when HP falls below this ratio
        public float AUTOPOT_MP_THRESH = 0.5f; // use MP pot when MP falls below this ratio

        // Follow stagger: each bot is offset this many px from the owner (index-based, alternating left/right)
        public int FOLLOW_STAGGER = 60;

        // Quest piggyback (BotQuestManager). AUTO_QUESTS: run autoStart+autoComplete quests on the
        // tick (no travel). QUEST_PIGGYBACK: while autopiloting, detour to start/turn-in mob quests
        // whose kills overlap the current grind. Kill switches - both default on.
        public boolean AUTO_QUESTS = true;
        public boolean QUEST_PIGGYBACK = true;

        // Owner inactivity (offline or dead) before bot scrolls/warps to nearest town and idles.
        public long OWNER_INACTIVE_TOWN_RETURN_MS = 5L * 60_000L;
        // Hard stop for explicitly-ordered autopilot bots playing on while the owner is
        // offline/dead: after this they take the normal town safe mode. Test value: 1 hour.
        public long AUTOPILOT_OWNER_OFFLINE_LIMIT_MS = 60L * 60_000L;

        // Grind recovery is looser than follow recovery so bots can work nearby platforms,
        // but still get pulled back to a same-map party anchor if they fall far out of bounds.
        public int GRIND_PARTY_TELEPORT_DIST_MULTIPLIER = 2;

        // Party-autopilot cohesion (BotAutopilotManager): the leader holds and waits for
        // stragglers instead of racing ahead. Hops>this many portals behind triggers a wait
        // (1 = wait once someone is 2+ maps back; tolerates one map of in-transit spread).
        // On the SAME map a member farther than SAME_MAP_STRAGGLER_PX also counts; the wait
        // releases only once they close to within SAME_MAP_STRAGGLER_RESUME_PX (hysteresis,
        // so the leader doesn't stop-start flap at the boundary).
        public int STRAGGLER_WAIT_HOPS = 1;
        public int SAME_MAP_STRAGGLER_PX = 700;
        public int SAME_MAP_STRAGGLER_RESUME_PX = 350;

        // Grind loot convenience: loot competes with mob navigation only when
        // lootDistSq < mobDistSq * ratio. 0.09 ≈ loot within 30% of mob distance.
        public float GRIND_LOOT_CONVENIENCE_RATIO = 0.09f;
        public int   GRIND_LOOT_RETRY_SUPPRESS_MS = 5_000;

        // Debug aid: keep stuck detection/logging active, but disable automatic recovery jumps
        // so pathing failures remain visible in logs and at runtime.
        public boolean ENABLE_UNSTUCK = false;

        // Gachapon (BotGachaponManager): autopilot bots spend NX earned from looted NX cards on
        // gachapon, chasing uniques by expected value. Kill switch + the spend knobs, all visible.
        public boolean GACHAPON_ENABLED = true;
        // Keep at least this much account NX in reserve - bots gamble only the surplus above it.
        public int GACHA_NX_RESERVE = 1_000;
        // Tickets bought+rolled per trip cap (humanlike: a session at the machine, not infinite).
        public int GACHA_TICKETS_PER_TRIP = 5;
        // A town's net score (expected item value per roll minus ticket price and travel penalty,
        // in NX-equivalent units) must clear this for the bot to make the trip - otherwise it hoards
        // the NX for a better/closer pool. Positive so the pull must beat its own ticket cost.
        public double GACHA_MIN_NET_EV = 50.0;

    }

    /** Singleton config — replace with `cfg = new Config()` after hotswapping to reset. */
    public static Config cfg = new Config();

    public static BotManager getInstance() { return instance; }

    // Public facade for the !botcfg GM command (BotCombatManager is package-private).
    public static List<String> botCombatConfigLines() { return BotCombatManager.configFieldLines(); }
    public static String botCombatConfigLine(String name) { return BotCombatManager.configFieldLine(name); }
    public static String setBotCombatConfig(String name, String value) { return BotCombatManager.setConfigField(name, value); }

    // ownerCharId → list of owned bot entries (1:N)
    private final Map<Integer, List<BotEntry>> bots = new ConcurrentHashMap<>();
    // ownerCharId → current formation (in-memory only, defaults to stagger)
    private final Map<Integer, FormationState> ownerFormations = new ConcurrentHashMap<>();
    // ownerCharId → cluster-anchor town position. First bot to warp picks a random
    // portal in the return map; later bots warp to a randomized nearby offset.
    // Cleared when the owner becomes active again.
    private final Map<Integer, Point> townClusterAnchors = new ConcurrentHashMap<>();
    enum FormationType { STAGGER, RANDOM, STACK, SPREAD, LEFT, RIGHT }

    record FormationState(FormationType type, int px, int snapRange) {
        static FormationState defaultStagger() { return new FormationState(FormationType.STAGGER, cfg.FOLLOW_STAGGER, BotMovementManager.cfg.FOLLOW_Y_CAP); }
        int offsetFor(int idx, int total) {
            return switch (type) {
                case STAGGER -> (idx % 2 == 0 ? 1 : -1) * (idx / 2 + 1) * px;
                // range scales with total so avg spread matches stagger: ±(px/2 * total)
                case RANDOM  -> { int range = px * total / 2; yield range > 0 ? ThreadLocalRandom.current().nextInt(-range, range + 1) : 0; }
                case STACK   -> 0;
                // idx 0 = owner, then alternating ±: 0, +px, -px, +2px, -2px …
                case SPREAD  -> idx == 0 ? 0 : (idx % 2 == 1 ? 1 : -1) * ((idx + 1) / 2) * px;
                case LEFT    -> -(idx + 1) * px;
                case RIGHT   ->  (idx + 1) * px;
            };
        }
    }

    record TargetSnapshot(FormationState formation,
                          Point rawOwnerPos,
                          Point followAnchorPos,
                          String followAnchorName,
                          Point followBasePos,
                          Point followTargetPos,
                          Point moveTargetPos,
                          Point farmAnchorPos,
                          Point grindTargetPos,
                          Point primaryTargetPos,
                          String primaryTargetSource) {
        Point steeringTargetPos(BotEntry entry) {
            return entry.navTargetPos != null ? new Point(entry.navTargetPos) : new Point(primaryTargetPos);
        }

        String steeringTargetSource(BotEntry entry) {
            return entry.navTargetPos != null ? "nav-waypoint" : primaryTargetSource;
        }
    }

    private record LocalOpportunityAttackResult(boolean consumedTick, Point targetPos) {}

    private static final Pattern DISMISS_PATTERN = Pattern.compile(
            "\\b(dismiss|disown|release)\\s+(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECRUIT_PATTERN = Pattern.compile(
            "\\b(recruit|adopt|hire|claim)\\s+(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMATION_PATTERN = Pattern.compile(
            "\\b(?:formation|form)\\b(?:\\s+(stagger|split|random|stack|spread|tight|loose|left|right|snap)(?:\\s+(\\d+|tight|loose|on|off))?)?",
            Pattern.CASE_INSENSITIVE);
    private static final int MIN_PREFIX_TARGET_LENGTH = 2;
    private static final int PLATFORM_EDGE_INSET_PX = 12;
    private static final int BOT_TICK_FAILURE_LIMIT = 3;
    private static final long BOT_TICK_FAILURE_WINDOW_MS = 10_000L;

    private Character resolveFollowTarget(Character owner, String targetToken) {
        if (owner == null || targetToken == null || targetToken.isBlank()) {
            if (owner != null) {
                owner.yellowMessage("Can't follow that target.");
            }
            return null;
        }

        List<Character> candidates = new ArrayList<>();
        if (owner.isLoggedinWorld()) {
            candidates.add(owner);
        }
        if (owner.getParty() != null) {
            for (Character member : owner.getPartyMembersOnline()) {
                if (member == null || !member.isLoggedinWorld() || member.getId() == owner.getId()) {
                    continue;
                }
                candidates.add(member);
            }
        }
        for (BotEntry sibling : getBotEntries(owner.getId())) {
            Character siblingBot = sibling.bot;
            if (siblingBot == null || !siblingBot.isLoggedinWorld()) {
                continue;
            }
            boolean duplicate = false;
            for (Character candidate : candidates) {
                if (candidate.getId() == siblingBot.getId()) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                candidates.add(siblingBot);
            }
        }

        for (Character candidate : candidates) {
            if (candidate.getName().equalsIgnoreCase(targetToken)) {
                return candidate;
            }
        }

        if (targetToken.length() < MIN_PREFIX_TARGET_LENGTH) {
            owner.yellowMessage("Follow target must use at least " + MIN_PREFIX_TARGET_LENGTH + " letters.");
            return null;
        }

        List<Character> prefixMatches = new ArrayList<>();
        for (Character candidate : candidates) {
            if (candidate.getName().regionMatches(true, 0, targetToken, 0, targetToken.length())) {
                prefixMatches.add(candidate);
            }
        }
        if (prefixMatches.size() == 1) {
            return prefixMatches.get(0);
        }
        if (prefixMatches.size() > 1) {
            StringBuilder message = new StringBuilder("Ambiguous follow target '")
                    .append(targetToken)
                    .append("': ");
            for (int i = 0; i < prefixMatches.size(); i++) {
                if (i > 0) {
                    message.append(", ");
                }
                message.append(prefixMatches.get(i).getName());
            }
            owner.yellowMessage(message.toString());
            return null;
        }

        owner.yellowMessage("Can't follow '" + targetToken + "'. Target must be a same-party character or one of your active bots.");
        return null;
    }

    private boolean applyFollowTargetCommand(Character owner, List<BotEntry> entries, String targetToken) {
        Character target = resolveFollowTarget(owner, targetToken);
        if (target == null) {
            return true;
        }
        for (BotEntry entry : entries) {
            if (entry == null || entry.bot == null || entry.bot.getId() == target.getId()) {
                continue;
            }
            clearDebugCommander(entry); // real owner commands -> owner wins
            BotChatManager.queueBotReply(entry, randomReply(List.of(
                    "ok",
                    "k",
                    "sure",
                    "omw",
                    "got it",
                    "following " + target.getName(),
                    "ok, following " + target.getName()
            )));
            after(randMs(250, 750), () -> {
                BotEquipManager.autoEquip(entry.bot, entry.owner, entry.pendingLootOfferItem);
                BotPotionManager.checkPotShareOnModeStart(entry, entry.bot);
                issueFollow(entry, target);
            });
        }
        return true;
    }

    static String randomReply(List<String> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** Schedule {@code r} to run after {@code ms} milliseconds. */
    static ScheduledFuture<?> after(long ms, Runnable r) {
        return TimerManager.getInstance().schedule(r, ms);
    }

    /** Uniform random delay in [lo, hi) ms — use wherever a fixed delay would feel robotic. */
    static long randMs(int lo, int hi) {
        return lo + ThreadLocalRandom.current().nextInt(hi - lo);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void registerBot(int ownerCharId, Character owner, Character bot) {
        registerBotInternal(ownerCharId, owner, bot, false);
    }

    public BotEntry registerSpawnedBot(int ownerCharId, Character owner, Character bot) {
        // Pre-build the spawn index + world graph off-thread: the first grind/autopilot
        // decision otherwise pays ~30s of WZ scanning right when the owner asks for it.
        BotGrindAdvisor.warmCachesAsync();
        return registerBotInternal(ownerCharId, owner, bot, true);
    }

    public record SpawnResult(boolean success, Character bot, boolean autoRegistered, String errorMessage) {
        static SpawnResult ok(Character bot, boolean autoRegistered) {
            return new SpawnResult(true, bot, autoRegistered, null);
        }
        static SpawnResult fail(String msg) {
            return new SpawnResult(false, null, false, msg);
        }
    }

    /** Spawn a registered bot for the given owner, leaving it at its legal login/current map and entering follow mode. */
    public SpawnResult spawnBotForOwner(Character owner, String botName) {
        BotOwnershipService ownershipService = BotOwnershipService.getInstance();
        BotOwnershipService.ResolvedCharacter resolved = ownershipService.resolveCharacterByName(botName);
        if (resolved == null) {
            return SpawnResult.fail("No character named '" + botName + "' exists.");
        }
        if (resolved.isOnline() && !resolved.isOnlineAsBot()) {
            return SpawnResult.fail("'" + botName + "' is currently being played by a real player.");
        }
        BotOwnershipService.AuthorizationResult auth = ownershipService.ensureCanControl(owner, resolved);
        if (!auth.allowed()) {
            return SpawnResult.fail(auth.failureMessage());
        }
        if (resolved.isOnline()) {
            Character botChar = resolved.onlineCharacter();
            Character activeOwner = getActiveOwnerByBotCharId(botChar.getId());
            if (activeOwner != null && activeOwner.getId() != owner.getId()) {
                return SpawnResult.fail("Bot '" + botName + "' is controlled by " + activeOwner.getName() + ".");
            }
            BotEntry entry = activeOwner == null
                    ? registerSpawnedBot(owner.getId(), owner, botChar)
                    : getBotEntry(owner.getId(), botChar.getId());
            placeSpawnedOnlineBot(entry, botChar);
            if (entry != null) {
                issueFollowOwner(entry);
            }
            return SpawnResult.ok(botChar, auth.autoRegistered());
        } else {
            try {
                Character botChar = loadOfflineBot(resolved.id(), owner.getClient().getWorld(), owner.getClient().getChannel());
                BotEntry entry = registerSpawnedBot(owner.getId(), owner, botChar);
                issueFollowOwner(entry);
                return SpawnResult.ok(botChar, auth.autoRegistered());
            } catch (SQLException e) {
                log.warn("Failed to load bot character '{}' for owner '{}'", botName, owner.getName(), e);
                return SpawnResult.fail("Failed to load bot character '" + botName + "'.");
            }
        }
    }

    public void joinBotToOwnerParty(Character owner, Character bot) {
        net.server.world.Party botParty = bot.getParty();
        if (botParty != null) {
            net.server.world.Party ownerParty = owner.getParty();
            if (ownerParty != null && botParty.getId() == ownerParty.getId()) {
                // Ensure the party member entry is marked online with a live character reference
                PartyCharacter pchar = new PartyCharacter(bot);
                pchar.setChannel(bot.getClient().getChannel());
                pchar.setMapId(bot.getMapId());
                bot.getWorldServer().updateParty(ownerParty.getId(), PartyOperation.LOG_ONOFF, pchar);
                bot.updatePartyMemberHP();
                return;
            }
            // Bot is in a different party — leave it first
            Party.leaveParty(botParty, bot.getClient());
        }
        net.server.world.Party ownerParty = owner.getParty();
        if (ownerParty == null) {
            if (!Party.createParty(owner, true)) return;
            ownerParty = owner.getParty();
        }
        if (ownerParty == null) return;
        if (Party.joinParty(bot, ownerParty.getId(), true)) {
            bot.updatePartyMemberHP();
        }
    }

    private BotEntry getBotEntry(int ownerCharId, int botCharId) {
        List<BotEntry> entries = bots.get(ownerCharId);
        if (entries == null) return null;
        for (BotEntry e : entries) {
            if (e.bot.getId() == botCharId) return e;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // @botme / @botparty: the player logs out and their character keeps playing
    // as a self-owned bot (owner == the bot itself). Logging back in on the
    // character takes it over again (Character.newClient -> cleanupBotRuntimeState).
    // -------------------------------------------------------------------------


    /** @autosell (admin debug): what the bot sell-trash pipeline would unload from this
     *  character, grouped by inventory type. The player stays connected. */
    public List<String> autoSellPreview(Character chr) {
        return BotInventoryManager.autoSellPreviewLines(chr);
    }

    /** @autosell confirm: instantly sells everything the preview lists at NPC prices. */
    public List<String> autoSellConfirm(Client c) {
        return BotInventoryManager.autoSellExecute(c);
    }

    /**
     * Disconnect the client and respawn its character as a self-owned autopilot bot.
     * Returns an error message to show the player, or null when the takeover is underway.
     * {@code requireBotParty} (the @botparty form) refuses unless every other online
     * party member is already a bot.
     */
    public String takeOverAsBot(Client c, boolean requireBotParty) {
        Character player = c.getPlayer();
        if (player == null || c instanceof BotClient || player.getMap() == null) {
            return "can't do that right now.";
        }
        if (requireBotParty && !inPartyOfBots(player)) {
            return "@botparty needs a party where everyone else is a bot. Use @botme to go solo.";
        }
        if (player.getTrade() != null) {
            return "finish or cancel your trade first.";
        }
        player.yellowMessage("Switching out - this character keeps playing as a bot. Log back in anytime to take over.");
        after(randMs(600, 900), () -> swapToBotInPlace(c, player));
        return null;
    }

    /**
     * Seamless takeover: attach a BotClient to the LIVE character and sever it from the real
     * socket BEFORE disconnecting, so the char never leaves the map — observers see no
     * despawn/respawn, position and buffs stay intact, and the bot announces its plan right
     * away. {@link Client}'s disconnect skips all character-side cleanup when the client has
     * no player, but still logs the ACCOUNT out and closes the session — so the player can
     * log back in anytime (the normal reclaim path swaps the client back via
     * Character.newClient).
     */
    private void swapToBotInPlace(Client c, Character player) {
        if (c.getPlayer() != player || player.getMap() == null) {
            return; // player logged out / moved on during the grace delay
        }
        BotClient botClient = new BotClient(c.getWorld(), c.getChannel());
        botClient.setPlayer(player);
        botClient.setAccID(player.getAccountID());
        botClient.setAccountName(c.getAccountName());
        player.setClient(botClient);
        c.setPlayer(null);
        c.disconnect(false, false);

        BotEntry entry = registerSpawnedBot(player.getId(), player, player); // self-owned
        startTakeoverAutopilot(entry, player);
    }

    /** True when the player is partied with at least one bot and no other online humans. */
    private static boolean inPartyOfBots(Character player) {
        if (player.getParty() == null) {
            return false;
        }
        boolean sawBot = false;
        for (Character member : player.getPartyMembersOnline()) {
            if (member == null || member.getId() == player.getId()) {
                continue;
            }
            if (member.getClient() instanceof BotClient) {
                sawBot = true;
            } else {
                return false; // another human in the party
            }
        }
        return sawBot;
    }

    private void startTakeoverAutopilot(BotEntry entry, Character botChar) {
        List<BotEntry> partyBots = partyBotEntries(botChar);
        boolean partyOfBots = partyBots.size() >= 2 && onlinePartyMembersAllBots(botChar);
        botSay(botChar, randomReply(List.of(
                "taking it from here", "autopilot time", "ok, playing on my own now")));
        if (partyOfBots) {
            BotAutopilotManager.startParty(botChar, partyBots);
        } else {
            BotAutopilotManager.start(entry, botChar);
        }
    }

    private static boolean onlinePartyMembersAllBots(Character botChar) {
        if (botChar.getParty() == null) {
            return false;
        }
        for (Character member : botChar.getPartyMembersOnline()) {
            if (member != null && !(member.getClient() instanceof BotClient)) {
                return false;
            }
        }
        return true;
    }

    public Character loadOfflineBot(int charId, int world, int channel) throws SQLException {
        BotClient botClient = new BotClient(world, channel);
        Character botChar = Character.loadCharFromDB(charId, botClient, true);
        botClient.setPlayer(botChar);
        botClient.setAccID(botChar.getAccountID());
        Map<Disease, Pair<Long, MobSkill>> diseases =
                Server.getInstance().getPlayerBuffStorage().getDiseasesFromStorage(charId);
        if (diseases != null) {
            botChar.silentApplyDiseases(diseases);
        }

        MapleMap spawnMap = Server.getInstance().getChannel(world, channel).getMapFactory().getMap(botChar.getMapId());
        Point spawnPos = resolveSpawnPosition(spawnMap, botChar.getPosition());

        botChar.setMapId(spawnMap.getId());
        botChar.newClient(botClient);
        botChar.recalcLocalStats();

        botChar.resetPlayerRates();
        if (YamlConfig.config.server.USE_ADD_RATES_BY_LEVEL) {
            botChar.setPlayerRates();
        }
        botChar.setWorldRates();
        botChar.updateCouponRates();

        botChar.setPosition(spawnPos);

        var channelServer = Server.getInstance().getChannel(world, channel);
        channelServer.addPlayer(botChar);
        channelServer.getWorldServer().addPlayer(botChar);
        botChar.setEnteredChannelWorld();
        spawnMap.addPlayer(botChar);
        botChar.visitMap(spawnMap);
        botChar.diseaseExpireTask();
        return botChar;
    }

    static void placeSpawnedOnlineBot(BotEntry entry, Character botChar) {
        if (entry == null) {
            botChar.updatePartyMemberHP();
            return;
        }

        MapleMap spawnMap = botChar.getMap();
        Point spawnPos = getInstance().resolveSpawnPosition(spawnMap, botChar.getPosition());
        BotPhysicsEngine.teleportTo(entry, botChar, spawnPos);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        entry.deadUntil = 0;
        entry.lastMapId = spawnMap != null ? spawnMap.getId() : botChar.getMapId();
        if (spawnMap != null && spawnMap.getFootholds() != null) {
            entry.fhIndex = BotMovementManager.buildFhIndex(spawnMap);
            BotNavigationGraphProvider.warmGraphAsync(spawnMap, entry.movementProfile);
        }
        entry.skipDelayMs = 0;
        entry.aiTickAccumulatorMs = 0;
        entry.moveDir = 0;
        entry.movementBroadcastValid = false;
        botChar.updatePartyMemberHP();
    }

    public Point resolveSpawnPosition(MapleMap map, Point desiredPosition) {
        if (map == null || desiredPosition == null) {
            return desiredPosition;
        }

        Point groundPoint = BotPhysicsEngine.findGroundPoint(map, new Point(desiredPosition.x, desiredPosition.y - 1));
        return groundPoint != null ? groundPoint : desiredPosition;
    }

    private BotEntry registerBotInternal(int ownerCharId, Character owner, Character bot, boolean normalizeSpawnState) {
        List<BotEntry> entries = bots.computeIfAbsent(ownerCharId, k -> new CopyOnWriteArrayList<>());
        // Replace if same bot character is already registered (e.g. relog)
        entries.removeIf(e -> {
            if (e.bot.getId() == bot.getId()) { e.task.cancel(false); return true; }
            return false;
        });
        int botCharId = bot.getId();
        // Capture the BotEntry directly in the tick lambda instead of re-resolving it from the
        // registry every tick (ConcurrentHashMap.get + linear CopyOnWriteArrayList scan). The
        // task is bound to exactly one entry and is cancelled on every removal/replace path, so
        // the captured reference is always the live entry. The holder breaks the task<->entry
        // construction cycle (BotEntry.task is final); the only window where ref[0] is null is
        // before the assignment two lines below, which tickCore already tolerates.
        BotEntry[] ref = new BotEntry[1];
        ScheduledFuture<?> task = TimerManager.getInstance().register(
                () -> tick(ref[0], ownerCharId, botCharId), BotMovementManager.cfg.TICK_MS);
        BotEntry entry = new BotEntry(bot, owner, task);
        ref[0] = entry;
        entry.movementProfile = BotMovementProfile.fromCharacter(bot);
        entry.selfScrollEnabled = BotPrefsStore.loadSelfScroll(bot.getId());
        BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
        entries.add(entry);
        FormationState fs = ownerFormations.getOrDefault(ownerCharId, FormationState.defaultStagger());
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).followOffsetX = fs.offsetFor(i, entries.size());
        }
        if (normalizeSpawnState) {
            normalizeSpawnedBot(entry);
        }
        after(randMs(30_000, 31_000), () -> BotChatManager.checkBotStatus(entry, bot));
        return entry;
    }

    private void normalizeSpawnedBot(BotEntry entry) {
        Character bot = entry.bot;
        Point spawnPos = resolveSpawnPosition(bot.getMap(), bot.getPosition());
        if (bot.getHp() <= 0) {
            bot.updateHp(Math.max(1, bot.getCurrentMaxHp()));
        }

        BotPhysicsEngine.teleportTo(entry, bot, spawnPos != null ? spawnPos : bot.getPosition());
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        entry.deadUntil = 0;
        entry.lastMapId = bot.getMapId();
        entry.fhIndex = BotMovementManager.buildFhIndex(bot.getMap());
        entry.skipDelayMs = 0;
        entry.aiTickAccumulatorMs = 0;
        entry.moveDir = 0;
        entry.movementBroadcastValid = false;
        if (entry.owner != null) {
            joinBotToOwnerParty(entry.owner, bot);
        }
    }

    public void removeBot(int ownerCharId) {
        List<BotEntry> entries = bots.remove(ownerCharId);
        if (entries != null) {
            entries.forEach(this::cancelBotTask);
        }
        ownerFormations.remove(ownerCharId);
        townClusterAnchors.remove(ownerCharId);
    }

    /** Cancel and remove a bot by the bot character's own ID (used during shutdown/disconnect). */
    public boolean removeBotByCharId(int botCharId) {
        boolean removed = false;
        for (Map.Entry<Integer, List<BotEntry>> ownerEntry : bots.entrySet()) {
            List<BotEntry> entries = ownerEntry.getValue();
            boolean removedFromOwner = entries.removeIf(e -> {
                if (e.bot.getId() == botCharId) {
                    cancelBotTask(e);
                    return true;
                }
                return false;
            });
            if (removedFromOwner) {
                removed = true;
                if (entries.isEmpty() && bots.remove(ownerEntry.getKey(), entries)) {
                    ownerFormations.remove(ownerEntry.getKey());
                    townClusterAnchors.remove(ownerEntry.getKey());
                }
            }
        }
        return removed;
    }

    /** Release bot-owned runtime state before this character leaves bot control. */
    public boolean cleanupBotRuntimeState(Character bot) {
        if (bot == null) {
            return false;
        }

        boolean removed = removeBotByCharId(bot.getId());
        clearBotOnlyAutopotState(bot);
        return removed;
    }

    private void cancelBotTask(BotEntry entry) {
        if (entry != null && entry.task != null) {
            entry.task.cancel(false);
        }
    }

    private static void clearBotOnlyAutopotState(Character bot) {
        bot.setAutopotHpAlert(0f);
        bot.setAutopotMpAlert(0f);
        normalizeAutopotKey(bot, 91);
        normalizeAutopotKey(bot, 92);
    }

    private static void normalizeAutopotKey(Character bot, int key) {
        KeyBinding binding = bot.getKeymap().get(key);
        if (binding != null && binding.getType() != 7 && binding.getAction() > 0) {
            bot.changeKeybinding(key, new KeyBinding(7, binding.getAction()));
        }
    }

    /** Disown a bot by name - cancels its AI tick and leaves it idle in the map. */
    public boolean dismissBot(int ownerCharId, String botName) {
        List<BotEntry> entries = bots.get(ownerCharId);
        if (entries == null) return false;
        BotEntry entry = getBotEntry(ownerCharId, botName);
        if (entry == null) return false;
        entries.remove(entry);
        entry.task.cancel(false);
        issueStop(entry);
        after(randMs(400, 600), () ->
                botReply(entry, randomReply(List.of(
                        "ok", "sure", "alright", "gotcha",
                        "later!", "see ya", "take care", "cya", "peace out"))));
        return true;
    }

    /** Recruit an ownerless bot by name into the owner's group. Returns an error string on failure, null on success. */
    public String recruitBot(int ownerCharId, Character owner, String botName) {
        Character bot = findOwnerlessBot(botName, owner.getWorld());
        if (bot == null) return "No ownerless bot named '" + botName + "' found.";

        BotOwnershipService.AuthorizationResult auth =
                BotOwnershipService.getInstance().ensureCanControl(
                        owner,
                        new BotOwnershipService.ResolvedCharacter(
                                bot.getId(),
                                bot.getName(),
                                bot.getAccountID(),
                                bot));
        if (!auth.allowed()) {
            return auth.failureMessage();
        }

        registerSpawnedBot(ownerCharId, owner, bot);
        return null;
    }

    /** Transfer a bot from this owner to another player in the same map. Returns an error string on failure, null on success. */
    public String giveBot(int ownerCharId, Character owner, String botName, String targetName) {
        List<BotEntry> entries = bots.get(ownerCharId);
        if (entries == null) return "You have no bots.";
        BotEntry found = getBotEntry(ownerCharId, botName);
        if (found == null) return "No bot named '" + botName + "' in your group.";

        // Find target player in the same map
        Character target = owner.getMap().getCharacterByName(targetName);
        if (target == null) return "Player '" + targetName + "' not found in this map.";
        if (target.getId() == ownerCharId) return "That's you.";

        BotOwnershipService.AuthorizationResult auth =
                BotOwnershipService.getInstance().ensureCanControl(
                        target,
                        new BotOwnershipService.ResolvedCharacter(
                                found.bot.getId(),
                                found.bot.getName(),
                                found.bot.getAccountID(),
                                found.bot));
        if (!auth.allowed()) {
            return auth.failureMessage();
        }

        // Disown from current owner
        Character bot = found.bot;
        entries.remove(found);
        found.task.cancel(false);
        issueStop(found);

        // Register under new owner
        registerBot(target.getId(), target, bot);
        after(randMs(700, 900), () ->
                botSay(bot, randomReply(List.of("ok!", "sure!", "hey " + target.getName() + "!", "hi " + target.getName() + "!"))));
        return null;
    }

    public Character getActiveOwnerByBotCharId(int botCharId) {
        BotEntry entry = getEntryByBotCharId(botCharId);
        return entry != null ? entry.owner : null;
    }

    BotEntry getEntryByBotCharId(int botCharId) {
        for (List<BotEntry> entries : bots.values()) {
            for (BotEntry entry : entries) {
                if (entry.bot.getId() == botCharId) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Registered bot entries for every online bot in {@code anyMember}'s game party, in
     * party-member order (deterministic across members). Empty when not in a party. The
     * game party is the source of truth for party autopilot: @botme bots own themselves,
     * so the per-owner registry can't enumerate a mixed group.
     */
    /** Admin debug: one status line per spawned bot on {@code mapId} (any owner, including
     *  ownerless/independent), using the same first-person status the bot answers "where are you"
     *  with - for a private system-message listing, sorted by name. */
    public List<String> mapBotStatusLines(int mapId) {
        List<String> lines = new ArrayList<>();
        for (List<BotEntry> entries : bots.values()) {
            for (BotEntry entry : entries) {
                Character bot = entry.bot;
                if (bot == null || bot.getMapId() != mapId) {
                    continue;
                }
                String job = bot.getJob() == null ? "?" : bot.getJob().toString();
                lines.add(bot.getName() + " [" + job + " lv" + bot.getLevel() + "]: "
                        + BotAutopilotManager.statusReport(entry, bot));
            }
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        return lines;
    }

    List<BotEntry> partyBotEntries(Character anyMember) {
        if (anyMember == null || anyMember.getParty() == null) {
            return List.of();
        }
        List<BotEntry> out = new ArrayList<>();
        for (Character member : anyMember.getPartyMembersOnline()) {
            if (member == null || !(member.getClient() instanceof BotClient)) {
                continue;
            }
            BotEntry entry = getEntryByBotCharId(member.getId());
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    public void requestBotPotionCheckSoon(Character bot) {
        if (bot == null || !(bot.getClient() instanceof BotClient)) {
            return;
        }
        Character owner = getActiveOwnerByBotCharId(bot.getId());
        if (owner == null) {
            return;
        }
        BotEntry entry = getBotEntry(owner.getId(), bot.getId());
        if (entry == null) {
            return;
        }
        int soonDelayMs = Math.max(0, cfg.POT_CHECK_RETRY_SOON_MS);
        if (entry.potCheckTimerMs <= 0 || entry.potCheckTimerMs > soonDelayMs) {
            entry.potCheckTimerMs = soonDelayMs;
        }
    }

    /** Finds a bot-client character with the given name that is not currently owned by anyone. */
    private Character findOwnerlessBot(String name, int world) {
        for (var ch : Server.getInstance().getWorld(world).getChannels()) {
            Character c = ch.getPlayerStorage().getCharacterByName(name);
            if (c == null || !(c.getClient() instanceof BotClient)) continue;
            if (getActiveOwnerByBotCharId(c.getId()) == null) return c;
        }
        return null;
    }

    public Character getBot(int ownerCharId) {
        List<BotEntry> entries = bots.get(ownerCharId);
        return (entries != null && !entries.isEmpty()) ? entries.get(0).bot : null;
    }

    BotEntry getFirstBotEntry(int ownerCharId) {
        List<BotEntry> entries = bots.get(ownerCharId);
        return (entries != null && !entries.isEmpty()) ? entries.get(0) : null;
    }

    List<BotEntry> getBotEntries(int ownerCharId) {
        List<BotEntry> entries = bots.get(ownerCharId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries);
    }

    /** Called when the owner picks up or receives an item; notifies bots that might want it. */
    public void notifyOwnerGainedItem(Character owner, Item item) {
        if (owner == null || item == null) return;
        if (ItemConstants.getInventoryType(item.getItemId()) != InventoryType.EQUIP) return;
        List<BotEntry> entries = getBotEntries(owner.getId());
        if (entries.isEmpty()) return;
        // Run the per-bot upgrade-recommendation scan off the player's pickup thread. The
        // scan is fire-and-forget (its only effect is a possible chat-prompt) so deferring
        // by one timer tick keeps rapid pickups from stalling the player behind K×N DPs.
        after(0L, () -> {
            for (BotEntry entry : entries) {
                BotOfferManager.notifyOwnerGainedEquip(entry, entry.bot, item);
            }
        });
    }

    /** Called when a trade recipient receives an item; skips circular own-bot trade scans. */
    public void notifyOwnerGainedTradeItem(Character recipient, Item item, Character source) {
        if (isItemFromOwnedBot(recipient, source)) {
            return;
        }
        notifyOwnerGainedItem(recipient, item);
    }

    private boolean isItemFromOwnedBot(Character owner, Character source) {
        if (owner == null || source == null || !(source.getClient() instanceof BotClient)) {
            return false;
        }
        Character activeOwner = getActiveOwnerByBotCharId(source.getId());
        return activeOwner != null && activeOwner.getId() == owner.getId();
    }

    public void notifyNearbyBotsOfScroll(Character source,
                                         client.inventory.Equip.ScrollResult result,
                                         int scrollItemId,
                                         long delayMs) {
        after(Math.max(0L, delayMs), () ->
                BotScrollReactionManager.handleScrollEvent(source, result, scrollItemId, bots.values()));
    }

    BotEntry getBotEntry(int ownerCharId, String botName) {
        List<BotEntry> entries = bots.get(ownerCharId);
        if (entries == null || botName == null) {
            return null;
        }

        for (BotEntry entry : entries) {
            if (entry.bot.getName().equalsIgnoreCase(botName)) {
                return entry;
            }
        }
        return null;
    }

    /** Any spawned bot by name across ALL owners - for GM debug tooling (e.g. !botnav pathlog on
     *  ownerless / independent bots), not the ownership-scoped command paths. Null if none. */
    BotEntry findSpawnedBotByName(String botName) {
        if (botName == null) {
            return null;
        }
        for (List<BotEntry> entries : bots.values()) {
            for (BotEntry entry : entries) {
                if (entry.bot != null && entry.bot.getName().equalsIgnoreCase(botName)) {
                    return entry;
                }
            }
        }
        return null;
    }

    public void syncPartyBotsQuestStart(Character source, Quest quest, int npc) {
        if (quest == null) {
            return;
        }

        for (Character bot : getPartyBots(source)) {
            if (bot.getQuest(quest).getStatus() == QuestStatus.Status.STARTED) {
                continue;
            }
            quest.forceStartWithActions(bot, resolveQuestNpc(source, quest, npc));
        }
    }

    public void syncPartyBotsQuestProgress(Character source, int questId, int infoNumber, String progress) {
        if (progress == null) {
            return;
        }

        Quest quest = Quest.getInstance(questId);
        int npc = resolveQuestNpc(source, quest, source.getQuest(quest).getNpc());
        for (Character bot : getPartyBots(source)) {
            ensureQuestStarted(bot, quest, npc);
            bot.setQuestProgress(questId, infoNumber, progress);
        }
    }

    public void syncPartyBotsQuestComplete(Character source, Quest quest, int npc, Integer selection) {
        if (quest == null) {
            return;
        }

        int resolvedNpc = resolveQuestNpc(source, quest, npc);
        for (Character bot : getPartyBots(source)) {
            ensureQuestStarted(bot, quest, resolvedNpc);
            quest.forceCompleteWithActions(bot, resolvedNpc, selection);
        }
    }

    public String manualTradeGreeting() {
        return randomReply(List.of(
                "?",
                "got something for me?",
                "what you got?",
                "trade?",
                "show me",
                "lets see",
                "whatcha got",
                "tryna trade?",
                "yes?",
                "sup",
                "ooh what is it",
                "what's up"));
    }

    private List<Character> getPartyBots(Character source) {
        if (source == null || source.getParty() == null || source.getClient() instanceof BotClient) {
            return List.of();
        }

        List<Character> partyBots = new ArrayList<>();
        for (Character member : source.getPartyMembersOnline()) {
            if (member == null || member.getId() == source.getId()) {
                continue;
            }
            if (member.getClient() instanceof BotClient) {
                partyBots.add(member);
            }
        }
        return partyBots;
    }

    private void ensureQuestStarted(Character bot, Quest quest, int npc) {
        if (bot.getQuest(quest).getStatus() == QuestStatus.Status.STARTED) {
            return;
        }

        quest.forceStartWithActions(bot, npc);
    }

    private int resolveQuestNpc(Character source, Quest quest, int fallbackNpc) {
        if (fallbackNpc > 0) {
            return fallbackNpc;
        }

        if (source != null) {
            int sourceNpc = source.getQuest(quest).getNpc();
            if (sourceNpc > 0) {
                return sourceNpc;
            }
        }

        return constants.id.NpcId.MAPLE_ADMINISTRATOR;
    }

    public void handleChat(Character owner, String message, ReplyChannel channel) {
        if (handlePendingLootOfferResponse(owner, message)) {
            return;
        }

        // Recruit must work even when owner has no bots yet
        Matcher rm = RECRUIT_PATTERN.matcher(message);
        if (rm.find()) {
            String name = rm.group(2);
            String err = recruitBot(owner.getId(), owner, name);
            if (err == null) {
                owner.yellowMessage("Bot '" + name + "' recruited!");
            } else {
                owner.yellowMessage(err);
            }
            return;
        }

        BotCommandParser.BotTransferCommand transferCommand = BotCommandParser.matchBotTransferCommand(message);
        if (transferCommand != null) {
            String err = giveBot(owner.getId(), owner, transferCommand.botName(), transferCommand.targetName());
            if (err != null) owner.yellowMessage(err);
            else owner.yellowMessage("Bot '" + transferCommand.botName() + "' transferred to " + transferCommand.targetName() + ".");
            return;
        }

        // Formation command
        Matcher fm = FORMATION_PATTERN.matcher(message);
        if (fm.find()) {
            String typeStr = fm.group(1);
            List<BotEntry> fEntries = bots.get(owner.getId());
            if (typeStr == null) {
                String help = "formations: stagger/split/random/spread/left/right <px>, stack, tight, loose | snap <px/on/off>";
                if (fEntries != null && !fEntries.isEmpty()) BotChatManager.queueBotReply(fEntries.get(0), help);
                else owner.yellowMessage(help);
                return;
            }
            FormationState current = ownerFormations.getOrDefault(owner.getId(), FormationState.defaultStagger());
            // snap [px|on|off] — changes Y-snap range, preserves type/px
            if (typeStr.equalsIgnoreCase("snap")) {
                String qualifier = fm.group(2);
                int newSnapRange;
                if (qualifier == null) {
                    String status = current.snapRange() > 0 ? "on (" + current.snapRange() + "px)" : "off";
                    if (fEntries != null && !fEntries.isEmpty()) BotChatManager.queueBotReply(fEntries.get(0), "snap: " + status);
                    else owner.yellowMessage("snap: " + status);
                    return;
                } else if (qualifier.equalsIgnoreCase("off")) {
                    newSnapRange = 0;
                } else if (qualifier.equalsIgnoreCase("on")) {
                    newSnapRange = current.snapRange() > 0 ? current.snapRange() : BotMovementManager.cfg.FOLLOW_Y_CAP;
                } else {
                    newSnapRange = Integer.parseInt(qualifier);
                }
                FormationState fs = new FormationState(current.type(), current.px(), newSnapRange);
                ownerFormations.put(owner.getId(), fs);
                String status = newSnapRange > 0 ? "on (" + newSnapRange + "px)" : "off";
                if (fEntries != null && !fEntries.isEmpty())
                    BotChatManager.queueBotReply(fEntries.get(0), "snap: " + status);
                return;
            }
            String pxToken = fm.group(2);
            int defaultPx = pxToken == null                      ? cfg.FOLLOW_STAGGER
                          : pxToken.equalsIgnoreCase("tight")    ? 30
                          : pxToken.equalsIgnoreCase("loose")    ? 120
                          : pxToken.equalsIgnoreCase("on")
                            || pxToken.equalsIgnoreCase("off")   ? cfg.FOLLOW_STAGGER
                          : Integer.parseInt(pxToken);
            FormationType type;
            int px = defaultPx;
            switch (typeStr.toLowerCase()) {
                case "tight"          -> { type = FormationType.STAGGER; px = 30; }
                case "loose"          -> { type = FormationType.STAGGER; px = 120; }
                case "stack"          -> { type = FormationType.STACK;   px = 0; }
                case "spread"         -> { type = FormationType.SPREAD;  px = defaultPx; }
                case "left"           -> { type = FormationType.LEFT;    px = defaultPx; }
                case "right"          -> { type = FormationType.RIGHT;   px = defaultPx; }
                case "random"         -> { type = FormationType.RANDOM;  px = defaultPx; }
                case "split","stagger"-> { type = FormationType.STAGGER; px = defaultPx; }
                default               -> { type = FormationType.STAGGER; px = defaultPx; }
            }
            FormationState fs = new FormationState(type, px, current.snapRange());
            ownerFormations.put(owner.getId(), fs);
            if (fEntries != null) {
                for (int i = 0; i < fEntries.size(); i++) fEntries.get(i).followOffsetX = fs.offsetFor(i, fEntries.size());
                if (!fEntries.isEmpty()) {
                    String label = typeStr.toLowerCase() + (px > 0 ? " " + px + "px" : "");
                    BotChatManager.queueBotReply(fEntries.get(0), "formation: " + label);
                }
            }
            return;
        }

        // ADMIN DEBUG ROUTING: a gm6 admin may name-target ANY spawned bot - including bots they
        // don't own and independent/self-owned bots - to interact with and debug it. Only
        // name-targeted commands cross the ownership boundary (bare broadcasts stay owner-scoped),
        // and the admin's OWN bots are excluded here so they keep the existing owner path below.
        // Placed before the own-bots fetch so an admin who owns zero bots can still command foreign
        // ones.
        if (owner.gmLevel() >= 6) {
            BotCommandParser.TargetedBotMatch foreignMatch = resolveForeignAdminTarget(owner, message);
            BotEntry foreign = foreignMatch.entry();
            if (foreign != null) {
                bindDebugCommander(foreign, owner);
                foreign.replyChannel = channel;
                if (foreignMatch.commandText() != null) {
                    // Only an explicit follow command transfers the follow dependency to the admin;
                    // every other interaction (e.g. "where ru") just replies, leaving the bot's task.
                    if (BotChatManager.isFollowCommand(foreignMatch.commandText())) {
                        foreign.debugCommanderFollow = true;
                    }
                    BotChatManager.handleChat(foreign, foreignMatch.commandText());
                }
                return;
            }
        }

        List<BotEntry> entries = bots.get(owner.getId());
        if (entries == null || entries.isEmpty()) return;

        // Dismiss: disown bot, leaves it idle in map
        Matcher dm = DISMISS_PATTERN.matcher(message);
        if (dm.find()) {
            String name = dm.group(2);
            if (dismissBot(owner.getId(), name)) {
                owner.yellowMessage("Bot '" + name + "' disowned - now idle.");
            } else {
                owner.yellowMessage("No bot named '" + name + "' in your group.");
            }
            return;
        }

        // Name-prefix routing: "Jason pots?" → only Jason responds
        BotCommandParser.TargetedBotMatch targetedBot = BotCommandParser.resolveTargetedBot(entries, message);
        if (targetedBot.entry() != null) {
            String followTargetToken = BotChatManager.matchFollowTarget(targetedBot.commandText());
            if (followTargetToken != null) {
                applyFollowTargetCommand(owner, List.of(targetedBot.entry()), followTargetToken);
                return;
            }
            clearDebugCommander(targetedBot.entry()); // real owner commands -> owner wins
            targetedBot.entry().replyChannel = channel;
            String cmd = targetedBot.commandText();
            if (server.bots.llm.BotLlmConfig.typoSuggesterEnabled) {
                String typo = server.bots.llm.CommandTypoSuggester.suggest(cmd);
                if (typo != null) {
                    BotChatManager.queueBotReply(targetedBot.entry(), "did you mean '" + typo + "'?");
                    return;
                }
            }
            BotChatManager.handleChat(targetedBot.entry(), cmd);
            boolean matched = BotChatManager.wasLastChatHandled();
            if (matched && targetedBot.entry().getOwner() != null
                    && owner.getId() == targetedBot.entry().getOwner().getId()) {
                targetedBot.entry().lastOwnerCommand = cmd;
                targetedBot.entry().lastOwnerCommandAtMs = System.currentTimeMillis();
            }
            // Fall through to LLM only if no command pattern matched.
            if (server.bots.llm.BotLlmConfig.enabled && !matched) {
                server.bots.llm.BotLlmReplyManager.maybeRespond(targetedBot.entry(), owner, cmd);
            }
            return;
        }
        if (targetedBot.feedbackMessage() != null) {
            owner.yellowMessage(targetedBot.feedbackMessage());
            return;
        }

        String followTargetToken = BotChatManager.matchFollowTarget(message);
        if (followTargetToken != null) {
            applyFollowTargetCommand(owner, entries, followTargetToken);
            return;
        }

        // Party autopilot ("go grind together"): ONE shared decision for the whole group.
        // Broadcasting would have every bot plan its own trip and scatter.
        if (BotChatManager.isPartyAutopilotCommand(message)) {
            List<BotEntry> snapshot = List.copyOf(entries);
            for (BotEntry e : snapshot) {
                e.replyChannel = channel;
            }
            after(randMs(900, 1600), () -> {
                for (BotEntry e : snapshot) {
                    BotChatManager.prepareActiveModeEntry(e);
                }
                BotAutopilotManager.startParty(owner, snapshot);
            });
            return;
        }

        // Group supply requests ("need pots", "anyone have hp pots", "need arrows"
        // etc.) elicit a single response from the bot group. Broadcasting these
        // would have every bot run handleNeedPotionCommand independently, each
        // selecting the same best-stocked donor → duplicate offer messages and
        // duplicate trade requests to the owner.
        if (BotChatManager.isGroupSupplyRequest(message)) {
            BotEntry responder = pickGroupSupplyResponder(owner, entries);
            if (responder != null) {
                clearDebugCommander(responder); // real owner commands -> owner wins
                responder.replyChannel = channel;
                BotChatManager.handleChat(responder, message);
            }
            return;
        }

        // No name prefix — typo-suggest once via the first bot, otherwise broadcast.
        if (server.bots.llm.BotLlmConfig.typoSuggesterEnabled) {
            String typo = server.bots.llm.CommandTypoSuggester.suggest(message);
            if (typo != null) {
                BotEntry first = entries.get(0);
                first.replyChannel = channel;
                BotChatManager.queueBotReply(first, "did you mean '" + typo + "'?");
                return;
            }
        }
        for (BotEntry entry : entries) {
            clearDebugCommander(entry); // real owner commands -> owner wins
            entry.replyChannel = channel;
            BotChatManager.handleChat(entry, message);
        }
    }

    /** Prefer a bot in the owner's current map so its reply/trade is visible. */
    private static BotEntry pickGroupSupplyResponder(Character owner, List<BotEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        int ownerMapId = owner != null ? owner.getMapId() : -1;
        for (BotEntry entry : entries) {
            if (entry.bot != null && entry.bot.getMapId() == ownerMapId) {
                return entry;
            }
        }
        return entries.get(0);
    }

    private boolean handlePendingLootOfferResponse(Character speaker, String message) {
        List<BotEntry> matches = new ArrayList<>();
        for (List<BotEntry> entries : bots.values()) {
            for (BotEntry entry : entries) {
                BotOfferManager.expirePendingOffer(entry);
                if (!isPendingLootOfferTarget(entry, speaker)) {
                    continue;
                }

                matches.add(entry);
            }
        }

        BotCommandParser.TargetedBotMatch targetedBot = BotCommandParser.resolveTargetedBot(matches, message);
        if (targetedBot.entry() != null) {
            return BotOfferManager.handlePendingOfferResponse(targetedBot.entry(), speaker, targetedBot.commandText());
        }
        if (targetedBot.feedbackMessage() != null) {
            speaker.dropMessage(5, targetedBot.feedbackMessage());
            return true;
        }

        if (matches.size() == 1) {
            return BotOfferManager.handlePendingOfferResponse(matches.get(0), speaker, message);
        }
        if (matches.size() > 1 && looksLikeConfirmation(message)) {
            speaker.dropMessage(5, "More than one bot is waiting on you. Say '<botname> yes' or '<slot> yes'.");
            return true;
        }

        return false;
    }

    private boolean isPendingLootOfferTarget(BotEntry entry, Character speaker) {
        return entry != null
                && BotOfferManager.hasPendingOffer(entry)
                && entry.pendingLootOfferRecipientId == speaker.getId()
                && entry.bot.getMapId() == speaker.getMapId();
    }

    private boolean looksLikeConfirmation(String message) {
        String normalized = message.trim().toLowerCase();
        return normalized.matches(".*\\b(yes|yep|yeah|yea|y|ok|sure|confirm|no|nope|nah|nvm|never\\s*mind|dont|don't|not\\s+now|skip)\\b.*");
    }

    // -------------------------------------------------------------------------
    /**
     * Resolve the follow target by sweeping for a real platform at followBase.x within
     * snapRange pixels of ownerPos.y. If a platform exists there, the bot may stand on
     * a platform different from the owner's (formation spread). If no platform is found
     * within range, or snap is disabled, fall back to the owner's foothold with X clamped
     * so the bot never targets a position that isn't on a real standing surface.
     * If the owner is on a rope, target the rope region instead of searching for ground platforms.
     */
    private static Point resolveFollowTargetPos(Point followBase,
                                                Character owner,
                                                Point ownerPos,
                                                int snapRange,
                                                MapleMap map) {
        if (owner != null && CharacterStance.isClimbing(owner.getStance()) && map != null) {
            return clampedOnOwnerRegion(followBase.x, owner, ownerPos, map);
        }

        if (snapRange > 0 && map != null) {
            Point below = BotPhysicsEngine.findGroundPoint(map, followBase);
            Point above = BotPhysicsEngine.findGroundPoint(map, new Point(followBase.x, ownerPos.y - snapRange));
            boolean belowOk = below != null && Math.abs(below.y - ownerPos.y) <= snapRange;
            boolean aboveOk = above != null && Math.abs(above.y - ownerPos.y) <= snapRange;
            if (belowOk || aboveOk) {
                if (!belowOk) return above;
                if (!aboveOk) return below;
                return Math.abs(below.y - ownerPos.y) <= Math.abs(above.y - ownerPos.y) ? below : above;
            }
        }
        // Swim maps: when owner is themselves swimming (mid-water) and no
        // platform is within snapRange, target the raw owner position so the
        // bot swims up to mid-water. If owner is grounded on a platform but
        // we couldn't snap-match it (e.g. very tall stack), keep the normal
        // clampedOnOwnerRegion behaviour — bot should land on a real floor,
        // not hover in water under a grounded owner.
        if (map != null && map.isSwim()
                && owner != null && CharacterStance.isSwimming(owner.getStance())) {
            return new Point(followBase.x, ownerPos.y);
        }
        return clampedOnOwnerRegion(followBase.x, owner, ownerPos, map);
    }

    /**
     * Clamps targetX to the owner's current walk region and returns a real standing point.
     * Falls back to the owner's foothold segment if the region cannot be resolved.
     * For rope targets, finds the nearest rope to the formation target position.
     */
    private static Point clampedOnOwnerRegion(int targetX, Character owner, Point ownerPos, MapleMap map) {
        if (map != null) {
            BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map);
            if (graph != null) {
                int ownerRegionId = owner != null
                        ? BotNavigationManager.resolveCharacterRegionId(graph, map, owner)
                        : graph.findRegionId(map, ownerPos);
                BotNavigationGraph.Region ownerRegion = graph.getRegion(ownerRegionId);
                if (ownerRegion != null) {
                    if (ownerRegion.isRopeRegion) {
                        // Find nearest rope at the post-formation offset position.
                        // If no rope is nearby, fall back to the owner's own rope so
                        // the bot still climbs up to follow rather than standing
                        // on the platform below.
                        BotNavigationGraph.Region nearestRope = findNearestRopeAtY(graph, targetX, ownerPos.y);
                        if (nearestRope == null) {
                            nearestRope = ownerRegion;
                        }
                        return new Point(nearestRope.minX, ownerPos.y);
                    } else {
                        // Inset by a small margin so the bot doesn't aim at the
                        // exact platform edge — owner's hit-region extends past
                        // the foothold endpoints, but the bot needs a real
                        // standing surface under its feet, and the integrator
                        // routinely overshoots edges by 1-2 px in swim maps.
                        int edgeMargin = PLATFORM_EDGE_INSET_PX;
                        int minX = ownerRegion.minX;
                        int maxX = ownerRegion.maxX;
                        if (maxX - minX > 2 * edgeMargin) {
                            minX += edgeMargin;
                            maxX -= edgeMargin;
                        }
                        int clampedX = Math.max(minX, Math.min(maxX, targetX));
                        return ownerRegion.pointAt(clampedX);
                    }
                }
            }
        }

        Foothold ownerFh = BotPhysicsEngine.findGroundFoothold(map, ownerPos);
        if (ownerFh != null) {
            int x1 = Math.min(ownerFh.getX1(), ownerFh.getX2());
            int x2 = Math.max(ownerFh.getX1(), ownerFh.getX2());
            targetX = Math.max(x1, Math.min(x2, targetX));
        }
        Point fallback = map == null ? null : BotPhysicsEngine.findGroundPoint(map, new Point(targetX, ownerPos.y));
        return fallback != null ? fallback : new Point(targetX, ownerPos.y);
    }

    /**
     * Finds the rope region nearest to the target position (targetX, targetY).
     * Returns null if no rope region is found within reasonable distance.
     */
    private static BotNavigationGraph.Region findNearestRopeAtY(BotNavigationGraph graph, int targetX, int targetY) {
        BotNavigationGraph.Region nearestRope = null;
        int nearestDistance = Integer.MAX_VALUE;
        int maxDistance = 400;

        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                if (region.minY > targetY || region.maxY < targetY) {
                    continue;
                }
                int ropeX = region.minX;
                int distance = Math.abs(ropeX - targetX);
                if (distance < nearestDistance && distance <= maxDistance) {
                    nearestDistance = distance;
                    nearestRope = region;
                }
            }
        }

        return nearestRope;
    }

    FormationState formationStateFor(BotEntry entry) {
        Character owner = entry.owner;
        if (owner == null) {
            return FormationState.defaultStagger();
        }
        return ownerFormations.getOrDefault(owner.getId(), FormationState.defaultStagger());
    }

    // Admin-debug commander binding: a gm6 admin commanding a foreign/independent bot temporarily
    // makes the bot interact with the admin instead of its real owner. ~5 min window, refreshed by
    // each admin command.
    static final long DEBUG_COMMANDER_TTL_MS = 5 * 60_000L;

    private static boolean isDebugCommanderFresh(BotEntry entry) {
        return entry != null && entry.debugCommanderId > 0
                && System.currentTimeMillis() < entry.debugCommanderUntilMs;
    }

    static void bindDebugCommander(BotEntry entry, Character commander) {
        if (entry == null || commander == null) {
            return;
        }
        entry.debugCommanderId = commander.getId();
        entry.debugCommanderUntilMs = System.currentTimeMillis() + DEBUG_COMMANDER_TTL_MS;
    }

    static void clearDebugCommander(BotEntry entry) {
        if (entry != null) {
            entry.debugCommanderId = 0;
            entry.debugCommanderUntilMs = 0L;
            entry.debugCommanderFollow = false;
        }
    }

    /** The bound admin commander while the binding is fresh, else null. Resolves the Character via
     *  the bot's current map (the admin name-targeted a bot they can see). */
    Character resolveDebugCommander(BotEntry entry) {
        if (!isDebugCommanderFresh(entry) || entry.bot == null || entry.bot.getMap() == null) {
            return null;
        }
        return entry.bot.getMap().getCharacterById(entry.debugCommanderId);
    }

    /** Who command-driven interactions (trade-with-owner, follow) should target: the fresh admin
     *  commander if bound and resolvable, else the real owner. */
    Character commanderOrOwner(BotEntry entry) {
        Character commander = resolveDebugCommander(entry);
        return commander != null ? commander : entry.owner;
    }

    /**
     * Resolve a foreign spawned bot a gm6 admin name-targeted. "Foreign" = any spawned bot whose
     * real owner is not the speaker (includes independent/self-owned bots and autopilot bots with
     * offline owners). Name matches only — numeric slot targets keep their own-bot-list meaning.
     * The returned match has a null entry when the message is not name-targeted at a foreign bot
     * (the speaker's own bots keep the existing owner path).
     */
    BotCommandParser.TargetedBotMatch resolveForeignAdminTarget(Character speaker, String message) {
        if (speaker == null) {
            return new BotCommandParser.TargetedBotMatch(null, null, null);
        }
        int speakerId = speaker.getId();
        List<BotEntry> foreign = new ArrayList<>();
        for (List<BotEntry> ownerEntries : bots.values()) {
            for (BotEntry entry : ownerEntries) {
                Character entryOwner = entry.owner;
                if (entryOwner == null || entryOwner.getId() != speakerId) {
                    foreign.add(entry);
                }
            }
        }
        return BotCommandParser.resolveTargetedBotByName(foreign, message);
    }

    /** True when the bot plays independently of its owner: autopilot is running, or it is a
     *  self-owned (@botme) bot whose owner is itself. Such bots must never anchor to the owner. */
    static boolean isAutopilotActive(BotEntry entry) {
        return BotAutopilotManager.isActive(entry)
                || (entry != null && entry.owner != null && entry.owner == entry.bot);
    }

    /** Whether an emergency may legally fall back to walking to the owner: only when the bot is
     *  not playing independently, has a real owner that is not itself, and that owner is online
     *  in this world (so its position is live, not a stale logged-off snapshot). */
    static boolean canWalkToOwner(BotEntry entry) {
        if (entry == null || isAutopilotActive(entry)) {
            return false;
        }
        Character owner = entry.owner;
        return owner != null && owner != entry.bot && owner.isLoggedinWorld();
    }

    Character resolveFollowAnchor(BotEntry entry, Character owner) {
        // Follow the admin ONLY when they issued an explicit follow command - a debug binding from a
        // mere status interaction must not transfer the bot's follow dependency off its leader.
        Character commander = resolveDebugCommander(entry);
        if (commander != null && entry.debugCommanderFollow) {
            return commander;
        }
        if (owner == null) {
            return null;
        }
        // Self-owned (@botme) bot: anchoring to itself makes the formation offset oscillate the
        // bot around its own position. No anchor — the commander check above still wins when set.
        if (owner == entry.bot) {
            return null;
        }

        int targetId = entry.followTargetId;
        if (targetId <= 0 || targetId == owner.getId() || targetId == entry.bot.getId()) {
            return owner;
        }

        if (owner.getParty() != null) {
            for (Character member : owner.getPartyMembersOnline()) {
                if (member != null && member.getId() == targetId && member.isLoggedinWorld()) {
                    return member;
                }
            }
        }

        for (BotEntry sibling : getBotEntries(owner.getId())) {
            if (sibling.bot != null && sibling.bot.getId() == targetId && sibling.bot.isLoggedinWorld()) {
                return sibling.bot;
            }
        }

        return owner;
    }

    void setFormationState(Character owner, FormationType type, int px, int snapRange, List<BotEntry> entries) {
        if (owner == null) {
            return;
        }

        FormationState formation = new FormationState(type, px, snapRange);
        ownerFormations.put(owner.getId(), formation);
        if (entries == null) {
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).followOffsetX = formation.offsetFor(i, entries.size());
        }
    }

    TargetSnapshot captureTargetSnapshot(BotEntry entry) {
        Character bot = entry.bot;
        Character owner = entry.owner;
        Character followAnchor = resolveFollowAnchor(entry, owner);
        Point fallbackPos = bot.getPosition();
        Point rawOwnerPos = owner != null ? owner.getPosition() : fallbackPos;
        Point rawFollowAnchorPos = followAnchor != null ? followAnchor.getPosition() : rawOwnerPos;
        String followAnchorName = followAnchor != null ? followAnchor.getName() : "owner";
        FormationState formation = formationStateFor(entry);
        Point followBasePos = new Point(rawFollowAnchorPos.x + entry.followOffsetX, rawFollowAnchorPos.y);
        Point followTargetPos = resolveFollowTargetPos(followBasePos, followAnchor, rawFollowAnchorPos, formation.snapRange(), bot.getMap());
        Point rawShopTargetPos = entry.shopVisitPending
                ? (entry.shopTargetPos != null ? entry.shopTargetPos : entry.shopNpcPos)
                : null;
        Point shopTargetPos = rawShopTargetPos == null ? null : new Point(rawShopTargetPos);
        Point moveTargetPos = entry.moveTarget == null ? null : new Point(entry.moveTarget);
        Point farmAnchorPos = entry.farmAnchor == null || entry.farmAnchorMapId != bot.getMapId()
                ? null
                : new Point(entry.farmAnchor);
        Monster activeGrindTarget = entry.grindTarget != null
                && entry.grindTarget.isAlive()
                && entry.grindTarget.getMap() == bot.getMap()
                ? entry.grindTarget
                : null;
        Point grindTargetPos = activeGrindTarget == null ? null : new Point(activeGrindTarget.getPosition());
        Point primaryTargetPos;
        String primaryTargetSource;
        if (shopTargetPos != null) {
            primaryTargetPos = shopTargetPos;
            primaryTargetSource = "shop-target";
        } else if (moveTargetPos != null) {
            primaryTargetPos = moveTargetPos;
            primaryTargetSource = "move-target";
        } else if (farmAnchorPos != null) {
            primaryTargetPos = farmAnchorPos;
            primaryTargetSource = "farm-anchor";
        } else if (grindTargetPos != null) {
            primaryTargetPos = grindTargetPos;
            primaryTargetSource = "grind-target";
        } else if (entry.grinding) {
            primaryTargetPos = resolveNoGrindTargetPosition(entry, fallbackPos, bot.getMap());
            primaryTargetSource = "grind-wander";
        } else if (entry.following) {
            primaryTargetPos = followTargetPos;
            primaryTargetSource = "follow-target";
        } else if (isAutopilotActive(entry)) {
            // Safety net: an autopilot / self-owned bot must never anchor to its owner. The
            // off-site stranded case is handled earlier by the portal wander; reaching here
            // means hold position (own spot) rather than walk toward the owner.
            primaryTargetPos = fallbackPos;
            primaryTargetSource = "autopilot-hold";
        } else {
            primaryTargetPos = rawOwnerPos;
            primaryTargetSource = "owner-raw";
        }
        return new TargetSnapshot(
                formation,
                new Point(rawOwnerPos),
                new Point(rawFollowAnchorPos),
                followAnchorName,
                new Point(followBasePos),
                new Point(followTargetPos),
                moveTargetPos,
                farmAnchorPos,
                grindTargetPos,
                new Point(primaryTargetPos),
                primaryTargetSource);
    }

    private static final int RETREAT_HOLD_MS = 600;
    private static final int RETREAT_ARRIVAL_TOLERANCE_X = 25; // 50ms tick can't land on an exact pixel

    // AoE reposition commitment: returns the sweet-spot Point to walk to before firing, or null to
    // fire now. Scores once when a commitment starts (BotCombatManager.aoeRepositionTarget); while
    // committed it just returns the stored anchor — no further scoring — until the bot arrives, the
    // bounded-chase deadline expires, or the target dies/clears.
    private static Point resolveAoeReposition(BotEntry entry, Character bot, Monster target,
                                              BotCombatManager.AttackPlan attackPlan, Point botPos) {
        long now = System.currentTimeMillis();
        if (entry.aoeRepositionAnchor != null) {
            boolean done = now > entry.aoeRepositionDeadlineMs
                    || target == null || !target.isAlive()
                    || Math.abs(entry.aoeRepositionAnchor.x - botPos.x) <= BotCombatManager.cfg.AOE_REPOSITION_ARRIVAL_X;
            if (done) {
                entry.aoeRepositionAnchor = null;
                entry.aoeRepositionDeadlineMs = 0L;
                return null;
            }
            return entry.aoeRepositionAnchor;
        }
        Point anchor = BotCombatManager.aoeRepositionTarget(entry, bot, target, attackPlan);
        if (anchor != null) {
            entry.aoeRepositionAnchor = anchor;
            entry.aoeRepositionDeadlineMs = now + BotCombatManager.cfg.AOE_REPOSITION_MAX_MS;
        }
        return anchor;
    }

    static Point selectGrindNavigationTarget(BotEntry entry, Point botPos, Point combatTargetPos) {
        return selectGrindNavigationTarget(entry, botPos, combatTargetPos, false);
    }

    private static Point selectGrindNavigationTarget(BotEntry entry,
                                                     Point botPos,
                                                     Point combatTargetPos,
                                                     boolean crossRegionRetreatChecked) {
        if (entry == null || botPos == null || combatTargetPos == null) {
            return combatTargetPos;
        }

        Character bot = entry.bot;
        if (bot == null) {
            return combatTargetPos;
        }

        long now = System.currentTimeMillis();
        boolean retreatNeeded = BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(
                BotAttackExecutionProvider.getEquippedWeaponType(bot), botPos, combatTargetPos);

        // Surround-breakout commitment: once pincered, keep bursting the SAME way until the
        // bot is no longer flanked on both sides (or a safety timeout), re-issuing a forward
        // step each tick. A per-step local retreat here would walk into the opposite mob and
        // flip direction every time it arrives — the swarm oscillation we are fixing.
        if (entry.breakoutDirection != 0) {
            if (now >= entry.breakoutUntilMs || !BotAttackExecutionProvider.isSurrounded(bot, botPos)) {
                entry.breakoutDirection = 0;
                entry.breakoutUntilMs = 0L;
            } else {
                return breakoutStep(botPos, entry.breakoutDirection);
            }
        }

        // Hysteresis: a previously committed retreat keeps its goal until either the
        // hold expires, the bot has effectively arrived, or the bot wandered too far
        // from the hold pos for it to still be the right answer.
        if (entry.retreatHoldPos != null && now < entry.retreatHoldUntilMs) {
            int dxHold = Math.abs(entry.retreatHoldPos.x - botPos.x);
            if (dxHold <= RETREAT_ARRIVAL_TOLERANCE_X) {
                entry.retreatHoldUntilMs = 0L;
                entry.retreatHoldPos = null;
            } else if (dxHold > BotCombatManager.cfg.RANGED_RETREAT_DISTANCE_X * 2) {
                entry.retreatHoldUntilMs = 0L;
                entry.retreatHoldPos = null;
            } else {
                return new Point(entry.retreatHoldPos);
            }
        } else if (entry.retreatHoldPos != null) {
            entry.retreatHoldUntilMs = 0L;
            entry.retreatHoldPos = null;
        }

        if (!retreatNeeded) {
            return combatTargetPos;
        }

        // Prefer landing on a different walkable region than the target — mobs there can't
        // path to the bot without traversing a nav edge, while the bot can still shoot across.
        // Empty/sparse adjacent regions score highest. Cross-region uses the nav-edge
        // pipeline for stickiness, so no separate hysteresis is set here.
        Point crossRegionPos = crossRegionRetreatChecked
                ? null
                : selectCrossRegionRetreatTarget(entry, botPos, combatTargetPos);
        if (crossRegionPos != null) {
            return crossRegionPos;
        }

        // No separated region to flee to (e.g. one open platform). If the bot is pincered,
        // commit to bursting out one side instead of micro-retreating into the other wall.
        if (BotAttackExecutionProvider.isSurrounded(bot, botPos)) {
            int dir = pickBreakoutDirection(entry, botPos, combatTargetPos);
            entry.breakoutDirection = dir;
            entry.breakoutUntilMs = now + BotCombatManager.cfg.BREAKOUT_MAX_MS;
            // Drop any stale one-step hysteresis so it can't fight the committed breakout.
            entry.retreatHoldPos = null;
            entry.retreatHoldUntilMs = 0L;
            return breakoutStep(botPos, dir);
        }

        Point retreatPos = BotAttackExecutionProvider.retreatTargetPosition(bot, botPos, combatTargetPos);
        if (shouldUseLocalCombatRetreatTarget(entry, botPos, combatTargetPos, retreatPos)) {
            entry.retreatHoldUntilMs = now + RETREAT_HOLD_MS;
            entry.retreatHoldPos = new Point(retreatPos);
            return retreatPos;
        }
        return combatTargetPos;
    }

    private static Point breakoutStep(Point botPos, int dir) {
        return new Point(botPos.x + dir * BotCombatManager.cfg.RANGED_RETREAT_DISTANCE_X, botPos.y);
    }

    /**
     * Choose which way to burst out of a surrounding swarm. Base preference is the more open
     * side (the flank whose nearest mob is farther). When the nav graph is available, override
     * with the side whose reachable walkable neighbor is less crowded, so the bot runs toward
     * an exit instead of into a dead-end wall. Ties fall back to the open-side preference.
     */
    private static int pickBreakoutDirection(BotEntry entry, Point botPos, Point combatTargetPos) {
        Character bot = entry.bot;
        int base = BotAttackExecutionProvider.pickRetreatDirection(bot, botPos, combatTargetPos);
        MapleMap map = bot != null ? bot.getMap() : null;
        if (map == null || map.getFootholds() == null) {
            return base;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph == null) {
            return base;
        }
        int botRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        if (botRegionId < 0) {
            return base;
        }
        int leftBestMobs = Integer.MAX_VALUE;
        int rightBestMobs = Integer.MAX_VALUE;
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(botRegionId)) {
            if (edge.type != BotNavigationGraph.EdgeType.WALK) {
                continue;
            }
            BotNavigationGraph.Region region = graph.getRegion(edge.toRegionId);
            if (region == null || region.isRopeRegion) {
                continue;
            }
            int side = Integer.signum(edge.endPoint.x - botPos.x);
            int mobs = countMobsInRegion(graph, map, region);
            if (side < 0) {
                leftBestMobs = Math.min(leftBestMobs, mobs);
            } else if (side > 0) {
                rightBestMobs = Math.min(rightBestMobs, mobs);
            }
        }
        if (leftBestMobs == rightBestMobs) {
            return base;
        }
        return leftBestMobs < rightBestMobs ? -1 : 1;
    }

    /**
     * Pick a one-edge-away region to retreat into, preferring regions that are NOT
     * the target's region and contain the fewest mobs. Returns the edge's landing
     * point so nav can route through the existing edge-traversal pipeline. Null
     * means no separated region qualifies — caller falls back to in-region retreat.
     *
     * Scoring is simple by design (easy to eyeball in-game):
     *   +1000  destination region has zero live mobs ("the empty platform next door")
     *   -100*N each live mob in the destination region
     *   -dx/10  prefer anchors closer to the target so projectiles still land
     */
    static Point selectCrossRegionRetreatTarget(BotEntry entry, Point botPos, Point combatTargetPos) {
        if (entry == null || botPos == null || combatTargetPos == null) {
            return null;
        }
        if (entry.climbing || entry.inAir || entry.navEdge != null) {
            return null;
        }
        Character bot = entry.bot;
        MapleMap map = bot != null ? bot.getMap() : null;
        if (map == null || map.getFootholds() == null) {
            return null;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
            return null;
        }

        int botRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        if (botRegionId < 0) {
            return null;
        }
        int targetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, combatTargetPos);

        int projectileRange = BotCombatManager.CLIENT_PROJECTILE_BASE_RANGE
                + BotCombatManager.passiveProjectileRangeBonus(bot);
        int yReachable = BotCombatManager.cfg.RANGED_DEGENERATE_RANGE_Y * 2;

        Point reachableRetreat = selectReachableProjectileRetreatTarget(
                graph, map, botPos, botRegionId, targetRegionId, combatTargetPos, projectileRange, yReachable);
        if (reachableRetreat != null) {
            return reachableRetreat;
        }

        BotNavigationGraph.Edge bestEdge = null;
        int bestScore = Integer.MIN_VALUE;
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(botRegionId)) {
            if (edge.type != BotNavigationGraph.EdgeType.WALK) {
                continue;
            }
            int toRegionId = edge.toRegionId;
            if (toRegionId == botRegionId || toRegionId == targetRegionId) {
                continue;
            }
            BotNavigationGraph.Region region = graph.getRegion(toRegionId);
            if (region == null || region.isRopeRegion) {
                continue;
            }
            Point anchor = edge.endPoint;
            int dx = Math.abs(anchor.x - combatTargetPos.x);
            int dy = Math.abs(anchor.y - combatTargetPos.y);
            if (dx > projectileRange || dy > yReachable) {
                continue;
            }
            // Don't land back inside the degenerate band — that defeats the retreat.
            if (dx <= BotCombatManager.cfg.RANGED_DEGENERATE_RANGE_X) {
                continue;
            }

            int mobsInRegion = countMobsInRegion(graph, map, region);
            int score = (mobsInRegion == 0 ? 1000 : 0) - mobsInRegion * 100 - dx / 10;
            if (score > bestScore) {
                bestScore = score;
                bestEdge = edge;
            }
        }

        return bestEdge != null ? new Point(bestEdge.endPoint) : null;
    }

    private static Point selectReachableProjectileRetreatTarget(BotNavigationGraph graph,
                                                                MapleMap map,
                                                                Point botPos,
                                                                int botRegionId,
                                                                int targetRegionId,
                                                                Point combatTargetPos,
                                                                int projectileRange,
                                                                int yReachable) {
        Point bestPoint = null;
        int bestScore = Integer.MIN_VALUE;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region == null || region.isRopeRegion) {
                continue;
            }
            if (region.id == botRegionId || region.id == targetRegionId) {
                continue;
            }

            Point candidate = selectProjectileRetreatPoint(region, combatTargetPos, projectileRange, yReachable);
            if (candidate == null) {
                continue;
            }

            List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                    graph, map, botPos, botRegionId, region.id, candidate);
            if (path.isEmpty() || pathUsesPortal(path)) {
                continue;
            }

            int pathCost = path.stream().mapToInt(pathEdge -> pathEdge.cost).sum();
            int mobsInRegion = countMobsInRegion(graph, map, region);
            int dx = Math.abs(candidate.x - combatTargetPos.x);
            int score = (mobsInRegion == 0 ? 1500 : 0) - mobsInRegion * 150 - pathCost / 10 - dx / 10;
            if (score > bestScore) {
                bestScore = score;
                bestPoint = candidate;
            }
        }
        return bestPoint;
    }

    private static boolean pathUsesPortal(List<BotNavigationGraph.Edge> path) {
        for (BotNavigationGraph.Edge edge : path) {
            if (edge.type == BotNavigationGraph.EdgeType.PORTAL) {
                return true;
            }
        }
        return false;
    }

    private static Point selectProjectileRetreatPoint(BotNavigationGraph.Region region,
                                                      Point combatTargetPos,
                                                      int projectileRange,
                                                      int yReachable) {
        int edgeMargin = Math.min(BotMovementManager.cfg.GRIND_EDGE_MARGIN, Math.max(0, region.width() / 4));
        int minX = Math.max(region.minX + edgeMargin, combatTargetPos.x - projectileRange);
        int maxX = Math.min(region.maxX - edgeMargin, combatTargetPos.x + projectileRange);
        if (minX > maxX) {
            minX = Math.max(region.minX, combatTargetPos.x - projectileRange);
            maxX = Math.min(region.maxX, combatTargetPos.x + projectileRange);
        }
        if (minX > maxX) {
            return null;
        }

        int minShootDx = BotCombatManager.cfg.RANGED_DEGENERATE_RANGE_X + 20;
        int bestScore = Integer.MIN_VALUE;
        Point bestPoint = null;
        int[] probes = {
                minX,
                maxX,
                combatTargetPos.x - minShootDx,
                combatTargetPos.x + minShootDx,
                (minX + maxX) / 2
        };
        for (int probe : probes) {
            Point candidate = projectileRetreatCandidate(region, probe, minX, maxX,
                    combatTargetPos, projectileRange, yReachable, minShootDx);
            if (candidate == null) {
                continue;
            }
            int dx = Math.abs(candidate.x - combatTargetPos.x);
            int dy = Math.abs(candidate.y - combatTargetPos.y);
            int score = -dx * 10 - dy;
            if (score > bestScore) {
                bestScore = score;
                bestPoint = candidate;
            }
        }
        return bestPoint;
    }

    private static Point projectileRetreatCandidate(BotNavigationGraph.Region region,
                                                    int probeX,
                                                    int minX,
                                                    int maxX,
                                                    Point combatTargetPos,
                                                    int projectileRange,
                                                    int yReachable,
                                                    int minShootDx) {
        int x = Math.max(minX, Math.min(maxX, probeX));
        if (x < combatTargetPos.x && combatTargetPos.x - x < minShootDx) {
            x = combatTargetPos.x - minShootDx;
        } else if (x > combatTargetPos.x && x - combatTargetPos.x < minShootDx) {
            x = combatTargetPos.x + minShootDx;
        } else if (x == combatTargetPos.x) {
            int leftX = combatTargetPos.x - minShootDx;
            int rightX = combatTargetPos.x + minShootDx;
            x = leftX >= minX ? leftX : rightX;
        }
        if (x < minX || x > maxX) {
            return null;
        }

        Point point = region.pointAt(x);
        int dx = Math.abs(point.x - combatTargetPos.x);
        int dy = Math.abs(point.y - combatTargetPos.y);
        if (dx < minShootDx
                || dx > projectileRange
                || dy > yReachable
                || point.y > combatTargetPos.y + BotMovementManager.cfg.JUMP_Y_THRESH) {
            return null;
        }
        return point;
    }

    private static int countMobsInRegion(BotNavigationGraph graph,
                                         MapleMap map,
                                         BotNavigationGraph.Region region) {
        int count = 0;
        for (server.life.Monster m : map.getAllMonsters()) {
            if (!m.isAlive()) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null) {
                continue;
            }
            // Cheap bbox prefilter — region.findRegionId does foothold lookup, more expensive.
            if (mp.x < region.minX - 5 || mp.x > region.maxX + 5
                    || mp.y < region.minY - 80 || mp.y > region.maxY + 80) {
                continue;
            }
            if (graph.findRegionId(map, mp) == region.id) {
                count++;
            }
        }
        return count;
    }

    static boolean shouldUseLocalCombatRetreatTarget(BotEntry entry,
                                                     Point botPos,
                                                     Point combatTargetPos,
                                                     Point retreatPos) {
        if (entry == null || botPos == null || combatTargetPos == null || retreatPos == null) {
            return false;
        }
        if (entry.climbing || entry.inAir || entry.navEdge != null) {
            return false;
        }

        Character bot = entry.bot;
        MapleMap map = bot != null ? bot.getMap() : null;
        if (map == null || map.getFootholds() == null) {
            return false;
        }

        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
            return false;
        }
        int botRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        int combatTargetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, combatTargetPos);
        if (botRegionId < 0 || combatTargetRegionId < 0 || botRegionId != combatTargetRegionId) {
            return false;
        }

        int retreatRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, retreatPos);
        return retreatRegionId == botRegionId;
    }

    static boolean shouldSearchForGrindTarget(BotEntry entry,
                                              Character bot,
                                              Monster currentTarget,
                                              BotCombatManager.AttackPlan currentAttackPlan,
                                              long now) {
        if (entry == null) {
            return false;
        }
        if (currentTarget == null) {
            return true;
        }
        if (now < entry.nextGrindTargetSearchAtMs) {
            return false;
        }
        if (bot == null
                || currentAttackPlan == null
                || !BotCombatManager.isTargetInAttackRange(currentAttackPlan, bot, currentTarget)) {
            return true;
        }
        // In range we normally stay committed (avoids flip-flop). Exception: an AoE bot stuck
        // single-targeting keeps scanning for a better cluster — the switch itself is gated by
        // cluster-size hysteresis in shouldSwitchToSearchedTarget.
        return BotCombatManager.isAoeBotSingleTargeting(entry, currentAttackPlan);
    }

    /**
     * Decide whether to adopt a freshly searched grind target over the current one. Always adopts
     * when not committed (current null, no plan, or current out of attack range). When committed to
     * an in-range target, only switches if the searched target anchors a strictly larger AoE cluster
     * — hysteresis that prevents flip-flop between near-equal targets.
     */
    static boolean shouldSwitchToSearchedTarget(BotEntry entry, Character bot, Monster current,
                                                Monster searched, BotCombatManager.AttackPlan currentPlan) {
        if (searched == null || searched == current) {
            return false;
        }
        if (current == null || bot == null || currentPlan == null
                || !BotCombatManager.isTargetInAttackRange(currentPlan, bot, current)) {
            return true;
        }
        return BotCombatManager.aoeClusterSize(entry, bot, searched)
                > BotCombatManager.aoeClusterSize(entry, bot, current);
    }

    static Point resolveNoGrindTargetPosition(BotEntry entry, Point botPos, MapleMap map) {
        if (entry == null || botPos == null) {
            return botPos;
        }
        if (entry.grindLootTarget != null) {
            Point lootPos = activeGrindLootPosition(entry, botPos);
            if (lootPos != null) {
                return lootPos;
            }
        }

        BotNavigationGraph graph = map != null ? BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile) : null;
        int regionId = graph != null ? BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos) : -1;
        BotNavigationGraph.Region region = graph != null ? graph.getRegion(regionId) : null;
        if (region != null && !region.isRopeRegion && region.width() > 0) {
            Point wander = entry.patrolWanderTarget;
            if (wander == null || isNear(botPos, wander, BotMovementManager.cfg.STOP_DIST)) {
                int x = ThreadLocalRandom.current().nextInt(region.minX, region.maxX + 1);
                wander = region.pointAt(x);
                entry.patrolWanderTarget = wander;
            }
            return wander;
        }

        entry.patrolWanderTarget = null;
        if (entry.wanderDirection == 0) {
            entry.wanderDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        }
        return new Point(botPos.x + entry.wanderDirection * 200, botPos.y);
    }

    static Point resolveNoGrindTargetPosition(BotEntry entry, Point botPos) {
        Character bot = entry != null ? entry.bot : null;
        MapleMap map = bot != null ? bot.getMap() : null;
        return resolveNoGrindTargetPosition(entry, botPos, map);
    }

    private static Point activeGrindLootPosition(BotEntry entry, Point botPos) {
        MapItem loot = entry.grindLootTarget;
        if (loot == null || botPos == null) {
            entry.grindLootTarget = null;
            return null;
        }
        Character bot = entry.bot;
        if (loot.isPickedUp() || bot == null || bot.getMap() == null
                || bot.getMap().getMapObject(loot.getObjectId()) != loot) {
            entry.grindLootTarget = null;
            return null;
        }
        if (!BotLootEligibility.canBotTargetLoot(entry, bot, bot.getMap(), loot, System.currentTimeMillis())) {
            entry.grindLootTarget = null;
            return null;
        }
        Point lootPos = loot.getPosition();
        if (Math.abs(lootPos.x - botPos.x) <= cfg.LOOT_RADIUS
                && Math.abs(lootPos.y - botPos.y) <= cfg.LOOT_RADIUS) {
            suppressGrindLootRetry(entry, loot);
            entry.grindLootTarget = null;
            return null;
        }
        return lootPos;
    }

    static void suppressGrindLootRetry(BotEntry entry, MapItem loot) {
        if (entry == null || loot == null) {
            return;
        }
        entry.ignoredGrindLootObjectId = loot.getObjectId();
        entry.ignoredGrindLootUntilMs = System.currentTimeMillis() + cfg.GRIND_LOOT_RETRY_SUPPRESS_MS;
    }

    static boolean isGrindLootRetrySuppressed(BotEntry entry, MapItem loot, long now) {
        if (entry == null || loot == null || entry.ignoredGrindLootObjectId <= 0) {
            return false;
        }
        if (now >= entry.ignoredGrindLootUntilMs) {
            entry.ignoredGrindLootObjectId = 0;
            entry.ignoredGrindLootUntilMs = 0L;
            return false;
        }
        return entry.ignoredGrindLootObjectId == loot.getObjectId();
    }

    static double activeLootTravelDistSq(Point botPos, Point lootPos) {
        if (botPos == null || lootPos == null) {
            return Double.MAX_VALUE;
        }
        int dx = Math.max(0, Math.abs(lootPos.x - botPos.x) - cfg.LOOT_RADIUS);
        int dy = Math.max(0, Math.abs(lootPos.y - botPos.y) - cfg.LOOT_RADIUS);
        return (double) dx * dx + (double) dy * dy;
    }

    static Point convenientLootTarget(BotEntry entry, Point botPos, Point mobPos) {
        Point lootPos = activeGrindLootPosition(entry, botPos);
        if (lootPos == null) {
            return null;
        }
        double lootDistSq = activeLootTravelDistSq(botPos, lootPos);
        double mobDistSq = mobPos.distanceSq(botPos);
        return lootDistSq < mobDistSq * cfg.GRIND_LOOT_CONVENIENCE_RATIO ? lootPos : null;
    }

    private static Point resolvePatrolWanderTarget(BotEntry entry, Point botPos, MapleMap map) {
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        BotNavigationGraph.Region region = graph != null ? graph.getRegion(entry.patrolRegionId) : null;
        if (region == null || region.isRopeRegion || region.width() == 0) {
            return resolveNoGrindTargetPosition(entry, botPos, map);
        }
        // Seek loot before roaming
        Point lootTarget = BotInventoryManager.findNearestPatrolLootTarget(entry, entry.patrolRegionId);
        if (lootTarget != null) {
            entry.patrolWanderTarget = lootTarget;
            return lootTarget;
        }
        // Roam within region
        Point wander = entry.patrolWanderTarget;
        if (wander == null || isNear(botPos, wander, BotMovementManager.cfg.STOP_DIST)) {
            int x = ThreadLocalRandom.current().nextInt(region.minX, region.maxX + 1);
            wander = region.pointAt(x);
            entry.patrolWanderTarget = wander;
        }
        return wander;
    }

    /**
     * True when the bot is physically standing in its patrol region. Gates roam-mode
     * opportunity attacks: while in-region the bot may fire at any mob in attack range
     * (including out-of-region ones), but if a knockback shoves it out of its region we
     * suppress OA so it paths home first instead of drifting away chasing stray mobs.
     */
    private static boolean isBotInPatrolRegion(BotEntry entry, Character bot, Point botPos) {
        if (entry.patrolRegionId < 0 || bot == null) {
            return false;
        }
        MapleMap map = bot.getMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (graph == null) {
            return false;
        }
        return BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos) == entry.patrolRegionId;
    }

    // Main tick
    // -------------------------------------------------------------------------

    private void tick(BotEntry entry, int ownerCharId, int botCharId) {
        long startedAt = System.nanoTime();
        BotPerformanceMonitor.beginTickTrace();
        try {
            tickCore(entry, ownerCharId, botCharId);
            resetBotTickFailures(entry);
        } catch (Throwable t) {
            handleBotTickFailure(entry, ownerCharId, botCharId, t);
        } finally {
            long elapsedNs = System.nanoTime() - startedAt;
            if (BotPerformanceMonitor.enabled()) {
                BotPerformanceMonitor.record("tick-total", elapsedNs);
            }
            BotPerformanceMonitor.noteTickStall(entry, elapsedNs);
            BotPerformanceMonitor.endTickTrace();
        }
    }

    /** Test-only hook: invokes {@link #runCommonTickSystems} on a caller-owned entry. */
    void runCommonTickSystemsForTest(BotEntry entry, Character bot, Character owner, boolean runAiTick) {
        runCommonTickSystems(entry, bot, owner, runAiTick);
    }

    /**
     * Test-only entry point that runs the full bot tick (including all common systems,
     * AI, navigation, and movement) against a {@link BotEntry} the caller already owns.
     * Bypasses the {@link #bots} registry lookup so harnesses can drive mocked bots
     * without going through {@code registerBotInternal}.
     */
    void runTickForTest(BotEntry entry) {
        if (entry == null || entry.bot == null) {
            return;
        }
        int botCharId = entry.bot.getId();
        int ownerCharId = entry.owner != null ? entry.owner.getId() : -1;
        long startedAt = BotPerformanceMonitor.enabled() ? System.nanoTime() : 0L;
        try {
            tickCore(entry, ownerCharId, botCharId);
        } catch (Throwable t) {
            log.warn("runTickForTest: tickCore threw for bot {}", entry.bot.getName(), t);
        } finally {
            if (startedAt != 0L) {
                BotPerformanceMonitor.record("tick-total", System.nanoTime() - startedAt);
            }
        }
    }

    private void tickCore(BotEntry entry, int ownerCharId, int botCharId) {
        if (entry == null) return;
        if (entry.airshowActive) return;
        if (entry.skipDelayMs > 0) {
            entry.skipDelayMs = BotMovementManager.tickDown(entry.skipDelayMs);
            return;
        }
        Character bot = entry.bot;

        // Guard: bot was removed from its map externally (e.g. a prior disconnect race).
        // Stop ticking and clean up rather than NPE-spamming TimerManager workers.
        if (bot.getMap() == null) {
            removeBotByCharId(botCharId);
            return;
        }

        // Heartbeat: keep the bot's lastPacket fresh and broadcast a standing-in-place
        // movement packet every 10 minutes so the server never considers the bot idle.
        // Covers all modes: idle, follow, and grind.
        long nowMs = System.currentTimeMillis();
        if (nowMs - entry.lastHeartbeatAtMs >= 600_000L) {
            entry.lastHeartbeatAtMs = nowMs;
            bot.getClient().updateLastPacket();
            BotMovementManager.broadcastMovement(entry);
        }

        BotOfferManager.expirePendingOffer(entry);
        boolean runAiTick = consumeAiTick(entry);
        entry.lastTickWasAi = runAiTick;
        entry.lastTickAtMs = System.currentTimeMillis();

        Character owner = resolveTickOwner(entry, ownerCharId);
        if (handleOwnerOfflineOrDead(entry, bot, owner, nowMs, ownerCharId)) {
            return;
        }

        // Dead state must run even when the owner is offline/null. Otherwise a bot
        // that dies just before owner disconnect stays dead because the owner-null
        // idle path below returns before respawn can fire.
        if (handleDeadTick(entry, bot, owner)) {
            return;
        }

        BotScrollManager.tickAutoScroll(entry, bot, nowMs);

        if (owner == null && !BotAutopilotManager.isActive(entry)) {
            entry.following = false;
            if (groundAfterMapChange(entry, bot)) {
                return;
            }
            // Owner-offline pass-through: when entry.moveTarget is set (currently by
            // the offline-town cluster, but any caller of issueMoveTo) the bot still
            // walks toward it. Same field and movement core as the player "here"
            // command — only the tick gate differs because the regular pipeline is
            // tightly coupled to `owner` for combat/follow/heal logic that's all
            // skipped here.
            if (entry.moveTarget != null) {
                tickStandaloneMoveTarget(entry, bot, runAiTick);
            } else {
                tickIdleEntry(entry, bot);
            }
            return;
        }
        if (owner == null) {
            // Explicitly-ordered autopilot keeps playing while the owner is offline (until
            // handleOwnerOfflineOrDead's hard limit warps it home). The pipeline below is
            // owner-null tolerant: snapshot/follow/recovery/common systems all guard.
            entry.following = false;
        }

        BotMovementManager.refreshMovementProfile(entry);

        Point botPos = bot.getPosition();
        Character followAnchor = resolveFollowAnchor(entry, owner);
        TargetSnapshot targetSnapshot = captureTargetSnapshot(entry);
        Point ownerPos = targetSnapshot.rawOwnerPos();
        updateObservedOwnerMotion(entry, ownerPos);
        entry.lastOwnerPos = new Point(ownerPos); // raw owner pos before formation offset/snap — used by path logger
        clearFarmAnchorOnMapChange(entry, bot);
        clearPatrolOnMapChange(entry, bot);
        Point targetPos = targetSnapshot.primaryTargetPos();
        boolean perf = BotPerformanceMonitor.enabled();
        clearFollowActionMoveWindowIfSettled(entry, botPos, targetSnapshot);

        // These run in all modes (idle, follow, grind)
        long tCommonTrace = BotPerformanceMonitor.startStallPhase();
        if (runCommonTickSystems(entry, bot, owner, runAiTick)) {
            BotPerformanceMonitor.recordStallPhase("tick-common-systems", tCommonTrace);
            return;
        }
        BotPerformanceMonitor.recordStallPhase("tick-common-systems", tCommonTrace);

        // Trade window open: keep physics consistent (gravity / swim / idle stance) but
        // do not issue any movement input — no follow, grind, attack, teleport, or shop visit.
        // Prevents the bot from wandering away or auto-equipping while the player is mid-trade.
        if (bot.getTrade() != null) {
            if (!perf) {
                tickTradePhysicsOnly(entry, bot);
            } else {
                long tTrade = System.nanoTime();
                try { tickTradePhysicsOnly(entry, bot); }
                finally { BotPerformanceMonitor.record("tick-trade-physics", System.nanoTime() - tTrade); }
            }
            return;
        }

        boolean idleConsumed;
        if (!perf) {
            idleConsumed = tickIdleEntry(entry, bot);
        } else {
            long tIdle = System.nanoTime();
            idleConsumed = tickIdleEntry(entry, bot);
            BotPerformanceMonitor.record("tick-idle", System.nanoTime() - tIdle);
        }
        if (idleConsumed) {
            return;
        }

        // On any map change (legacy warp landing, travel portal hop, NPC-triggered portal):
        // rebuild footholds, reset physics, and snap to ground BEFORE any follow/warp/recovery
        // decision runs — none of those may act on stale footholds or a half-landed position.
        if (entry.lastMapId != bot.getMapId()) {
            if (!perf) {
                entry.fhIndex  = BotMovementManager.buildFhIndex(bot.getMap());
                entry.lastMapId = bot.getMapId();
                Point cur = bot.getPosition();
                Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
                BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : cur);
                BotMovementManager.resetEntryStateAfterTeleport(entry);
                BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
                BotMovementManager.broadcastMovement(entry);
                if (BotPqHooks.requiresGrind(entry, bot)) { issueGrind(entry); }
                else if (BotPqHooks.requiresFollow(entry, bot) && entry.owner != bot) { issueFollowOwner(entry); }
                else { entry.kpq.stage5Claimed = false; } // left KPQ — reset for next run
                BotShopManager.onMapChange(entry, bot);
                BotChatManager.checkBotStatus(entry, bot);
            } else {
                long tMapChange = System.nanoTime();
                try {
                    entry.fhIndex  = BotMovementManager.buildFhIndex(bot.getMap());
                    entry.lastMapId = bot.getMapId();
                    Point cur = bot.getPosition();
                    Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
                    BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : cur);
                    BotMovementManager.resetEntryStateAfterTeleport(entry);
                    BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
                    BotMovementManager.broadcastMovement(entry);
                    if (BotPqHooks.requiresGrind(entry, bot)) { issueGrind(entry); }
                    else if (BotPqHooks.requiresFollow(entry, bot) && entry.owner != bot) { issueFollowOwner(entry); }
                    else { entry.kpq.stage5Claimed = false; } // left KPQ — reset for next run
                    BotShopManager.onMapChange(entry, bot);
                    BotChatManager.checkBotStatus(entry, bot);
                } finally {
                    BotPerformanceMonitor.record("tick-map-change", System.nanoTime() - tMapChange);
                }
            }
            return;
        }

        // Autopilot: owner-ordered independent play. Consumes the tick while walking a
        // travel hop toward its chosen grind map; on site it lets the grind flow run.
        long tAutopilotTrace = BotPerformanceMonitor.startStallPhase();
        if (BotAutopilotManager.tick(entry, bot, runAiTick)) {
            BotPerformanceMonitor.recordStallPhase("tick-autopilot", tAutopilotTrace);
            return;
        }
        BotPerformanceMonitor.recordStallPhase("tick-autopilot", tAutopilotTrace);

        // Map change and teleport checks only apply when following a live anchor.
        // Shop visits are intentional same-map detours and must not be pulled back
        // to the owner while walking to the NPC.
        if (!entry.shopVisitPending && syncFollowMap(entry, bot, followAnchor, runAiTick)) {
            return;
        }
        if (recoverGrindPartyTeleportDistance(entry, bot, followAnchor)) {
            return;
        }
        // Teleport if hopelessly far — applies to both follow and grind (catches falling off map)
        if (recoverTeleportDistance(entry, bot, targetPos)) {
            return;
        }

        // Shop visit: navigate to approach point before resuming normal flow.
        // Keep this ahead of follow/combat/grind logic so resupply movement is not
        // coupled to owner proximity.
        if (entry.shopVisitPending) {
            boolean consumed;
            if (!perf) {
                consumed = BotShopManager.tickShopVisit(entry, bot);
            } else {
                long tShop = System.nanoTime();
                consumed = BotShopManager.tickShopVisit(entry, bot);
                BotPerformanceMonitor.record("tick-shop-visit", System.nanoTime() - tShop);
            }
            targetPos = entry.shopTargetPos != null ? entry.shopTargetPos : entry.shopNpcPos;
            if (!consumed && entry.shopApproachDelayMs > 0) {
                return;
            }
            if (targetPos != null) {
                stepMovementCore(entry, targetPos, runAiTick);
            }
            return;
        }

        // Follow mode: attack monsters already in attack range without chasing
        if (entry.following && runAiTick && !entry.climbing
                && followAnchor != null
                && bot.getMapId() == followAnchor.getMapId()
                && Math.abs(botPos.x - followAnchor.getPosition().x) <= BotMovementManager.cfg.FOLLOW_DIST * 5) {
            LocalOpportunityAttackResult result;
            if (!perf) {
                result = tryLocalOpportunityAttack(
                        entry, bot, botPos, targetPos, targetSnapshot.followTargetPos(), true, true);
            } else {
                long tOpp = System.nanoTime();
                result = tryLocalOpportunityAttack(
                        entry, bot, botPos, targetPos, targetSnapshot.followTargetPos(), true, true);
                BotPerformanceMonitor.record("opportunity-attack", System.nanoTime() - tOpp);
            }
            targetPos = result.targetPos();
            if (result.consumedTick()) {
                return;
            }
        }

        if (tryFollowIdleMovementFastPath(entry, bot, targetPos, nowMs)) {
            return;
        }

        if (runAiTick && shouldUseScriptedMoveLocalCombat(entry, targetPos)) {
            clearActionMoveWindowIfSettled(entry, botPos, targetPos);
            LocalOpportunityAttackResult result;
            if (!perf) {
                result = tryLocalOpportunityAttack(
                        entry, bot, botPos, targetPos, targetPos, true, true);
            } else {
                long tOppS = System.nanoTime();
                result = tryLocalOpportunityAttack(
                        entry, bot, botPos, targetPos, targetPos, true, true);
                BotPerformanceMonitor.record("opportunity-attack", System.nanoTime() - tOppS);
            }
            if (result.consumedTick()) {
                return;
            }
            targetPos = result.targetPos();
            if (!perf) {
                stepMovementCore(entry, targetPos, runAiTick);
            } else {
                long tStep = System.nanoTime();
                try { stepMovementCore(entry, targetPos, runAiTick); }
                finally { BotPerformanceMonitor.record("step-movement-core", System.nanoTime() - tStep); }
            }
            return;
        }

        if (entry.farmAnchor != null) {
            if (!perf) {
                tickAnchoredFarm(entry, bot, botPos, runAiTick);
            } else {
                long tFarm = System.nanoTime();
                try { tickAnchoredFarm(entry, bot, botPos, runAiTick); }
                finally { BotPerformanceMonitor.record("tick-anchored-farm", System.nanoTime() - tFarm); }
            }
            return;
        }

        // Party-cohesion portal-anchored wait: the leader is holding in transit for stragglers,
        // loitering at the next-hop portal (opportunity-attacking, not chasing) so the group
        // reassembles there. The mapId guard self-clears the anchor once the leader changes
        // maps. tickPartyCohesion sets/clears autopilotWaitAnchor; combat keeps firing here.
        if (entry.autopilotWaitAnchor != null) {
            if (entry.autopilotWaitAnchorMapId == bot.getMapId()) {
                loiterAtAnchor(entry, bot, botPos, new Point(entry.autopilotWaitAnchor), runAiTick);
                return;
            }
            entry.autopilotWaitAnchor = null; // moved on (e.g. arrived) — drop the stale pin
            entry.autopilotWaitAnchorMapId = -1;
        }

        // Grind mode: navigate toward nearest monster, attack when in range
        if (entry.grinding) {
            LocalOpportunityAttackResult grindResult;
            if (!perf) {
                long tGrindTrace = BotPerformanceMonitor.startStallPhase();
                grindResult = tickGrindMode(entry, bot, botPos, targetPos, runAiTick);
                BotPerformanceMonitor.recordStallPhase("tick-grind-dispatch", tGrindTrace);
            } else {
                long tGrindDispatch = System.nanoTime();
                try {
                    grindResult = tickGrindMode(entry, bot, botPos, targetPos, runAiTick);
                } finally {
                    BotPerformanceMonitor.record("tick-grind-dispatch", System.nanoTime() - tGrindDispatch);
                }
            }
            if (grindResult.consumedTick()) {
                return;
            }
            targetPos = grindResult.targetPos();
        }

        if (!perf) {
            long tStepTrace = BotPerformanceMonitor.startStallPhase();
            stepMovementCore(entry, targetPos, runAiTick);
            BotPerformanceMonitor.recordStallPhase("step-movement-core", tStepTrace);
        } else {
            long tStepTail = System.nanoTime();
            try { stepMovementCore(entry, targetPos, runAiTick); }
            finally { BotPerformanceMonitor.record("step-movement-core", System.nanoTime() - tStepTail); }
        }
    }

    /**
     * Grind-mode decision pipeline. Returns a consumed result (caller returns immediately, any
     * required movement already issued) or a fall-through result carrying the resolved targetPos
     * for the shared stepMovementCore tail. Single source of truth shared by the perf and non-perf
     * dispatch arms in the bot tick.
     */
    private LocalOpportunityAttackResult tickGrindMode(BotEntry entry, Character bot, Point botPos,
            Point targetPos, boolean runAiTick) {
        double seekRangeSq = (double) BotCombatManager.cfg.GRIND_SEEK_RANGE * BotCombatManager.cfg.GRIND_SEEK_RANGE;
        Monster target = entry.grindTarget;
        if (target == null || !target.isAlive()
                || target.getMap() != bot.getMap()
                || target.getPosition().distanceSq(botPos) > seekRangeSq) {
            target = null;
        }
        long now = System.currentTimeMillis();
        BotCombatManager.AttackPlan attackPlan = target == null
                ? null
                : BotCombatManager.planAttack(entry, bot, target);
        // Validate cached loot target
        if (entry.grindLootTarget != null) {
            MapItem loot = entry.grindLootTarget;
            if (loot.isPickedUp() || bot.getMap().getMapObject(loot.getObjectId()) != loot) {
                entry.grindLootTarget = null;
            }
        }
        if (runAiTick && shouldSearchForGrindTarget(entry, bot, target, attackPlan, now)) {
            Monster searchedTarget = entry.patrolRegionId >= 0
                    ? BotCombatManager.findPatrolTarget(entry, bot)
                    : BotCombatManager.findGrindTarget(entry, bot);
            if (shouldSwitchToSearchedTarget(entry, bot, target, searchedTarget, attackPlan)) {
                target = searchedTarget;
                attackPlan = null;
            }
            entry.nextGrindTargetSearchAtMs = now + BotCombatManager.cfg.GRIND_RETARGET_INTERVAL_MS;
        }
        // Search for a convenient loot drop every AI tick (grind mode only)
        if (runAiTick && entry.patrolRegionId < 0) {
            entry.grindLootTarget = BotInventoryManager.findNearestGrindLootTarget(entry, bot);
        }
        if (target == null) {
            entry.grindTarget = null;
            if (isSwimMap(entry) && entry.inAir) {
                BotMovementManager.tickSwimming(entry, targetPos);
                return new LocalOpportunityAttackResult(true, targetPos);
            } else if (entry.inAir) {
                BotMovementManager.tickAirborne(entry, targetPos);
                return new LocalOpportunityAttackResult(true, targetPos);
            } else {
                // Patrol mode: prefer an opportunity shot over wandering. As long as we're
                // standing inside our own patrol region, fire at any mob already in attack
                // range — even one sitting outside the region — instead of idling. If a
                // knockback pushed us out of the region, skip OA so the wander logic paths
                // us back home first.
                if (entry.patrolRegionId >= 0 && isBotInPatrolRegion(entry, bot, botPos)) {
                    LocalOpportunityAttackResult oa = tryLocalOpportunityAttack(
                            entry, bot, botPos, botPos, botPos, true, true);
                    if (oa.consumedTick()) {
                        return oa;
                    }
                    if (oa.targetPos() != botPos) {
                        // OA found a mob but needs ranged spacing — move to the spacing point
                        // instead of wandering.
                        stepMovementCore(entry, oa.targetPos(), runAiTick);
                        return new LocalOpportunityAttackResult(true, oa.targetPos());
                    }
                }
                // No mob in seek range — pick a wander direction once and walk that way until
                // a mob enters range. Beats standing still and lets the bot self-relocate.
                if (entry.wanderDirection == 0) {
                    entry.wanderDirection = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                }
                targetPos = new Point(botPos.x + entry.wanderDirection * 200, botPos.y);
                // falls through to stepMovementCore below
            }
        }
        if (target == null) {
            targetPos = entry.patrolRegionId >= 0
                    ? resolvePatrolWanderTarget(entry, botPos, bot.getMap())
                    : resolveNoGrindTargetPosition(entry, botPos, bot.getMap());
            stepMovementCore(entry, targetPos, runAiTick);
            return new LocalOpportunityAttackResult(true, targetPos);
        }
        entry.grindTarget = target;
        entry.wanderDirection = 0;
        entry.patrolWanderTarget = null;
        Point tp = target.getPosition();
        Monster rangedPriorityTarget = selectPriorityRangedAttackTarget(entry, bot, botPos, target);
        if (rangedPriorityTarget != null && rangedPriorityTarget != target) {
            target = rangedPriorityTarget;
            entry.grindTarget = rangedPriorityTarget;
            tp = target.getPosition();
            attackPlan = null;
        }
        // Crowding swap: if a closer mob is breaching the retreat band, attack THAT mob
        // instead of fleeing the original far target. The bot would have retreated either
        // way (the close mob also triggers retreat), but with the right target our shots
        // land on the actual threat instead of pointing at the far one.
        server.life.Monster closerThreat = rangedPriorityTarget == null
                ? BotAttackExecutionProvider.findCloserThreatMob(bot, botPos, tp)
                : null;
        if (closerThreat != null && closerThreat != target) {
            target = closerThreat;
            entry.grindTarget = closerThreat;
            tp = target.getPosition();
            attackPlan = null;
        }
        if (attackPlan == null) {
            attackPlan = BotCombatManager.planAttack(entry, bot, target);
        }
        WeaponType grindWeaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean targetInDegenerateBand = BotAttackExecutionProvider.shouldDegenerateRangedAttack(grindWeaponType, botPos, tp);
        boolean allowOneDegenerateAttack = targetInDegenerateBand && !entry.degenAttackDone && rangedPriorityTarget == null;
        boolean shouldRetreatForRangedSpacing = entry.degenAttackDone
                || (BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(grindWeaponType, botPos, tp)
                && !allowOneDegenerateAttack);
        // Opportunity attack: keep firing during retreat as long as the shot would land
        // as a true ranged hit. Suppress only inside the degenerate band, since firing
        // there would re-trigger degenAttackDone and extend the retreat indefinitely.
        boolean canFireWithoutDegen = grindWeaponType == null
                || !BotAttackExecutionProvider.shouldDegenerateRangedAttack(grindWeaponType, botPos, tp);
        boolean attackGateOpen = !shouldRetreatForRangedSpacing || canFireWithoutDegen || allowOneDegenerateAttack;
        // Sticky cross-region retreat: pre-compute so an opportunity attack doesn't stall
        // the traversal — bot fires AND keeps walking toward the safe vantage in the same tick.
        Point crossRegionRetreatPos = shouldRetreatForRangedSpacing
                ? selectCrossRegionRetreatTarget(entry, botPos, tp)
                : null;
        // AoE positioning: when in range but the chosen plan is single-target, defer the shot
        // and walk into the cluster centroid if the AoE would beat it on DPS there (bounded).
        // Suppressed during ranged-spacing/cross-region retreats — spacing takes priority.
        Point aoeRepositionPos = (!shouldRetreatForRangedSpacing && crossRegionRetreatPos == null
                && attackGateOpen && BotCombatManager.isTargetInAttackRange(attackPlan, bot, target))
                ? resolveAoeReposition(entry, bot, target, attackPlan, botPos)
                : null;

        boolean attackAttemptedInRange = false;
        if (!entry.climbing) {
            if (aoeRepositionPos == null
                    && attackGateOpen && BotCombatManager.isTargetInAttackRange(attackPlan, bot, target)
                    && BotCombatManager.canUseAttackPlanNow(entry, grindWeaponType, attackPlan)) {
                attackAttemptedInRange = true;
                // In range — attack if grounded, or during ascent of a jump
                int prevCooldown = entry.attackCooldownMs;
                BotCombatManager.attackMonster(entry, bot, attackPlan);
                boolean attacked = entry.attackCooldownMs != prevCooldown;
                // If a ranged bot just did a degenerate close-range hit, force retreat next tick
                if (attacked && attackPlan.isCloseRangeRoute()
                        && BotCombatManager.isRangedAmmoWeapon(grindWeaponType)) {
                    entry.degenAttackDone = true;
                }
                // Don't short-circuit when a cross-region retreat is in progress — the
                // bot must still walk to the edge launch this tick.
                if (attacked && !entry.inAir && crossRegionRetreatPos == null) {
                    return new LocalOpportunityAttackResult(true, targetPos);
                }
            } else if (!entry.inAir
                    && attackPlan != null
                    && BotCombatManager.isTargetJumpable(entry.movementProfile, attackPlan.isCloseRangeRoute(), botPos, tp)
                    && grindWeaponType != WeaponType.BOW && grindWeaponType != WeaponType.CROSSBOW
                    && grindWeaponType != WeaponType.WAND && grindWeaponType != WeaponType.STAFF) {
                // Target is above but within jump height — jump toward it
                BotMovementManager.initiateJump(entry, bot, tp.x - botPos.x);
                return new LocalOpportunityAttackResult(true, targetPos);
            }
        }
        // Stand still when in attack range and no retreat is needed. Walking toward the
        // mob during cooldown drops dx into the retreat band, triggering a walk-away the
        // next tick — produces a tight (~100 ms) left-right oscillation when the bot is
        // already in firing position.
        if (target != null && !entry.inAir && !entry.climbing
                && !shouldRetreatForRangedSpacing && crossRegionRetreatPos == null
                && aoeRepositionPos == null
                && !attackAttemptedInRange
                && BotCombatManager.isTargetInAttackRange(attackPlan, bot, target)) {
            BotPhysicsEngine.idleOnGround(entry, bot);
            BotMovementManager.broadcastMovement(entry);
            return new LocalOpportunityAttackResult(true, targetPos);
        }
        // Retreat positioning is a local combat adjustment, not an inter-region path target.
        // Feeding a synthetic same-Y retreat point into nav while the monster is elsewhere
        // can make rope/ladder bots path back onto the nearby foothold instead of toward
        // the monster's actual region.
        targetPos = crossRegionRetreatPos != null
                ? crossRegionRetreatPos
                : aoeRepositionPos != null
                ? selectGrindNavigationTarget(entry, botPos, aoeRepositionPos)
                : selectGrindNavigationTarget(entry, botPos, tp, shouldRetreatForRangedSpacing);
        // Clear only once the bot has physically left the retreat zone, not after the
        // first retreat tick — otherwise the flag resets while the bot is still overlapping
        // and allowOneDegenerateAttack re-opens the attack gate next tick.
        if (entry.degenAttackDone
                && !BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(grindWeaponType, botPos, tp)) {
            entry.degenAttackDone = false;
        }
        // Small detour: take a very close loot drop on the way when not retreating.
        if (crossRegionRetreatPos == null && !shouldRetreatForRangedSpacing
                && aoeRepositionPos == null && entry.patrolRegionId < 0) {
            Point lootPos = convenientLootTarget(entry, botPos, tp);
            if (lootPos != null) targetPos = lootPos;
        }
        return new LocalOpportunityAttackResult(false, targetPos);
    }

    private void handleBotTickFailure(BotEntry entry, int ownerCharId, int botCharId, Throwable t) {
        if (entry == null) {
            log.error("Bot tick failed for missing entry ownerCharId={} botCharId={}", ownerCharId, botCharId, t);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - entry.tickFailureWindowStartedAtMs > BOT_TICK_FAILURE_WINDOW_MS) {
            entry.tickFailureWindowStartedAtMs = now;
            entry.tickFailureCount = 0;
        }
        entry.tickFailureCount++;

        Character bot = entry.bot;
        Character owner = entry.owner;
        String botName = bot != null ? bot.getName() : "?";
        String ownerName = owner != null ? owner.getName() : "?";
        int mapId = bot != null ? bot.getMapId() : -1;

        clearBotVolatileActions(entry);
        if (entry.tickFailureCount >= BOT_TICK_FAILURE_LIMIT) {
            log.error("Disabling bot '{}' after {} tick failures within {} ms (owner={}, map={}, grinding={}, following={})",
                    botName, entry.tickFailureCount, BOT_TICK_FAILURE_WINDOW_MS, ownerName, mapId,
                    entry.grinding, entry.following, t);
            removeBotByCharId(botCharId);
            return;
        }

        if (entry.tickFailureCount == 2) {
            forceBotIdleAfterTickFailure(entry);
        }

        log.warn("Bot '{}' tick failed {}/{} (owner={}, map={}, grinding={}, following={})",
                botName, entry.tickFailureCount, BOT_TICK_FAILURE_LIMIT, ownerName, mapId,
                entry.grinding, entry.following, t);
    }

    private static void resetBotTickFailures(BotEntry entry) {
        if (entry == null || entry.tickFailureCount == 0) {
            return;
        }
        entry.tickFailureCount = 0;
        entry.tickFailureWindowStartedAtMs = 0L;
    }

    private static void clearBotVolatileActions(BotEntry entry) {
        entry.pendingAction = null;
        entry.pendingDropCategory = null;
        entry.grindTarget = null;
        entry.grindLootTarget = null;
        entry.patrolWanderTarget = null;
        BotMovementManager.resetEntryStateAfterTeleport(entry);
    }

    private void forceBotIdleAfterTickFailure(BotEntry entry) {
        issueStop(entry);
        try {
            botReply(entry, "unrecoverable error caught, idling");
        } catch (Throwable chatError) {
            Character bot = entry.bot;
            log.warn("Failed to send bot failure idle message for '{}'",
                    bot != null ? bot.getName() : "?", chatError);
        }
    }

    static Monster selectPriorityRangedAttackTarget(BotEntry entry,
                                                   Character bot,
                                                   Point botPos,
                                                   Monster preferredTarget) {
        if (entry == null || entry.noAmmo || bot == null || botPos == null) {
            return null;
        }

        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (!BotCombatManager.isRangedAmmoWeapon(weaponType)) {
            return null;
        }
        if (isNonDegenerateRangedAttackTarget(entry, bot, botPos, weaponType, preferredTarget)) {
            return preferredTarget;
        }

        Monster best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Monster candidate : bot.getMap().getAllMonsters()) {
            if (candidate == preferredTarget) {
                continue;
            }
            if (!isNonDegenerateRangedAttackTarget(entry, bot, botPos, weaponType, candidate)) {
                continue;
            }
            double distanceSq = candidate.getPosition().distanceSq(botPos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isNonDegenerateRangedAttackTarget(BotEntry entry,
                                                            Character bot,
                                                            Point botPos,
                                                            WeaponType weaponType,
                                                            Monster target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        Point targetPos = target.getPosition();
        if (BotAttackExecutionProvider.shouldDegenerateRangedAttack(weaponType, botPos, targetPos)) {
            return false;
        }
        BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, target);
        return plan != null
                && plan.route == BotCombatManager.AttackRoute.RANGED
                && BotCombatManager.isTargetInAttackRange(plan, bot, target)
                && BotCombatManager.canUseAttackPlanNow(entry, weaponType, plan);
    }

    private void tickAnchoredFarm(BotEntry entry, Character bot, Point botPos, boolean runAiTick) {
        if (entry.farmAnchor == null || entry.farmAnchorMapId != bot.getMapId()) {
            clearFarmAnchorOnMapChange(entry, bot);
            tickIdleEntry(entry, bot);
            return;
        }
        loiterAtAnchor(entry, bot, botPos, new Point(entry.farmAnchor), runAiTick);
    }

    /**
     * Hold a fixed spot and opportunity-attack: fire at any mob already in attack range
     * (no chasing — {@code false, false}), otherwise walk back toward {@code anchor} and idle
     * within 8px of it. Shared by the "farm this spot" command (BotEntry.farmAnchor) and the
     * party-cohesion portal-anchored wait, so both loiter identically without a target re-impl.
     */
    void loiterAtAnchor(BotEntry entry, Character bot, Point botPos, Point anchor, boolean runAiTick) {
        if (runAiTick) {
            LocalOpportunityAttackResult attackResult = tryLocalOpportunityAttack(
                    entry, bot, botPos, anchor, anchor, false, false);
            if (attackResult.consumedTick()) {
                return;
            }
        }

        if (isNear(botPos, anchor, 8) && !entry.inAir && !entry.climbing) {
            entry.moveTarget = null;
            entry.moveTargetPrecise = false;
            BotPhysicsEngine.idleOnGround(entry, bot);
            BotMovementManager.broadcastMovement(entry);
            return;
        }

        entry.moveTarget = anchor;
        entry.moveTargetPrecise = true;
        stepMovementCore(entry, anchor, runAiTick);
    }

    private static boolean shouldUseScriptedMoveLocalCombat(BotEntry entry, Point targetPos) {
        if (entry == null || targetPos == null || entry.activeScriptTask == null) {
            return false;
        }
        if (entry.activeScriptTask.type != BotTask.Type.MOVE_TO
                || entry.activeScriptTask.moveCombatMode != BotTask.MoveCombatMode.LOCAL_OPPORTUNITY) {
            return false;
        }
        if (entry.moveTarget == null || !entry.moveTarget.equals(entry.activeScriptTask.point)) {
            return false;
        }
        return !entry.following;
    }

    private LocalOpportunityAttackResult tryLocalOpportunityAttack(BotEntry entry,
                                                                  Character bot,
                                                                  Point botPos,
                                                                  Point movementTargetPos,
                                                                  Point moveWindowReferencePos,
                                                                  boolean allowCombatMovement,
                                                                  boolean allowJumpTowardTarget) {
        Point targetPos = movementTargetPos;
        if (entry.noAmmo || bot == null || botPos == null) {
            return new LocalOpportunityAttackResult(false, targetPos);
        }

        Monster localTarget = BotCombatManager.findFollowAttackTarget(entry, bot);
        if (localTarget == null) {
            return new LocalOpportunityAttackResult(false, targetPos);
        }

        Point localTargetPos = localTarget.getPosition();
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean shouldRetreat = allowCombatMovement
                && (entry.degenAttackDone
                || BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(weaponType, botPos, localTargetPos)
                || BotAttackExecutionProvider.isAnyMobNearerThanTarget(bot, botPos, localTargetPos));
        if (shouldRetreat) {
            entry.degenAttackDone = false;
            return new LocalOpportunityAttackResult(
                    false, selectGrindNavigationTarget(entry, botPos, localTargetPos));
        }

        BotCombatManager.AttackPlan attackPlan = BotCombatManager.planAttack(entry, bot, localTarget);
        if (attackPlan == null) {
            return new LocalOpportunityAttackResult(false, targetPos);
        }
        if (entry.inAir) {
            if (BotCombatManager.canUseAttackPlanNow(entry, weaponType, attackPlan)
                    && BotCombatManager.isTargetInAttackRange(attackPlan, bot, localTarget)) {
                BotCombatManager.attackMonster(entry, bot, attackPlan);
                if (allowCombatMovement && attackPlan.isCloseRangeRoute()
                        && BotCombatManager.isRangedAmmoWeapon(weaponType)) {
                    entry.degenAttackDone = true;
                }
            }
            return new LocalOpportunityAttackResult(false, targetPos);
        }

        if (allowJumpTowardTarget
                && weaponType != WeaponType.BOW && weaponType != WeaponType.CROSSBOW
                && weaponType != WeaponType.WAND && weaponType != WeaponType.STAFF
                && BotCombatManager.isTargetJumpable(entry.movementProfile, true, botPos, localTargetPos)) {
            BotMovementManager.initiateJump(entry, bot, localTargetPos.x - botPos.x);
            return new LocalOpportunityAttackResult(true, targetPos);
        }

        if (entry.moveWindowMs <= 0 && BotCombatManager.isTargetInAttackRange(attackPlan, bot, localTarget)) {
            BotCombatManager.attackMonster(entry, bot, attackPlan);
            setLocalAttackMoveWindow(entry, botPos, moveWindowReferencePos);
            if (allowCombatMovement && attackPlan.isCloseRangeRoute()
                    && BotCombatManager.isRangedAmmoWeapon(weaponType)) {
                entry.degenAttackDone = true;
            }
            return new LocalOpportunityAttackResult(!entry.inAir, targetPos);
        }

        return new LocalOpportunityAttackResult(false, targetPos);
    }

    private static void setLocalAttackMoveWindow(BotEntry entry, Point botPos, Point referencePos) {
        if (botPos == null || referencePos == null) {
            entry.moveWindowMs = 0;
            return;
        }
        int dx = Math.abs(botPos.x - referencePos.x);
        entry.moveWindowMs = dx > BotMovementManager.cfg.FOLLOW_DIST * 3 ? 1000
                           : dx > BotMovementManager.cfg.FOLLOW_DIST     ? 200
                           : 0;
        clearActionMoveWindowIfSettled(entry, botPos, referencePos);
    }

    private static void clearFollowActionMoveWindowIfSettled(BotEntry entry,
                                                             Point botPos,
                                                             TargetSnapshot targetSnapshot) {
        if (entry == null || !entry.following || targetSnapshot == null) {
            return;
        }
        clearActionMoveWindowIfSettled(entry, botPos, targetSnapshot.followTargetPos());
    }

    private static void clearActionMoveWindowIfSettled(BotEntry entry,
                                                       Point botPos,
                                                       Point targetPos) {
        if (entry == null || entry.moveWindowMs <= 0 || botPos == null || targetPos == null) {
            return;
        }

        int followStopBand = Math.max(BotMovementManager.cfg.STOP_DIST, BotMovementManager.cfg.FOLLOW_DIST);
        if (Math.abs(botPos.x - targetPos.x) <= followStopBand
                && Math.abs(botPos.y - targetPos.y) <= BotMovementManager.cfg.FOLLOW_Y_CAP) {
            entry.moveWindowMs = 0;
        }
    }

    private Character resolveTickOwner(BotEntry entry, int ownerCharId) {
        Character owner = entry.owner;
        if (owner == null || owner.getId() != ownerCharId || !owner.isLoggedinWorld()) {
            owner = Server.getInstance()
                    .getWorld(entry.bot.getWorld())
                    .getPlayerStorage()
                    .getCharacterById(ownerCharId);
            entry.owner = owner;
        }
        return owner;
    }

    /**
     * If the owner has been offline or dead for >= 5 minutes, scroll/warp the
     * bot to the nearest town and idle there. Prevents pot drain, death-loops
     * with no anchor, and silent grinding while the owner is unable to leech.
     *
     * Returns true when a town warp was performed this tick (caller should
     * short-circuit; map-change reset runs on the next tick).
     */
    private boolean handleOwnerOfflineOrDead(BotEntry entry, Character bot, Character owner, long nowMs, int ownerCharId) {
        boolean inactive = (owner == null) || (owner.getHp() <= 0);

        if (!inactive) {
            if (entry.ownerAwaySafeMode && entry.ownerOfflineOrDeadSinceMs == 0) {
                return false;
            }
            if (entry.ownerOfflineOrDeadSinceMs != 0 || entry.ownerReturnedToTown) {
                boolean justReturnedFromTown = entry.ownerReturnedToTown;
                entry.ownerOfflineOrDeadSinceMs = 0;
                entry.ownerReturnedToTown = false;
                entry.ownerAwaySafeMode = false;
                // Cancel any in-flight cluster walk: while owner was offline the
                // only setter of moveTarget was the offline-town path, so clearing
                // here is safe and avoids stale state when owner reconnects.
                entry.moveTarget = null;
                entry.moveTargetPrecise = false;
                // Race-safe single-representative wb: each bot tries to remove the
                // group's anchor entry; only the winner (first to observe owner
                // active) speaks. Other bots find the anchor already cleared and
                // skip the announcement, so the party sees one wb line, not N.
                Point removedAnchor = townClusterAnchors.remove(ownerCharId);
                if (justReturnedFromTown && removedAnchor != null) {
                    BotChatManager.announceOwnerReturnedFromOffline(entry);
                }
            }
            return false;
        }

        if (entry.ownerReturnedToTown) {
            if (entry.ownerAwaySafeMode && entry.ownerOfflineOrDeadSinceMs == 0) {
                entry.ownerOfflineOrDeadSinceMs = nowMs;
            }
            return false;
        }

        if (entry.ownerOfflineOrDeadSinceMs == 0) {
            entry.ownerOfflineOrDeadSinceMs = nowMs;
            return false;
        }

        // Normal bots take the town safe mode after 5 min. A bot the owner explicitly ordered
        // independent (autopilot) keeps playing until the hard offline limit, then stops too.
        long inactiveLimitMs = BotAutopilotManager.isActive(entry)
                ? cfg.AUTOPILOT_OWNER_OFFLINE_LIMIT_MS
                : cfg.OWNER_INACTIVE_TOWN_RETURN_MS;
        if (nowMs - entry.ownerOfflineOrDeadSinceMs < inactiveLimitMs) {
            return false;
        }

        return enterOwnerInactiveSafeMode(entry, bot, ownerCharId, shouldTownWarpForOwnerInactive(entry));
    }

    private boolean shouldTownWarpForOwnerInactive(BotEntry entry) {
        MapleMap currentMap = entry != null && entry.bot != null ? entry.bot.getMap() : null;
        return currentMap != null
                && currentMap.getAllMonsters().stream().anyMatch(Monster::isAlive)
                && canReturnToDifferentMap(currentMap);
    }

    private static boolean canReturnToDifferentMap(MapleMap currentMap) {
        if (currentMap == null) {
            return false;
        }
        MapleMap returnMap = currentMap.getReturnMap();
        return returnMap != null && returnMap.getId() != currentMap.getId();
    }

    public boolean shouldOfferTownForAwayCommand(BotEntry entry) {
        return shouldTownWarpForOwnerInactive(entry);
    }

    public boolean isFirstBotEntry(BotEntry entry) {
        return entry != null
                && entry.owner != null
                && getFirstBotEntry(entry.owner.getId()) == entry;
    }

    public void issueOwnerAwaySafeModeForOwner(int ownerCharId, boolean town) {
        for (BotEntry entry : getBotEntries(ownerCharId)) {
            if (entry.bot == null || entry.bot.getMap() == null) {
                continue;
            }
            enterOwnerInactiveSafeMode(entry, entry.bot, ownerCharId,
                    town && shouldTownWarpForOwnerInactive(entry));
        }
    }

    private boolean enterOwnerInactiveSafeMode(BotEntry entry, Character bot, int ownerCharId, boolean town) {
        prepareOwnerInactiveIdle(entry, ownerCharId);
        if (town) {
            return scrollBotToTown(entry, bot, ownerCharId);
        }

        BotPhysicsEngine.idleOnGround(entry, bot);
        BotMovementManager.broadcastMovement(entry);
        entry.ownerReturnedToTown = true;
        return false;
    }

    private void prepareOwnerInactiveIdle(BotEntry entry, int ownerCharId) {
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        clearMode(entry);
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.grindTarget = null;
        entry.degenAttackDone = false;
        entry.buffConsumablesEnabled = false;
        entry.ownerAwaySafeMode = true;
    }

    private boolean scrollBotToTown(BotEntry entry, Character bot, int ownerCharId) {
        MapleMap currentMap = bot.getMap();
        if (currentMap == null) {
            return false;
        }
        MapleMap returnMap = currentMap.getReturnMap();
        if (returnMap == null || returnMap.getId() == currentMap.getId()) {
            // No return map (e.g. some PQ/town maps): mark handled to avoid re-evaluating every tick.
            entry.ownerReturnedToTown = true;
            return false;
        }

        BotPhysicsEngine.idleOnGround(entry, bot);

        // Every bot uses the same path: applyTo handles scroll-consume + random
        // portal warp. Falls back to a plain changeMap when the bot has no scroll.
        if (!tryUseReturnScroll(bot)) {
            bot.changeMap(returnMap);
        }
        groundAfterMapChange(entry, bot);

        // Capture the cluster anchor at the first bot's post-warp position, then let
        // every bot walk to the deterministic formation slot around that anchor. This
        // mirrors the owner's current follow formation instead of stacking everyone on
        // the same portal landing point.
        Point post = new Point(bot.getPosition());
        Point anchor = townClusterAnchors.putIfAbsent(ownerCharId, post);
        if (anchor == null) {
            anchor = post;
        }
        Point target = resolveTownClusterTarget(entry, ownerCharId, returnMap, anchor);

        BotMovementManager.resetEntryState(entry);
        startMoveTo(entry, target, true);
        entry.ownerReturnedToTown = true;
        return true;
    }

    private Point resolveTownClusterTarget(BotEntry entry, int ownerCharId, MapleMap map, Point anchor) {
        Point base = anchor != null ? new Point(anchor) : new Point(entry.bot.getPosition());
        if (entry == null || entry.bot == null || map == null) {
            return base;
        }

        List<BotEntry> entries = getBotEntries(ownerCharId);
        int idx = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) {
                idx = i;
                break;
            }
        }

        FormationState formation = ownerFormations.getOrDefault(ownerCharId, FormationState.defaultStagger());
        int offsetX = formation.offsetFor(idx, Math.max(1, entries.size()));
        int targetX = base.x + offsetX;

        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph != null) {
            int anchorRegionId = graph.findRegionId(map, base);
            BotNavigationGraph.Region anchorRegion = graph.getRegion(anchorRegionId);
            if (anchorRegion != null && !anchorRegion.isRopeRegion) {
                int edgeMargin = PLATFORM_EDGE_INSET_PX;
                int minX = anchorRegion.minX;
                int maxX = anchorRegion.maxX;
                if (maxX - minX > 2 * edgeMargin) {
                    minX += edgeMargin;
                    maxX -= edgeMargin;
                }
                int clampedX = Math.max(minX, Math.min(maxX, targetX));
                return anchorRegion.pointAt(clampedX);
            }
        }

        Rectangle area = map.getMapArea();
        int minX = area != null ? area.x : targetX;
        int maxX = area != null ? area.x + area.width : targetX;
        if (map.getFootholds() != null && map.getFootholds().getMinDropX() < map.getFootholds().getMaxDropX()) {
            minX = map.getFootholds().getMinDropX();
            maxX = map.getFootholds().getMaxDropX();
        }

        int clampedX = Math.max(minX, Math.min(maxX, targetX));
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(clampedX, base.y - 1));
        if (ground != null) {
            return ground;
        }

        Point anchorGround = BotPhysicsEngine.findGroundPoint(map, new Point(base.x, base.y - 1));
        return anchorGround != null ? anchorGround : base;
    }

    /**
     * Public hook: tell a bot to walk to a fixed point using the same field and
     * pipeline as the player "here" command. No owner reference required, so it
     * works for bots whose owner is offline (e.g. owner-inactive town clustering)
     * as well as any future code-driven "go here" feature.
     *
     * Movement runs through the regular tick when the owner is online, and
     * through tickStandaloneMoveTarget when the owner is offline. Arrival is
     * detected by the existing clearReachedMoveTarget logic.
     */
    public void issueMoveTo(BotEntry entry, Point dest, boolean precise) {
        if (entry == null || dest == null) {
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startMoveTo(entry, dest, precise);
    }

    private void startMoveTo(BotEntry entry, Point dest, boolean precise) {
        clearMode(entry);
        entry.moveTarget = new Point(dest);
        entry.moveTargetPrecise = precise;
    }

    public void issueFarmHere(BotEntry entry, Point dest) {
        if (entry == null || dest == null || entry.bot == null) {
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startFarmHere(entry, dest);
    }

    private void startFarmHere(BotEntry entry, Point dest) {
        // Sentry/farm-here is an active combat mode that just anchors to a fixed spot.
        // Route through the shared active-mode reset so it stays in lock-step with
        // grind/patrol (self-buff, pot-share, ammo-low, "low on pots" fallback all
        // gate on entry.grinding — see kb feedback_bot_coding_guidelines).
        enterActiveMode(entry);
        entry.farmAnchor = new Point(dest);
        entry.farmAnchorMapId = entry.bot.getMapId();
        entry.moveTarget = new Point(dest);
        entry.moveTargetPrecise = true;
    }

    public void issuePatrol(BotEntry entry, Point ownerPos) {
        if (entry == null || ownerPos == null || entry.bot == null) {
            return;
        }
        MapleMap map = entry.bot.getMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        int regionId = graph != null ? graph.findRegionId(map, ownerPos) : -1;
        if (regionId < 0) {
            botReply(entry, "can't find a patrol region here");
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startPatrol(entry, regionId);
    }

    private void startPatrol(BotEntry entry, int regionId) {
        enterActiveMode(entry);
        entry.patrolRegionId = regionId;
        entry.patrolMapId = entry.bot.getMapId();
        entry.patrolWanderTarget = null;
    }

    /**
     * Public hook: return the bot to ordinary owner-follow mode. Scripted map
     * automation and chat commands should use this instead of writing mode
     * fields directly.
     */
    public void issueFollowOwner(BotEntry entry) {
        issueFollow(entry, entry != null ? entry.owner : null);
    }

    /**
     * Public hook: follow a concrete party/member/bot target. Passing the owner
     * (or null) means regular owner-follow.
     */
    public void issueFollow(BotEntry entry, Character target) {
        if (entry == null) {
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startFollow(entry, target);
    }

    private void startFollow(BotEntry entry, Character target) {
        BotAutopilotManager.clear(entry);
        Character owner = entry.owner;
        entry.followTargetId = owner != null && target != null && owner.getId() != target.getId()
                ? target.getId()
                : 0;
        entry.grinding = false;
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.farmAnchor = null;
        entry.farmAnchorMapId = -1;
        entry.following = true;
    }

    /**
     * Public hook: enter autonomous grind/combat mode using the same setup as
     * player chat commands. This deliberately clears fixed movement and follow
     * targets so scripted "grind" steps do not run in parallel with a stale
     * navigation command.
     */
    public void issueGrind(BotEntry entry) {
        if (entry == null) {
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startGrind(entry);
    }

    private void startGrind(BotEntry entry) {
        enterActiveMode(entry);
    }

    /**
     * Shared baseline for all active combat modes (grind / sentry / patrol). Sets
     * {@code grinding = true} and zeros out follow, move, and sub-mode state so
     * each mode starts from the same baseline. Mode-specific fields (anchor /
     * patrol region) are set by the caller after this returns.
     *
     * When adding a new active mode, route it through this helper to avoid the
     * sentry-mode regression where {@code grinding=false} silently disabled the
     * pot-share, self-buff, and ammo-low fallback paths.
     */
    private void enterActiveMode(BotEntry entry) {
        // Owner-issued grind/sentry/patrol replaces autopilot. BotAutopilotManager.start
        // relies on this ordering: it calls issueGrind first, then sets its destination.
        BotAutopilotManager.clear(entry);
        enterActiveModeCore(entry);
    }

    /**
     * Re-enter grind combat WITHOUT clearing autopilot state: used when a party-autopilot
     * follower arrives at the group destination and swaps from transit-follow back to grind.
     */
    void resumeAutopilotGrind(BotEntry entry) {
        enterActiveModeCore(entry);
    }

    private void enterActiveModeCore(BotEntry entry) {
        entry.followTargetId = 0;
        entry.following = false;
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.farmAnchor = null;
        entry.farmAnchorMapId = -1;
        entry.patrolRegionId = -1;
        entry.patrolMapId = -1;
        entry.patrolWanderTarget = null;
        entry.grindTarget = null;
        entry.grindLootTarget = null;
        entry.nextGrindTargetSearchAtMs = 0L;
        entry.moveWindowMs = 0;
        entry.degenAttackDone = false;
        entry.retreatHoldUntilMs = 0L;
        entry.retreatHoldPos = null;
        entry.wanderDirection = 0;
        BotMovementManager.clearNavigationState(entry);
        entry.grinding = true;
    }

    /** Public hook: stop all scripted movement/combat mode and idle in place. */
    public void issueStop(BotEntry entry) {
        if (entry == null) {
            return;
        }
        clearScriptTasks(entry);
        BotShopManager.cancelShopVisit(entry);
        startStop(entry);
    }

    private void startStop(BotEntry entry) {
        clearMode(entry);
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
    }

    /**
     * Public hook for map scripts: drop up to {@code quantity} from the first
     * stack of {@code itemId}. Use {@code quantity <= 0} to drop the whole stack.
     */
    public boolean issueDropItem(BotEntry entry, InventoryType type, int itemId, short quantity) {
        if (entry == null || entry.bot == null || type == null) {
            return false;
        }
        var inventory = entry.bot.getInventory(type);
        if (inventory == null) {
            return false;
        }
        Item item = inventory.findById(itemId);
        if (item == null || item.getQuantity() <= 0) {
            return false;
        }
        short dropQuantity = quantity <= 0 ? item.getQuantity() : (short) Math.min(quantity, item.getQuantity());
        InventoryManipulator.drop(entry.bot.getClient(), type, item.getPosition(), dropQuantity);
        return true;
    }

    public void clearScriptTasks(BotEntry entry) {
        if (entry == null) {
            return;
        }
        entry.activityEpoch++;   // signal background batches (Maker craft/disassembly) to self-interrupt
        entry.scriptTasks.clear();
        entry.activeScriptTask = null;
    }

    public void queueTask(BotEntry entry, BotTask task) {
        if (entry == null || task == null) {
            return;
        }
        entry.scriptTasks.add(task);
    }

    public void queueMoveTo(BotEntry entry, Point point, boolean precise) {
        queueTask(entry, BotTask.moveTo(point, precise));
    }

    public void queueMoveTo(BotEntry entry, Point point, boolean precise, BotTask.MoveCombatMode moveCombatMode) {
        queueTask(entry, BotTask.moveTo(point, precise, moveCombatMode));
    }

    public void queueMoveThenDropItem(BotEntry entry, Point point, boolean precise, InventoryType type, int itemId, short quantity) {
        queueTask(entry, BotTask.moveTo(point, precise));
        queueTask(entry, BotTask.dropItem(type, itemId, quantity));
    }

    public void queueFollowThenDropItem(BotEntry entry, Character target, int nearPx, InventoryType type, int itemId, short quantity) {
        queueTask(entry, BotTask.followUntilNear(target, nearPx));
        queueTask(entry, BotTask.dropItem(type, itemId, quantity));
    }

    public boolean hasQueuedTasks(BotEntry entry) {
        return entry != null && (entry.activeScriptTask != null || !entry.scriptTasks.isEmpty());
    }

    public boolean isCheapScriptMoveTarget(BotEntry entry,
                                           Point targetPos,
                                           int maxPathCost,
                                           int fallbackRangeX,
                                           int fallbackRangeY) {
        if (entry == null || entry.bot == null || targetPos == null) {
            return false;
        }

        Character bot = entry.bot;
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return false;
        }
        if (Math.abs(targetPos.x - botPos.x) <= cfg.LOOT_RADIUS
                && Math.abs(targetPos.y - botPos.y) <= cfg.LOOT_RADIUS) {
            return false;
        }

        MapleMap map = bot.getMap();
        if (map == null || map.getFootholds() == null) {
            return false;
        }

        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (graph == null) {
            return Math.abs(targetPos.x - botPos.x) <= fallbackRangeX
                    && Math.abs(targetPos.y - botPos.y) <= fallbackRangeY;
        }

        int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        int targetRegionId = BotNavigationManager.resolvePointTargetRegionId(graph, map, targetPos);
        if (startRegionId < 0 || targetRegionId < 0) {
            return false;
        }
        if (startRegionId == targetRegionId) {
            return true;
        }

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(graph, bot, startRegionId, targetRegionId, targetPos);
        if (path.isEmpty()) {
            return false;
        }

        int totalCost = 0;
        for (BotNavigationGraph.Edge edge : path) {
            totalCost += edge.cost;
            if (totalCost > maxPathCost) {
                return false;
            }
        }
        return true;
    }

    private static void clearMode(BotEntry entry) {
        BotAutopilotManager.clear(entry);
        entry.followTargetId = 0;
        entry.following = false;
        entry.grinding = false;
        entry.farmAnchor = null;
        entry.farmAnchorMapId = -1;
        entry.patrolRegionId = -1;
        entry.patrolMapId = -1;
        entry.patrolWanderTarget = null;
        entry.grindLootTarget = null;
    }

    private static boolean isNear(Point source, Point target, int dist) {
        return source != null && target != null
                && Math.abs(source.x - target.x) <= dist
                && Math.abs(source.y - target.y) <= dist;
    }

    private void tickScriptTasks(BotEntry entry) {
        if (entry == null || entry.bot == null) {
            return;
        }

        while (true) {
            if (entry.activeScriptTask == null) {
                entry.activeScriptTask = entry.scriptTasks.poll();
                if (entry.activeScriptTask == null) {
                    return;
                }
                startScriptTask(entry, entry.activeScriptTask);
            }

            if (!isScriptTaskComplete(entry, entry.activeScriptTask)) {
                return;
            }
            entry.activeScriptTask = null;
        }
    }

    private void startScriptTask(BotEntry entry, BotTask task) {
        switch (task.type) {
            case MOVE_TO -> startMoveTo(entry, task.point, task.precise);
            case FOLLOW_OWNER -> startFollow(entry, entry.owner);
            case FOLLOW_TARGET -> startFollow(entry, resolveFollowCharacterById(entry, task.targetCharacterId));
            case FOLLOW_UNTIL_NEAR -> startFollow(entry, resolveFollowCharacterById(entry, task.targetCharacterId));
            case GRIND -> startGrind(entry);
            case STOP -> startStop(entry);
            case DROP_ITEM -> issueDropItem(entry, task.inventoryType, task.itemId, task.quantity);
        }
    }

    private boolean isScriptTaskComplete(BotEntry entry, BotTask task) {
        return switch (task.type) {
            case MOVE_TO -> entry.moveTarget == null || isNear(entry.bot.getPosition(), task.point,
                    task.precise ? 8 : BotMovementManager.cfg.STOP_DIST);
            case FOLLOW_UNTIL_NEAR -> {
                Character target = resolveFollowCharacterById(entry, task.targetCharacterId);
                yield target != null
                        && entry.bot.getMapId() == target.getMapId()
                        && isNear(entry.bot.getPosition(), target.getPosition(), task.nearPx);
            }
            case FOLLOW_OWNER, FOLLOW_TARGET, GRIND, STOP, DROP_ITEM -> true;
        };
    }

    private Character resolveFollowCharacterById(BotEntry entry, int targetCharacterId) {
        if (entry == null || targetCharacterId <= 0) {
            return entry != null ? entry.owner : null;
        }
        if (entry.owner != null && entry.owner.getId() == targetCharacterId) {
            return entry.owner;
        }
        if (entry.owner != null && entry.owner.getParty() != null) {
            for (Character member : entry.owner.getPartyMembersOnline()) {
                if (member != null && member.getId() == targetCharacterId && member.isLoggedinWorld()) {
                    return member;
                }
            }
        }
        if (entry.owner != null) {
            for (BotEntry sibling : getBotEntries(entry.owner.getId())) {
                if (sibling.bot != null && sibling.bot.getId() == targetCharacterId && sibling.bot.isLoggedinWorld()) {
                    return sibling.bot;
                }
            }
        }
        return entry.owner;
    }

    /**
     * Apply Return Scroll - Nearest Town (item 2030000) via StatEffect.applyTo.
     * The standard scroll effect handles random-portal warp inside applyTo;
     * we only need to remove the consumable afterwards (mirrors ScrollHandler).
     * Returns false when no 2030000 is in the bot's USE inventory or applyTo failed.
     */
    boolean tryUseReturnScroll(Character bot) {
        var use = bot.getInventory(InventoryType.USE);
        if (use == null) {
            return false;
        }
        for (Item item : use.list()) {
            if (item == null || item.getQuantity() <= 0) {
                continue;
            }
            if (item.getItemId() != 2030000) {
                continue;
            }
            StatEffect effect;
            try {
                effect = ItemInformationProvider.getInstance().getItemEffect(2030000);
            } catch (Exception e) {
                return false;
            }
            if (effect == null || !effect.applyTo(bot)) {
                return false;
            }
            InventoryManipulator.removeFromSlot(bot.getClient(), InventoryType.USE, item.getPosition(), (short) 1, false);
            return true;
        }
        return false;
    }

    /**
     * Owner-offline tick path for entry.moveTarget — drives the bot to its
     * point using the same stepMovementCore as the regular pipeline. The
     * regular tick handles moveTarget when owner is online; this is the
     * minimal version for owner-null sessions (currently the offline-town
     * cluster walk). Arrival is auto-detected by clearReachedMoveTarget
     * inside stepMovementCore.
     */
    private void tickStandaloneMoveTarget(BotEntry entry, Character bot, boolean runAiTick) {
        // Just warped in (likely via applyTo): rebuild physics state once.
        if (groundAfterMapChange(entry, bot)) {
            return;
        }

        BotMovementManager.refreshMovementProfile(entry);
        stepMovementCore(entry, entry.moveTarget, runAiTick);
    }

    private boolean groundAfterMapChange(BotEntry entry, Character bot) {
        if (entry.lastMapId == bot.getMapId()) {
            return false;
        }

        entry.fhIndex = BotMovementManager.buildFhIndex(bot.getMap());
        entry.lastMapId = bot.getMapId();
        Point cur = bot.getPosition();
        Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
        BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : cur);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
        BotMovementManager.broadcastMovement(entry);
        return true;
    }

    private boolean handleDeadTick(BotEntry entry, Character bot, Character owner) {
        if (entry.deadUntil == 0 && bot.getHp() <= 0) {
            BotCombatManager.enterDeadState(entry, bot, false);
        }
        if (entry.deadUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() >= entry.deadUntil) {
            respawnBot(entry, bot, owner);
        }
        return true;
    }

    private boolean runCommonTickSystems(BotEntry entry, Character bot, Character owner, boolean runAiTick) {
        // Single source of truth for the subsystem sequence. Perf instrumentation is gated by
        // `perf` so the hot (monitor-disabled) path pays only cheap branch checks and never
        // allocates timing state, while the enabled path keeps every per-subsystem label.
        boolean perf = BotPerformanceMonitor.enabled();
        long t = perf ? System.nanoTime() : 0L;
        BotCombatManager.tickMobDamage(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-mob-damage", System.nanoTime() - t);
        if (bot.getHp() <= 0) {
            if (entry.deadUntil == 0) {
                BotCombatManager.enterDeadState(entry, bot, false);
            }
            return true;
        }
        if (perf) t = System.nanoTime();
        tickReleaseMonsterControl(bot);
        if (perf) BotPerformanceMonitor.record("common-release-mob", System.nanoTime() - t);
        // While a trade window is open, suppress passive loot pickup. pickupItem() runs on
        // this scheduler thread and races Trade.completeTrade()'s addFromDrop on the packet
        // thread: fitsInInventory() can pass, then this fills the last slot before addFromDrop
        // runs, and the silently-ignored false return loses the partner's item.
        // See memory/kb_bot_trade_dupe_loss_audit.md.
        if (bot.getTrade() == null) {
            if (perf) t = System.nanoTime();
            BotInventoryManager.tickPassiveLoot(entry, bot);
            if (perf) BotPerformanceMonitor.record("common-passive-loot", System.nanoTime() - t);
        }
        if (perf) t = System.nanoTime();
        long tPotionTrace = BotPerformanceMonitor.startStallPhase();
        BotPotionManager.tickPotionCheck(entry, bot);
        BotPerformanceMonitor.recordStallPhase("common-potion-check", tPotionTrace);
        if (perf) BotPerformanceMonitor.record("common-potion-check", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotPotionManager.tickPassiveRecovery(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-passive-recovery", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotBuildManager.checkLevelUp(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-build-levelup", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        if (owner != null) { // owner-null tick = autopilot playing while owner is offline
            BotChatManager.tickAfkCheck(entry, owner);
        }
        if (perf) BotPerformanceMonitor.record("common-afk-check", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotQuestManager.tickScan(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-quest-scan", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotGachaponManager.tickScan(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-gacha-scan", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotInventoryManager.tickTrade(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-trade", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        BotInventoryManager.tickManualTrade(entry, bot);
        if (perf) BotPerformanceMonitor.record("common-manual-trade", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        if (owner != null) { // PQ scripts need a live owner
            BotPqHooks.tick(entry, bot, owner);
        }
        if (perf) BotPerformanceMonitor.record("common-pq-hooks", System.nanoTime() - t);
        if (perf) t = System.nanoTime();
        tickScriptTasks(entry);
        if (perf) BotPerformanceMonitor.record("common-script-tasks", System.nanoTime() - t);
        if (BotPqHooks.isNpcLocked(entry)) {
            return true;
        }
        if (perf) t = System.nanoTime();
        BotCombatManager.tickActionLock(entry);
        if (perf) BotPerformanceMonitor.record("common-action-lock", System.nanoTime() - t);
        if (runAiTick) {
            if (perf) t = System.nanoTime();
            BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);
            if (perf) BotPerformanceMonitor.record("common-skill-cache", System.nanoTime() - t);
            // Support healing is top priority — runs before buffs so that a bot below the heal
            // threshold casts Heal before a rebuff uses up this tick's action window. If it fires,
            // entry.attackCooldownMs is set to the heal animation lock and tickActionLocked() will
            // return true, causing the caller to skip attack logic this tick.
            if (perf) t = System.nanoTime();
            BotCombatManager.tickSupportHealing(entry, bot);
            if (perf) BotPerformanceMonitor.record("common-support-heal", System.nanoTime() - t);
            if (perf) t = System.nanoTime();
            BotCombatManager.tickBuffs(entry, bot);
            if (perf) BotPerformanceMonitor.record("common-combat-buffs", System.nanoTime() - t);
            if (perf) t = System.nanoTime();
            BotBuffManager.tick(entry, bot);
            if (perf) BotPerformanceMonitor.record("common-buff-pots", System.nanoTime() - t);
        }
        return tickActionLocked(entry);
    }

    /**
     * Physics-only tick used while a trade window is open. Mirrors {@link #tickIdleEntry}'s
     * physics body but skips the active-mode early-return so gravity / swim / stance stay
     * consistent even if the bot was following or grinding when the trade started. Issues
     * no movement input (no follow, grind, teleport, shop visit, or attack).
     */
    private void tickTradePhysicsOnly(BotEntry entry, Character bot) {
        if (isSwimMap(entry) && entry.inAir && !entry.climbing) {
            BotMovementManager.tickSwimming(entry, null);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, null);
        } else if (!entry.climbing) {
            int expectedIdleStance = BotPhysicsEngine.resolveIdleGroundStance(entry);
            if (BotPhysicsEngine.resolveStance(entry) != expectedIdleStance
                    || bot.getStance() != expectedIdleStance) {
                BotPhysicsEngine.idleOnGround(entry, bot);
                BotMovementManager.broadcastMovement(entry);
            }
        }
    }

    private boolean tickIdleEntry(BotEntry entry, Character bot) {
        if (entry.following || entry.grinding || entry.moveTarget != null
                || entry.farmAnchor != null || entry.shopVisitPending) {
            return false;
        }
        if (isSwimMap(entry) && entry.inAir && !entry.climbing) {
            BotMovementManager.tickSwimming(entry, null);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, null);
        } else if (!entry.climbing) {
            int expectedIdleStance = BotPhysicsEngine.resolveIdleGroundStance(entry);
            if (BotPhysicsEngine.resolveStance(entry) != expectedIdleStance
                    || bot.getStance() != expectedIdleStance) {
                BotPhysicsEngine.idleOnGround(entry, bot);
                BotMovementManager.broadcastMovement(entry);
            }
        }
        return true;
    }

    private boolean syncFollowMap(BotEntry entry, Character bot, Character followAnchor, boolean runAiTick) {
        if (!entry.following || followAnchor == null || bot.getMapId() == followAnchor.getMapId()) {
            BotTravelManager.clear(entry);
            return false;
        }
        // Anchor is one portal hop away: walk to that portal and enter it legally like a
        // trailing player would. Multi-hop / no-portal / failed walks fall through to the warp.
        if (BotTravelManager.tickFollowTravel(entry, bot, followAnchor, runAiTick)) {
            return true;
        }
        // Ground against the anchor's actual position in their NEW map. The previously-passed
        // followTargetPos was computed from the bot's OLD map (foothold snaps, formation offsets),
        // so it could land off-map in the new map and cause far-away/OOB spawns.
        MapleMap targetMap = followAnchor.getMap();
        Point anchorPos = followAnchor.getPosition();
        Point spawn = BotPhysicsEngine.findGroundPoint(targetMap, new Point(anchorPos.x, anchorPos.y - 1));
        if (spawn == null) {
            spawn = anchorPos;
        }
        BotPhysicsEngine.idleOnGround(entry, bot);
        bot.changeMap(targetMap, spawn);
        BotMovementManager.resetEntryState(entry);
        return true;
    }

    private boolean recoverTeleportDistance(BotEntry entry, Character bot, Point targetPos) {
        Point botPos = bot.getPosition();
        int manhattan = Math.abs(botPos.x - targetPos.x) + Math.abs(botPos.y - targetPos.y);
        if (manhattan > BotMovementManager.cfg.TELEPORT_DIST) {
            return executeRecoveryTeleport(entry, bot, targetPos);
        }
        // Out-of-bounds recovery: airborne physics has no VR-bottom hard stop, so a bot that
        // slips below the floor (or past the side walls in rare cases) keeps free-falling
        // indefinitely until the 4000 Manhattan fallback catches it. That can leave the bot
        // out of the map for several seconds with the owner still on the same screen
        // (manhattan < 4000). Trigger a tighter teleport once we can prove the bot is
        // outside the map's VR rectangle.
        Rectangle area = bot.getMap() == null ? null : bot.getMap().getMapArea();
        if (area != null && area.width > 0 && area.height > 0
                && !area.contains(botPos)
                && manhattan > BotMovementManager.cfg.OOB_TELEPORT_DIST) {
            return executeRecoveryTeleport(entry, bot, targetPos);
        }
        return false;
    }

    private boolean recoverGrindPartyTeleportDistance(BotEntry entry, Character bot, Character partyAnchor) {
        if (entry == null || bot == null || partyAnchor == null || !entry.grinding || entry.shopVisitPending) {
            return false;
        }
        if (entry.moveTarget != null || entry.farmAnchor != null) {
            return false;
        }
        if (bot.getMap() == null || partyAnchor.getMap() != bot.getMap()) {
            return false;
        }

        Point botPos = bot.getPosition();
        Point anchorPos = partyAnchor.getPosition();
        if (botPos == null || anchorPos == null || !isInKnownMapBounds(bot.getMap(), anchorPos)) {
            return false;
        }

        int manhattan = Math.abs(botPos.x - anchorPos.x) + Math.abs(botPos.y - anchorPos.y);
        int multiplier = Math.max(1, cfg.GRIND_PARTY_TELEPORT_DIST_MULTIPLIER);
        if (manhattan > BotMovementManager.cfg.TELEPORT_DIST * multiplier) {
            return executeRecoveryTeleport(entry, bot, anchorPos);
        }

        Rectangle area = bot.getMap().getMapArea();
        if (hasKnownMapBounds(area)
                && !area.contains(botPos)
                && manhattan > BotMovementManager.cfg.OOB_TELEPORT_DIST * multiplier) {
            return executeRecoveryTeleport(entry, bot, anchorPos);
        }
        return false;
    }

    // ~1.5s of airborne ticks with zero motion before the watchdog teleports the bot out.
    private static final int AIR_STUCK_RECOVER_TICKS = 30;

    /**
     * Frozen-air watchdog: a falling/jumping bot's position must change every tick. Zero
     * motion while {@code inAir} means it is wall-pinned at a map edge with no foothold
     * below (the WALL collision re-pins the same point forever — see
     * logs/bot-nav/pathlog-Clawer-2026-06-10T094813.txt, stuck 3px under the OOB recovery
     * threshold). Regular stuck handling skips airborne bots, so catch it here and teleport
     * to the active goal's ground.
     */
    private static void tickFrozenAirborneWatchdog(BotEntry entry) {
        if (!entry.inAir || entry.climbing) {
            entry.airStuckTicks = 0;
            entry.airStuckX = Integer.MIN_VALUE;
            return;
        }
        Point pos = entry.bot.getPosition();
        if (pos.x != entry.airStuckX || pos.y != entry.airStuckY) {
            entry.airStuckTicks = 0;
            entry.airStuckX = pos.x;
            entry.airStuckY = pos.y;
            return;
        }
        if (++entry.airStuckTicks < AIR_STUCK_RECOVER_TICKS) {
            return;
        }
        entry.airStuckTicks = 0;
        entry.airStuckX = Integer.MIN_VALUE;

        Point goal = entry.moveTarget != null ? entry.moveTarget : entry.navTargetPos;
        if (goal == null && entry.bot.getMap() != null) {
            var portal = entry.bot.getMap().findClosestPortal(pos);
            goal = portal != null ? portal.getPosition() : null;
        }
        getInstance().executeRecoveryTeleport(entry, entry.bot, goal != null ? goal : pos);
    }

    private static boolean isInKnownMapBounds(MapleMap map, Point point) {
        Rectangle area = map == null ? null : map.getMapArea();
        return !hasKnownMapBounds(area) || area.contains(point);
    }

    private static boolean hasKnownMapBounds(Rectangle area) {
        return area != null && area.width > 0 && area.height > 0;
    }

    private boolean executeRecoveryTeleport(BotEntry entry, Character bot, Point targetPos) {
        Point spawn = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(targetPos.x, targetPos.y - 1));
        if (spawn == null) {
            spawn = targetPos;
        }
        BotPhysicsEngine.teleportTo(entry, bot, spawn);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        BotMovementManager.broadcastMovement(entry);
        return true;
    }

    boolean stepMovementOnly(BotEntry entry, long tickAtMs) {
        if (entry == null || entry.bot == null) {
            return false;
        }

        boolean runAiTick = consumeAiTick(entry);
        entry.lastTickWasAi = runAiTick;
        entry.lastTickAtMs = tickAtMs;

        TargetSnapshot targetSnapshot = captureTargetSnapshot(entry);
        Point ownerPos = targetSnapshot.rawOwnerPos();
        updateObservedOwnerMotion(entry, ownerPos);
        entry.lastOwnerPos = new Point(ownerPos);
        stepMovementOnly(entry, targetSnapshot.primaryTargetPos(), ownerPos, runAiTick);
        return runAiTick;
    }

    void stepMovementOnly(BotEntry entry,
                          Point targetPos,
                          Point ownerPos,
                          boolean runAiTick) {
        if (entry == null || entry.bot == null || targetPos == null) {
            return;
        }

        Character bot = entry.bot;
        Character owner = entry.owner;

        if (tickIdleEntry(entry, bot)) {
            return;
        }

        // Rebuild physics on map change BEFORE follow/warp/recovery decisions (see tickEntry).
        if (entry.lastMapId != bot.getMapId()) {
            entry.fhIndex  = BotMovementManager.buildFhIndex(bot.getMap());
            entry.lastMapId = bot.getMapId();
            Point cur = bot.getPosition();
            Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
            BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : cur);
            BotMovementManager.resetEntryStateAfterTeleport(entry);
            BotMovementManager.broadcastMovement(entry);
            BotShopManager.onMapChange(entry, bot);
            BotChatManager.checkBotStatus(entry, bot);
            return;
        }

        if (owner != null && !entry.shopVisitPending && syncFollowMap(entry, bot, owner, runAiTick)) {
            return;
        }
        Character followAnchor = resolveFollowAnchor(entry, owner);
        if (recoverGrindPartyTeleportDistance(entry, bot, followAnchor)) {
            return;
        }
        if (recoverTeleportDistance(entry, bot, targetPos)) {
            return;
        }

        // Shop visit: navigate to approach point before resuming normal flow.
        if (entry.shopVisitPending) {
            boolean consumed = BotShopManager.tickShopVisit(entry, bot);
            targetPos = entry.shopTargetPos != null ? entry.shopTargetPos : entry.shopNpcPos;
            if (!consumed && entry.shopApproachDelayMs > 0) {
                return;
            }
            if (targetPos != null) {
                stepMovementCore(entry, targetPos, runAiTick);
            }
            return;
        }

        if (tryFollowIdleMovementFastPath(entry, bot, targetPos, entry.lastTickAtMs)) {
            return;
        }

        stepMovementCore(entry, targetPos, runAiTick);
    }

    static boolean tryFollowIdleMovementFastPath(BotEntry entry, Character bot, Point targetPos, long nowMs) {
        if (!isFollowIdleMovementFastPathEligible(entry, bot, targetPos)) {
            return false;
        }

        if (entry.nextFollowIdleMovementCheckAtMs == 0L) {
            entry.nextFollowIdleMovementCheckAtMs = nowMs + 1000L;
        } else if (nowMs >= entry.nextFollowIdleMovementCheckAtMs) {
            entry.nextFollowIdleMovementCheckAtMs = nowMs + 1000L;
            return false;
        }

        entry.lastNavDecision = "idle-fast";
        entry.stuckMs = 0;
        entry.stuckCheckX = Integer.MIN_VALUE;
        return true;
    }

    private static boolean isFollowIdleMovementFastPathEligible(BotEntry entry, Character bot, Point targetPos) {
        if (entry == null || bot == null || targetPos == null) {
            return false;
        }
        if (!entry.following || entry.grinding || entry.moveTarget != null) {
            return false;
        }
        if (entry.inAir || entry.climbing || entry.downJumpPending || entry.graphWarmupFallback) {
            return false;
        }
        if (entry.navEdge != null || entry.navPreciseTarget || entry.fidgetMode != BotFidgetMode.NONE) {
            return false;
        }
        if (entry.shopVisitPending || entry.shopSequenceActive) {
            return false;
        }
        if (entry.wasMovingX || entry.moveDir != 0 || entry.movementVelX != 0 || entry.movementVelY != 0) {
            return false;
        }
        if (entry.observedOwnerStepX != 0 || entry.observedOwnerStepY != 0) {
            return false;
        }

        Point botPos = bot.getPosition();
        return Math.abs(targetPos.x - botPos.x) <= BotMovementManager.cfg.FOLLOW_DIST
                && Math.abs(targetPos.y - botPos.y) <= BotMovementManager.cfg.STOP_DIST;
    }

    void stepMovementCore(BotEntry entry,
                          Point targetPos,
                          boolean runAiTick) {
        BotNavigationManager.NavigationDirective navDirective = BotNavigationManager.resolveTarget(entry, targetPos, runAiTick);
        if (navDirective.consumedTick) {
            return;
        }

        Point steeringTarget = navDirective.targetPos;
        if (entry.moveTargetPrecise && entry.navEdge == null) {
            entry.navPreciseTarget = true;
        }
        if (BotFidgetManager.tryHandleTick(entry, steeringTarget, runAiTick)) {
            return;
        }

        tickMovementPhase(entry, steeringTarget, runAiTick);
        if (runAiTick && !entry.inAir && !entry.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(entry, targetPos);
        }
        tickStuckDetection(entry);
        clearReachedMoveTarget(entry);
    }

    private void tickMovementPhase(BotEntry entry, Point targetPos, boolean runAiTick) {
        if (entry.climbing) {
            BotMovementManager.tickClimbing(entry, targetPos, runAiTick);
        } else if (isSwimMap(entry) && entry.inAir) {
            BotMovementManager.tickSwimming(entry, targetPos);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, targetPos);
        } else {
            BotMovementManager.tickGrounded(entry, targetPos);
        }
    }

    private static boolean isSwimMap(BotEntry entry) {
        return entry.bot != null && entry.bot.getMap() != null && entry.bot.getMap().isSwim();
    }

    private void clearReachedMoveTarget(BotEntry entry) {
        if (entry.moveTarget == null) {
            return;
        }
        Point botPos = entry.bot.getPosition();
        int arrivalDist = entry.moveTargetPrecise ? 8 : BotMovementManager.cfg.STOP_DIST;
        if (Math.abs(botPos.x - entry.moveTarget.x) <= arrivalDist
                && Math.abs(botPos.y - entry.moveTarget.y) <= arrivalDist) {
            entry.moveTarget = null;
            entry.moveTargetPrecise = false;
        }
    }

    private static void updateObservedOwnerMotion(BotEntry entry, Point ownerPos) {
        if (entry == null || ownerPos == null) {
            return;
        }
        entry.observedOwnerStepX = entry.lastOwnerPos == null ? 0 : ownerPos.x - entry.lastOwnerPos.x;
        entry.observedOwnerStepY = entry.lastOwnerPos == null ? 0 : ownerPos.y - entry.lastOwnerPos.y;
    }

    private static void clearFarmAnchorOnMapChange(BotEntry entry, Character bot) {
        if (entry == null || bot == null || entry.farmAnchor == null) {
            return;
        }
        if (entry.farmAnchorMapId != bot.getMapId()) {
            entry.farmAnchor = null;
            entry.farmAnchorMapId = -1;
            if (entry.moveTargetPrecise) {
                entry.moveTarget = null;
                entry.moveTargetPrecise = false;
            }
        }
    }

    private static void clearPatrolOnMapChange(BotEntry entry, Character bot) {
        if (entry == null || bot == null || entry.patrolRegionId < 0) {
            return;
        }
        if (entry.patrolMapId != bot.getMapId()) {
            entry.patrolRegionId = -1;
            entry.patrolMapId = -1;
            entry.patrolWanderTarget = null;
        }
    }

    private static void tickStuckDetection(BotEntry entry) {
        if (!BotPerformanceMonitor.enabled()) {
            doStuckDetection(entry);
            return;
        }

        long startedAt = System.nanoTime();
        try {
            doStuckDetection(entry);
        } finally {
            BotPerformanceMonitor.record("stuck-detect", System.nanoTime() - startedAt);
        }
    }

    private static void doStuckDetection(BotEntry entry) {
        entry.unstuckCooldownMs = BotMovementManager.tickDown(entry.unstuckCooldownMs);

        tickFrozenAirborneWatchdog(entry);

        // Only detect/act while actively navigating — idling near owner is not stuck.
        if (entry.inAir || entry.climbing
                || entry.graphWarmupFallback
                || (entry.navEdge == null && entry.moveTarget == null)) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            return;
        }

        Point botPos = entry.bot.getPosition();
        if (entry.stuckCheckX == Integer.MIN_VALUE) {
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
            return;
        }

        boolean moved = Math.abs(botPos.x - entry.stuckCheckX) > 8
                || Math.abs(botPos.y - entry.stuckCheckY) > 8;
        if (moved) {
            entry.stuckMs = 0;
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
        } else {
            entry.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        }

        if (cfg.ENABLE_UNSTUCK && entry.stuckMs >= 500 && entry.unstuckCooldownMs == 0) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(entry);
        }
    }

    private boolean tickActionLocked(BotEntry entry) {
        if (entry.attackCooldownMs <= 0) {
            return false;
        }
        if (isSwimMap(entry) && entry.inAir && !entry.climbing) {
            BotMovementManager.tickSwimming(entry, null);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, null);
        } else if (!entry.climbing) {
            // Ground physics must keep ticking during attack lock so prior walk momentum decays
            // via friction, walk-offs trigger falls, and broadcastMovement updates stance from
            // WALK→STAND. Without this the client extrapolates the last walk packet for the
            // entire animation lock and the bot visibly "walks in place" until cooldown ends.
            // External forces (mob knockback) replace this state via applyAirKnockback /
            // beginKnockback before this tick runs, so passing null target is safe.
            BotMovementManager.tickGrounded(entry, null);
        }
        return true;
    }


    void reloginBot(int charId, int ownerCharId, int world, int channel) {
        Character owner = Server.getInstance()
                .getWorld(world)
                .getPlayerStorage()
                .getCharacterById(ownerCharId);
        if (owner == null) return; // owner logged off — skip

        try {
            Character botChar = loadOfflineBot(charId, world, channel);

            BotEntry entry = registerSpawnedBot(ownerCharId, owner, botChar);
            issueFollowOwner(entry);
            after(randMs(900, 1100), () -> {
                botSay(botChar, "back!!");
                botChar.changeFaceExpression(Emote.HAPPY.getValue());
            });
        } catch (SQLException e) {
            log.warn("reloginBot: failed to reload charId={}", charId, e);
        }
    }

    private static final List<String> RESPAWN_REPLIES = List.of(
            "ouch... omw back", "died lol, running back", "rip. be right back",
            "that hurt, coming back now", "welp. respawning, omw"
    );

    private void respawnBot(BotEntry entry, Character bot, Character owner) {
        entry.deadUntil = 0;
        if (BotAutopilotManager.isActive(entry)) {
            entry.autopilotLastDeathAtMs = System.currentTimeMillis();
        }

        // Inside a PQ/event instance a town respawn can't re-enter the run — keep the
        // legacy warp-to-owner there so the party isn't down a member for the whole PQ.
        boolean inEvent = bot.getEventInstance() != null
                || (owner != null && owner.getEventInstance() != null)
                || BotPqHooks.requiresGrind(entry, bot)
                || BotPqHooks.requiresFollow(entry, bot);
        if (inEvent && owner != null) {
            respawnAtOwner(entry, bot, owner);
            return;
        }

        // Player-legal respawn: revive in the return map with the standard 50 HP —
        // the exact Character.respawn path ChangeMapHandler runs for real players —
        // then walk back through portals like anyone else (follow/autopilot travel
        // handles the trip; autopot tops the HP back up).
        bot.respawn(bot.getMap().getReturnMapId());
        if (!groundAfterMapChange(entry, bot)) {
            // Died in a map that is its own return map (e.g. a town): no map change
            // happened, so revive the physics state in place.
            Point cur = bot.getPosition();
            Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
            BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : cur);
            BotMovementManager.resetEntryStateAfterTeleport(entry);
            BotMovementManager.broadcastMovement(entry);
        }
        botSay(bot, randomReply(RESPAWN_REPLIES));
        bot.changeFaceExpression(Emote.GLARE.getValue());
    }

    private void respawnAtOwner(BotEntry entry, Character bot, Character owner) {
        bot.updateHp(bot.getMaxHp());

        if (bot.getMapId() != owner.getMapId()) {
            bot.forceChangeMap(owner.getMap(), owner.getMap().findClosestPortal(owner.getPosition()));
        }
        Point ownerPos = owner.getPosition();
        Point spawnPos = bot.getMap().getPointBelow(new Point(ownerPos.x, ownerPos.y - 1));
        BotPhysicsEngine.teleportTo(entry, bot, spawnPos != null ? spawnPos : ownerPos);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        BotMovementManager.broadcastMovement(entry);
        botSay(bot, "back!");
        bot.changeFaceExpression(Emote.GLARE.getValue());
    }

    // -------------------------------------------------------------------------
    // Monster control hand-off
    // -------------------------------------------------------------------------

    /**
     * Bots can't drive mob AI (BotClient.sendPacket is a no-op), so any monster
     * assigned to a bot as controller would freeze. Release it from the bot and
     * let the upstream controller-selection SSOT ({@link Monster#aggroUpdateController})
     * pick an eligible real player, or leave it released if none is eligible.
     * Bots and hidden GMs are excluded by that SSOT, so neither is ever handed control.
     */
    private static void tickReleaseMonsterControl(Character bot) {
        java.util.Collection<Monster> controlled = bot.getControlledMonsters();
        if (controlled.isEmpty()) return;

        for (Monster monster : controlled) {
            monster.aggroRedirectController();
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    void botSay(Character bot, String text) {
        bot.getMap().broadcastMessage(PacketCreator.getChatText(bot.getId(), sanitizeChat(text), false, 0));
    }

    // Common typographic chars an LLM/source string may slip in; the v83 client chat
    // charset (usually US-ASCII) can't encode them, so they render as '?'. Map to ASCII.
    private static final Map<java.lang.Character, String> CHAT_CHAR_FALLBACKS = Map.ofEntries(
            Map.entry('—', "-"),    // em dash
            Map.entry('–', "-"),    // en dash
            Map.entry('‘', "'"),    // left single quote
            Map.entry('’', "'"),    // right single quote / apostrophe
            Map.entry('“', "\""),   // left double quote
            Map.entry('”', "\""),   // right double quote
            Map.entry('…', "..."),  // ellipsis
            Map.entry(' ', " "));   // non-breaking space

    private static final java.nio.charset.CharsetEncoder CHAT_ENCODER =
            CharsetConstants.CHARSET.newEncoder();

    /**
     * Replaces characters the chat charset ({@link CharsetConstants#CHARSET}) can't encode —
     * which the client renders as '?' — with ASCII equivalents, logging a warning so the
     * offending source string can be located and fixed. Fast no-op for already-clean text.
     */
    static synchronized String sanitizeChat(String text) {
        if (text == null || CHAT_ENCODER.canEncode(text)) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CHAT_ENCODER.canEncode(c)) {
                sb.append(c);
            } else {
                sb.append(CHAT_CHAR_FALLBACKS.getOrDefault(c, "?"));
            }
        }
        String cleaned = sb.toString();
        log.warn("Bot chat had non-encodable char(s) (would show as '?'): \"{}\" -> \"{}\"", text, cleaned);
        return cleaned;
    }

    void botSay(Character bot, ReplyChannel channel, String text) {
        switch (channel) {
            case PARTY, WHISPER -> botSayParty(bot, text);
            default -> botSay(bot, text);
        }
    }

    /** Bot-to-bot visible say — routes MAP→map broadcast, PARTY→party, WHISPER→party fallback. */
    void botSay(BotEntry entry, String text) {
        botSay(entry.bot, entry.replyChannel, text);
    }

    /** Owner-directed reply — routes MAP→map broadcast, PARTY→party, WHISPER→whisper to owner. */
    public void botReply(BotEntry entry, String text) {
        switch (entry.replyChannel) {
            case PARTY -> botSayParty(entry.bot, text);
            case WHISPER -> {
                // While an admin debug binding is fresh, whisper replies go to the commander.
                Character owner = commanderOrOwner(entry);
                if (owner != null && owner.getClient() != null) {
                    owner.sendPacket(PacketCreator.getWhisperReceive(
                            entry.bot.getName(),
                            entry.bot.getClient().getChannel() - 1,
                            false, sanitizeChat(text)));
                }
            }
            default -> botSay(entry.bot, text);
        }
    }

    /**
     * Broadcasts via party chat so the owner sees the message even when they're
     * on a different map. Falls back to map chat if the bot has no party.
     */
    void botSayParty(Character bot, String text) {
        Party party = bot.getParty();
        if (party != null && bot.getClient() != null && bot.getClient().getWorldServer() != null) {
            bot.getClient().getWorldServer().partyChat(party, sanitizeChat(text), bot.getName());
        } else {
            botSay(bot, text);
        }
    }

    /**
     * Whisper-driven command to a specific owned bot. Bypasses the global name-
     * prefix routing in handleChat because the whisper target already identifies
     * the bot uniquely. No-op if target isn't a bot owned by the speaker.
     */
    public void handleWhisperToBot(Character owner, Character target, String message) {
        if (owner == null || target == null || message == null) {
            return;
        }
        if (!(target.getClient() instanceof BotClient)) {
            return;
        }
        BotEntry entry = getBotEntry(owner.getId(), target.getId());
        if (entry == null) {
            return;
        }
        entry.replyChannel = ReplyChannel.WHISPER;
        BotChatManager.handleChat(entry, message);
    }

    private boolean consumeAiTick(BotEntry entry) {
        entry.aiTickAccumulatorMs += BotMovementManager.cfg.TICK_MS;
        if (entry.aiTickAccumulatorMs < cfg.AI_TICK_MS) {
            return false;
        }

        entry.aiTickAccumulatorMs -= cfg.AI_TICK_MS;
        return true;
    }

}
