package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.processor.stat.AssignAPProcessor;
import constants.game.GameConstants;
import constants.skills.Rogue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import server.bots.build.BowmanBuilds;
import constants.skills.Beginner;
import server.bots.build.BuildStep;
import server.bots.build.MageBuilds;
import server.bots.build.ThiefBuilds;
import server.bots.build.WarriorBuilds;

class BotBuildManager {
    enum StatType {
        STR,
        DEX,
        INT,
        LUK
    }

    /**
     * AP build by job tree: fill the secondary stat up to its target, then dump all remaining AP into the primary stat.
     */
    public static class ApBuild {
        final StatType primaryStat;
        final StatType secondaryStat;
        final int secondaryTarget;

        public ApBuild(StatType primaryStat, StatType secondaryStat, int secondaryTarget) {
            this.primaryStat = primaryStat;
            this.secondaryStat = secondaryStat;
            this.secondaryTarget = Math.max(4, secondaryTarget);
        }
    }

    /** Stores the AP build, confirms it to the owner, and immediately spends any pending AP. */
    static void setApBuild(BotEntry entry, ApBuild build, String confirmMsg) {
        entry.apBuild = build;
        entry.apPromptSent = false;
        BotManager.getInstance().botReply(entry, confirmMsg);
        autoAssignAp(entry, entry.bot);
    }

    /**
     * Returns a prompt asking the owner to choose an AP build, or null if:
     * no AP is pending, a build is already chosen, a prompt was already sent,
     * or the bot is not on a supported branch.
     */
    static String buildApPrompt(BotEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        if (entry.apBuild != null || entry.apPromptSent || bot.getRemainingAp() < 1) return null;
        if (isOwnerless(entry) || entry.apAuto) {
            maybeRecomputeAutonomousApBuild(entry, bot); // resolve + assign autonomously, no owner prompt
            return null;
        }
        return requestApBuildPrompt(entry, bot);
    }

    static String requestApBuildPrompt(BotEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        entry.apPromptSent = true;
        return prompt;
    }

    /** Owner picked "auto" at the build prompt: hand AP over to the same self-managed path an
     *  ownerless bot uses — resolve the build (job + accuracy floor + weapon needs) and ratchet it on
     *  every future level-up, no more prompts. Returns the confirm line, or null if the job has no AP
     *  build (Beginner/Pirate). */
    static String setAutoApBuild(BotEntry entry, Character bot) {
        if (apPromptForJob(bot.getJob()) == null) return null;
        entry.apAuto = true;
        maybeRecomputeAutonomousApBuild(entry, bot);
        return "ok, i'll manage my own ap from here";
    }

    /** Spends all remaining AP according to the stored build. */
    static void autoAssignAp(BotEntry entry, Character bot) {
        if (entry.apBuild == null || bot.getRemainingAp() < 1) return;

        int ap = bot.getRemainingAp();
        int[] gains = new int[StatType.values().length];
        int secondaryNeeded = Math.max(0, entry.apBuild.secondaryTarget - currentStat(bot, entry.apBuild.secondaryStat));
        int secondaryGain = Math.min(secondaryNeeded, ap);
        gains[entry.apBuild.secondaryStat.ordinal()] = secondaryGain;
        ap -= secondaryGain;
        gains[entry.apBuild.primaryStat.ordinal()] += ap;

        if (gains[StatType.STR.ordinal()] > 0
                || gains[StatType.DEX.ordinal()] > 0
                || gains[StatType.INT.ordinal()] > 0
                || gains[StatType.LUK.ordinal()] > 0) {
            bot.assignStrDexIntLuk(
                    gains[StatType.STR.ordinal()],
                    gains[StatType.DEX.ordinal()],
                    gains[StatType.INT.ordinal()],
                    gains[StatType.LUK.ordinal()]
            );
        }
    }

    static String respecAp(BotEntry entry, Character bot) {
        if (apPromptForJob(bot.getJob()) == null) {
            return "dont have an ap build for my job yet";
        }
        if (entry.apBuild == null) {
            entry.apPromptSent = false;
            String prompt = requestApBuildPrompt(entry, bot);
            return prompt != null ? prompt : "need your ap build first";
        }

        if (!reallocateAp(entry, bot)) {
            return "couldnt rebuild my ap";
        }

        return "ok, rebuilt my ap using the bot build";
    }

    static void handleJobAdvance(BotEntry entry, Character bot, Job oldJob, Job newJob) {
        // 1st job advancement resets stats for real players: the instructor NPC scripts call
        // cm.resetStats() right after the job change (e.g. magician 1032001.js:137). A Beginner
        // legitimately dumps all AP into STR, so without the reset a fresh mage/thief/etc. keeps it.
        // Bots advance via changeJob directly (BotStarterKitManager.advanceJob), bypassing that
        // script, so invoke the same player SSOT here. resetStats() refunds str/dex/int/luk down to
        // the job floor as spendable AP (and recomputes SP); the autoAssign calls below then spend
        // the refunded AP/SP into the job build. (The old apBuild-gated reallocateAp never fired -
        // apBuild is null until maybeRecomputeAutonomousApBuild runs, two lines down.) Honors the
        // same USE_AUTOASSIGN_STARTERS_AP config gate as the player path - no-op when it's off.
        if (oldJob == Job.BEGINNER && oldJob != newJob) {
            bot.resetStats();
        }

        autoAssignSp(entry, bot);
        maybeRecomputeAutonomousApBuild(entry, bot);
        autoAssignAp(entry, bot);
    }

