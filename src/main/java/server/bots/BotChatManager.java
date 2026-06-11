package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.processor.stat.AssignAPProcessor;
import constants.game.ExpTable;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Trade;
import server.combat.CombatFormulaProvider;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotChatManager {
    private static final String SKILL_TREE_CHOICE_ACTION = "skill_tree_choice";
    private static final ExecutorService TRADE_COMMAND_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "bot-trade-command");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Integer, AtomicInteger> PENDING_TRANSFER_REQUESTS = new ConcurrentHashMap<>();

    private record LearnedSkill(int id, String name, int level) {}
    private record TransferCommandResult(boolean hasItems, int count) {}
    private record ItemQueryResult(int count) {}
    static final class QueuedMessage {
        final String text;
        final boolean ownerDirected;

        QueuedMessage(String text, boolean ownerDirected) {
            this.text = text;
            this.ownerDirected = ownerDirected;
        }
    }

    // --- helper prefix used in several info patterns ---
    // optional preamble: "what's/tell me/check … your/ur" or nothing at all
    // \\b at end ensures word boundary when no prefix is present
    private static final String INFO_PFX =
            "(?:(?:(?:what.?s?|what\\s+is|tell\\s+me|show\\s+me|check|how.?s?)\\s+)?(?:your|ur)\\s+)?\\b";

    private static final Pattern FOLLOW_PATTERN = Pattern.compile(
            "\\b(follow(\\s+(me|here|pls|please|now))?|come(\\s+(here|to\\s+me|with\\s+me|closer|on|back))?|"
            + "get\\s+over\\s+here|f\\s+me|(pls|please)\\s+follow)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_TARGET_PATTERN = Pattern.compile(
            "^\\s*follow\\s+(\\S+?)(?:\\s+(?:pls|please|now))?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MOVE_HERE_PATTERN = Pattern.compile(
            "(?:move\\s+(?:here|there)|go\\s+(?:here|there)|here|move)(?:\\s+(?:now|pls|please))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FARM_HERE_PATTERN = Pattern.compile(
            "(?:(?:farm|grind|hunt|train)\\s+here"
            + "|(?:go\\s+)?(?:sentry|camp|guard|defend|post\\s+up|anchor)(?:\\s+(?:here|mode))?)"
            + "(?:\\s+(?:now|pls|please))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATROL_PATTERN = Pattern.compile(
            "(?:patrol|roam|wander)(?:\\s+(?:here|the\\s+area|around))?(?:\\s+(?:now|pls|please))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STOP_PATTERN = Pattern.compile(
            "\\b(stop(\\s+(moving|it|now|pls|please))?|stay(\\s+(here|there|put))?|"
            + "wait(\\s+(here|up|for\\s+me|a\\s+(sec|moment|bit)))?|"
            + "hold(\\s+(on|up|still|it))?|halt|freeze|don.?t\\s+move|stand\\s+(still|by)|"
            + "chill(\\s+here)?|idle|park(\\s+here)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> FOLLOW_REPLIES = List.of(
            "ok", "k", "sure", "omw", "got it", "coming",
            "roger", "yep", "alright", "aye", "lets go!", "as you wish", "ok boss",
            "on my way", "right behind you", "np", "kk omw", "w8 up",
            "gotchu", "moving now", "aye aye");
    private static final List<String> MOVE_HERE_REPLIES = List.of(
            "k, coming", "omw", "ok heading over", "on my way", "k",
            "got it, moving there", "coming, then staying put", "k moving there", "sure omw");
    private static final List<String> STOP_REPLIES = List.of(
            "ok", "k", "sure", "alright", "got it", "stopping",
            "ok ill wait here", "ill be here", "np", "standing by",
            "understood", "ok boss", "staying put", "chilling here",
            "resting", "aye aye", "on it", "noted");

    private static final Pattern GRIND_PATTERN = Pattern.compile(
            "\\b(go\\s+|start\\s+|begin\\s+|let.?s\\s+)?(farm(ing)?|grind(ing)?|hunt(ing)?|train(ing)?)\\b"
            + "|\\b(kill|fight)\\s+(mobs?|monsters?|stuff)\\b"
            + "|\\btime\\s+to\\s+(farm|grind|hunt)\\b"
            + "|\\bgo\\s+get\\s+(exp|xp)\\b"
            + "|\\b(auto|attack)\\s*(on|mode)?\\b"
            + "|\\bstart\\s+(killing|attacking)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JOB_SELECT_PATTERN = Pattern.compile(
            "\\b(warrior|fighter|page|spearman|sader|crusader|hero|dk|drk|dark knight|paladin|" +
            "mage|magician|wizard|cleric|healer|fp|il|fp mage|il mage|fp arch|il arch|priest|bishop|" +
            "bowman|bowmen|archer|hunter|crossbow|xbow|sniper|ranger|bowmaster|bm|marksman|mm|" +
            "thief|assassin|sin|bandit|dit|hermit|chief bandit|cb|shadower|shad|night lord|nl|" +
            "pirate|brawler|gunslinger|gun|marauder|outlaw|bucc|buccaneer|corsair|" +
            "white knight|wk|dragon knight)\\b", Pattern.CASE_INSENSITIVE);


    // Whole-message match (with optional trailing punctuation/whitespace) so
    // longer chat like "hi how are you today" falls through to the LLM instead
    // of being short-circuited by a canned greeting.
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^\\s*(hi+|hey+|hello+|sup|yo+|howdy|hiya|heya|hai|ello|"
            + "whats?\\s*up|waz+up|wassup|hows?\\s+it\\s+going|"
            + "(good\\s+)?(morning|evening|afternoon)|"
            + "how\\s+(are|r)\\s+(you|u|ya)(\\s+doing)?|"
            + "what.?s\\s+(good|up|new|poppin.?))\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIDGET_PATTERN = Pattern.compile(
            "^\\s*fidget\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATS_PATTERN = Pattern.compile(
            INFO_PFX + "(stats?|str(ength)?|dex(terity)?|int(elligence)?|luk|level|lv)\\b"
            + "|\\bwhat\\s+(are|r)\\s+(your|ur)\\s+stats\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            INFO_PFX + "(range|damage|dmg|dps|watk|atk)\\b"
            + "|\\bwhat.?s\\s+(your|ur)\\s+(range|damage|dmg)\\b"
            + "|\\bhow\\s+(strong|powerful)\\s+(are|r)\\s+(you|u)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVEMENT_STATS_PATTERN = Pattern.compile(
            INFO_PFX + "(?:move\\s*speed|movespeed|speed|jump|movement|mobility)(?:\\s+stats?)?\\b"
            + "|\\bwhat.?s\\s+(your|ur)\\s+(?:move\\s*speed|movespeed|speed|jump)\\b"
            + "|\\bhow\\s+fast\\s+(are|r)\\s+(you|u)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_STATUS_PATTERN = Pattern.compile(
            "^\\s*(?:"
            + "where\\s+(?:are|r)\\s+(?:you|u|ya)"
            + "|where\\s+(?:are|r)?\\s*(?:you|u|ya)\\s+at"
            + "|where\\s*(?:r|are)?\\s*u"
            + "|where\\s+you\\s+at"
            + "|(?:what|which)\\s+map\\s+(?:are|r)\\s+(?:you|u|ya)\\s+(?:in|on|at)"
            + "|(?:what|which)\\s+map\\s+(?:are|r)\\s+(?:you|u|ya)"
            + "|(?:what\\s+are\\s+you|what\\s+r\\s+u|what\\s+you)\\s+doing"
            + "|(?:location|loc|where)\\s*\\??"
            + ")\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BUILD_PATTERN = Pattern.compile(
            INFO_PFX + "(build|ap|sp)\\b"
            + "|\\bwhat.?s\\s+(your|ur)\\s+build\\b"
            + "|\\bhow\\s+(did|do)\\s+(you|u)\\s+(build|assign|spend)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILLS_PATTERN = Pattern.compile(
            INFO_PFX + "(skills?|skill\\s+trees?|skill\\s+tabs?)\\b"
            + "|\\bwhat\\s+skills?\\s+do\\s+(you|u)\\s+have\\b"
            + "|\\bshow\\s+me\\s+(your|ur)\\s+skills?\\b"
            + "|^\\s*skills\\s*\\??\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INVENTORY_PATTERN = Pattern.compile(
            INFO_PFX + "(inv(entory)?|bag|items?|equips?|equipment)\\b"
            + "|\\bwhat.?s\\s+in\\s+(your|ur)\\s+(inv(entory)?|bag)\\b"
            + "|\\b(show|check)\\s+(your|ur)\\s+(inv(entory)?|items?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBUG_STATS_PATTERN = Pattern.compile(
            INFO_PFX + "(debug\\s+stats?|attack\\s+cooldown|atk\\s+cooldown)\\b"
            + "|\\bshow\\s+(me\\s+)?debug\\s+stats\\b"
            + "|\\bwhat.?s\\s+(your|ur)\\s+attack\\s+cooldown\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRIT_DEBUG_PATTERN = Pattern.compile(
            "\\bcrit\\s*(debug|stats?|rate|chance|info)?\\s*\\??\\s*$"
            + "|\\bdo\\s+you\\s+(crit|get\\s+crits?)\\b"
            + "|\\bwhat.?s\\s+(your|ur)\\s+crit\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POT_DEBUG_PATTERN = Pattern.compile(
            "\\b(pot|potion|autopot)\\s*(debug|info|select(ion)?|status)\\b"
            + "|\\bdebug\\s+(pot|potion|autopot)s?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_PATTERN = Pattern.compile(
            "\\b(help|commands?|what\\s+can\\s+you\\s+do|how\\s+do\\s+i\\s+use\\s+you)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECOMMENDED_GEAR_PATTERN = Pattern.compile(
            "\\b(any\\s+upgrades?|better\\s+gear|recommended\\s+gear|gear\\s+recommendations?|"
            + "any\\s+(better|recommended)\\s+(gear|equips?|equipment))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_UPGRADE_PATTERN = Pattern.compile(
            "\\brequest\\s*\\?|\\bneed\\s+anything\\b|\\bdo\\s+you\\s+need\\s+(anything|something)\\b"
            + "|\\bdo\\s+you\\s+need\\s+(?:any\\s+)?(?:gear|equips?|equipment)\\s*(?:from\\s+me)?\\b"
            + "|\\bneed\\s+(?:any\\s+)?(?:gear|equips?|equipment)\\s+from\\s+me\\b"
            + "|\\bwhat\\s+do\\s+you\\s+need\\b|\\bwhat.?s\\s+(on\\s+your\\s+)?wish\\s*list\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEED_HP_POT_PATTERN = Pattern.compile(
            "\\b(?:need|nned|low\\s+on|out\\s+of|running\\s+low\\s+on)\\s+(?:some\\s+)?(?:hp|health)\\s+(?:pots?|potions?|supplies)\\b"
            + "|\\b(?:any(?:body|one)?|someone|somebody|u|you)\\s+(?:got|have|has)\\s+(?:any\\s+|some\\s+)?(?:hp|health)\\s+(?:pots?|potions?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEED_MP_POT_PATTERN = Pattern.compile(
            "\\b(?:need|nned|low\\s+on|out\\s+of|running\\s+low\\s+on)\\s+(?:some\\s+)?(?:mp|mana)\\s+(?:pots?|potions?|supplies)\\b"
            + "|\\b(?:any(?:body|one)?|someone|somebody|u|you)\\s+(?:got|have|has)\\s+(?:any\\s+|some\\s+)?(?:mp|mana)\\s+(?:pots?|potions?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEED_POT_PATTERN = Pattern.compile(
            "\\b(?:need|nned|low\\s+on|out\\s+of|running\\s+low\\s+on)\\s+(?:some\\s+)?(?:pots?|potions?|supplies)\\b"
            + "|\\b(?:any(?:body|one)?|someone|somebody|u|you)\\s+(?:got|have|has)\\s+(?:any\\s+|some\\s+)?(?:pots?|potions?|supplies)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEED_AMMO_PATTERN = Pattern.compile(
            "\\b(?:need|nned|low\\s+on|out\\s+of|running\\s+low\\s+on)\\s+(?:some\\s+)?(?:ammo|arrows?|bolts?)\\b"
            + "|\\b(?:any(?:body|one)?|someone|somebody|u|you)\\s+(?:got|have|has)\\s+(?:any\\s+|some\\s+)?(?:ammo|arrows?|bolts?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> AMMO_NOT_NEEDED_REPLIES = List.of(
            "i don't use shareable arrow ammo rn",
            "i don't need arrows or bolts rn",
            "ammo sharing is only for arrows and bolts",
            "not using bow ammo rn");

    private static final Pattern FAME_PATTERN = Pattern.compile(
            "^\\s*fame\\s+(me|\\S+?)\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> FAME_OK_REPLIES = List.of(
            "k", "kk", "kkk", "ok", "sure", "done",
            "famed %s", "my turn tomorrow?", "that would be 1m pls",
            "trade me 500k first", "S> fame 1m", "famed!", "got u",
            "ok famed", "yw", "done, fame me back?",
            "np", "ok but u owe me", "famed %s, now we're even",
            "ok done, 1m in the mail pls", "fame4fame?",
            "consider this a gift", "ok fine, famed",
            "you're welcome, now buy me dinner", "done. u got a good one");
    private static final List<String> FAME_COOLDOWN_REPLIES = List.of(
            "already famed someone today, try tmrw",
            "can only fame once a day, already used it",
            "famed earlier today, comeback tomorrow",
            "daily limit hit, tomorrow ok?",
            "i'm tapped out on fame for today");
    private static final List<String> FAME_SAME_PERSON_REPLIES = List.of(
            "already famed %s this month",
            "famed %s too recently, next month",
            "can't fame %s again yet, monthly limit",
            "monthly limit for %s, try again next month",
            "famed %s already this month, gotta wait");
    private static final List<String> OWNER_POT_SHORTAGE_REPLIES = List.of(
            "almost out of %s pots too, i thought u were our shopper?",
            "i checked, nobody has spare %s pots. that's kinda your department lol",
            "we're low on %s pots too, boss",
            "no spare %s pots in the squad rn",
            "everyone's light on %s pots, might need a shop run",
            "i'd help, but we're all thin on %s pots",
            "no one has enough %s pots to share rn",
            "we're not holding extra %s pots, thought you packed supplies",
            "can't find spare %s pots. maybe time to restock?",
            "almost dry on %s pots too, don't look at me");
    private static final List<String> OWNER_AMMO_SHORTAGE_REPLIES = List.of(
            "almost out of ammo too, i thought u were our shopper?",
            "i checked, nobody has spare ammo. that's kinda your department lol",
            "we're low on ammo too, boss",
            "no spare ammo in the squad rn",
            "everyone's light on ammo, might need a shop run",
            "i'd help, but we're all thin on ammo",
            "no one has enough ammo to share rn",
            "we're not holding extra ammo, thought you packed supplies",
            "can't find spare ammo. maybe time to restock?",
            "almost dry on ammo too, don't look at me");
    private static final Pattern SUPPORT_ON_PATTERN = Pattern.compile(
            "\\b(support\\s+(me|us|party)|support\\s+on|auto\\s+support|skill\\s+buffs?\\s+on)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORT_OFF_PATTERN = Pattern.compile(
            "\\b(support\\s+off|stop\\s+support(ing)?|no\\s+support|skill\\s+buffs?\\s+off|no\\s+skill\\s+buffs?|stop\\s+(skill\\s+)?buffing)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALS_ON_PATTERN = Pattern.compile(
            "\\b(heals?\\s+(me|us|party)|heals?\\s+on|auto\\s+heals?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALS_OFF_PATTERN = Pattern.compile(
            "\\b(heals?\\s+off|stop\\s+heal(ing)?|no\\s+heals?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_ON_PATTERN = Pattern.compile(
            "\\bbuff\\s+(pots?\\s+)?on\\b|\\bauto\\s+buff\\s+pots?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_OFF_PATTERN = Pattern.compile(
            "\\bbuff\\s+(pots?\\s+)?off\\b|\\bno\\s+buff\\s+pots?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_CHEAP_PATTERN = Pattern.compile(
            "\\bbuff\\s+(pots?\\s+)?cheap\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_MAX_PATTERN = Pattern.compile(
            "\\bbuff\\s+(pots?\\s+)?(max|best|good)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROACTIVE_OFFERS_ON_PATTERN = Pattern.compile(
            "\\b(?:(?:proactive|future)\\s+(?:offers?|upgrades?)\\s+on|offers?\\s+(?:proactive|future)\\s+on)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROACTIVE_OFFERS_OFF_PATTERN = Pattern.compile(
            "\\b(?:(?:proactive|future)\\s+(?:offers?|upgrades?)\\s+off|offers?\\s+(?:proactive|future)\\s+off)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_SCROLL_ON_PATTERN = Pattern.compile(
            "\\bscroll\\s+(?:on|my\\s+(?:gear|equips?)|gear|equips?)\\b"
            + "|\\bauto-?scroll\\s+on\\b|\\bstart\\s+scrolling\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_SCROLL_OFF_PATTERN = Pattern.compile(
            "\\bscroll\\s+off\\b|\\bauto-?scroll\\s+off\\b|\\bstop\\s+scrolling\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_SCROLL_NOW_PATTERN = Pattern.compile(
            "\\bscroll\\s+(?:now|something|stuff)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_SCROLL_DEBUG_PATTERN = Pattern.compile(
            "\\bscroll\\s+debug\\b|\\bdebug\\s+scroll\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_LIST_PATTERN = Pattern.compile(
            "\\bbuff\\s+(pots?\\s+)?list\\b|\\bbuffs?\\s*\\?|\\bwhat\\s+buffs?\\b|\\bwhich\\s+buffs?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUFF_DEBUG_PATTERN = Pattern.compile(
            "\\bbuffs?\\s*(?:debug|\\?)?\\b|\\bdebug\\s+buffs?\\b|\\bactive\\s+buffs?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_BUFF_DEBUG_PATTERN = Pattern.compile(
            "\\bskill\\s+buffs?\\s*(?:debug|\\?)?\\b|\\bdebug\\s+skill\\s+buffs?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String SCROLL_WORDS = "scrolls?";
    private static final String POTION_WORDS = "(?:pots?|potions?|hp\\s+pots?|mp\\s+pots?|supplies)";
    private static final String BUFF_WORDS   = "(?:buff\\s+pots?|buff\\s+potions?|buffs?\\s+items?)";
    private static final String AMMO_WORDS = "(?:ammo|arrows?|bolts?|bullets?|stars?|throwing\\s+stars?)";
    private static final String USE_WORDS = "(?:use|use\\s+items?|consumables?)";
    private static final String EQUIP_WORDS = "(?:equips?|equipment|gear)";
    private static final String ETC_WORDS = "(?:etc|misc(?:ellaneous)?)";
    private static final String TRASH_WORDS = "(?:trash|junk)";
    private static final String MESO_WORDS = "mesos?";

    private static final Pattern SCROLLS_PATTERN = Pattern.compile(
            "\\b(any|do\\s+(you|u)\\s+have(\\s+any)?|got(\\s+any)?|"
            + "carrying(\\s+any)?|you\\s+got(\\s+any)?)\\s+" + SCROLL_WORDS + "\\b"
            + "|\\bhow\\s+many\\s+scrolls?\\b"
            + "|\\bscrolls?\\s+on\\s+(you|u|ya)\\b"
            + "|\\b(your|ur)\\s+scrolls?\\b"
            + "|^\\s*scrolls?\\s*\\??\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POTIONS_PATTERN = Pattern.compile(
            INFO_PFX + POTION_WORDS + "\\b"
            + "|\\b(any|do\\s+(you|u)\\s+have(\\s+any)?|got(\\s+any)?|how\\s+many)"
            +   "\\s+" + POTION_WORDS + "\\b"
            + "|\\b(pots?|potions?)\\s+left\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_PATTERN = Pattern.compile(
            "^\\s*(?:exp|xp|experience)\\s*[?!.,]*\\s*$"
            + "|\\bhow\\s+much\\s+(?:exp|xp|experience)(?:\\s+do\\s+(?:you|u)\\s+have)?\\b"
            + "|\\bwhat.?s\\s+(?:your|ur)\\s+(?:exp|xp|experience)\\b"
            + "|\\b(?:your|ur)\\s+(?:exp|xp|experience)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MESOS_PATTERN = Pattern.compile(
            "^\\s*(?:meso|mesos|cash)\\s*[?!.,]*\\s*$"
            + "|\\bhow\\s+much\\s+(?:meso|mesos|cash)(?:\\s+do\\s+(?:you|u)\\s+have)?\\b"
            + "|\\bwhat.?s\\s+(?:your|ur)\\s+(?:meso|mesos|cash)\\b"
            + "|\\bshow\\s+me\\s+(?:your|ur)\\s+(?:meso|mesos|cash)\\b"
            + "|\\b(?:your|ur)\\s+(?:meso|mesos|cash)\\b"
            + "|\\b(?:meso|mesos|cash)\\s+(?:left|on\\s+(?:you|u|ya))\\b"
            + "|\\b(?:do\\s+(you|u)\\s+have|got(?:\\s+any)?|(you|u)\\s+got)\\s+(?:any\\s+)?(?:meso|mesos|cash)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNEQUIP_PATTERN = Pattern.compile(
            "\\b(unequip|take\\s+off|remove)\\s+(?:everything|all|all\\s+(?:your|ur|my)\\s+gear|gear|equipment|equips?)\\b"
            + "|\\bstrip\\s+(?:down|everything|all)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String EQUIP_SLOT_WORDS =
            "(?:weapon|wep|shield|offhand|cape|hat|helm(?:et)?|top|shirt|overall|bottom|pants|shoes|boots|"
            + "gloves?|face(?:\\s*acc(?:essory)?)?|eye(?:\\s*(?:acc(?:essory)?|piece))?|"
            + "earrings?|rings?\\s*[1-4]?|pendant|medal|belt)";
    private static final Pattern UNEQUIP_SLOT_PATTERN = Pattern.compile(
            "\\b(unequip|take\\s+off|remove)\\s+(" + EQUIP_SLOT_WORDS + ")\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOEQUIP_DEBUG_PATTERN = Pattern.compile(
            "\\b(?:auto[\\-\\s]?equip|optimi[sz]e\\s+(?:gear|equip(?:s|ment)?))\\s+(?:debug|verbose|why|explain)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOEQUIP_PATTERN = Pattern.compile(
            "\\b(?:auto[\\-\\s]?equip|optimi[sz]e\\s+(?:gear|equip(?:s|ment)?))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_VIEW_SLOT_COMMAND_PATTERN = Pattern.compile(
            "\\b(?:can\\s+i\\s+(?:c|see)|let\\s+me\\s+(?:c|see)|show(?:\\s+me)?)\\s+"
            + "(?:(?:u|ur|yo|your)\\s+)?(" + EQUIP_SLOT_WORDS + ")\\b[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // SP variant selection — only matched when spVariantPromptSent=true and spVariant=null
    private static final Pattern SP_1H_PATTERN = Pattern.compile(
            "\\b1h\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SP_2H_PATTERN = Pattern.compile(
            "\\b2h\\b", Pattern.CASE_INSENSITIVE);

    // "pure <stat>" matches only the class whose primary stat it names.
    // Bare "pure" (no stat qualifier) matches all classes via the negative lookahead,
    // and the per-class job gate in handleApBuildSelection ensures only the right bot acts.
    private static final String PURE_NO_STAT = "^\\s*pure\\s*$";
    private static final Pattern AP_PURE_STR_PATTERN = Pattern.compile(
            "\\bpure\\s+str\\b|\\bdexless\\b|" + PURE_NO_STAT, Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_DEXLESS_PATTERN = Pattern.compile(
            "\\bdexless\\b|\\bpure\\s+luk\\b|" + PURE_NO_STAT, Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_LUKLESS_PATTERN = Pattern.compile(
            "\\blukless\\b|\\bpure\\s+int\\b|" + PURE_NO_STAT, Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_STRLESS_PATTERN = Pattern.compile(
            "\\bstrless\\b|\\bpure\\s+dex\\b|" + PURE_NO_STAT, Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_FIXED_DEX_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*dex\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_FIXED_LUK_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*luk\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_FIXED_STR_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*str\\b", Pattern.CASE_INSENSITIVE);
    // Bare trade invite — whole-message match so "trade me" isn't swallowed by TRADE_ITEM_COMMAND_PATTERN
    private static final Pattern TRADE_INVITE_PATTERN = Pattern.compile(
            "^\\s*trade(\\s+(me|pls|please))?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> TRADE_INVITE_REPLIES = List.of(
            "ok", "sure", "k", "one sec", "coming to trade", "np", "k opening trade");

    // Drop-choice responses (matched only when pendingAction = "item_choice")
    private static final Pattern DROP_CHOICE_DROP_PATTERN = Pattern.compile(
            "^(?:drop|drop it|drop them|drop to ground|floor|ground)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_CHOICE_TRADE_PATTERN = Pattern.compile(
            "^(?:trade|trade me|send|give|transfer|give me)$",
            Pattern.CASE_INSENSITIVE);

    // Shared verb prefix for all drop/give/trade category commands

    // Drop category commands
    // Generic drop by item name — captured group 1 is the name; processed only if category patterns don't match
    private static final String TRADE_CMD_VERB = "(?:trade(?:\\s+(?:me|us))?)";
    private static final String DROP_CMD_VERB = "(?:drop|toss)";
    private static final String ASK_CMD_VERB = "(?:give(?:\\s+(?:me|us))?|pass(?:\\s+me)?)";
    private static final String MESO_CMD_VERB = "(?:trade(?:\\s+(?:me|us))?|give(?:\\s+(?:me|us))?|gimme|pass(?:\\s+me)?)";
    private static final String TRANSFER_OWNER = "(?:(?:your|ur|my|all)\\s+)?";
    private static final String TRANSFER_RECIPIENT = "(?:(?:me|us)\\s+)?";
    private static final String MESO_AMOUNT_TOKEN = "\\d[\\d,]*(?:\\.\\d+)?\\s*[kmb]?";
    private static final Pattern TRADE_MESOS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + MESO_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(all)\\s+)?"
            + "(?:(?:your|ur|my)\\s+)?"
            + "(?:(" + MESO_AMOUNT_TOKEN + ")\\s+)?"
            + MESO_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_MESOS_AFTER_WORD_COMMAND_PATTERN = Pattern.compile(
            "\\b" + MESO_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(?:your|ur|my)\\s+)?"
            + MESO_WORDS + "\\s+(" + MESO_AMOUNT_TOKEN + ")[?!.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_MESOS_AMOUNT_ONLY_COMMAND_PATTERN = Pattern.compile(
            "\\b" + MESO_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(all)|(" + MESO_AMOUNT_TOKEN + "))[?!.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_SCROLLS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + SCROLL_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_POTS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + POTION_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELL_TRASH_COMMAND_PATTERN = Pattern.compile(
            "^\\s*(?:sell|vendor)\\s+(?:(?:my|ur|your)\\s+)?(?:trash|junk)\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRIND_WHERE_PATTERN = Pattern.compile(
            "^\\s*where\\s+(?:should|do|can|could|to)?\\s*(?:we|i|u|you)?\\s*(?:wanna\\s+|want\\s+to\\s+)?"
                    + "(?:go\\s+(?:to\\s+)?)?(?:grind|train|farm|level|lvl)(?:\\s+(?:up|at|next))?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRIND_DEBUG_PATTERN = Pattern.compile(
            "^\\s*(?:grind\\s+debug|debug\\s+grind)\\s*[?!.,]*\\s*$", Pattern.CASE_INSENSITIVE);
    // Autopilot: independent play on owner's order. Distinct from plain "go grind" (this map).
    private static final Pattern AUTOPILOT_PATTERN = Pattern.compile(
            "^\\s*(?:(?:go\\s+)?(?:grind|train|farm|level|play|hunt)\\s+"
                    + "(?:on\\s+(?:your|ur)\\s+own|somewhere(?:\\s+(?:good|else|nice))?|wherever(?:\\s+(?:you|u)\\s+want)?)"
                    + "|autopilot|go\\s+solo|go\\s+(?:be\\s+)?independent)\\s*[?!.,~]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    // Ferry permission: the bot only boards cross-sea boats with the owner around after this.
    private static final Pattern SAIL_AWAY_PATTERN = Pattern.compile(
            "^\\s*(?:sail\\s+away|take\\s+the\\s+boat|go\\s+sail)\\s*[?!.,~]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    // Party autopilot: the whole group shares ONE grind decision instead of scattering.
    private static final Pattern PARTY_AUTOPILOT_PATTERN = Pattern.compile(
            "^\\s*(?:(?:go\\s+)?(?:grind|train|farm|level|play|hunt)\\s+together"
                    + "|party\\s+(?:grind|autopilot)|go\\s+together)\\s*[?!.,~]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    // "farm <item>": excludes the farm-here / autopilot / party suffixes handled above.
    private static final Pattern FARM_ITEM_PATTERN = Pattern.compile(
            "^\\s*farm\\s+(?!here\\b|somewhere\\b|wherever\\b|together\\b|on\\s+(?:your|ur)\\s+own\\b)(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAKE_CRYSTALS_COMMAND_PATTERN = Pattern.compile(
            "^\\s*(?:make|craft|create)\\s+(?:some\\s+)?(?:mob|mon|monster|monsters|mobs)\\s+crystals?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISASSEMBLE_TRASH_COMMAND_PATTERN = Pattern.compile(
            "^\\s*(?:disassemble|dismantle|scrap|break\\s*down)\\s+(?:(?:my|ur|your)\\s+)?(?:trash|junk)(?:\\s+(?:equips?|gear))?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_USE_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + USE_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_EQUIPS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + EQUIP_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_RESERVE_COMMAND_PATTERN = Pattern.compile(
            "^\\s*" + TRADE_CMD_VERB
            + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(?:your|ur|my)\\s+)?reserve(?:d)?(?:\\s+(\\d+))?\\s*[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_TRASH_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(?:your|ur|my)\\s+)?" + TRASH_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHOW_JUNK_COMMAND_PATTERN = Pattern.compile(
            "^\\s*show(?:\\s+me)?\\s+(?:(?:your|ur)\\s+)?junk[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_ETC_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + ETC_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_BUFF_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + BUFF_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_AMMO_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + AMMO_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_BUFF_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + BUFF_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_BUFF_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + BUFF_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_RECOMMENDED_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT
            + "(?:(?:your|ur|my)\\s+)?"
            + "(?:(?:recommended|better)\\s+(?:gear|equips?|equipment)|upgrades?|recommended\\s+items?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_ITEM_COMMAND_PATTERN = Pattern.compile(
            "\\b" + TRADE_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + "(?:(?:your|ur|my)\\s+)?([\\w][\\w '\\-]{1,39})[?!.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_SCROLLS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + SCROLL_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_POTS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + POTION_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_USE_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + USE_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_EQUIPS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + EQUIP_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TRASH_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + "(?:(?:your|ur|my)\\s+)?" + TRASH_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_ETC_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + ETC_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_ITEM_COMMAND_PATTERN = Pattern.compile(
            "\\b" + DROP_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + "(?:(?:your|ur|my)\\s+)?([\\w][\\w '\\-]{1,39})[?!.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_SCROLLS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + SCROLL_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_POTS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + POTION_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_USE_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + USE_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_EQUIPS_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + EQUIP_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_ETC_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + ETC_WORDS + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_ITEM_COMMAND_PATTERN = Pattern.compile(
            "\\b" + ASK_CMD_VERB + "\\s+" + TRANSFER_RECIPIENT + TRANSFER_OWNER + "(?:(?:your|ur|my)\\s+)?([\\w][\\w '\\-]{1,39})[?!.,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_QUERY_PATTERN = Pattern.compile(
            "^\\s*(?:(?:do\\s+(?:you|u)\\s+have)|(?:(?:any(?:body|one)?|someone|somebody|you|u)\\s+(?:got|have|has))|got|have)\\s+"
            + "(?:any\\s+|some\\s+)?(?:(?:your|ur)\\s+)?([\\w][\\w '\\-]{1,39})[?!.,]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    // Inventory slot query
    private static final Pattern INV_SLOTS_PATTERN = Pattern.compile(
            "\\bslots?\\s*(?:left|free|remaining)?\\b"
            + "|\\binv(?:entory)?\\s+(?:full|space|slots?)\\b"
            + "|\\bhow\\s+full\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AP_CHANGE_BUILD_PATTERN = Pattern.compile(
            "\\b(change|switch|update|reset|new)\\s+(your\\s+|ur\\s+)?build\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPEC_PATTERN = Pattern.compile(
            "\\b(respec|reset\\s+(skills?|sp)|rebuild\\s+(skills?|sp)|fix\\s+(skills?|sp|build))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AP_RESPEC_PATTERN = Pattern.compile(
            "\\b(respec\\s+ap|reset\\s+ap|rebuild\\s+ap|fix\\s+ap(?:\\s+build)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGOUT_PATTERN = Pattern.compile(
            "(?:(?:i\\s+)?(?:(?:have|got|need)\\s+to|gotta)\\s+)?"
            + "(?:(?:save\\s+and\\s+)?log\\s*(?:off|out)|disconnect|log\\s+me\\s+(?:off|out))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELOG_PATTERN = Pattern.compile(
            "(?:(?:i\\s+)?(?:(?:have|got|need)\\s+to|gotta)\\s+)?"
            + "(?:relog|save\\s+and\\s+relog|reconnect|log\\s+back\\s+in)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWAY_PATTERN = Pattern.compile(
            "(?:(?:gtg|g2g)"
            + "|(?:i\\s+)?(?:(?:have|got|need)\\s+to|gotta)\\s+go"
            + "|(?:i\\s+)?(?:(?:have|got|gotta|need)\\s+to\\s+)?(?:leave|bounce)"
            + "|(?:(?:i\\s+am|i['’]?m|im)\\s+)?(?:brb|afk)"
            + "|(?:be\\s+right\\s+back|back\\s+in\\s+(?:a\\s+)?(?:bit|sec|minute|min))"
            + "|(?:(?:i\\s+am|i['’]?m|im)\\s+)?(?:off|logging\\s+out\\s+soon)"
            + "|(?:i\\s+)?(?:have|got|gotta)\\s+to\\s+(?:head\\s+out|run))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGOUT_CONFIRM_PATTERN = Pattern.compile(
            "\\b(yes|yep|yeah|yea|y|ok|sure|confirm|do\\s+it|go\\s+(ahead|for\\s+it))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWAY_TOWN_CONFIRM_PATTERN = Pattern.compile(
            "^(?:yes|yep|yeah|yea|y|ok|sure|confirm|town|nearest\\s+town|go\\s+town|go\\s+to\\s+town)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWAY_STAY_CONFIRM_PATTERN = Pattern.compile(
            "^(?:stay|stay\\s+here|here|idle|wait\\s+here)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWAY_LOGOUT_CONFIRM_PATTERN = Pattern.compile(
            "^(?:logout|log\\s*out|log\\s*off|disconnect|save\\s+and\\s+log\\s*(?:out|off))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_CONFIRM_PATTERN = Pattern.compile(
            "\\b(no|nope|nah|nvm|never\\s*mind|dont|don't|not\\s+now|skip)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> GREETING_REPLIES = List.of(
            "hey", "hi", "sup", "yo", "heya", "hii", "hey!!", "hi!!", "hai", "haii",
            "heyo", "ello", "o/", "hai", "eyy", "henlo", "o hey", "yo dude", "hey there", "hi there", "hi guys", "what's up", "howdy", "how's it going");
    private static final List<String> WB_REPLIES = List.of(
            "wb", "wb!", "welcome back", "oh ur back", "hey ur back", "welcome back!!",
            "wb~", "there you are", "oh hey", "finally lol", "took ya a bit", "wb lol", "where were you lol", "ready to roll?", "lets continue!",
            "hey you're back", "oh wb!", "been waiting for you", "waiting on you", "ready to go?", "ready?", "back already?", "back?", "u back?");
    // %s = current map name (bot is in town since the offline-return warp put it there).
    // Sent via party chat so the owner sees it across maps when they reconnect.
    private static final List<String> WB_OFFLINE_PARTY_TEMPLATES = List.of(
            "wb! we've been waiting at %s since u went offline",
            "yoo wb, chillin at %s for a while now",
            "back online? we parked at %s",
            "wb, took a break in %s when u dropped",
            "hey wb! waiting in %s",
            "wb!! we're at %s",
            "yo wb, headed to %s when u afk'd",
            "oh wb, been camping at %s",
            "wb~ we're in %s, come grab us",
            "hey ur back!! we're at %s");
    private static final List<String> MESO_REPLIES = List.of(
            "I have %s",
            "got %s on me",
            "im at %s rn",
            "sitting on %s");

    private enum TransferMode {
        TRADE,
        CHOICE
    }

    private static final class TransferCommand {
        private final TransferMode mode;
        private final String category;

        private TransferCommand(TransferMode mode, String category) {
            this.mode = mode;
            this.category = category;
        }
    }

    private static void markOwnerActive(BotEntry entry) {
        Character owner = entry.owner;
        entry.ownerWasAfk = false;
        entry.ownerAfkSinceMs = System.currentTimeMillis();
        entry.ownerAfkPos = owner != null ? new Point(owner.getPosition()) : null;
    }

    // Set true on entry; cleared to false only if we fall off the natural end of handleChat
    // (no command pattern matched). Every match path returns early, leaving this true. Caller
    // (BotManager) reads via wasLastChatHandled() to gate the LLM fallback.
    private static final ThreadLocal<Boolean> LAST_CHAT_HANDLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean wasLastChatHandled() {
        return LAST_CHAT_HANDLED.get();
    }

    static void handleChat(BotEntry entry, String message) {
        LAST_CHAT_HANDLED.set(true);
        markOwnerActive(entry);
        // Logout / relog — two-step confirmation
        if (entry.pendingAction == null && matchesWholeCommand(RELOG_PATTERN, message)) {
            BotManager.after(BotManager.randMs(900, 1100), () -> {
                entry.pendingAction = "relog";
                BotManager.getInstance().issueStop(entry);
                List<String> prompts = List.of(
                        "relog? say yes to confirm",
                        "save and relog? type yes",
                        "relogging? say yes to go ahead");
                BotManager.getInstance().botReply(entry, BotManager.randomReply(prompts));
            });
            return;
        }
        if (entry.pendingAction == null && matchesWholeCommand(LOGOUT_PATTERN, message)) {
            BotManager.after(BotManager.randMs(900, 1100), () -> {
                entry.pendingAction = "logout";
                BotManager.getInstance().issueStop(entry);
                List<String> prompts = List.of(
                        "log off? you sure? say yes to confirm",
                        "save and log off? say yes if you're sure",
                        "logging off? type yes to confirm");
                BotManager.getInstance().botReply(entry, BotManager.randomReply(prompts));
            });
            return;
        }
        if (entry.pendingAction == null && matchesWholeCommand(AWAY_PATTERN, message)) {
            if (!BotManager.getInstance().isFirstBotEntry(entry)) {
                return;
            }
            BotManager.after(BotManager.randMs(900, 1100), () -> promptOwnerAway(entry));
            return;
        }
        if (entry.pendingAction != null) {
            if ("owner_away".equals(entry.pendingAction)) {
                handleOwnerAwayChoice(entry, message);
                return;
            }
            // Item-choice: three-way "drop / trade / cancel" — handled independently of yes/no
            if ("item_choice".equals(entry.pendingAction)) {
                String category = entry.pendingDropCategory;
                String choice = normalizeCommandText(message);
                if (DROP_CHOICE_TRADE_PATTERN.matcher(choice).matches()) {
                    entry.pendingAction       = null;
                    entry.pendingDropCategory = null;
                    BotManager.after(BotManager.randMs(400, 600),
                            () -> BotInventoryManager.executeChoice(category, true, entry, entry.bot));
                } else if (DROP_CHOICE_DROP_PATTERN.matcher(choice).matches()) {
                    entry.pendingAction       = null;
                    entry.pendingDropCategory = null;
                    BotManager.after(BotManager.randMs(400, 600),
                            () -> BotInventoryManager.executeChoice(category, false, entry, entry.bot));
                } else {
                    // any other response = cancel
                    entry.pendingAction       = null;
                    entry.pendingDropCategory = null;
                    BotManager.after(BotManager.randMs(400, 600),
                            () -> BotManager.getInstance().botReply(entry, "ok! keeping them"));
                }
                return;
            }
            if (SKILL_TREE_CHOICE_ACTION.equals(entry.pendingAction)) {
                handleSkillTreeChoice(entry, entry.bot, message);
                return;
            }
            if ("scroll_confirm".equals(entry.pendingAction)) {
                BotScrollManager.handleScrollConfirm(entry, message);
                return;
            }
            if (LOGOUT_CONFIRM_PATTERN.matcher(message).find()) {
                String action = entry.pendingAction;
                entry.pendingAction = null;
                if ("relog".equals(action)) {
                    BotManager.after(BotManager.randMs(900, 1100), () -> {
                        Character o = entry.owner;
                        if (o == null) return; // owner logged out before relog fired
                        BotManager.getInstance().botReply(entry, BotManager.randomReply(List.of("brb!", "relogging~", "one sec, relogging")));
                        int charId      = entry.bot.getId();
                        int ownerCharId = o.getId();
                        int world       = entry.bot.getClient().getWorld();
                        int channel     = entry.bot.getClient().getChannel();
                        BotManager.after(BotManager.randMs(1800, 2200), () -> {
                            entry.bot.saveCharToDB(true);
                            entry.bot.getClient().disconnect(false, false);
                            BotManager.after(BotManager.randMs(10000, 10100),
                                    () -> BotManager.getInstance().reloginBot(charId, ownerCharId, world, channel));
                        });
                    });
                } else {
                    BotManager.after(BotManager.randMs(900, 1100), () -> {
                        BotManager.getInstance().botReply(entry, BotManager.randomReply(List.of("ok! saving and logging off~", "cya!!", "ok bye!!")));
                        BotManager.after(BotManager.randMs(1800, 2200), () -> {
                            entry.bot.saveCharToDB(true);
                            entry.bot.getClient().disconnect(false, false);
                        });
                    });
                }
            } else {
                String action = entry.pendingAction;
                entry.pendingAction = null;
                String cancelMsg = action != null && action.startsWith("drop") ? "ok! keeping them" : "ok nvm, staying!";
                BotManager.after(BotManager.randMs(700, 900), () ->
                        BotManager.getInstance().botReply(entry, cancelMsg));
            }
            return;
        }

        if (matchesWholeCommand(HELP_PATTERN, message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> reportHelp(entry));
            return;
        }
        if (isLocationStatusQuery(message)) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotManager.getInstance().botReply(entry, BotAutopilotManager.statusReport(entry, entry.bot)));
            return;
        }
        if (NEED_HP_POT_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> handleNeedPotionCommand(entry, true));
            return;
        }
        if (NEED_MP_POT_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> handleNeedPotionCommand(entry, false));
            return;
        }
        if (NEED_POT_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> handleNeedAnyPotionCommand(entry));
            return;
        }
        if (NEED_AMMO_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> handleNeedAmmoCommand(entry));
            return;
        }
        Matcher fameMatcher = FAME_PATTERN.matcher(message);
        if (fameMatcher.matches()) {
            String fameTarget = fameMatcher.group(1);
            BotManager.after(BotManager.randMs(500, 900), () -> handleFameCommand(entry, fameTarget));
            return;
        }
        if (SUPPORT_OFF_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.skillBuffsEnabled = false;
                BotManager.getInstance().botReply(entry, "ok, skill buffs off");
            });
            return;
        }
        if (SUPPORT_ON_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.skillBuffsEnabled = true;
                BotManager.getInstance().botReply(entry, "ok, skill buffs on");
            });
            return;
        }
        if (HEALS_OFF_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.supportHealsEnabled = false;
                BotManager.getInstance().botReply(entry, "ok, no heals");
            });
            return;
        }
        if (HEALS_ON_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.supportHealsEnabled = true;
                BotManager.getInstance().botReply(entry, "ok, ill heal when needed");
            });
            return;
        }
        if (BUFF_OFF_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.buffConsumablesEnabled = false;
                entry.lastBuffScanMs = 0;
                BotManager.getInstance().botReply(entry, "ok, no buff pots");
            });
            return;
        }
        if (BUFF_ON_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.buffConsumablesEnabled = true;
                entry.lastBuffScanMs = 0;
                String mode = entry.buffCheapMode ? "cheap" : "max";
                BotManager.getInstance().botReply(entry, "ok, using buff pots (" + mode + ")");
            });
            return;
        }
        if (BUFF_CHEAP_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.buffCheapMode = true;
                entry.lastBuffScanMs = 0;
                BotManager.getInstance().botReply(entry, "ok, using cheapest buff pots");
            });
            return;
        }
        if (BUFF_MAX_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.buffCheapMode = false;
                entry.lastBuffScanMs = 0;
                BotManager.getInstance().botReply(entry, "ok, using best buff pots");
            });
            return;
        }
        if (PROACTIVE_OFFERS_OFF_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.proactiveUpgradeOffers = false;
                BotManager.getInstance().botReply(entry, "ok, only offering immediate upgrades");
            });
            return;
        }
        if (PROACTIVE_OFFERS_ON_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.proactiveUpgradeOffers = true;
                BotManager.getInstance().botReply(entry, "ok, proactive upgrade offers on");
            });
            return;
        }
        if (SELF_SCROLL_DEBUG_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(300, 500), () -> BotScrollManager.exportScrollDecision(entry, entry.bot));
            return;
        }
        if (SELF_SCROLL_OFF_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.selfScrollEnabled = false;
                BotScrollManager.cancelPending(entry);
                BotManager.getInstance().botReply(entry, "ok, ill stop scrolling my gear");
            });
            return;
        }
        if (SELF_SCROLL_NOW_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> BotScrollManager.requestScrollPass(entry, entry.bot));
            return;
        }
        if (SELF_SCROLL_ON_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                entry.selfScrollEnabled = true;
                BotManager.getInstance().botReply(entry, "ok! ill scroll my gear, ill ask before each one");
                BotManager.after(BotManager.randMs(700, 1000), () -> BotScrollManager.requestScrollPass(entry, entry.bot));
            });
            return;
        }
        if (matchesWholeCommand(BUFF_LIST_PATTERN, message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                String summary = BotBuffManager.getChatSummary(entry.buffConsumablesEnabled, entry.buffCheapMode, entry.bot);
                BotManager.getInstance().botReply(entry, summary);
            });
            return;
        }
        if (matchesWholeCommand(BUFF_DEBUG_PATTERN, message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> reportBuffDebug(entry, entry.bot));
            return;
        }
        if (matchesWholeCommand(SKILL_BUFF_DEBUG_PATTERN, message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> reportSkillBuffDebug(entry, entry.bot));
            return;
        }
        if (isApRespecCommand(message)) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotManager.getInstance().botReply(entry, BotBuildManager.respecAp(entry, entry.bot)));
            return;
        }
        if (isRespecCommand(message)) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotManager.getInstance().botReply(entry, BotBuildManager.respecSp(entry, entry.bot)));
            return;
        }
        Matcher unequipSlotMatcher = UNEQUIP_SLOT_PATTERN.matcher(message);
        if (unequipSlotMatcher.find()) {
            String slotName = unequipSlotMatcher.group(2);
            short[] slots = BotEquipManager.slotsFromName(slotName);
            if (slots.length > 0) {
                BotManager.after(BotManager.randMs(500, 700), () ->
                        BotManager.getInstance().botReply(entry, BotEquipManager.unequipSlot(entry.bot, slots)));
                return;
            }
        }
        if (UNEQUIP_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(500, 700), () -> {
                BotManager.getInstance().issueStop(entry);
                BotManager.getInstance().botReply(entry, BotEquipManager.unequipAll(entry.bot));
            });
            return;
        }
        // Debug match must run BEFORE the plain autoequip match (else "autoequip debug" is
        // swallowed by the plain pattern).
        if (AUTOEQUIP_DEBUG_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(400, 600), () -> {
                List<String> lines = BotEquipManager.autoEquipDebug(entry.bot);
                for (String line : lines) {
                    BotManager.getInstance().botReply(entry, line);
                }
            });
            return;
        }
        if (AUTOEQUIP_PATTERN.matcher(message).find()) {
            BotManager.after(BotManager.randMs(400, 600), () -> {
                BotEquipManager.autoEquip(entry.bot, entry.owner, entry.pendingLootOfferItem, true);
                BotManager.getInstance().botReply(entry, "ok, gear optimized");
            });
            return;
        }

        // Ferry green light: keep whatever the bot is doing, just widen its travel horizon
        // and let the next decision tick take the boat if it's still worth it.
        if (isSailAwayCommand(message)) {
            BotManager.after(BotManager.randMs(500, 900), () -> {
                BotAutopilotManager.approveFerry(entry);
                BotManager.getInstance().botReply(entry, "aye, i'll take the boat when it's worth it");
            });
            return;
        }

        // Independent supervised grind must run before plain "go grind"; otherwise
        // "go grind somewhere" schedules local grind and then clears autopilot.
        if (isAutopilotCommand(message)) {
            BotManager.after(BotManager.randMs(900, 1600), () -> {
                prepareActiveModeEntry(entry);
                BotAutopilotManager.start(entry, entry.bot);
            });
            return;
        }

        // Targeted form ("Jason go grind together"): a party of one, routed through the party
        // planner for consistent behavior. The broadcast form is intercepted at the owner
        // level (BotManager.handleChat) so the whole group shares ONE decision.
        if (isPartyAutopilotCommand(message)) {
            BotManager.after(BotManager.randMs(900, 1600), () -> {
                prepareActiveModeEntry(entry);
                BotAutopilotManager.startParty(entry.owner, List.of(entry));
            });
            return;
        }

        // "farm <item name|id>": autopilot with the objective pinned to that item.
        String farmItemArgs = matchFarmItemArgs(message);
        if (farmItemArgs != null) {
            handleFarmItemCommand(entry, farmItemArgs);
            return;
        }

        if (isFarmHereCommand(message)) {
            Point dest = entry.owner != null ? new Point(entry.owner.getPosition()) : null;
            if (dest != null) {
                BotManager.after(BotManager.randMs(1000, 1500), () -> {
                    prepareActiveModeEntry(entry);
                    BotManager.getInstance().issueFarmHere(entry, dest);
                    BotManager.getInstance().botReply(entry, BotManager.randomReply(MOVE_HERE_REPLIES));
                });
            }
        } else if (isPatrolCommand(message)) {
            Point ownerPos = entry.owner != null ? new Point(entry.owner.getPosition()) : null;
            if (ownerPos != null) {
                BotManager.after(BotManager.randMs(1000, 1500), () -> {
                    prepareActiveModeEntry(entry);
                    BotManager.getInstance().issuePatrol(entry, ownerPos);
                    BotManager.getInstance().botReply(entry, BotManager.randomReply(MOVE_HERE_REPLIES));
                });
            }
        } else if (isMoveHereCommand(message)) {
            Point dest = entry.owner != null ? new Point(entry.owner.getPosition()) : null;
            if (dest != null) {
                BotManager.after(BotManager.randMs(1000, 1500), () -> {
                    BotManager.getInstance().issueMoveTo(entry, dest, true);
                    BotManager.getInstance().botReply(entry, BotManager.randomReply(MOVE_HERE_REPLIES));
                });
            }
        } else if (isFollowCommand(message)) {
            BotManager.after(BotManager.randMs(1500, 2000), () -> {
                BotEquipManager.autoEquip(entry.bot, entry.owner, entry.pendingLootOfferItem);
                entry.nextGearSuggestionAt = 0;
                maybeSuggestGearToSiblings(entry, entry.bot);
                BotManager.getInstance().botReply(entry, BotManager.randomReply(FOLLOW_REPLIES));
                BotPotionManager.checkPotShareOnModeStart(entry, entry.bot);
                BotManager.after(BotManager.randMs(250, 750), () -> BotManager.getInstance().issueFollowOwner(entry));
            });
        } else if (isGrindCommand(message)) {
            BotManager.after(BotManager.randMs(1500, 2000), () -> {
                prepareActiveModeEntry(entry);
                BotManager.getInstance().botReply(entry, BotPotionManager.grindStartMessage(entry.bot));
                BotManager.after(BotManager.randMs(250, 750), () -> {
                    BotManager.getInstance().issueGrind(entry);
                    checkBotStatus(entry, entry.bot);
                });
            });
        } else if (isStopCommand(message)) {
            BotManager.after(BotManager.randMs(900, 1100), () -> {
                BotManager.getInstance().issueStop(entry);
                BotEquipManager.autoEquip(entry.bot, entry.owner, entry.pendingLootOfferItem);
                entry.nextGearSuggestionAt = 0;
                maybeSuggestGearToSiblings(entry, entry.bot);
                BotManager.after(BotManager.randMs(1400, 1600), () ->
                        BotManager.getInstance().botReply(entry, BotManager.randomReply(STOP_REPLIES)));
            });
        } else if (isFidgetCommand(message)) {
            BotManager.after(BotManager.randMs(250, 500), () -> {
                entry.bot.changeFaceExpression(randomFidgetExpression());
                BotFidgetManager.maybeStartSocialFidget(entry);
            });
        } else if (GREETING_PATTERN.matcher(message).matches()) {
            BotManager.after(BotManager.randMs(900, 1100), () -> {
                entry.bot.changeFaceExpression(Emote.HAPPY.getValue());
                BotFidgetManager.maybeStartGreetingFidget(entry, ThreadLocalRandom.current().nextInt(100));
                queueBotReply(entry, BotManager.randomReply(GREETING_REPLIES));
                checkBotStatus(entry, entry.bot);
            });
        }

        // SP build variant selection — only matched when waiting for an answer (Hero 1h vs 2h)
        if (entry.spVariantPromptSent && entry.spVariant == null) {
            if (SP_1H_PATTERN.matcher(message).find()) {
                entry.spVariant = "1h";
                BotManager.getInstance().botReply(entry, "ok! going 1h sword build, Brandish first");
                BotBuildManager.autoAssignSp(entry, entry.bot);
            } else if (SP_2H_PATTERN.matcher(message).find()) {
                entry.spVariant = "2h";
                BotManager.getInstance().botReply(entry, "ok! going 2h build, interleaving AC early for faster charges");
                BotBuildManager.autoAssignSp(entry, entry.bot);
            }
        }

        // AP build selection — "change build" always triggers a re-prompt;
        // "dexless" / "X dex" only apply when bot is actively waiting for the answer (apPromptSent=true)
        if (AP_CHANGE_BUILD_PATTERN.matcher(message).find()) {
            entry.apBuild      = null;
            entry.apPromptSent = false;
            String prompt = BotBuildManager.requestApBuildPrompt(entry, entry.bot);
            if (prompt != null) BotManager.getInstance().botReply(entry, prompt);
        } else if (entry.apPromptSent) {
            handleApBuildSelection(entry, message);
        }

        if (TRADE_INVITE_PATTERN.matcher(message).find()) {
            Character bot = entry.bot;
            Character owner = entry.owner;
            if (owner != null && bot.getTrade() == null && owner.getTrade() == null
                    && entry.pendingTradeCategory == null) {
                BotManager.after(BotManager.randMs(600, 1000), () -> {
                    BotManager.getInstance().botReply(entry, BotManager.randomReply(TRADE_INVITE_REPLIES));
                    BotManager.after(BotManager.randMs(800, 1200), () -> {
                        Trade.startTrade(bot);
                        Trade.inviteTrade(bot, owner);
                    });
                });
            }
            return;
        }

        if (SELL_TRASH_COMMAND_PATTERN.matcher(message).matches()) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotShopManager.requestSellTrashVisit(entry, entry.bot));
            return;
        }

        if (GRIND_WHERE_PATTERN.matcher(message).matches()) {
            // First pass scans WZ mob data — answer arrives when the thinking's done.
            BotManager.after(BotManager.randMs(900, 1600), () ->
                    BotGrindAdvisor.requestGrindAdvice(entry, entry.bot));
            return;
        }

        if (GRIND_DEBUG_PATTERN.matcher(message).matches()) {
            BotManager.after(BotManager.randMs(300, 500), () ->
                    BotGrindAdvisor.exportGrindDecision(entry, entry.bot));
            return;
        }

        if (MAKE_CRYSTALS_COMMAND_PATTERN.matcher(message).matches()) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotMakerManager.handleMakeCrystals(entry));
            return;
        }

        if (DISASSEMBLE_TRASH_COMMAND_PATTERN.matcher(message).matches()) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotMakerManager.handleDisassembleTrash(entry));
            return;
        }

        TransferCommand transferCommand = matchTransferCommand(message);
        if (transferCommand != null) {
            handleTransferCommand(entry, transferCommand, message);
            return;
        }

        String queriedItem = matchItemQuery(message);
        if (queriedItem != null) {
            handleItemQuery(entry, queriedItem);
            return;
        }

        // Info commands
        if (isRequestUpgradeCommand(message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> handleRequestUpgradeCommand(entry, entry.bot));
            return;
        }
        if (matchesWholeCommand(RECOMMENDED_GEAR_PATTERN, message)) {
            BotManager.after(BotManager.randMs(500, 700), () -> reportRecommendedGear(entry, entry.bot));
            return;
        }
        if (matchesWholeCommand(SKILLS_PATTERN, message)) {
            BotManager.after(BotManager.randMs(900, 1100), () -> reportSkills(entry, entry.bot));
            return;
        }
        if (matchesWholeCommand(STATS_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportStats(entry, entry.bot));
        if (isMovementStatsQuery(message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportMovementStats(entry, entry.bot));
        if (matchesWholeCommand(RANGE_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportRange(entry, entry.bot));
        if (matchesWholeCommand(BUILD_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportBuild(entry, entry.bot));
        if (matchesWholeCommand(INVENTORY_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportInventory(entry, entry.bot));
        if (isMesoQuery(message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportMesos(entry, entry.bot));
        if (matchesWholeCommand(EXP_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportExp(entry, entry.bot));
        if (matchesWholeCommand(INV_SLOTS_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportInventorySlots(entry, entry.bot));
        if (matchesWholeCommand(SCROLLS_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportScrolls(entry, entry.bot));
        if (matchesWholeCommand(POTIONS_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportPotions(entry, entry.bot));
        if (matchesWholeCommand(DEBUG_STATS_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportDebugStats(entry, entry.bot));
        if (matchesWholeCommand(CRIT_DEBUG_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportCritDebug(entry, entry.bot));
        if (matchesWholeCommand(POT_DEBUG_PATTERN, message))
            BotManager.after(BotManager.randMs(900, 1100), () -> reportPotDebug(entry, entry.bot));

        // Job advancement — check if message contains a valid job selection
        if (JOB_SELECT_PATTERN.matcher(message).find()) {
            Job advJob = resolveJobChange(entry.bot, message.toLowerCase());
            if (advJob != null) {
                String jobName = jobDisplayName(advJob);
                List<String> replies = List.of(
                        "ok, ill change to " + jobName + "!",
                        "alright becoming a " + jobName + " then",
                        "ok " + jobName + " it is!",
                        "sure, going " + jobName,
                        "ok changing to " + jobName + "...");
                BotManager.getInstance().botReply(entry, BotManager.randomReply(replies));
                BotManager.after(BotManager.randMs(900, 1100), () -> BotStarterKitManager.advanceJob(entry, advJob));
            }
        }
        LAST_CHAT_HANDLED.set(false);
    }

    private static void promptOwnerAway(BotEntry entry) {
        entry.pendingAction = "owner_away";
        BotManager.getInstance().issueStop(entry);
        if (BotManager.getInstance().shouldOfferTownForAwayCommand(entry)) {
            BotManager.getInstance().botReply(entry,
                    "ok, want us to wait at nearest town or logout? say yes/town or logout");
        } else {
            BotManager.getInstance().botReply(entry,
                    "ok, want us to stay safe here or logout? say yes/stay or logout");
        }
    }

    private static void handleOwnerAwayChoice(BotEntry entry, String message) {
        String choice = normalizeCommandText(message);
        boolean townOffered = BotManager.getInstance().shouldOfferTownForAwayCommand(entry);
        entry.pendingAction = null;

        if (AWAY_LOGOUT_CONFIRM_PATTERN.matcher(choice).matches()) {
            BotManager.after(BotManager.randMs(700, 900), () -> {
                BotManager.getInstance().botReply(entry, "ok, logging us out");
                logoutOwnerBots(entry);
            });
            return;
        }

        if (AWAY_TOWN_CONFIRM_PATTERN.matcher(choice).matches()) {
            int ownerId = entry.owner != null ? entry.owner.getId() : 0;
            if (ownerId != 0) {
                BotManager.getInstance().issueOwnerAwaySafeModeForOwner(ownerId, townOffered);
            }
            BotManager.after(BotManager.randMs(700, 900), () ->
                    BotManager.getInstance().botReply(entry, townOffered
                            ? "ok, heading to town and waiting"
                            : "ok, staying safe here"));
            return;
        }

        if (AWAY_STAY_CONFIRM_PATTERN.matcher(choice).matches() && !townOffered) {
            int ownerId = entry.owner != null ? entry.owner.getId() : 0;
            if (ownerId != 0) {
                BotManager.getInstance().issueOwnerAwaySafeModeForOwner(ownerId, false);
            }
            BotManager.after(BotManager.randMs(700, 900), () ->
                    BotManager.getInstance().botReply(entry, "ok, staying safe here"));
            return;
        }

        BotManager.after(BotManager.randMs(700, 900), () ->
                BotManager.getInstance().botReply(entry, "ok nvm, staying with you"));
    }

    private static void logoutOwnerBots(BotEntry entry) {
        Character owner = entry.owner;
        if (owner == null) {
            return;
        }

        for (BotEntry owned : BotManager.getInstance().getBotEntries(owner.getId())) {
            BotManager.getInstance().issueStop(owned);
            BotManager.after(BotManager.randMs(1200, 1800), () -> {
                owned.bot.saveCharToDB(true);
                owned.bot.getClient().disconnect(false, false);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Message queue — 5-second spacing between consecutive bot messages
    // -------------------------------------------------------------------------

    public static void queueBotSay(BotEntry entry, String message) {
        queueMessageWithEstimatedDelay(entry, message, false);
    }

    static void queueBotReply(BotEntry entry, String message) {
        queueMessageWithEstimatedDelay(entry, message, true);
    }

    static long queueBotSayWithEstimatedDelay(BotEntry entry, String message) {
        return queueMessageWithEstimatedDelay(entry, message, false);
    }

    static long queueBotReplyWithEstimatedDelay(BotEntry entry, String message) {
        return queueMessageWithEstimatedDelay(entry, message, true);
    }

    private static long queueMessageWithEstimatedDelay(BotEntry entry, String message, boolean ownerDirected) {
        long estimatedDelayMs;
        synchronized (entry.msgQueue) {
            estimatedDelayMs = entry.msgSending
                    ? (long) (entry.msgQueue.size() + 1) * 5_200L
                    : 0L;
            entry.msgQueue.add(new QueuedMessage(message, ownerDirected));
            if (!entry.msgSending) {
                entry.msgSending = true;
                drainMsgQueue(entry);
            }
        }
        return estimatedDelayMs;
    }

    private static void drainMsgQueue(BotEntry entry) {
        QueuedMessage msg;
        synchronized (entry.msgQueue) {
            msg = entry.msgQueue.poll();
            if (msg == null) { entry.msgSending = false; return; }
        }
        if (msg.ownerDirected) {
            BotManager.getInstance().botReply(entry, msg.text);
        } else {
            BotManager.getInstance().botSay(entry, msg.text);
        }
        BotManager.after(BotManager.randMs(4900, 5100), () -> drainMsgQueue(entry));
    }

    // Status check — called on spawn, grind start, greeting, and level-up
    static void checkBotStatus(BotEntry entry, Character bot) {
        String jobPrompt = BotBuildManager.buildJobPrompt(entry, bot);
        if (jobPrompt != null) queueBotReply(entry, jobPrompt);
        String spPrompt = BotBuildManager.buildSpVariantPrompt(entry, bot);
        if (spPrompt != null) {
            queueBotReply(entry, spPrompt);
        } else {
            BotBuildManager.autoAssignSp(entry, bot);
        }
        String apPrompt = BotBuildManager.buildApPrompt(entry, bot);
        if (apPrompt != null) {
            queueBotReply(entry, apPrompt);
        } else {
            BotBuildManager.autoAssignAp(entry, bot);
        }
        maybeSuggestRecommendedGear(entry, bot);
        maybeSuggestGearToSiblings(entry, bot);
        if (!entry.spawnUpgradeCheckDone) {
            entry.spawnUpgradeCheckDone = true;
            Character owner = entry.owner;
            if (owner != null && !isOwnerIdle(entry) && entry.pendingAction == null && !BotOfferManager.hasPendingOffer(entry)) {
                List<BotEquipManager.EquipRecommendation> recs = BotEquipManager.findRecommendedEquips(bot, owner);
                if (!recs.isEmpty()) {
                    BotOfferManager.notifyOwnerGainedEquip(entry, bot, recs.get(0).candidate());
                }
            }
        }
    }

    /**
     * Announces the bot's town location via party chat after the owner reconnects
     * (or revives) following a 5+ min offline-or-dead window during which the bot
     * scrolled to town. Party chat reaches the owner even if they spawn back into
     * a different map.
     */
    static void announceOwnerReturnedFromOffline(BotEntry entry) {
        final Character bot = entry.bot;
        if (bot == null) {
            return;
        }
        String mapName = bot.getMap() != null ? bot.getMap().getMapName() : null;
        if (mapName == null || mapName.isBlank()) {
            mapName = "town";
        }
        final String text = String.format(BotManager.randomReply(WB_OFFLINE_PARTY_TEMPLATES), mapName);
        BotManager.after(BotManager.randMs(1500, 2500), () -> {
            bot.changeFaceExpression(ThreadLocalRandom.current().nextBoolean() ? 2 : 3);
            BotManager.getInstance().botSayParty(bot, text);
        });
    }

    /** Detects owner AFK (same position ≥5 min) and says "wb" when they return. */
    static void tickAfkCheck(BotEntry entry, Character owner) {
        Point pos = owner.getPosition();
        long now  = System.currentTimeMillis();

        if (entry.ownerAfkPos == null) {
            entry.ownerAfkPos     = pos;
            entry.ownerAfkSinceMs = now;
            return;
        }

        if (!pos.equals(entry.ownerAfkPos)) {
            if (entry.ownerWasAfk) {
                entry.ownerWasAfk = false;
                final Character bot = entry.bot;
                BotManager.after(BotManager.randMs(1800, 2200), () -> {
                    bot.changeFaceExpression(ThreadLocalRandom.current().nextBoolean() ? 2 : 3);
                    BotManager.getInstance().botReply(entry, BotManager.randomReply(WB_REPLIES));
                });
            }
            entry.ownerAfkPos     = pos;
            entry.ownerAfkSinceMs = now;
        } else if (!entry.ownerWasAfk && (now - entry.ownerAfkSinceMs) >= 5 * 60_000L) {
            entry.ownerWasAfk = true;
        }
    }

    private static void reportStats(BotEntry entry, Character bot) {
        queueBotReply(entry, String.format("lv%d %s | str %d dex %d int %d luk %d | hp %d/%d mp %d/%d",
                bot.getLevel(), jobDisplayName(bot.getJob()),
                bot.getStr(), bot.getDex(), bot.getInt(), bot.getLuk(),
                bot.getHp(), bot.getCurrentMaxHp(),
                bot.getMp(), bot.getCurrentMaxMp()));
    }

    private static void reportRange(BotEntry entry, Character bot) {
        queueBotReply(entry, buildRangeReport(bot));
    }

    static String buildRangeReport(Character bot) {
        BotEquipManager.MapDamageProfile dmgProfile = BotEquipManager.MapDamageProfile.snapshot(bot);
        BotEquipManager.MapDamageProfile hitProfile = BotEquipManager.MapDamageProfile.snapshotByAvoid(bot);
        return buildRangeReport(bot, dmgProfile, hitProfile);
    }

    static String buildRangeReport(Character bot, BotEquipManager.MapDamageProfile mobProfile) {
        return buildRangeReport(bot, mobProfile, mobProfile);
    }

    private static String buildRangeReport(Character bot, BotEquipManager.MapDamageProfile mobProfile,
                                           BotEquipManager.MapDamageProfile hitProfile) {
        CombatFormulaProvider formulas = CombatFormulaProvider.getInstance();
        boolean magicAttack = BotEquipManager.isMageJob(bot.getJob());
        int attackStat;
        int accuracy;
        int minDmg;
        int maxDmg;
        String attackLabel;
        String accuracyLabel;

        if (magicAttack) {
            attackStat = bot.getTotalMagic();
            accuracy = formulas.getTotalMagicAccuracy(bot);
            maxDmg = (int) Math.max(1L, formulas.magicDamageBase(attackStat, bot.getTotalInt()));
            minDmg = (int) Math.max(1L, formulas.magicDamageBaseMin(attackStat, bot.getTotalInt(), 0.1d));
            attackLabel = "matk";
            accuracyLabel = "magic acc";
        } else {
            attackStat = bot.getTotalWatk();
            accuracy = formulas.getTotalAccuracy(bot);
            maxDmg = Math.max(1, bot.calculateMaxBaseDamage(attackStat));
            minDmg = Math.max(1, bot.calculateMinBaseDamage(attackStat, formulas.resolvePhysicalMastery(bot)));
            attackLabel = "watk";
            accuracyLabel = "acc";
        }

        String report = String.format("my dmg is %d-%d, %s %d, %s %d",
                minDmg, maxDmg, attackLabel, attackStat, accuracyLabel, accuracy);
        if (hitProfile == null) {
            return report;
        }

        double hitChance = magicAttack
                ? formulas.calculateMagicMobHitChance(accuracy, bot.getLevel(), hitProfile.mobLevel(), hitProfile.mobAvoid())
                : formulas.calculatePhysicalMobHitChance(accuracy, bot.getLevel(), hitProfile.mobLevel(), hitProfile.mobAvoid());
        int hitPercent = (int) Math.round(hitChance * 100.0d);
        return String.format("%s | hit %d%% vs hardest mob (avd %d)", report, hitPercent, hitProfile.mobAvoid());
    }

    private static void reportMovementStats(BotEntry entry, Character bot) {
        for (String line : buildMovementStatsReport(bot)) {
            queueBotReply(entry, line);
        }
    }

    private static void reportBuild(BotEntry entry, Character bot) {
        queueBotReply(entry, String.format("build: str %d / dex %d / int %d / luk %d, %d ap left",
                bot.getStr(), bot.getDex(), bot.getInt(), bot.getLuk(),
                bot.getRemainingAp()));
    }

    private static void reportSkills(BotEntry entry, Character bot) {
        if (bot.isBeginnerJob()) {
            reportBeginnerSkills(entry, bot);
            return;
        }

        Map<Integer, List<LearnedSkill>> skillTrees = collectLearnedSkillTrees(bot);
        if (skillTrees.isEmpty()) {
            queueBotReply(entry, "no job skills yet " + bot.getRemainingSp() + " SP left");
            return;
        }

        if (skillTrees.size() == 1) {
            Map.Entry<Integer, List<LearnedSkill>> onlyTree = skillTrees.entrySet().iterator().next();
            queueSkillTreeReport(entry, onlyTree.getKey(), onlyTree.getValue());
            return;
        }

        entry.pendingAction = SKILL_TREE_CHOICE_ACTION;
        queueBotReply(entry, skillTreeChoicePrompt(skillTrees));
    }

    private static void reportBeginnerSkills(BotEntry entry, Character bot) {
        List<LearnedSkill> beginnerSkills = collectLearnedBeginnerSkills(bot);
        int beginnerSpLeft = getRemainingBeginnerSp(bot);

        if (beginnerSkills.isEmpty()) {
            queueBotReply(entry, "no learned beginner skills yet " + beginnerSpLeft + " beginner SP left");
            return;
        }

        StringBuilder line = new StringBuilder("beginner: ");
        for (int i = 0; i < beginnerSkills.size(); i++) {
            if (i > 0) {
                line.append(", ");
            }

            LearnedSkill skill = beginnerSkills.get(i);
            line.append(skill.name()).append(" lv").append(skill.level());
        }
        line.append(" | ").append(beginnerSpLeft).append(" beginner SP left");
        queueBotReply(entry, line.toString());
    }

    private static void reportInventory(BotEntry entry, Character bot) {
        queueBotReply(entry, BotInventoryManager.inventorySummary(bot));
    }

    private static void reportMesos(BotEntry entry, Character bot) {
        queueBotReply(entry, buildMesoReport(bot.getMeso()));
    }

    private static void reportExp(BotEntry entry, Character bot) {
        queueBotReply(entry, buildExpReport(bot.getExp(), bot.getLevel()));
    }

    static String buildExpReport(int currentExp, int level) {
        int needed = ExpTable.getExpNeededForLevel(level);
        if (needed <= 0) {
            return "0%";
        }
        double pct = (currentExp / (double) needed) * 100.0;
        String formatted = String.format(Locale.ROOT, "%.2f", pct);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted + "%";
    }

    private static void reportInventorySlots(BotEntry entry, Character bot) {
        queueBotReply(entry, BotInventoryManager.slotsReport(bot));
    }

    private static void reportScrolls(BotEntry entry, Character bot) {
        int count = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            int id = item.getItemId();
            if (ItemConstants.isEquipScroll(id)) count += item.getQuantity();
        }
        queueBotReply(entry, count > 0
                ? "I have " + count + " scroll" + (count != 1 ? "s" : "") + " on me"
                : "no scrolls on me");
    }

    private static void reportPotions(BotEntry entry, Character bot) {
        int[] counts = BotPotionManager.countPotions(bot);
        queueBotReply(entry, buildPotionReport(counts[0], counts[1]));
    }

    private static void reportPotDebug(BotEntry entry, Character bot) {
        queueBotReply(entry, BotPotionManager.autopotDebugReport(bot));
    }

    static String buildPotionReport(int hp, int mp) {
        if (hp == 0 && mp == 0) {
            return "no pots on me rn";
        }
        if (mp == 0) {
            return "I have " + hp + " hp pot" + (hp != 1 ? "s" : "") + ", no mp pots";
        }
        if (hp == 0) {
            return "no hp pots, " + mp + " mp pot" + (mp != 1 ? "s" : "");
        }

        return "I have " + hp + " hp pot" + (hp != 1 ? "s" : "")
                + " and " + mp + " mp pot" + (mp != 1 ? "s" : "");
    }

    static boolean isMesoQuery(String message) {
        return matchesWholeCommand(MESOS_PATTERN, message);
    }

    static String buildMesoReport(int mesos) {
        String amount = formatCompactMesos(mesos);
        String pattern = MESO_REPLIES.get(ThreadLocalRandom.current().nextInt(MESO_REPLIES.size()));
        return String.format(pattern, amount);
    }

    static boolean isMovementStatsQuery(String message) {
        return matchesWholeCommand(MOVEMENT_STATS_PATTERN, message);
    }

    static boolean isLocationStatusQuery(String message) {
        return message != null && LOCATION_STATUS_PATTERN.matcher(message).matches();
    }

    static List<String> buildMovementStatsReport(Character bot) {
        if (bot == null) {
            return List.of("cant read my movement stats rn");
        }

        BotMovementProfile profile = BotMovementProfile.fromCharacter(bot);
        MapleMap map = bot.getMap();
        int rawSpeedStat = bot.getTotalMoveSpeedStat();
        int rawJumpStat = bot.getTotalJumpStat();
        String speedLine = movementStatLine(map, profile, rawSpeedStat, rawJumpStat);

        if (map == null) {
            return List.of(
                    speedLine,
                    String.format(Locale.ROOT, "walk %.1f px/s, hforce %.1f, climb %d px/tick",
                            profile.walkVelocityPxs(), profile.hForcePxs(), BotPhysicsEngine.climbStepPerTick()),
                    String.format(Locale.ROOT, "jump %.1f/tick, rope %.1f/tick, max jump %.1f px",
                            BotPhysicsEngine.jumpForcePerTick(profile),
                            BotPhysicsEngine.ropeJumpForcePerTick(profile),
                            BotPhysicsEngine.calculateMaxJumpHeight(profile))
            );
        }

        return List.of(
                speedLine,
                String.format(Locale.ROOT, "walk %.1f px/s, %d px/tick, climb %d, hforce %.1f",
                        profile.walkVelocityPxs(),
                        BotMovementManager.walkStep(map, profile),
                        BotPhysicsEngine.climbStepPerTick(),
                        profile.hForcePxs()),
                String.format(Locale.ROOT, "jump %.1f, rope %.1f, max %.1f px, reach %d/%d px",
                        BotPhysicsEngine.jumpForcePerTick(profile),
                        BotPhysicsEngine.ropeJumpForcePerTick(profile),
                        BotPhysicsEngine.calculateMaxJumpHeight(profile),
                        BotPhysicsEngine.maxJumpHorizontalTravel(map, profile),
                        BotPhysicsEngine.maxRopeJumpHorizontalTravel(map, profile))
        );
    }

    private static String movementStatLine(MapleMap map,
                                           BotMovementProfile profile,
                                           int rawSpeedStat,
                                           int rawJumpStat) {
        if (map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit())
                && (rawSpeedStat != profile.totalSpeedStat() || rawJumpStat != profile.totalJumpStat())) {
            return String.format(Locale.ROOT,
                    "speed %d%% jump %d%% (map forced; raw %d%%/%d%%)",
                    profile.totalSpeedStat(), profile.totalJumpStat(), rawSpeedStat, rawJumpStat);
        }
        return String.format(Locale.ROOT, "speed %d%% jump %d%%",
                profile.totalSpeedStat(), profile.totalJumpStat());
    }

    static String formatCompactMesos(int mesos) {
        if (mesos < 1_000) {
            return String.valueOf(mesos);
        }

        double value = mesos;
        String[] suffixes = {"k", "m", "b"};
        int suffixIndex = -1;

        while (value >= 1_000d && suffixIndex < suffixes.length - 1) {
            value /= 1_000d;
            suffixIndex++;
        }

        double rounded = Math.round(value * 10d) / 10d;
        if (rounded >= 1_000d && suffixIndex < suffixes.length - 1) {
            rounded = Math.round((rounded / 1_000d) * 10d) / 10d;
            suffixIndex++;
        }

        if (Math.floor(rounded) == rounded) {
            return String.format(Locale.ROOT, "%.0f%s", rounded, suffixes[suffixIndex]);
        }

        return String.format(Locale.ROOT, "%.1f%s", rounded, suffixes[suffixIndex]);
    }

    private static void reportDebugStats(BotEntry entry, Character bot) {
        queueBotReply(entry, BotCombatManager.describeDebugStats(entry, bot));
    }

    private static void reportCritDebug(BotEntry entry, Character bot) {
        CombatFormulaProvider formula = CombatFormulaProvider.getInstance();
        CombatFormulaProvider.CritProfile crit = formula.resolveCritProfile(bot);
        CombatFormulaProvider.DamageProfile dmg = formula.resolveDamageProfile(bot, 0, 0, false);

        int critPct = (int) Math.round(crit.critChance() * 100);
        if (critPct == 0) {
            queueBotReply(entry, "i can't crit (my job doesn't have a crit passive)");
            return;
        }

        int critMin = (int) Math.min(99999, Math.floor(dmg.minDamage() * crit.critMultiplier()));
        int critMax = (int) Math.min(99999, Math.floor(dmg.maxDamage() * crit.critMultiplier()));
        queueBotReply(entry, String.format(
                "crit: %d%% chance, %.2fx multiplier | base %d-%d | crit %d-%d",
                critPct, crit.critMultiplier(),
                dmg.minDamage(), dmg.maxDamage(),
                critMin, critMax));
    }

    private static void reportBuffDebug(BotEntry entry, Character bot) {
        for (String line : BotBuffManager.getDebugLines(entry, bot)) {
            queueBotReply(entry, line);
        }
    }

    private static void reportSkillBuffDebug(BotEntry entry, Character bot) {
        for (String line : BotCombatManager.getSkillBuffDebugLines(entry, bot)) {
            queueBotReply(entry, line);
        }
    }

    private static void reportHelp(BotEntry entry) {
        queueBotReply(entry, "commands: follow, stop, move here, fidget, grind, where are you, stats, speed, skills, inventory, mesos, exp, slots, scrolls, pots, debug stats, crit, respec, respec ap");
        queueBotReply(entry, "support: skill buffs on/off (= support on/off), heals on/off, buff on/off, buff cheap/max, proactive offers on/off, buff debug, skill buff debug");
        queueBotReply(entry, "gear: ask 'any upgrades?' or say 'trade recommended gear'");
        queueBotReply(entry, "supplies: need hp pot, need mp pot, need pot, need ammo");
        queueBotReply(entry, "trade: mesos, scrolls, pots, equips, etc, or named items");
    }

    static boolean isRespecCommand(String message) {
        return RESPEC_PATTERN.matcher(message).find();
    }

    static boolean isApRespecCommand(String message) {
        return AP_RESPEC_PATTERN.matcher(message).find();
    }

    static boolean isFarmHereCommand(String message) {
        return matchesWholeCommand(FARM_HERE_PATTERN, message);
    }

    static boolean isPatrolCommand(String message) {
        return matchesWholeCommand(PATROL_PATTERN, message);
    }

    static boolean isMoveHereCommand(String message) {
        return matchesWholeCommand(MOVE_HERE_PATTERN, message);
    }

    static boolean isProactiveOffersOnCommand(String message) {
        return PROACTIVE_OFFERS_ON_PATTERN.matcher(message).find();
    }

    static boolean isProactiveOffersOffCommand(String message) {
        return PROACTIVE_OFFERS_OFF_PATTERN.matcher(message).find();
    }

    static boolean isFollowCommand(String message) {
        return matchesWholeCommand(FOLLOW_PATTERN, message);
    }

    static boolean isGrindCommand(String message) {
        return matchesWholeCommand(GRIND_PATTERN, message);
    }

    static boolean isAutopilotCommand(String message) {
        return message != null && AUTOPILOT_PATTERN.matcher(message).matches();
    }

    static boolean isPartyAutopilotCommand(String message) {
        return message != null && PARTY_AUTOPILOT_PATTERN.matcher(message).matches();
    }

    static boolean isSailAwayCommand(String message) {
        return message != null && SAIL_AWAY_PATTERN.matcher(message).matches();
    }

    /** The item-name/id args of a "farm <item>" command, or null when it isn't one. */
    static String matchFarmItemArgs(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = FARM_ITEM_PATTERN.matcher(message);
        return matcher.matches() ? matcher.group(1) : null;
    }

    // Test seams: ItemInformationProvider's static init needs WZ/DB.
    static java.util.function.Function<String, List<tools.Pair<Integer, String>>> farmItemSearch =
            name -> ItemInformationProvider.getInstance().getItemDataByName(name);
    static java.util.function.IntFunction<String> farmItemName =
            id -> ItemInformationProvider.getInstance().getName(id);

    /**
     * Resolve "farm <item name|id>" to one item. Exact name match wins; otherwise ambiguity
     * gets a short numbered list back so the owner can repeat with the id (or more words).
     */
    private static void handleFarmItemCommand(BotEntry entry, String args) {
        String query = args.strip();
        int itemId;
        String itemName;
        Integer directId = null;
        try {
            directId = Integer.parseInt(query);
        } catch (NumberFormatException ignored) {
        }
        if (directId != null) {
            String name = farmItemName.apply(directId);
            if (name == null) {
                queueBotReply(entry, "don't know any item with id " + directId);
                return;
            }
            itemId = directId;
            itemName = name;
        } else {
            List<tools.Pair<Integer, String>> matches = farmItemSearch.apply(query);
            tools.Pair<Integer, String> exact = null;
            for (tools.Pair<Integer, String> m : matches) {
                if (m.getRight().equalsIgnoreCase(query)) {
                    exact = m;
                    break;
                }
            }
            if (matches.isEmpty()) {
                queueBotReply(entry, "never heard of '" + query + "'");
                return;
            }
            if (exact == null && matches.size() > 1) {
                StringBuilder options = new StringBuilder("which one? ");
                int shown = Math.min(4, matches.size());
                for (int i = 0; i < shown; i++) {
                    if (i > 0) {
                        options.append(", ");
                    }
                    options.append(matches.get(i).getRight()).append(" (").append(matches.get(i).getLeft()).append(")");
                }
                if (matches.size() > shown) {
                    options.append(", +").append(matches.size() - shown).append(" more");
                }
                queueBotReply(entry, options.toString());
                queueBotReply(entry, "say 'farm <id>' or be more specific");
                return;
            }
            tools.Pair<Integer, String> pick = exact != null ? exact : matches.get(0);
            itemId = pick.getLeft();
            itemName = pick.getRight();
        }
        int finalItemId = itemId;
        String finalItemName = itemName;
        BotManager.after(BotManager.randMs(900, 1600), () -> {
            prepareActiveModeEntry(entry);
            BotAutopilotManager.startFarmItem(entry, entry.bot, finalItemId, finalItemName);
        });
    }

    static boolean isStopCommand(String message) {
        return matchesWholeCommand(STOP_PATTERN, message);
    }

    private static void handleApBuildSelection(BotEntry entry, String message) {
        Job job = entry.bot.getJob();

        if (job.isA(Job.WARRIOR) && AP_PURE_STR_PATTERN.matcher(message).find()) {
            int effectiveDex = Math.max(minStatFloor(job, Stat.DEX), entry.bot.getDex());
            applyApBuildChoice(entry,
                    new BotBuildManager.ApBuild(BotBuildManager.StatType.STR, BotBuildManager.StatType.DEX, 4),
                    "dexless it is! keeping dex at " + effectiveDex + ", rest into str",
                    "already doing dexless!");
            return;
        }
        if (job.isA(Job.THIEF) && AP_DEXLESS_PATTERN.matcher(message).find()) {
            int effectiveDex = Math.max(minStatFloor(job, Stat.DEX), entry.bot.getDex());
            applyApBuildChoice(entry,
                    new BotBuildManager.ApBuild(BotBuildManager.StatType.LUK, BotBuildManager.StatType.DEX, 4),
                    "dexless it is! keeping dex at " + effectiveDex + ", rest into luk",
                    "already doing dexless!");
            return;
        }
        if (job.isA(Job.MAGICIAN) && AP_LUKLESS_PATTERN.matcher(message).find()) {
            int effectiveLuk = Math.max(minStatFloor(job, Stat.LUK), entry.bot.getLuk());
            applyApBuildChoice(entry,
                    new BotBuildManager.ApBuild(BotBuildManager.StatType.INT, BotBuildManager.StatType.LUK, 4),
                    "lukless it is! keeping luk at " + effectiveLuk + ", rest into int",
                    "already doing lukless!");
            return;
        }
        if (job.isA(Job.BOWMAN) && AP_STRLESS_PATTERN.matcher(message).find()) {
            int effectiveStr = Math.max(minStatFloor(job, Stat.STR), entry.bot.getStr());
            applyApBuildChoice(entry,
                    new BotBuildManager.ApBuild(BotBuildManager.StatType.DEX, BotBuildManager.StatType.STR, 4),
                    "strless it is! keeping str at " + effectiveStr + ", rest into dex",
                    "already doing strless!");
            return;
        }

        if (job.isA(Job.WARRIOR) || job.isA(Job.THIEF)) {
            Matcher matcher = AP_FIXED_DEX_PATTERN.matcher(message);
            if (matcher.find()) {
                int dexTarget = Integer.parseInt(matcher.group(1));
                int legalDexTarget = Math.max(minStatFloor(job, Stat.DEX), dexTarget);
                int effectiveDex = Math.max(legalDexTarget, entry.bot.getDex());
                BotBuildManager.StatType primary = job.isA(Job.WARRIOR)
                        ? BotBuildManager.StatType.STR
                        : BotBuildManager.StatType.LUK;
                applyApBuildChoice(entry,
                        new BotBuildManager.ApBuild(primary, BotBuildManager.StatType.DEX, dexTarget),
                        "ok! keeping dex at " + effectiveDex + ", rest into " + primary.name().toLowerCase(Locale.ROOT),
                        "already doing " + legalDexTarget + " dex build!");
                return;
            }
        }
        if (job.isA(Job.MAGICIAN)) {
            Matcher matcher = AP_FIXED_LUK_PATTERN.matcher(message);
            if (matcher.find()) {
                int lukTarget = Integer.parseInt(matcher.group(1));
                int legalLukTarget = Math.max(minStatFloor(job, Stat.LUK), lukTarget);
                int effectiveLuk = Math.max(legalLukTarget, entry.bot.getLuk());
                applyApBuildChoice(entry,
                        new BotBuildManager.ApBuild(BotBuildManager.StatType.INT, BotBuildManager.StatType.LUK, lukTarget),
                        "ok! keeping luk at " + effectiveLuk + ", rest into int",
                        "already doing " + legalLukTarget + " luk build!");
                return;
            }
        }
        if (job.isA(Job.BOWMAN)) {
            Matcher matcher = AP_FIXED_STR_PATTERN.matcher(message);
            if (matcher.find()) {
                int strTarget = Integer.parseInt(matcher.group(1));
                int legalStrTarget = Math.max(minStatFloor(job, Stat.STR), strTarget);
                int effectiveStr = Math.max(legalStrTarget, entry.bot.getStr());
                applyApBuildChoice(entry,
                        new BotBuildManager.ApBuild(BotBuildManager.StatType.DEX, BotBuildManager.StatType.STR, strTarget),
                        "ok! keeping str at " + effectiveStr + ", rest into dex",
                        "already doing " + legalStrTarget + " str build!");
            }
        }
    }

    private static int minStatFloor(Job job, Stat stat) {
        return AssignAPProcessor.getMinStatFloor(job, stat);
    }

    private static void applyApBuildChoice(BotEntry entry, BotBuildManager.ApBuild build, String confirmMsg, String alreadyMsg) {
        if (sameApBuild(entry.apBuild, build)) {
            BotManager.getInstance().botReply(entry, alreadyMsg);
            return;
        }
        BotBuildManager.setApBuild(entry, build, confirmMsg);
    }

    private static boolean sameApBuild(BotBuildManager.ApBuild left, BotBuildManager.ApBuild right) {
        return left != null
                && right != null
                && left.primaryStat == right.primaryStat
                && left.secondaryStat == right.secondaryStat
                && left.secondaryTarget == right.secondaryTarget;
    }

    private static boolean matchesWholeCommand(Pattern pattern, String message) {
        String normalized = normalizeCommandText(message);
        return !normalized.isEmpty() && pattern.matcher(normalized).matches();
    }

    private static String normalizeCommandText(String message) {
        if (message == null) {
            return "";
        }

        return message.strip()
                .replaceAll("^[\\p{Punct}\\s]+", "")
                .replaceAll("[\\p{Punct}\\s]+$", "")
                .replaceFirst("^(?:(?:please|pls|hey|yo)\\s+)+", "")
                .replaceFirst("^(?:(?:can|could|will|would)\\s+you\\s+)", "")
                .replaceFirst("^(?:(?:please|pls)\\s+)+", "")
                .replaceFirst("\\s+(?:please|pls)$", "")
                .replaceAll("\\s+", " ");
    }

    private static void reportRecommendedGear(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null) {
            queueBotReply(entry, "can't check your gear rn");
            return;
        }
        if (!BotOfferManager.offerBestRecommendedGear(entry, bot, owner)) {
            queueBotReply(entry, "no better gear for you rn");
        }
        entry.nextGearSuggestionAt = System.currentTimeMillis() + 60_000L;
    }

    private static void maybeSuggestRecommendedGear(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        long now = System.currentTimeMillis();
        if (owner == null || now < entry.nextGearSuggestionAt) {
            return;
        }

        if (BotOfferManager.offerBestRecommendedGear(entry, bot, owner)) {
            entry.nextGearSuggestionAt = now + 60_000L;
        }
    }

    /** Check if this bot has gear that would be an upgrade for a sibling bot. */
    private static void maybeSuggestGearToSiblings(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        long now = System.currentTimeMillis();
        if (owner == null || now < entry.nextGearSuggestionAt) {
            return;
        }

        if (BotOfferManager.offerBestGearToSibling(entry, bot)) {
            entry.nextGearSuggestionAt = now + 60_000L;
        }
    }

    /**
     * Shared prelude for owner-issued active-combat-mode commands (grind / sentry
     * / patrol). Keeps the modes in lock-step on autoEquip, gear suggestion,
     * autopot keybind setup, and the initial pot-share request — otherwise new
     * modes silently miss one of these (the original sentry-mode bug).
     */
    static void prepareActiveModeEntry(BotEntry entry) {
        BotEquipManager.autoEquip(entry.bot, entry.owner, entry.pendingLootOfferItem);
        entry.nextGearSuggestionAt = 0;
        maybeSuggestGearToSiblings(entry, entry.bot);
        BotPotionManager.setupAutopotForBot(entry.bot);
        BotPotionManager.checkPotShareOnModeStart(entry, entry.bot);
    }

    /** Returns true when the owner hasn't moved in ≥5 min (AFK). Skip chat interactions. */
    static boolean isOwnerIdle(BotEntry entry) {
        return entry.ownerWasAfk;
    }

    static boolean isFidgetCommand(String message) {
        return message != null && FIDGET_PATTERN.matcher(message).find();
    }

    static int randomFidgetExpression() {
        int[] expressions = {2, 3, 5, 6, 7};
        return expressions[ThreadLocalRandom.current().nextInt(expressions.length)];
    }

    static boolean isNeedHpPotCommand(String message) {
        return NEED_HP_POT_PATTERN.matcher(message).find();
    }

    static boolean isNeedMpPotCommand(String message) {
        return NEED_MP_POT_PATTERN.matcher(message).find();
    }

    static boolean isNeedPotCommand(String message) {
        return NEED_POT_PATTERN.matcher(message).find();
    }

    static boolean isNeedAmmoCommand(String message) {
        return NEED_AMMO_PATTERN.matcher(message).find();
    }

    static boolean isRequestUpgradeCommand(String message) {
        return matchesWholeCommand(REQUEST_UPGRADE_PATTERN, message);
    }

    /**
     * Group-wide supply requests ("need pots", "anyone have hp pots", "need arrows"
     * etc.) trigger a single response from the bot group. Broadcasting these to
     * every entry causes duplicate replies and duplicate trades because each bot
     * independently selects the same donor sibling.
     */
    static boolean isGroupSupplyRequest(String message) {
        return isNeedHpPotCommand(message)
                || isNeedMpPotCommand(message)
                || isNeedPotCommand(message)
                || isNeedAmmoCommand(message);
    }

    private static void handleRequestUpgradeCommand(BotEntry entry, Character bot) {
        BotOfferManager.clearPendingOfferForOwnerAsk(entry);
        if (BotPotionManager.requestLowSuppliesFromOwnerAsk(entry, bot)) {
            return;
        }
        BotOfferManager.requestBestUpgradeFromOwner(entry, bot);
    }

    private static void handleNeedAnyPotionCommand(BotEntry entry) {
        if (entry.owner == null) {
            return;
        }
        int[] pots = BotPotionManager.countPotions(entry.owner);
        handleNeedPotionCommand(entry, pots[0] <= pots[1]);
    }

    private static void handleNeedPotionCommand(BotEntry entry, boolean forHp) {
        BotPotionManager.OwnerPotShareResult result = BotPotionManager.offerPotShareToOwner(entry, forHp);
        if (result == BotPotionManager.OwnerPotShareResult.NO_DONOR) {
            String type = forHp ? "hp" : "mp";
            queueBotReply(entry, String.format(BotManager.randomReply(OWNER_POT_SHORTAGE_REPLIES), type));
        }
    }

    private static void handleNeedAmmoCommand(BotEntry entry) {
        Character owner = entry.owner;
        if (owner == null) {
            return;
        }
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(owner);
        if (weaponType != WeaponType.BOW && weaponType != WeaponType.CROSSBOW) {
            queueBotReply(entry, BotManager.randomReply(AMMO_NOT_NEEDED_REPLIES));
            return;
        }
        BotAmmoManager.OwnerAmmoShareResult result = BotAmmoManager.offerAmmoShareToOwner(entry, weaponType);
        if (result == BotAmmoManager.OwnerAmmoShareResult.NO_DONOR) {
            queueBotReply(entry, BotManager.randomReply(OWNER_AMMO_SHORTAGE_REPLIES));
        }
    }

    private static void handleSkillTreeChoice(BotEntry entry, Character bot, String message) {
        Map<Integer, List<LearnedSkill>> skillTrees = collectLearnedSkillTrees(bot);
        if (skillTrees.isEmpty()) {
            entry.pendingAction = null;
            queueBotReply(entry, "no job skills yet");
            return;
        }

        if (skillTrees.size() == 1) {
            entry.pendingAction = null;
            Map.Entry<Integer, List<LearnedSkill>> onlyTree = skillTrees.entrySet().iterator().next();
            queueSkillTreeReport(entry, onlyTree.getKey(), onlyTree.getValue());
            return;
        }

        Integer treeId = resolveSkillTreeChoice(message, skillTrees);
        if (treeId == null) {
            queueBotReply(entry, skillTreeChoicePrompt(skillTrees));
            return;
        }

        entry.pendingAction = null;
        queueSkillTreeReport(entry, treeId, skillTrees.get(treeId));
    }

    private static Map<Integer, List<LearnedSkill>> collectLearnedSkillTrees(Character bot) {
        Map<Integer, List<LearnedSkill>> skillTrees = new TreeMap<>();
        for (Map.Entry<Skill, Character.SkillEntry> entry : bot.getSkills().entrySet()) {
            Skill skill = entry.getKey();
            Character.SkillEntry skillEntry = entry.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }

            int treeId = skillId / 10000;
            skillTrees.computeIfAbsent(treeId, ignored -> new ArrayList<>())
                    .add(new LearnedSkill(skillId, skillName(skillId), skillEntry.skillevel));
        }

        for (List<LearnedSkill> skills : skillTrees.values()) {
            skills.sort(Comparator.comparingInt(LearnedSkill::id));
        }
        return skillTrees;
    }

    private static List<LearnedSkill> collectLearnedBeginnerSkills(Character bot) {
        List<LearnedSkill> beginnerSkills = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> entry : bot.getSkills().entrySet()) {
            Skill skill = entry.getKey();
            Character.SkillEntry skillEntry = entry.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (!skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }

            beginnerSkills.add(new LearnedSkill(skillId, skillName(skillId), skillEntry.skillevel));
        }

        beginnerSkills.sort(Comparator.comparingInt(LearnedSkill::id));
        return beginnerSkills;
    }

    private static int getRemainingBeginnerSp(Character bot) {
        int usedBeginnerSp = 0;
        int beginnerSkillBase = bot.getJobType() * 10000000 + 1000;
        for (int i = 0; i < 3; i++) {
            Skill skill = SkillFactory.getSkill(beginnerSkillBase + i);
            if (skill != null) {
                usedBeginnerSp += bot.getSkillLevel(skill);
            }
        }

        return Math.max(0, Math.min(bot.getLevel() - 1, 6) - usedBeginnerSp);
    }

    private static void queueSkillTreeReport(BotEntry entry, int treeId, List<LearnedSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            queueBotReply(entry, "no learned skills in " + skillTreeLabel(treeId));
            return;
        }

        String label = skillTreeLabel(treeId);
        String prefix = label + ": ";
        String followupPrefix = "more " + label + ": ";
        StringBuilder line = new StringBuilder(prefix);
        int countOnLine = 0;

        for (LearnedSkill skill : skills) {
            String piece = skill.name() + " lv" + skill.level();
            boolean needsSeparator = countOnLine > 0;
            int extraChars = piece.length() + (needsSeparator ? 2 : 0);
            if ((line.length() + extraChars > 100 || countOnLine >= 3) && countOnLine > 0) {
                queueBotReply(entry, line.toString());
                line = new StringBuilder(followupPrefix);
                countOnLine = 0;
                needsSeparator = false;
            }

            if (needsSeparator) {
                line.append(", ");
            }
            line.append(piece);
            countOnLine++;
        }

        if (countOnLine > 0) {
            queueBotReply(entry, line.toString());
        }
    }

    private static Integer resolveSkillTreeChoice(String message, Map<Integer, List<LearnedSkill>> skillTrees) {
        Matcher matcher = Pattern.compile("\\b(\\d{3,4})\\b").matcher(message);
        while (matcher.find()) {
            int treeId = Integer.parseInt(matcher.group(1));
            if (skillTrees.containsKey(treeId)) {
                return treeId;
            }
        }

        String normalizedMessage = normalizeChoiceText(message);
        List<Integer> matches = new ArrayList<>();
        for (int treeId : skillTrees.keySet()) {
            if (matchesSkillTreeChoice(normalizedMessage, treeId)) {
                matches.add(treeId);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean matchesSkillTreeChoice(String normalizedMessage, int treeId) {
        String fullLabel = normalizeChoiceText(skillTreeLabel(treeId));
        if (!fullLabel.isEmpty() && normalizedMessage.contains(fullLabel)) {
            return true;
        }

        Job job = Job.getById(treeId);
        if (job == null) {
            return false;
        }

        String baseLabel = normalizeChoiceText(jobDisplayName(job));
        return !baseLabel.isEmpty() && normalizedMessage.contains(baseLabel);
    }

    private static String skillTreeChoicePrompt(Map<Integer, List<LearnedSkill>> skillTrees) {
        List<String> labels = new ArrayList<>();
        for (int treeId : skillTrees.keySet()) {
            labels.add(skillTreeLabel(treeId));
        }
        return "which skill tree? " + String.join(", ", labels);
    }

    private static String skillTreeLabel(int treeId) {
        Job job = Job.getById(treeId);
        if (job == null) {
            return "tree " + treeId;
        }

        return switch (job) {
            case NOBLESSE -> "noblesse (" + treeId + ")";
            case DAWNWARRIOR1 -> "dawn warrior 1st job (" + treeId + ")";
            case DAWNWARRIOR2 -> "dawn warrior 2nd job (" + treeId + ")";
            case DAWNWARRIOR3 -> "dawn warrior 3rd job (" + treeId + ")";
            case DAWNWARRIOR4 -> "dawn warrior 4th job (" + treeId + ")";
            case BLAZEWIZARD1 -> "blaze wizard 1st job (" + treeId + ")";
            case BLAZEWIZARD2 -> "blaze wizard 2nd job (" + treeId + ")";
            case BLAZEWIZARD3 -> "blaze wizard 3rd job (" + treeId + ")";
            case BLAZEWIZARD4 -> "blaze wizard 4th job (" + treeId + ")";
            case WINDARCHER1 -> "wind archer 1st job (" + treeId + ")";
            case WINDARCHER2 -> "wind archer 2nd job (" + treeId + ")";
            case WINDARCHER3 -> "wind archer 3rd job (" + treeId + ")";
            case WINDARCHER4 -> "wind archer 4th job (" + treeId + ")";
            case NIGHTWALKER1 -> "night walker 1st job (" + treeId + ")";
            case NIGHTWALKER2 -> "night walker 2nd job (" + treeId + ")";
            case NIGHTWALKER3 -> "night walker 3rd job (" + treeId + ")";
            case NIGHTWALKER4 -> "night walker 4th job (" + treeId + ")";
            case THUNDERBREAKER1 -> "thunder breaker 1st job (" + treeId + ")";
            case THUNDERBREAKER2 -> "thunder breaker 2nd job (" + treeId + ")";
            case THUNDERBREAKER3 -> "thunder breaker 3rd job (" + treeId + ")";
            case THUNDERBREAKER4 -> "thunder breaker 4th job (" + treeId + ")";
            case LEGEND -> "legend (" + treeId + ")";
            case ARAN1 -> "aran 1st job (" + treeId + ")";
            case ARAN2 -> "aran 2nd job (" + treeId + ")";
            case ARAN3 -> "aran 3rd job (" + treeId + ")";
            case ARAN4 -> "aran 4th job (" + treeId + ")";
            case EVAN -> "evan (" + treeId + ")";
            case EVAN1 -> "evan 1st job (" + treeId + ")";
            case EVAN2 -> "evan 2nd job (" + treeId + ")";
            case EVAN3 -> "evan 3rd job (" + treeId + ")";
            case EVAN4 -> "evan 4th job (" + treeId + ")";
            case EVAN5 -> "evan 5th job (" + treeId + ")";
            case EVAN6 -> "evan 6th job (" + treeId + ")";
            case EVAN7 -> "evan 7th job (" + treeId + ")";
            case EVAN8 -> "evan 8th job (" + treeId + ")";
            case EVAN9 -> "evan 9th job (" + treeId + ")";
            case EVAN10 -> "evan 10th job (" + treeId + ")";
            default -> jobDisplayName(job) + " (" + treeId + ")";
        };
    }

    private static String normalizeChoiceText(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String skillName(int skillId) {
        String name = SkillFactory.getSkillName(skillId);
        return name != null && !name.isBlank() ? name : String.valueOf(skillId);
    }

    private static void handleTransferCommand(BotEntry entry, TransferCommand transferCommand, String message) {
        String category = transferCommand.category;
        if (transferCommand.mode == TransferMode.TRADE
                && "trash".equals(category)
                && message != null
                && SHOW_JUNK_COMMAND_PATTERN.matcher(message).matches()) {
            BotManager.getInstance().botReply(entry, "that sounded weird but ok");
        }
        if (transferCommand.mode == TransferMode.TRADE && BotInventoryManager.isMesoCategory(category)) {
            BotManager.after(BotManager.randMs(500, 700), () ->
                    BotInventoryManager.startTradeTransfer(category, entry, entry.bot));
            return;
        }

        scheduleTransferCommandEvaluation(entry, transferCommand, category);
    }

    private static void scheduleTransferCommandEvaluation(BotEntry entry, TransferCommand transferCommand, String category) {
        Character bot = entry.bot;
        if (bot == null) {
            return;
        }

        int requestId = nextTransferRequestId(bot);
        long replyDelay = BotManager.randMs(500, 700);
        long requestedAt = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> evaluateTransferCommand(entry, transferCommand, category, bot), TRADE_COMMAND_EXECUTOR)
                .thenAccept(result -> {
                    long elapsedMs = (System.nanoTime() - requestedAt) / 1_000_000L;
                    long remainingDelay = Math.max(0L, replyDelay - elapsedMs);
                    BotManager.after(remainingDelay, () ->
                            applyTransferCommandResult(entry, transferCommand, category, bot, requestId, result));
                });
    }

    private static TransferCommandResult evaluateTransferCommand(BotEntry entry,
                                                                 TransferCommand transferCommand,
                                                                 String category,
                                                                 Character bot) {
        long hasItemsStartedAt = transferCommand.mode == TransferMode.TRADE
                && BotInventoryManager.profileTradeCategory(category)
                ? System.nanoTime() : 0L;
        boolean hasItems = BotInventoryManager.hasTransferableItems(category, entry, bot);
        BotInventoryManager.logSlowTradeCommand(category, "hasTransferableItems", entry, bot, hasItemsStartedAt);
        int count = hasItems && transferCommand.mode == TransferMode.CHOICE
                ? BotInventoryManager.countTransferableItems(category, entry, bot)
                : 0;
        return new TransferCommandResult(hasItems, count);
    }

    private static void applyTransferCommandResult(BotEntry entry,
                                                   TransferCommand transferCommand,
                                                   String category,
                                                   Character bot,
                                                   int requestId,
                                                   TransferCommandResult result) {
        if (!isLatestTransferRequest(bot, requestId)) {
            return;
        }
        if (!result.hasItems()) {
            BotManager.getInstance().botReply(entry, BotInventoryManager.noItemsReply(category));
            return;
        }

        switch (transferCommand.mode) {
            case TRADE -> BotInventoryManager.startTradeTransfer(category, entry, bot);
            case CHOICE -> {
                entry.pendingAction = "item_choice";
                entry.pendingDropCategory = category;
                BotManager.getInstance().botReply(entry, dropOrTradePrompt(category, result.count()));
            }
        }
    }

    private static int nextTransferRequestId(Character bot) {
        return PENDING_TRANSFER_REQUESTS
                .computeIfAbsent(bot.getId(), ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    private static boolean isLatestTransferRequest(Character bot, int requestId) {
        AtomicInteger current = PENDING_TRANSFER_REQUESTS.get(bot.getId());
        return current != null && current.get() == requestId;
    }

    private static void handleItemQuery(BotEntry entry, String itemName) {
        String category = "name:" + itemName;
        Character bot = entry.bot;
        if (bot == null) {
            return;
        }

        int requestId = nextTransferRequestId(bot);
        long replyDelay = BotManager.randMs(500, 700);
        long requestedAt = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> new ItemQueryResult(
                        BotInventoryManager.countTransferableItems(category, entry, bot)), TRADE_COMMAND_EXECUTOR)
                .thenAccept(result -> {
                    long elapsedMs = (System.nanoTime() - requestedAt) / 1_000_000L;
                    long remainingDelay = Math.max(0L, replyDelay - elapsedMs);
                    BotManager.after(remainingDelay, () ->
                            applyItemQueryResult(entry, category, bot, requestId, result));
                });
    }

    private static void applyItemQueryResult(BotEntry entry,
                                             String category,
                                             Character bot,
                                             int requestId,
                                             ItemQueryResult result) {
        if (!isLatestTransferRequest(bot, requestId)) {
            return;
        }
        if (result.count() <= 0) {
            BotManager.getInstance().botReply(entry, BotInventoryManager.noItemsReply(category));
            return;
        }

        entry.pendingAction = "item_choice";
        entry.pendingDropCategory = category;
        BotManager.getInstance().botReply(entry, dropOrTradePrompt(category, result.count()));
    }

    private static TransferCommand matchTransferCommand(String message) {
        String tradeCategory = matchTradeCategory(message);
        if (tradeCategory != null) {
            return new TransferCommand(TransferMode.TRADE, tradeCategory);
        }

        String choiceCategory = matchChoiceCategory(message);
        if (choiceCategory != null) {
            return new TransferCommand(TransferMode.CHOICE, choiceCategory);
        }

        return null;
    }

    static String matchItemQuery(String message) {
        String itemName = matchNormalizedItemQuery(message);
        if (itemName == null) return null;
        String generic = itemName.toLowerCase(Locale.ROOT);
        if (generic.equals("pot") || generic.equals("potion")) {
            return null;
        }
        if (Pattern.matches(TRASH_WORDS, generic)) {
            return null;
        }
        return itemName;
    }

    static String matchTradeCategory(String message) {
        String mesoCategory = matchTradeMesoCategory(message);
        if (mesoCategory != null) return mesoCategory;
        if (message != null && SHOW_JUNK_COMMAND_PATTERN.matcher(message).matches()) return "trash";
        if ("trash".equals(matchItemQueryCategory(message))) return "trash";

        if (TRADE_RECOMMENDED_COMMAND_PATTERN.matcher(message).find()) return "recommended";
        if (TRADE_SCROLLS_COMMAND_PATTERN.matcher(message).find()) return "scrolls";
        if (TRADE_POTS_COMMAND_PATTERN.matcher(message).find()) return "pots";
        if (TRADE_BUFF_COMMAND_PATTERN.matcher(message).find()) return "buff";
        if (TRADE_AMMO_COMMAND_PATTERN.matcher(message).find()) return "ammo";
        Matcher reserveMatcher = TRADE_RESERVE_COMMAND_PATTERN.matcher(message);
        if (reserveMatcher.matches()) {
            return BotInventoryManager.reservedEquipsCategory(parseTradeReservePage(reserveMatcher.group(1)));
        }
        if (TRADE_USE_COMMAND_PATTERN.matcher(message).find()) return "use";
        if (TRADE_EQUIPS_COMMAND_PATTERN.matcher(message).find()) return "equips";
        if (TRADE_TRASH_COMMAND_PATTERN.matcher(message).find()) return "trash";
        if (TRADE_ETC_COMMAND_PATTERN.matcher(message).find()) return "etc";
        Matcher viewSlotMatcher = TRADE_VIEW_SLOT_COMMAND_PATTERN.matcher(message);
        if (viewSlotMatcher.find()) return "name:" + BotInventoryManager.normalizeItemQuery(viewSlotMatcher.group(1));

        Matcher matcher = TRADE_ITEM_COMMAND_PATTERN.matcher(message);
        return matcher.find() ? "name:" + BotInventoryManager.normalizeItemQuery(matcher.group(1)) : null;
    }

    static String matchFollowTarget(String message) {
        Matcher matcher = FOLLOW_TARGET_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        String target = matcher.group(1);
        if (target == null) {
            return null;
        }

        String normalized = target.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "me", "here", "pls", "please", "now" -> null;
            default -> target.trim();
        };
    }

    private static String matchTradeMesoCategory(String message) {
        Matcher matcher = TRADE_MESOS_AFTER_WORD_COMMAND_PATTERN.matcher(message);
        if (matcher.find()) {
            return "mesos:" + parseMesoAmount(matcher.group(1));
        }

        matcher = TRADE_MESOS_COMMAND_PATTERN.matcher(message);
        if (matcher.find()) {
            if (matcher.group(1) != null) {
                return "mesos";
            }

            String amountToken = matcher.group(2);
            if (amountToken == null || amountToken.isBlank()) {
                return "mesos";
            }

            return "mesos:" + parseMesoAmount(amountToken);
        }

        matcher = TRADE_MESOS_AMOUNT_ONLY_COMMAND_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        if (matcher.group(1) != null) {
            return "mesos";
        }

        return "mesos:" + parseMesoAmount(matcher.group(2));
    }

    private static int parseMesoAmount(String amountToken) {
        String normalized = amountToken.toLowerCase().replace(",", "").replaceAll("\\s+", "");
        long multiplier = 1L;
        if (!normalized.isEmpty()) {
            char suffix = normalized.charAt(normalized.length() - 1);
            if (suffix == 'k' || suffix == 'm' || suffix == 'b') {
                multiplier = switch (suffix) {
                    case 'k' -> 1_000L;
                    case 'm' -> 1_000_000L;
                    default -> 1_000_000_000L;
                };
                normalized = normalized.substring(0, normalized.length() - 1);
            }
        }

        if (normalized.isEmpty()) {
            return 0;
        }

        try {
            long amount = Math.round(Double.parseDouble(normalized) * multiplier);
            if (amount < 0) {
                return 0;
            }
            return (int) Math.min(amount, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int parseTradeReservePage(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(pageToken);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    static String matchChoiceCategory(String message) {
        if (DROP_SCROLLS_COMMAND_PATTERN.matcher(message).find()) return "scrolls";
        if (DROP_POTS_COMMAND_PATTERN.matcher(message).find()) return "pots";
        if (DROP_BUFF_COMMAND_PATTERN.matcher(message).find()) return "buff";
        if (DROP_USE_COMMAND_PATTERN.matcher(message).find()) return "use";
        if (DROP_EQUIPS_COMMAND_PATTERN.matcher(message).find()) return "equips";
        if (DROP_TRASH_COMMAND_PATTERN.matcher(message).find()) return "trash";
        if (DROP_ETC_COMMAND_PATTERN.matcher(message).find()) return "etc";
        Matcher dropMatcher = DROP_ITEM_COMMAND_PATTERN.matcher(message);
        if (dropMatcher.find()) return "name:" + BotInventoryManager.normalizeItemQuery(dropMatcher.group(1));

        if (ASK_SCROLLS_COMMAND_PATTERN.matcher(message).find()) return "scrolls";
        if (ASK_POTS_COMMAND_PATTERN.matcher(message).find()) return "pots";
        if (ASK_BUFF_COMMAND_PATTERN.matcher(message).find()) return "buff";
        if (ASK_USE_COMMAND_PATTERN.matcher(message).find()) return "use";
        if (ASK_EQUIPS_COMMAND_PATTERN.matcher(message).find()) return "equips";
        if (ASK_ETC_COMMAND_PATTERN.matcher(message).find()) return "etc";

        Matcher matcher = ASK_ITEM_COMMAND_PATTERN.matcher(message);
        return matcher.find() ? "name:" + BotInventoryManager.normalizeItemQuery(matcher.group(1)) : null;
    }

    private static String matchNormalizedItemQuery(String message) {
        Matcher matcher = ITEM_QUERY_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String itemName = BotInventoryManager.normalizeItemQuery(matcher.group(1));
        return itemName.isBlank() ? null : itemName;
    }

    private static String matchItemQueryCategory(String message) {
        String itemName = matchNormalizedItemQuery(message);
        if (itemName == null) return null;
        String generic = itemName.toLowerCase(Locale.ROOT);
        return Pattern.matches(TRASH_WORDS, generic) ? "trash" : null;
    }

    private static final String[] DROP_OR_TRADE_PROMPTS = {
        "got %s, want me to trade or drop?",
        "i have %s, trade or drop?",
        "sure, %s - trade or drop?",
        "just to confirm, trade or drop my %s?",
        "want me to trade or drop %s?",
    };

    private static String dropOrTradePrompt(String category, int count) {
        String base = switch (category) {
            case "scrolls" -> "scrolls";
            case "pots"    -> "pots";
            case "buff"    -> "buff pots";
            case "use"     -> "use items";
            case "equips"  -> "equips";
            case "etc"     -> "etc items";
            default        -> category.startsWith("name:") ? category.substring(5) : "those items";
        };
        String what = count > 0 ? count + " " + base : base;
        String fmt = DROP_OR_TRADE_PROMPTS[ThreadLocalRandom.current().nextInt(DROP_OR_TRADE_PROMPTS.length)];
        return String.format(fmt, what);
    }

    /** Maps a chat keyword to the correct next Job given bot's current job and level. Returns null if not valid. */
    private static Job resolveJobChange(Character bot, String msg) {
        Job cur = bot.getJob();
        int lvl = bot.getLevel();

        return switch (cur) {
            case BEGINNER -> {
                if (lvl >= 8  && msg.matches(".*\\b(mage|magician|wizard|cleric|healer|fp|il|fp mage|il mage)\\b.*")) yield Job.MAGICIAN;
                if (lvl >= 10 && msg.matches(".*\\b(warrior|fighter|page|spearman|sader)\\b.*")) yield Job.WARRIOR;
                if (lvl >= 10 && msg.matches(".*\\b(bowman|bowmen|archer|hunter|crossbow|xbow)\\b.*")) yield Job.BOWMAN;
                if (lvl >= 10 && msg.matches(".*\\b(thief|assassin|sin|bandit|dit)\\b.*")) yield Job.THIEF;
                if (lvl >= 10 && msg.matches(".*\\b(pirate|brawler|gunslinger|gun|bucc)\\b.*")) yield Job.PIRATE;
                yield null;
            }
            // 2nd job
            case WARRIOR -> lvl < 30 ? null :
                    msg.matches(".*\\b(fighter|sader)\\b.*") ? Job.FIGHTER :
                    msg.matches(".*\\bpage\\b.*") ? Job.PAGE :
                    msg.matches(".*\\b(spearman|spear)\\b.*") ? Job.SPEARMAN : null;
            case MAGICIAN -> lvl < 30 ? null :
                    msg.matches(".*\\b(fp|fp wizard|fp mage|fire|f\\.p)\\b.*") ? Job.FP_WIZARD :
                    msg.matches(".*\\b(il|il wizard|il mage|ice|i\\.l)\\b.*") ? Job.IL_WIZARD :
                    msg.matches(".*\\b(cleric|healer|priest|bishop)\\b.*") ? Job.CLERIC : null;
            case BOWMAN -> lvl < 30 ? null :
                    msg.matches(".*\\b(hunter|bow)\\b.*") ? Job.HUNTER :
                    msg.matches(".*\\b(crossbow|xbow|crossbowman)\\b.*") ? Job.CROSSBOWMAN : null;
            case THIEF -> lvl < 30 ? null :
                    msg.matches(".*\\b(assassin|sin)\\b.*") ? Job.ASSASSIN :
                    msg.matches(".*\\b(bandit|dit)\\b.*") ? Job.BANDIT : null;
            case PIRATE -> lvl < 30 ? null :
                    msg.matches(".*\\b(brawler|knuckle)\\b.*") ? Job.BRAWLER :
                    msg.matches(".*\\b(gunslinger|gun)\\b.*") ? Job.GUNSLINGER : null;
            // 3rd job
            case FIGHTER     -> lvl >= 70 && msg.matches(".*\\bcrusader\\b.*")                 ? Job.CRUSADER     : null;
            case PAGE        -> lvl >= 70 && msg.matches(".*\\b(white knight|wk)\\b.*")         ? Job.WHITEKNIGHT  : null;
            case SPEARMAN    -> lvl >= 70 && msg.matches(".*\\b(dragon knight|dk)\\b.*")        ? Job.DRAGONKNIGHT : null;
            case FP_WIZARD   -> lvl >= 70 && msg.matches(".*\\b(fp mage|fp)\\b.*")              ? Job.FP_MAGE      : null;
            case IL_WIZARD   -> lvl >= 70 && msg.matches(".*\\b(il mage|il)\\b.*")              ? Job.IL_MAGE      : null;
            case CLERIC      -> lvl >= 70 && msg.matches(".*\\bpriest\\b.*")                    ? Job.PRIEST       : null;
            case HUNTER      -> lvl >= 70 && msg.matches(".*\\branger\\b.*")                    ? Job.RANGER       : null;
            case CROSSBOWMAN -> lvl >= 70 && msg.matches(".*\\bsniper\\b.*")                    ? Job.SNIPER       : null;
            case ASSASSIN    -> lvl >= 70 && msg.matches(".*\\bhermit\\b.*")                    ? Job.HERMIT       : null;
            case BANDIT      -> lvl >= 70 && msg.matches(".*\\b(chief bandit|cb|chief)\\b.*")   ? Job.CHIEFBANDIT  : null;
            case BRAWLER     -> lvl >= 70 && msg.matches(".*\\bmarauder\\b.*")                  ? Job.MARAUDER     : null;
            case GUNSLINGER  -> lvl >= 70 && msg.matches(".*\\boutlaw\\b.*")                    ? Job.OUTLAW       : null;
            // 4th job
            case CRUSADER    -> lvl >= 120 && msg.matches(".*\\bhero\\b.*")                         ? Job.HERO        : null;
            case WHITEKNIGHT -> lvl >= 120 && msg.matches(".*\\bpaladin\\b.*")                      ? Job.PALADIN     : null;
            case DRAGONKNIGHT -> lvl >= 120 && msg.matches(".*\\b(dark knight|drk)\\b.*")           ? Job.DARKKNIGHT  : null;
            case FP_MAGE     -> lvl >= 120 && msg.matches(".*\\b(fp archmage|fp arch)\\b.*")        ? Job.FP_ARCHMAGE : null;
            case IL_MAGE     -> lvl >= 120 && msg.matches(".*\\b(il archmage|il arch)\\b.*")        ? Job.IL_ARCHMAGE : null;
            case PRIEST      -> lvl >= 120 && msg.matches(".*\\bbishop\\b.*")                       ? Job.BISHOP      : null;
            case RANGER      -> lvl >= 120 && msg.matches(".*\\b(bowmaster|bm)\\b.*")               ? Job.BOWMASTER   : null;
            case SNIPER      -> lvl >= 120 && msg.matches(".*\\b(marksman|mm)\\b.*")                ? Job.MARKSMAN    : null;
            case HERMIT      -> lvl >= 120 && msg.matches(".*\\b(night lord|nl)\\b.*")              ? Job.NIGHTLORD   : null;
            case CHIEFBANDIT -> lvl >= 120 && msg.matches(".*\\b(shadower|shad)\\b.*")              ? Job.SHADOWER    : null;
            case MARAUDER    -> lvl >= 120 && msg.matches(".*\\b(buccaneer|bucc)\\b.*")             ? Job.BUCCANEER   : null;
            case OUTLAW      -> lvl >= 120 && msg.matches(".*\\bcorsair\\b.*")                      ? Job.CORSAIR     : null;
            default -> null;
        };
    }

    static String jobDisplayName(Job job) {
        return switch (job) {
            case WARRIOR     -> "warrior";      case MAGICIAN    -> "mage";
            case BOWMAN      -> "bowman";       case THIEF       -> "thief";
            case PIRATE      -> "pirate";       case FIGHTER     -> "fighter";
            case PAGE        -> "page";         case SPEARMAN    -> "spearman";
            case FP_WIZARD   -> "f/p wizard";   case IL_WIZARD   -> "i/l wizard";
            case CLERIC      -> "cleric";       case HUNTER      -> "hunter";
            case CROSSBOWMAN -> "crossbowman";  case ASSASSIN    -> "assassin";
            case BANDIT      -> "bandit";       case BRAWLER     -> "brawler";
            case GUNSLINGER  -> "gunslinger";   case CRUSADER    -> "crusader";
            case WHITEKNIGHT -> "white knight"; case DRAGONKNIGHT-> "dragon knight";
            case FP_MAGE     -> "f/p mage";     case IL_MAGE     -> "i/l mage";
            case PRIEST      -> "priest";       case RANGER      -> "ranger";
            case SNIPER      -> "sniper";       case HERMIT      -> "hermit";
            case CHIEFBANDIT -> "chief bandit"; case MARAUDER    -> "marauder";
            case OUTLAW      -> "outlaw";       case HERO        -> "hero";
            case PALADIN     -> "paladin";      case DARKKNIGHT  -> "dark knight";
            case FP_ARCHMAGE -> "f/p archmage"; case IL_ARCHMAGE -> "i/l archmage";
            case BISHOP      -> "bishop";       case BOWMASTER   -> "bowmaster";
            case MARKSMAN    -> "marksman";     case NIGHTLORD   -> "night lord";
            case SHADOWER    -> "shadower";     case BUCCANEER   -> "buccaneer";
            case NOBLESSE    -> "noblesse";
            case DAWNWARRIOR1 -> "dawn warrior";   case DAWNWARRIOR2 -> "dawn warrior";
            case DAWNWARRIOR3 -> "dawn warrior";   case DAWNWARRIOR4 -> "dawn warrior";
            case BLAZEWIZARD1 -> "blaze wizard";   case BLAZEWIZARD2 -> "blaze wizard";
            case BLAZEWIZARD3 -> "blaze wizard";   case BLAZEWIZARD4 -> "blaze wizard";
            case WINDARCHER1  -> "wind archer";    case WINDARCHER2  -> "wind archer";
            case WINDARCHER3  -> "wind archer";    case WINDARCHER4  -> "wind archer";
            case NIGHTWALKER1 -> "night walker";   case NIGHTWALKER2 -> "night walker";
            case NIGHTWALKER3 -> "night walker";   case NIGHTWALKER4 -> "night walker";
            case THUNDERBREAKER1 -> "thunder breaker"; case THUNDERBREAKER2 -> "thunder breaker";
            case THUNDERBREAKER3 -> "thunder breaker"; case THUNDERBREAKER4 -> "thunder breaker";
            case LEGEND       -> "legend";
            case ARAN1        -> "aran";         case ARAN2        -> "aran";
            case ARAN3        -> "aran";         case ARAN4        -> "aran";
            case CORSAIR     -> "corsair";
            default -> job.name().toLowerCase();
        };
    }

    private static void handleFameCommand(BotEntry entry, String targetName) {
        Character bot = entry.bot;
        Character target;
        if (targetName.equalsIgnoreCase("me")) {
            target = entry.owner;
        } else {
            target = bot.getMap().getCharacters().stream()
                    .filter(c -> c.getName().equalsIgnoreCase(targetName))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            BotManager.getInstance().botReply(entry, "can't find " + targetName + " on the map");
            return;
        }
        if (target.getId() == bot.getId()) {
            BotManager.getInstance().botReply(entry, "lol can't fame myself");
            return;
        }
        if (bot.getLevel() < 15) {
            BotManager.getInstance().botReply(entry, "i'm too low level to fame");
            return;
        }
        Character.FameStatus status = bot.canGiveFame(target);
        if (status == Character.FameStatus.NOT_TODAY) {
            BotManager.getInstance().botReply(entry, BotManager.randomReply(FAME_COOLDOWN_REPLIES));
            return;
        }
        if (status == Character.FameStatus.NOT_THIS_MONTH) {
            String reply = String.format(BotManager.randomReply(FAME_SAME_PERSON_REPLIES), target.getName());
            BotManager.getInstance().botReply(entry, reply);
            return;
        }
        if (target.gainFame(1, bot, 1)) {
            bot.hasGivenFame(target);
            String template = BotManager.randomReply(FAME_OK_REPLIES);
            String reply = template.contains("%s") ? String.format(template, target.getName()) : template;
            BotManager.getInstance().botReply(entry, reply);
        } else {
            BotManager.getInstance().botReply(entry, "fame failed, might be at max already");
        }
    }
}