    private static boolean reallocateAp(BotEntry entry, Character bot) {
        int minStr = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.STR);
        int minDex = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.DEX);
        int minInt = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.INT);
        int minLuk = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.LUK);

        if (!bot.assignStrDexIntLuk(minStr - bot.getStr(), minDex - bot.getDex(), minInt - bot.getInt(), minLuk - bot.getLuk())) {
            return false;
        }

        autoAssignAp(entry, bot);
        return true;
    }

    /** True for a bot playing autonomously with no online player to answer choice prompts: a
     *  self-owned (@botme) bot, or an autopilot bot whose owner is offline. These resolve job/AP/SP
     *  choices themselves instead of waiting on owner chat. Supervised + owner-online bots are unaffected. */
    private static boolean isOwnerless(BotEntry entry) {
        return BotManager.isAutopilotActive(entry) && !BotManager.hasOnlinePlayerOwner(entry);
    }

    /**
     * The autonomous AP build for the bot's current job (the ownerless default), or null if the job
     * has no AP build (Beginner/Pirate). Reuses the BotScrollManager job->stat SSOT and the
     * BotEquipManager DPS/requirement scorer. Mages park the secondary (LUK) at the floor: magic
     * damage and wand/staff requirements ignore LUK, so a non-floor target would only waste AP.
     */
    static ApBuild resolveApBuild(BotEntry entry, Character bot) {
        Job job = bot.getJob();
        if (apPromptForJob(job) == null) return null;
        boolean[] mageOut = new boolean[1];
        char[] ms = BotScrollManager.mainSecondary(job.getId(), mageOut);
        StatType primary = statTypeOf(ms[0]);
        StatType secondary = statTypeOf(ms[1]);
        if (primary == null || secondary == null) return null;
        int floor = AssignAPProcessor.getMinStatFloor(job, statOf(secondary));
        // Accuracy floor: a STR job (warrior/pirate) with DEX parked at the stat floor can't hit
        // similar-level mobs (the lv12/9-DEX warrior whiffing). Raise the DEX floor to ~25% hit on a
        // nearby same-level mob so it lands hits. Kept low on purpose - equips/buffs add accuracy later
        // and the floor relaxes as they do. Only when DEX is the secondary stat (archers have DEX as
        // primary, thieves get accuracy from LUK, mages use magic accuracy - none need this).
        if (!mageOut[0] && ms[1] == 'd') {
            floor = Math.max(floor, accuracyDexFloor(entry, bot));
        }
        int target = mageOut[0] ? floor : BotEquipManager.recommendSecondaryTarget(bot, ms[0], ms[1], floor);
        return new ApBuild(primary, secondary, target);
    }

    static final int ACC_FLOOR_TARGET_HIT_PCT = 25; // aim for >= this % hit on a nearby same-level mob
    static final int ACC_FLOOR_LEVEL_BAND = 10;     // "nearby" = within this many levels (skip noise)

    /**
     * Minimum BASE DEX so the bot lands ~{@link #ACC_FLOOR_TARGET_HIT_PCT}% on its ASPIRATIONAL grind
     * mob — the map it would farm if accuracy were free ({@link BotGrindAdvisor}, cached on the entry).
     * Aiming at where the bot wants to be (not the easy map it's stuck on) is what breaks the "too low
     * DEX to farm harder mobs, so it never farms them, so it never needs more DEX" loop. Falls back to
     * the median avoid of nearby non-boss mobs on the CURRENT map until the first grind pass runs;
     * 0 (no floor) when nothing's around or current gear/LUK/flat accuracy already covers it. The floor
     * relaxes automatically as equips/buffs raise accuracy. SSOT: inverts {@link
     * server.combat.CombatFormulaProvider}'s hit math.
     */
    static int accuracyDexFloor(BotEntry entry, Character bot) {
        if (bot == null) {
            return 0;
        }
        int refAvoid;
        int refLevel;
        if (entry != null && entry.aspirationalMobAvoid >= 0) {
            refAvoid = entry.aspirationalMobAvoid;
            refLevel = entry.aspirationalMobLevel;
        } else {
            if (bot.getMap() == null) {
                return 0;
            }
            int botLevel = bot.getLevel();
            java.util.List<Integer> avoids = new java.util.ArrayList<>();
            for (server.life.Monster m : bot.getMap().getAllMonsters()) {
                if (m == null || m.getStats() == null || m.isBoss()) {
                    continue;
                }
                if (Math.abs(m.getLevel() - botLevel) > ACC_FLOOR_LEVEL_BAND) {
                    continue;
                }
                int av = m.getAvoidability();
                if (av > 0) {
                    avoids.add(av);
                }
            }
            if (avoids.isEmpty()) {
                return 0;
            }
            java.util.Collections.sort(avoids);
            refAvoid = avoids.get(avoids.size() / 2); // median is robust to a stray high-avoid mob
            refLevel = botLevel;
        }
        if (refAvoid <= 0) {
            return 0;
        }
        // Invert the physical hit formula for the reference mob (accuracyRate = acc*100/(levelDelta*10+255)):
        //   hit = (1.3 - avoid/accuracyRate)/0.6  =>  acc = avoid / (1.3 - 0.6*hit) * (levelDelta*10+255)/100
        int levelDelta = Math.max(0, refLevel - bot.getLevel());
        double targetHit = ACC_FLOOR_TARGET_HIT_PCT / 100.0;
        double accNeeded = refAvoid / (1.3 - 0.6 * targetHit) * ((levelDelta * 10 + 255) / 100.0);
        // accuracy = floor(totalDex*0.8 + totalLuk*0.5) + flat(equip/buff). Solve for the base DEX that
        // reaches accNeeded, crediting current LUK + flat + worn DEX.
        server.combat.CombatFormulaProvider f = server.combat.CombatFormulaProvider.getInstance();
        int totalAcc = f.getTotalAccuracy(bot);
        int dexLukAcc = (int) Math.floor(bot.getTotalDex() * 0.8 + bot.getTotalLuk() * 0.5);
        int flatAcc = totalAcc - dexLukAcc;
        double minTotalDex = (accNeeded - bot.getTotalLuk() * 0.5 - flatAcc) / 0.8;
        int gearDex = bot.getTotalDex() - bot.getDex();
        return Math.max(0, (int) Math.ceil(minTotalDex) - gearDex);
    }

    /**
     * Ownerless autonomous AP build: set/raise the secondary target and spend AP additively, with no
     * chat prompt. Deliberately ADDITIVE ONLY - it never reduces an already-spent secondary stat and
     * never autonomously respecs, because a legal player cannot un-spend AP without an AP reset. So
     * the target only ever ratchets UP (toward unlocking a better owned weapon); it never drifts back
     * down as gear improves. Reclaiming over-invested secondary (funded->pure) is deferred to a future
     * NX-funded AP-reset feature (a well-geared bot with spare NX resets when worth it - see the
     * design doc). No-op for jobs with no build (Beginner/Pirate) and for owned bots UNLESS the owner
     * opted into "auto" ({@code entry.apAuto}), which mirrors them onto this same self-managed path.
     */
    static void maybeRecomputeAutonomousApBuild(BotEntry entry, Character bot) {
        if (!isOwnerless(entry) && !entry.apAuto) return;
        ApBuild fresh = resolveApBuild(entry, bot);
        if (fresh == null) return;
        entry.apPromptSent = true; // ownerless: never wait on an owner reply
        int target = fresh.secondaryTarget;
        if (entry.apBuild != null && entry.apBuild.secondaryStat == fresh.secondaryStat) {
            target = Math.max(entry.apBuild.secondaryTarget, fresh.secondaryTarget); // ratchet up only
        }
        entry.apBuild = new ApBuild(fresh.primaryStat, fresh.secondaryStat, target);
        autoAssignAp(entry, bot); // additive: fill secondary to target, rest to primary; never reduces
    }

    /**
     * Autonomous job choice for an ownerless bot: weighted-random 1st job (from Beginner) using
     * BotManager.cfg.JOB_WEIGHTS, or a uniform 2nd job among the current 1st job's options. Returns
     * null when there is no choice topology (e.g. an unsupported/non-explorer branch), so the caller
     * falls back to the owner-prompt path.
     */
    static Job pickWeightedJob(Job currentJob) {
        if (currentJob == null || currentJob == Job.BEGINNER) {
            // Only classes the bot can actually BUILD: PIRATE has no AP build (apPromptForJob) and no
            // SP build tree, so an ownerless pirate would bank AP/SP forever. Filtering here (rather
            // than dropping PIRATE from firstJobChoices, which mirrors the owner prompt) auto-includes
            // pirate the moment a pirate build lands.
            List<Job> buildable = new ArrayList<>();
            for (Job j : BotStarterKitManager.firstJobChoices()) {
                if (apPromptForJob(j) != null) buildable.add(j);
            }
            return weightedPick(buildable, BotManager.cfg.JOB_WEIGHTS);
        }
        List<Job> choices = BotStarterKitManager.secondJobChoices(currentJob);
        if (choices.isEmpty()) return null;
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    /**
     * The job an ownerless bot advances into: its creation-time PLANNED job (stored on the personality,
     * so the procedural name matches the eventual class) when that plan is still a legal choice, else a
     * fresh autonomous pick. Validated against the live topology so a stale/garbled plan can't advance
     * into an illegal class.
     */
    static Job plannedOrPicked(BotEntry entry, Job currentJob) {
        BotPersonality p = entry != null ? entry.personality : null;
        if (p != null) {
            if (currentJob == null || currentJob == Job.BEGINNER) {
                Job planned = p.plannedFirstJob();
                if (planned != null && apPromptForJob(planned) != null
                        && BotStarterKitManager.firstJobChoices().contains(planned)) {
                    return planned;
                }
            } else {
                Job planned = p.plannedSecondJob();
                if (planned != null && BotStarterKitManager.secondJobChoices(currentJob).contains(planned)) {
                    return planned;
                }
            }
        }
        // Thief safety: the trained 1st-job attack skill commits the weapon line, so never advance a
        // Double-Stab (dagger) Rogue into Assassin or a Lucky-Seven (claw) Rogue into Bandit even if
        // the stored plan was lost. Mirrors the weapon gate in BotEquipManager.isWeaponCompatible.
        if (currentJob == Job.THIEF && entry != null && entry.bot != null) {
            if (entry.bot.getSkillLevel(Rogue.DOUBLE_STAB) > 0) return Job.BANDIT;
            if (entry.bot.getSkillLevel(Rogue.LUCKY_SEVEN) > 0) return Job.ASSASSIN;
        }
        return pickWeightedJob(currentJob);
    }

    /** Dagger (Bandit) thief line. */
    private static boolean isDaggerThiefJob(Job job) {
        return job == Job.BANDIT || job == Job.CHIEFBANDIT || job == Job.SHADOWER;
    }

    /** Claw (Assassin) thief line. */
    private static boolean isClawThiefJob(Job job) {
        return job == Job.ASSASSIN || job == Job.HERMIT || job == Job.NIGHTLORD;
    }

    /**
     * Resolve the thief claw/dagger build variant the first time SP is spent on a thief-tree bot,
     * reusing {@code entry.spVariant} ("claw"/"dagger"). An already-trained 1st-job attack skill or a
     * 2nd+-job class is authoritative (mid-career spawned bot); otherwise a fresh Rogue derives it
     * from its planned 2nd job, and an unplanned Rogue rolls one and FORCES its planned 2nd job to
     * match so build, eventual class, and procedural name stay aligned. The trained skill is the SSOT
     * the weapon gate reads, so the variant must commit before any SP lands.
     */
    private static void resolveThiefVariantIfNeeded(BotEntry entry, Character bot) {
        if (entry.spVariant != null) return;
        Job job = bot.getJob();
        if (job != Job.THIEF && !isDaggerThiefJob(job) && !isClawThiefJob(job)) return;

        if (bot.getSkillLevel(Rogue.DOUBLE_STAB) > 0 || isDaggerThiefJob(job)) {
            entry.spVariant = "dagger";
            return;
        }
        if (bot.getSkillLevel(Rogue.LUCKY_SEVEN) > 0 || isClawThiefJob(job)) {
            entry.spVariant = "claw";
            return;
        }
        BotPersonality p = entry.personality;
        Job planned2 = p != null ? p.plannedSecondJob() : null;
        if (planned2 == Job.BANDIT) {
            entry.spVariant = "dagger";
        } else if (planned2 == Job.ASSASSIN) {
            entry.spVariant = "claw";
        } else {
            // ponytail: 50/50 roll; add BotManager.cfg weights here if a specific claw/dagger mix is wanted.
            boolean dagger = ThreadLocalRandom.current().nextBoolean();
            entry.spVariant = dagger ? "dagger" : "claw";
            tiePlannedSecondJob(entry, bot, dagger ? Job.BANDIT : Job.ASSASSIN);
        }
    }

    /** Persist the rolled 2nd job onto the personality so lv30 advancement ({@link #plannedOrPicked})
     *  and the weapon gate agree with the variant chosen here. Best-effort save; the in-memory plan is
     *  authoritative this session even if the blob write fails. */
    private static void tiePlannedSecondJob(BotEntry entry, Character bot, Job second) {
        BotPersonality p = entry.personality;
        if (p == null) return;
        BotPersonality updated = p.withPlannedJobs(p.plannedFirstJob(), second);
        entry.personality = updated;
        try {
            BotConfigService.getInstance().save(bot.getId(), updated.serialize());
        } catch (RuntimeException ignored) {
            // persistence best-effort
        }
    }

    static Job weightedPick(List<Job> choices, Map<Job, Integer> weights) {
        if (choices.isEmpty()) return null;
        int total = 0;
        for (Job j : choices) total += Math.max(0, weights.getOrDefault(j, 1));
        if (total <= 0) return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Job j : choices) {
            roll -= Math.max(0, weights.getOrDefault(j, 1));
            if (roll < 0) return j;
        }
        return choices.get(choices.size() - 1);
    }

    private static StatType statTypeOf(char code) {
        return switch (code) {
            case 's' -> StatType.STR;
            case 'd' -> StatType.DEX;
            case 'i' -> StatType.INT;
            case 'l' -> StatType.LUK;
            default -> null;
        };
    }

    private static Stat statOf(StatType type) {
        return switch (type) {
            case STR -> Stat.STR;
            case DEX -> Stat.DEX;
            case INT -> Stat.INT;
            case LUK -> Stat.LUK;
        };
    }

    /**
     * Returns a prompt asking for the SP build variant, or null if not needed.
     * Currently only Hero has two documented builds.
     */
    static String buildSpVariantPrompt(BotEntry entry, Character bot) {
        if (bot.getJob() != Job.HERO) return null;
        if (entry.spVariant != null || entry.spVariantPromptSent || bot.getRemainingSps()[3] < 1) return null;
        if (isOwnerless(entry)) {
            entry.spVariant = "2h"; // autonomous default; no owner to choose 1h vs 2h
            entry.spVariantPromptSent = true;
            return null;
        }
        entry.spVariantPromptSent = true;
        return "hero build: '1h' (1h sword, Brandish first) or '2h' (interleave AC + Brandish for faster charges)?";
    }

    /**
     * Spends all available SP following the configured build order.
     * Hero SP is held until the owner chooses a variant.
     */
    static void autoAssignSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) return;
        resolveThiefVariantIfNeeded(entry, bot);

        List<BuildStep> steps = getBuildOrder(bot.getJob(), entry.spVariant);
        if (steps == null) return;

        autoAssignSp(bot, steps);
    }

    static String respecSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) {
            return "need your hero build first. say '1h' or '2h'";
        }

        List<Job> buildPath = getSupportedBuildPath(bot.getJob());
        if (buildPath == null) {
            return "dont have an sp respec build for my job yet";
        }

        int[] refundedSp = new int[5];
        List<Skill> skillsToReset = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry skillEntry = learned.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }
            if (!GameConstants.isInJobTree(skillId, bot.getJob().getId())) {
                continue;
            }

            refundedSp[GameConstants.getSkillBook(skillId / 10000)] += skillEntry.skillevel;
            skillsToReset.add(skill);
        }

        for (Skill skill : skillsToReset) {
            bot.changeSkillLevel(skill, (byte) 0, bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
        }
        for (int book = 0; book < refundedSp.length; book++) {
            if (refundedSp[book] > 0) {
                bot.gainSp(refundedSp[book], book, false);
            }
        }

        for (Job job : buildPath) {
            List<BuildStep> steps = getBuildOrder(job, entry.spVariant);
            if (steps != null) {
                autoAssignSp(bot, steps);
            }
        }

        return "ok, rebuilt my sp using the bot build";
    }

    private static void autoAssignSp(Character bot, List<BuildStep> steps) {
        for (BuildStep step : steps) {
            Skill skill = SkillFactory.getSkill(step.skillId());
            if (skill == null) continue;

            int book = GameConstants.getSkillBook(step.skillId() / 10000);
            if (bot.getRemainingSps()[book] < 1) continue;

            while (bot.getRemainingSps()[book] > 0) {
                int currentLevel = bot.getSkillLevel(skill);
                if (currentLevel >= step.targetLevel()) break;
                if (!canLevelSkill(bot, skill, currentLevel)) return;

                bot.gainSp(-1, book, false);
                bot.changeSkillLevel(
                        skill,
                        (byte) (currentLevel + 1),
                        bot.getMasterLevel(skill),
                        bot.getSkillExpiration(skill)
                );
            }
        }
    }

    private static boolean canLevelSkill(Character bot, Skill skill, int currentLevel) {
        int cap = skill.isFourthJob() ? bot.getMasterLevel(skill) : skill.getMaxLevel();
        return currentLevel < cap;
    }

    private static List<Job> getSupportedBuildPath(Job job) {
        return switch (job) {
            case WARRIOR -> List.of(Job.WARRIOR);
            case FIGHTER -> List.of(Job.WARRIOR, Job.FIGHTER);
            case CRUSADER -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER);
            case HERO -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO);
            case BOWMAN -> List.of(Job.BOWMAN);
            case HUNTER -> List.of(Job.BOWMAN, Job.HUNTER);
            case RANGER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER);
            case BOWMASTER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER, Job.BOWMASTER);
            case THIEF -> List.of(Job.THIEF);
            case ASSASSIN -> List.of(Job.THIEF, Job.ASSASSIN);
            case HERMIT -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT);
            case NIGHTLORD -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case BANDIT -> List.of(Job.THIEF, Job.BANDIT);
            case CHIEFBANDIT -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT);
            case SHADOWER -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT, Job.SHADOWER);
            case PAGE -> List.of(Job.WARRIOR, Job.PAGE);
            case WHITEKNIGHT -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT);
            case SPEARMAN -> List.of(Job.WARRIOR, Job.SPEARMAN);
            case DRAGONKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT);
            case MAGICIAN -> List.of(Job.MAGICIAN);
            case CLERIC -> List.of(Job.MAGICIAN, Job.CLERIC);
            case PRIEST -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST);
            case BISHOP -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            default -> null;
        };
    }

    /** Beginner SP goes into Recovery (spend MP -> HP regen): a cheap survival skill that lets a
     *  fresh/broke bot self-heal without potions. Recovery maxes at level 3; canLevelSkill caps it. */
    private static final List<BuildStep> BEGINNER_BUILD = List.of(new BuildStep(Beginner.RECOVERY, 3));

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
        if (job == Job.BEGINNER) {
            return BEGINNER_BUILD;
        }
        List<BuildStep> warriorBuild = WarriorBuilds.getBuildOrder(job, variant);
        if (warriorBuild != null) {
            return warriorBuild;
        }
        List<BuildStep> bowmanBuild = BowmanBuilds.getBuildOrder(job);
        if (bowmanBuild != null) {
            return bowmanBuild;
        }
        List<BuildStep> thiefBuild = ThiefBuilds.getBuildOrder(job, variant);
        if (thiefBuild != null) {
            return thiefBuild;
        }
        return MageBuilds.getBuildOrder(job);
    }

    private static String apPromptForJob(Job job) {
        if (job == null) {
            return null;
        }
        if (job.isA(Job.WARRIOR)) {
            return "what AP build? type 'auto' to let me decide, 'dexless'/'pure' or e.g. '25 dex' for a dex target";
        }
        if (job.isA(Job.MAGICIAN)) {
            return "what AP build? type 'auto' to let me decide, 'lukless'/'pure' or e.g. '25 luk' for a luk target";
        }
        if (job.isA(Job.BOWMAN)) {
            return "what AP build? type 'auto' to let me decide, 'strless'/'pure' or e.g. '25 str' for a str target";
        }
        if (job.isA(Job.THIEF)) {
            return "what AP build? type 'auto' to let me decide, 'dexless'/'pure' or e.g. '25 dex' for a dex target";
        }
        return null;
    }

    /** Response-overlay labels for the AP prompt — kept in sync with {@link #apPromptForJob}. */
    static List<String> apBuildOptions(Job job) {
        if (job == null) {
            return List.of();
        }
        if (job.isA(Job.MAGICIAN)) {
            return List.of("auto", "pure", "lukless", "25 luk");
        }
        if (job.isA(Job.BOWMAN)) {
            return List.of("auto", "pure", "strless", "25 str");
        }
        if (job.isA(Job.WARRIOR) || job.isA(Job.THIEF)) {
            return List.of("auto", "pure", "dexless", "25 dex");
        }
        return List.of();
    }

    /** Response-overlay labels for the Hero SP-variant prompt. */
    static List<String> spVariantOptions() {
        return List.of("1h", "2h");
    }

    private static int currentStat(Character bot, StatType statType) {
        return switch (statType) {
            case STR -> bot.getStr();
            case DEX -> bot.getDex();
            case INT -> bot.getInt();
            case LUK -> bot.getLuk();
        };
    }

    /**
     * Detects level-up and sends prompts before spending SP/AP so gating can apply.
     */
    static void checkLevelUp(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        if (entry.lastKnownLevel == lvl) return;

        int prev = entry.lastKnownLevel;
        entry.lastKnownLevel = lvl;
        if (prev == -1) {
            // First observation: baseline the job-prompt tracker to the bot's CURRENT tier so a bot
            // spawned already past a milestone (e.g. a lv50 Fighter) doesn't get re-prompted or
            // re-advanced and doesn't fire checkBotStatus on every level-up. Beginners stay at 0.
            entry.jobPromptSent = Math.max(entry.jobPromptSent, passedMilestoneForJob(bot.getJob()));
            autoAssignSp(entry, bot);
            maybeRecomputeAutonomousApBuild(entry, bot);
            autoAssignAp(entry, bot);
            return;
        }

        // Job-advancement milestone reached. Robust to a level that skips the exact milestone value:
        // fire while the bot is at or past a milestone it hasn't been advanced/prompted through yet
        // (jobPromptSent is the SSOT tracker, advanced by buildJobPrompt). Threshold list mirrors
        // buildJobPrompt. Supervised bots get pulled back to the owner to be handy for the prompt;
        // autopilot bots are handled inside checkBotStatus -> buildJobPrompt (auto-advance 3rd/4th,
        // leave the cohort and follow/town for the 1st/2nd job choice).
        BotAutopilotManager.noteLevelUp(entry, lvl); // re-look at grind map: value is level-relative

        int milestoneFloor = lvl >= 120 ? 120 : lvl >= 70 ? 70 : lvl >= 30 ? 30 : lvl >= 10 ? 10 : lvl >= 8 ? 8 : 0;
        if (milestoneFloor > 0 && entry.jobPromptSent < milestoneFloor) {
            if (!BotManager.isAutopilotActive(entry)) {
                BotManager.getInstance().issueFollowOwner(entry);
            }
            BotChatManager.checkBotStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
        maybeRecomputeAutonomousApBuild(entry, bot);
        autoAssignAp(entry, bot);
    }

    /** The job-advance milestone a bot of {@code job} has already passed, used to baseline
     *  jobPromptSent so an already-advanced spawned bot isn't re-prompted. Beginner -> 0 (prompts
     *  at 8/10); explorer 1st/2nd/3rd/4th -> 10/30/70/120; anything else -> 120 (suppress). */
    private static int passedMilestoneForJob(Job job) {
        if (job == null || job == Job.BEGINNER) {
            return 0;
        }
        int id = job.getId();
        if (id >= 100 && id < 600) {
            if (id % 100 == 0) return 10; // 1st job (X00)
            int tier = id % 10;
            if (tier == 0) return 30;     // 2nd job (X10/X20/X30)
            if (tier == 1) return 70;     // 3rd job
            if (tier == 2) return 120;    // 4th job
        }
        return 120;
    }

    /** An autopilot bot at a 1st/2nd-job (choice) milestone leaves the cohort and waits for the
     *  owner decision; supervised bots are unaffected. */
    private static void parkIfAutopilot(BotEntry entry) {
        if (BotManager.isAutopilotActive(entry)) {
            BotManager.getInstance().parkAutopilotForJobDecision(entry);
        }
    }

    /** Auto-advance an autopilot bot to its deterministic 3rd/4th job, after a short human-like
     *  delay (mirrors the owner-typed advance path in BotChatManager). Reuses the job-advance SSOT
     *  BotStarterKitManager.advanceJob (changeJob + handleJobAdvance award SP/AP); no quest. */
    private static void scheduleAutoAdvance(BotEntry entry, Job target) {
        BotManager.after(BotManager.randMs(900, 1100), () -> {
            if (entry.jobErrandMapId != -1) {
                return; // the per-tick reconciliation already started this errand — don't double-begin
            }
            // Every explorer job on autopilot: walk to the job instructor first (advances on arrival),
            // so the bot is physically present and doesn't grind/over-level en route. Supervised/
            // owner-following bots (and any unrouted target) advance instantly.
            if (BotStarterKitManager.jobChangeNpcFor(target) != null && BotAutopilotManager.isActive(entry)) {
                BotStarterKitManager.beginJobErrand(entry, target);
            } else {
                BotStarterKitManager.advanceJob(entry, target);
            }
        });
    }

    /** The job an autopilot bot is currently OVERDUE to advance into (level past its tier's milestone),
     *  or null. 3rd/4th are deterministic; 1st/2nd honor the creation-time plan else an autonomous pick.
     *  SSOT shared by the level-up trigger ({@link #buildJobPrompt}) and the resume reconciliation. */
    static Job autoAdvanceTarget(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        if (job == Job.BEGINNER) {
            return lvl >= 10 ? plannedOrPicked(entry, Job.BEGINNER) : null;
        }
        int id = job.getId();
        if (id < 100 || id >= 600) {
            return null; // non-explorer numbering (Cygnus/Aran/Evan): leave alone
        }
        if (id % 100 == 0) {
            return lvl >= 30 ? plannedOrPicked(entry, job) : null;            // 1st -> 2nd (a choice)
        }
        int tier = id % 10;
        if (tier == 0) {
            return lvl >= 70 ? BotStarterKitManager.thirdJobOf(job) : null;   // 2nd -> 3rd
        }
        if (tier == 1) {
            return lvl >= 120 ? BotStarterKitManager.fourthJobOf(job) : null; // 3rd -> 4th
        }
        return null; // 4th job: done
    }

    /**
     * Re-entrant autopilot job-advance reconciliation. Unlike {@link #buildJobPrompt} (edge-triggered on
     * a level-up and gated by jobPromptSent), this restarts a job errand that was never started, got
     * interrupted (e.g. a follow command), or was lost to a relog mid-walk — so the bot resumes heading
     * to the instructor instead of silently farming on. Caller guarantees no errand is in flight
     * (jobErrandMapId == -1). No-op when not autopilot, not overdue, or at a 1st/2nd-job choice an owner
     * still owns.
     */
    static void maybeStartOverdueJobAdvance(BotEntry entry, Character bot) {
        if (!BotManager.isAutopilotActive(entry)) {
            return;
        }
        Job job = bot.getJob();
        boolean choiceTier = job == Job.BEGINNER || job.getId() % 100 == 0; // 1st/2nd job is the owner's choice
        if (choiceTier && !isOwnerless(entry)) {
            return; // supervised bot waits for the owner to pick (parkIfAutopilot owns the prompt)
        }
        Job target = autoAdvanceTarget(entry, bot);
        if (target == null) {
            return;
        }
        if (choiceTier) {
            persistPlannedChoice(entry, bot, job, target); // lock the pick so a restart re-targets the same job
        }
        if (BotStarterKitManager.jobChangeNpcFor(target) != null) {
            BotStarterKitManager.beginJobErrand(entry, target);
        } else {
            BotStarterKitManager.advanceJob(entry, target); // unrouted tier: advance in place
        }
    }

    /** Persist a re-derived 1st/2nd job choice so an interrupted-and-restarted errand re-targets the
     *  same job (plannedOrPicked otherwise re-rolls pickWeightedJob). Best-effort, mirrors
     *  {@link #tiePlannedSecondJob}. */
    private static void persistPlannedChoice(BotEntry entry, Character bot, Job currentJob, Job target) {
        BotPersonality p = entry.personality;
        if (p == null) {
            return;
        }
        boolean firstChoice = currentJob == null || currentJob == Job.BEGINNER;
        if ((firstChoice ? p.plannedFirstJob() : p.plannedSecondJob()) == target) {
            return; // already locked to this job
        }
        BotPersonality updated = firstChoice
                ? p.withPlannedJobs(target, p.plannedSecondJob())
                : p.withPlannedJobs(p.plannedFirstJob(), target);
        entry.personality = updated;
        try {
            BotConfigService.getInstance().save(bot.getId(), updated.serialize());
        } catch (RuntimeException ignored) {
            // persistence best-effort; in-memory plan is authoritative this session
        }
    }

    /**
     * A job-advancement prompt: the chat line plus the response-overlay labels. The labels are reply
     * tokens accepted by the advancement parser in {@code BotChatManager} (see {@code resolveAdvanceJob}),
     * kept in sync with the prompt text here so the overlay never offers a reply the bot would reject.
     */
    record JobPrompt(String text, List<String> options) {}

    /** Returns the next job-advancement prompt, or null if none is pending. */
    static JobPrompt buildJobPrompt(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        int prompted = entry.jobPromptSent;

        if (job == Job.BEGINNER) {
            if (lvl >= 10 && prompted < 10) {
                if (isOwnerless(entry)) {
                    Job target = plannedOrPicked(entry, Job.BEGINNER); // honor creation-time plan, else pick autonomously
                    if (target != null) {
                        entry.jobPromptSent = 10;
                        scheduleAutoAdvance(entry, target);
                        return null;
                    }
                }
                entry.jobPromptSent = 10;
                parkIfAutopilot(entry); // 1st job is a choice: autopilot bot stops + the owner still decides
                return new JobPrompt("hey i can change jobs now!! warrior, mage, bowman, thief, or pirate?",
                        List.of("warrior", "mage", "bowman", "thief", "pirate"));
            } else if (lvl >= 8 && prompted < 8) {
                // Ownerless bots skip the lv8 mage-only early choice and pick from all classes at lv10.
                if (isOwnerless(entry)) return null;
                entry.jobPromptSent = 8;
                parkIfAutopilot(entry);
                return new JobPrompt("i can become a mage already if u want, or wait til lv10 for other jobs",
                        List.of("mage"));
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
            if (isOwnerless(entry)) {
                Job target = plannedOrPicked(entry, job); // honor creation-time plan, else pick autonomously
                if (target != null) {
                    entry.jobPromptSent = 30;
                    scheduleAutoAdvance(entry, target);
                    return null;
                }
            }
            JobPrompt p = switch (job) {
                case WARRIOR -> new JobPrompt("lv30! 2nd job time~ fighter, page, or spearman?",
                        List.of("fighter", "page", "spearman"));
                case MAGICIAN -> new JobPrompt("lv30! pick 2nd job: f/p wizard, i/l wizard, or cleric?",
                        List.of("fp", "il", "cleric"));
                case BOWMAN -> new JobPrompt("lv30! hunter or crossbowman?", List.of("hunter", "crossbow"));
                case THIEF -> new JobPrompt("lv30! assassin or bandit?", List.of("assassin", "bandit"));
                case PIRATE -> new JobPrompt("lv30! brawler or gunslinger?", List.of("brawler", "gunslinger"));
                default -> null;
            };
            if (p != null) {
                entry.jobPromptSent = 30;
                parkIfAutopilot(entry); // 2nd job is a choice: autopilot bot stops + the owner still decides
                return p;
            }
        }

        if (lvl >= 70 && prompted < 70) {
            // 3rd job is deterministic - an autopilot bot advances itself in place (no owner prompt).
            if (BotManager.isAutopilotActive(entry)) {
                Job target = BotStarterKitManager.thirdJobOf(job);
                if (target != null) {
                    entry.jobPromptSent = 70;
                    scheduleAutoAdvance(entry, target);
                    return null;
                }
            }
            JobPrompt p = switch (job) {
                case FIGHTER -> new JobPrompt("lv70!! 3rd job, type 'crusader'", List.of("crusader"));
                case PAGE -> new JobPrompt("lv70!! type 'white knight' or 'wk'", List.of("white knight"));
                case SPEARMAN -> new JobPrompt("lv70!! type 'dragon knight' or 'dk'", List.of("dragon knight"));
                case FP_WIZARD -> new JobPrompt("lv70!! type 'fp mage'", List.of("fp mage"));
                case IL_WIZARD -> new JobPrompt("lv70!! type 'il mage'", List.of("il mage"));
                case CLERIC -> new JobPrompt("lv70!! type 'priest'", List.of("priest"));
                case HUNTER -> new JobPrompt("lv70!! type 'ranger'", List.of("ranger"));
                case CROSSBOWMAN -> new JobPrompt("lv70!! type 'sniper'", List.of("sniper"));
                case ASSASSIN -> new JobPrompt("lv70!! type 'hermit'", List.of("hermit"));
                case BANDIT -> new JobPrompt("lv70!! type 'chief bandit' or 'cb'", List.of("chief bandit"));
                case BRAWLER -> new JobPrompt("lv70!! type 'marauder'", List.of("marauder"));
                case GUNSLINGER -> new JobPrompt("lv70!! type 'outlaw'", List.of("outlaw"));
                default -> null;
            };
            if (p != null) {
                entry.jobPromptSent = 70;
                return p;
            }
        }

        if (lvl >= 120 && prompted < 120) {
            // 4th job is deterministic - an autopilot bot advances itself in place (no owner prompt).
            if (BotManager.isAutopilotActive(entry)) {
                Job target = BotStarterKitManager.fourthJobOf(job);
                if (target != null) {
                    entry.jobPromptSent = 120;
                    scheduleAutoAdvance(entry, target);
                    return null;
                }
            }
            JobPrompt p = switch (job) {
                case CRUSADER -> new JobPrompt("lv120!! type 'hero' for 4th job!!", List.of("hero"));
                case WHITEKNIGHT -> new JobPrompt("lv120!! type 'paladin'", List.of("paladin"));
                case DRAGONKNIGHT -> new JobPrompt("lv120!! type 'dark knight' or 'drk'", List.of("dark knight"));
                case FP_MAGE -> new JobPrompt("lv120!! type 'fp archmage' or 'fp arch'", List.of("fp archmage"));
                case IL_MAGE -> new JobPrompt("lv120!! type 'il archmage' or 'il arch'", List.of("il archmage"));
                case PRIEST -> new JobPrompt("lv120!! type 'bishop'", List.of("bishop"));
                case RANGER -> new JobPrompt("lv120!! type 'bowmaster' or 'bm'", List.of("bowmaster"));
                case SNIPER -> new JobPrompt("lv120!! type 'marksman' or 'mm'", List.of("marksman"));
                case HERMIT -> new JobPrompt("lv120!! type 'night lord' or 'nl'", List.of("night lord"));
                case CHIEFBANDIT -> new JobPrompt("lv120!! type 'shadower'", List.of("shadower"));
                case MARAUDER -> new JobPrompt("lv120!! type 'buccaneer' or 'bucc'", List.of("buccaneer"));
                case OUTLAW -> new JobPrompt("lv120!! type 'corsair'", List.of("corsair"));
                default -> null;
            };
            if (p != null) {
                entry.jobPromptSent = 120;
                return p;
            }
        }

        return null;
    }
}
