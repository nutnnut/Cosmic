package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.inventory.WeaponType;
import client.processor.stat.AssignAPProcessor;
import constants.game.GameConstants;
import java.util.ArrayList;
import java.util.List;
import server.bots.build.BowmanBuilds;
import server.bots.build.BuildStep;
import server.bots.build.MageBuilds;
import server.bots.build.PirateBuilds;
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
     * AP build by job tree. Two modes:
     *  - fixed target ({@code levelScaled == false}): fill the secondary stat up to {@code secondaryTarget},
     *    then dump all remaining AP into the primary stat (used by "dexless", "120 dex", etc.).
     *  - level-scaled ({@code levelScaled == true}, the auto build): the secondary target grows with level
     *    (+1 per level, +2 on every even level), so each level puts 1 AP (2 every other level) into the
     *    secondary stat and the remaining 4 (or 3) into the primary. See {@link #secondaryApForLevel(int)}.
     */
    public static class ApBuild {
        final StatType primaryStat;
        final StatType secondaryStat;
        final int secondaryTarget;
        final boolean levelScaled;

        public ApBuild(StatType primaryStat, StatType secondaryStat, int secondaryTarget) {
            this(primaryStat, secondaryStat, secondaryTarget, false);
        }

        public ApBuild(StatType primaryStat, StatType secondaryStat, int secondaryTarget, boolean levelScaled) {
            this.primaryStat = primaryStat;
            this.secondaryStat = secondaryStat;
            this.secondaryTarget = Math.max(4, secondaryTarget);
            this.levelScaled = levelScaled;
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
        return requestApBuildPrompt(entry, bot);
    }

    static String requestApBuildPrompt(BotEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        entry.apPromptSent = true;
        return prompt;
    }

    /**
     * Spends all remaining AP. Uses the player-chosen build when one is set; otherwise spends
     * silently with the job's default build — UNLESS an AP-build prompt is currently outstanding,
     * in which case it waits for the answer. This is what stops the nagging: the bot only ASKS at
     * job advancements; every other spawn/level-up just auto-assigns (defaulting if never answered).
     */
    static void autoAssignAp(BotEntry entry, Character bot) {
        if (bot.getRemainingAp() < 1) return;

        ApBuild build = entry.apBuild;
        if (build == null) {
            if (entry.apPromptSent) return;          // a prompt is waiting on the player's answer
            build = defaultApBuild(bot.getJob());     // no choice made (respawn / ignored) — default silently
            if (build == null) return;                // unsupported job
        }

        int ap = bot.getRemainingAp();
        int[] gains = new int[StatType.values().length];
        int secondaryTarget = build.levelScaled
                ? AssignAPProcessor.getMinStatFloor(bot.getJob(), toStat(build.secondaryStat))
                        + secondaryApForLevel(bot.getLevel())
                : build.secondaryTarget;
        int secondaryNeeded = Math.max(0, secondaryTarget - currentStat(bot, build.secondaryStat));
        int secondaryGain = Math.min(secondaryNeeded, ap);
        gains[build.secondaryStat.ordinal()] = secondaryGain;
        ap -= secondaryGain;
        gains[build.primaryStat.ordinal()] += ap;

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

    /**
     * Cumulative AP the level-scaled auto build targets for the secondary stat at a given level:
     * +1 per level, with +2 on every even level (so 1/4 split most levels, 2/3 every other level).
     * Level 1 grants no level-up AP, so counting starts at level 2.
     */
    private static int secondaryApForLevel(int level) {
        if (level < 2) {
            return 0;
        }
        return level + level / 2 - 1;
    }

    private static Stat toStat(StatType type) {
        return switch (type) {
            case STR -> Stat.STR;
            case DEX -> Stat.DEX;
            case INT -> Stat.INT;
            case LUK -> Stat.LUK;
        };
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
        if (oldJob == Job.BEGINNER && oldJob != newJob && entry.apBuild != null) {
            reallocateAp(entry, bot);
        }

        // Character.changeJob just max-leveled the previous job's skills for free
        // (maxPreviousJobSkills). Any SP the bot banked toward that now-free job — e.g. levels
        // gained before it advanced — would otherwise dump into the new job in autoAssignSp
        // below and over-build it (the "maxed 2nd-job skills at level 32" symptom). Drop the
        // surplus to 1 SP so the new job builds only from SP earned after this advancement;
        // the previous jobs stay fully usable via their free max.
        if (oldJob != newJob) {
            forceRemainingSpToOne(bot);
        }

        autoAssignSp(entry, bot);

        // Ask for the AP build ONLY at the two points where the stat/weapon path is chosen: the first
        // job advance (Beginner -> 1st job) and the 2nd-job advance (~level 30, where weapons diverge).
        // Reset the prior choice so the player re-picks for the new path; AP then waits for the answer
        // (autoAssignAp no-ops while a prompt is outstanding). No other advance/spawn/level-up prompts.
        if (oldJob != newJob && isApBuildChoicePoint(oldJob) && apPromptForJob(newJob) != null) {
            entry.apBuild = null;
            entry.apPromptSent = false;
            String prompt = requestApBuildPrompt(entry, bot);
            if (prompt != null) {
                BotManager.getInstance().botReply(entry, prompt);
            }
        }

        autoAssignAp(entry, bot);
    }

    /** The two advances where the bot asks its AP build: Beginner -> 1st job, and 1st job -> 2nd job. */
    private static boolean isApBuildChoicePoint(Job oldJob) {
        return oldJob == Job.BEGINNER
                || oldJob == Job.WARRIOR || oldJob == Job.MAGICIAN
                || oldJob == Job.BOWMAN || oldJob == Job.THIEF || oldJob == Job.PIRATE;
    }

    /** Forcibly set the bot's remaining SP (current job's skill book) to 1 — see handleJobAdvance. */
    private static void forceRemainingSpToOne(Character bot) {
        int book = GameConstants.getSkillBook(bot.getJob().getId());
        int current = bot.getRemainingSps()[book];
        if (current != 1) {
            bot.gainSp(1 - current, book, false);
        }
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

    /**
     * Returns a prompt asking for the SP build variant, or null if not needed.
     * Currently only Hero has two documented builds.
     */
    static String buildSpVariantPrompt(BotEntry entry, Character bot) {
        if (bot.getJob() != Job.HERO) return null;
        if (entry.spVariant != null || entry.spVariantPromptSent || bot.getRemainingSps()[3] < 1) return null;
        entry.spVariantPromptSent = true;
        return "hero build: '1h' (1h sword, Brandish first) or '2h' (interleave AC + Brandish for faster charges)?";
    }

    /**
     * Spends all available SP following the configured build order.
     * Hero SP is held until the owner chooses a variant.
     */
    static void autoAssignSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) return;

        List<BuildStep> steps = getBuildOrder(bot.getJob(), effectiveSpVariant(entry, bot));
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

        Job currentJob = bot.getJob();
        int currentJobId = currentJob.getId();

        // Mirror a job advancement: wipe only THIS job's own skills (earlier jobs are restored
        // to max below), then grant SP as if the bot had just advanced and leveled normally.
        for (Skill skill : new ArrayList<>(bot.getSkills().keySet())) {
            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }
            if (skillId / 10000 == currentJobId) {
                bot.changeSkillLevel(skill, (byte) 0, bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
            }
        }

        // Set every previous job's skills to max for free (same as maxPreviousJobSkills on advance).
        for (Job job : buildPath) {
            if (job != currentJob) {
                bot.maxJobSkills(job.getId());
            }
        }

        // Grant 1 SP (the advancement award) + 3 per level gained since this job started.
        int sp = 1 + 3 * Math.max(0, bot.getLevel() - jobAdvanceLevel(currentJob));
        int book = GameConstants.getSkillBook(currentJobId);
        int current = bot.getRemainingSps()[book];
        bot.gainSp(sp - current, book, false);

        // Rebuild this job's skills from the granted SP, following the bot build order.
        autoAssignSp(entry, bot);

        return "ok, maxed my earlier jobs and rebuilt this one with " + sp + " sp";
    }

    /** Canonical level a bot advances into the given job's tier (explorer thresholds). */
    private static int jobAdvanceLevel(Job job) {
        return switch (GameConstants.getJobBranch(job)) {
            case 1 -> job.isA(Job.MAGICIAN) ? 8 : 10;
            case 2 -> 30;
            case 3 -> 70;
            case 4 -> 120;
            default -> 10;
        };
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
            case CROSSBOWMAN -> List.of(Job.BOWMAN, Job.CROSSBOWMAN);
            case SNIPER -> List.of(Job.BOWMAN, Job.CROSSBOWMAN, Job.SNIPER);
            case MARKSMAN -> List.of(Job.BOWMAN, Job.CROSSBOWMAN, Job.SNIPER, Job.MARKSMAN);
            case THIEF -> List.of(Job.THIEF);
            case ASSASSIN -> List.of(Job.THIEF, Job.ASSASSIN);
            case HERMIT -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT);
            case NIGHTLORD -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case BANDIT -> List.of(Job.THIEF, Job.BANDIT);
            case CHIEFBANDIT -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT);
            case SHADOWER -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT, Job.SHADOWER);
            case PAGE -> List.of(Job.WARRIOR, Job.PAGE);
            case WHITEKNIGHT -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT);
            case PALADIN -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT, Job.PALADIN);
            case SPEARMAN -> List.of(Job.WARRIOR, Job.SPEARMAN);
            case DRAGONKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT);
            case DARKKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT, Job.DARKKNIGHT);
            case MAGICIAN -> List.of(Job.MAGICIAN);
            case CLERIC -> List.of(Job.MAGICIAN, Job.CLERIC);
            case PRIEST -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST);
            case BISHOP -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            case FP_WIZARD -> List.of(Job.MAGICIAN, Job.FP_WIZARD);
            case FP_MAGE -> List.of(Job.MAGICIAN, Job.FP_WIZARD, Job.FP_MAGE);
            case FP_ARCHMAGE -> List.of(Job.MAGICIAN, Job.FP_WIZARD, Job.FP_MAGE, Job.FP_ARCHMAGE);
            case IL_WIZARD -> List.of(Job.MAGICIAN, Job.IL_WIZARD);
            case IL_MAGE -> List.of(Job.MAGICIAN, Job.IL_WIZARD, Job.IL_MAGE);
            case IL_ARCHMAGE -> List.of(Job.MAGICIAN, Job.IL_WIZARD, Job.IL_MAGE, Job.IL_ARCHMAGE);
            case PIRATE -> List.of(Job.PIRATE);
            case BRAWLER -> List.of(Job.PIRATE, Job.BRAWLER);
            case MARAUDER -> List.of(Job.PIRATE, Job.BRAWLER, Job.MARAUDER);
            case BUCCANEER -> List.of(Job.PIRATE, Job.BRAWLER, Job.MARAUDER, Job.BUCCANEER);
            case GUNSLINGER -> List.of(Job.PIRATE, Job.GUNSLINGER);
            case OUTLAW -> List.of(Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW);
            case CORSAIR -> List.of(Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW, Job.CORSAIR);
            default -> null;
        };
    }

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
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
        List<BuildStep> pirateBuild = PirateBuilds.getBuildOrder(job, variant);
        if (pirateBuild != null) {
            return pirateBuild;
        }
        return MageBuilds.getBuildOrder(job);
    }

    /**
     * The SP build variant to use. Pirates need a weapon-aware variant for the shared 1st-job
     * build (gun vs knuckler); everyone else uses the owner-chosen {@code entry.spVariant} (Hero).
     */
    private static String effectiveSpVariant(BotEntry entry, Character bot) {
        if (bot.getJob().isA(Job.PIRATE)) {
            return BotAttackExecutionProvider.getEquippedWeaponType(bot) == WeaponType.GUN
                    ? "gun" : "knuckler";
        }
        if (bot.getJob().isA(Job.THIEF)) {
            WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
            return (wt == WeaponType.DAGGER_THIEVES || wt == WeaponType.DAGGER_OTHER) ? "dagger" : "claw";
        }
        return entry.spVariant;
    }

    private static String apPromptForJob(Job job) {
        if (job == null) {
            return null;
        }
        if (job.isA(Job.WARRIOR)) {
            return "what AP build? 'auto' to let me handle it, 'dexless'/'pure', or e.g. '25 dex' for a dex target";
        }
        if (job.isA(Job.MAGICIAN)) {
            return "what AP build? 'auto' to let me handle it, 'lukless'/'pure', or e.g. '25 luk' for a luk target";
        }
        if (job.isA(Job.BOWMAN)) {
            return "what AP build? 'auto' to let me handle it, 'strless'/'pure', or e.g. '25 str' for a str target";
        }
        if (job.isA(Job.THIEF)) {
            return "what AP build? 'auto' to let me handle it, 'dexless'/'pure', or e.g. '25 dex' for a dex target";
        }
        if (job.isA(Job.PIRATE)) {
            return "what AP build? 'auto' to let me handle it, 'dexless'/'pure', or e.g. '20 dex' for a dex target";
        }
        return null;
    }

    /**
     * The standard "auto-assign" AP build for a job: each level puts 1 AP into the secondary stat
     * (2 on every other level) and the remaining 4 (or 3) into the primary stat, so the secondary
     * grows gently with level instead of staying at the floor. Covers every adventurer branch
     * (pirates included). Null if unsupported.
     */
    static ApBuild defaultApBuild(Job job) {
        if (job == null) {
            return null;
        }
        if (job.isA(Job.WARRIOR)) {
            return new ApBuild(StatType.STR, StatType.DEX, 4, true);
        }
        if (job.isA(Job.MAGICIAN)) {
            return new ApBuild(StatType.INT, StatType.LUK, 4, true);
        }
        if (job.isA(Job.BOWMAN)) {
            return new ApBuild(StatType.DEX, StatType.STR, 4, true);
        }
        if (job.isA(Job.THIEF)) {
            return new ApBuild(StatType.LUK, StatType.DEX, 4, true);
        }
        if (job.isA(Job.PIRATE)) {
            return new ApBuild(StatType.STR, StatType.DEX, 4, true);
        }
        return null;
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
            autoAssignSp(entry, bot);
            autoAssignAp(entry, bot);
            return;
        }

        if (lvl > prev) {
            BotManager.getInstance().whisperOwnerIfAway(entry, "leveled up — now level " + lvl + "!");
        }

        if (lvl == 8 || lvl == 10 || lvl == 30 || lvl == 70 || lvl == 120) {
            BotManager.getInstance().issueFollowOwner(entry);
            BotChatManager.checkBotStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
        autoAssignAp(entry, bot);
    }

    /** Returns the next job-advancement prompt, or null if none is pending. */
    static String buildJobPrompt(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        int prompted = entry.jobPromptSent;

        if (job == Job.BEGINNER) {
            if (lvl >= 10 && prompted < 10) {
                entry.jobPromptSent = 10;
                return "hey i can change jobs now!! warrior, mage, bowman, thief, or pirate?";
            } else if (lvl >= 8 && prompted < 8) {
                entry.jobPromptSent = 8;
                return "i can become a mage already if u want, or wait til lv10 for other jobs";
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
            String msg = switch (job) {
                case WARRIOR -> "lv30! 2nd job time~ fighter, page, or spearman?";
                case MAGICIAN -> "lv30! pick 2nd job: f/p wizard, i/l wizard, or cleric?";
                case BOWMAN -> "lv30! hunter or crossbowman?";
                case THIEF -> "lv30! assassin or bandit?";
                case PIRATE -> "lv30! brawler or gunslinger?";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 30;
                return msg;
            }
        }

        if (lvl >= 70 && prompted < 70) {
            String msg = switch (job) {
                case FIGHTER -> "lv70!! 3rd job, type 'crusader'";
                case PAGE -> "lv70!! type 'white knight' or 'wk'";
                case SPEARMAN -> "lv70!! type 'dragon knight' or 'dk'";
                case FP_WIZARD -> "lv70!! type 'fp mage'";
                case IL_WIZARD -> "lv70!! type 'il mage'";
                case CLERIC -> "lv70!! type 'priest'";
                case HUNTER -> "lv70!! type 'ranger'";
                case CROSSBOWMAN -> "lv70!! type 'sniper'";
                case ASSASSIN -> "lv70!! type 'hermit'";
                case BANDIT -> "lv70!! type 'chief bandit' or 'cb'";
                case BRAWLER -> "lv70!! type 'marauder'";
                case GUNSLINGER -> "lv70!! type 'outlaw'";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 70;
                return msg;
            }
        }

        if (lvl >= 120 && prompted < 120) {
            String msg = switch (job) {
                case CRUSADER -> "lv120!! type 'hero' for 4th job!!";
                case WHITEKNIGHT -> "lv120!! type 'paladin'";
                case DRAGONKNIGHT -> "lv120!! type 'dark knight' or 'drk'";
                case FP_MAGE -> "lv120!! type 'fp archmage' or 'fp arch'";
                case IL_MAGE -> "lv120!! type 'il archmage' or 'il arch'";
                case PRIEST -> "lv120!! type 'bishop'";
                case RANGER -> "lv120!! type 'bowmaster' or 'bm'";
                case SNIPER -> "lv120!! type 'marksman' or 'mm'";
                case HERMIT -> "lv120!! type 'night lord' or 'nl'";
                case CHIEFBANDIT -> "lv120!! type 'shadower'";
                case MARAUDER -> "lv120!! type 'buccaneer' or 'bucc'";
                case OUTLAW -> "lv120!! type 'corsair'";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 120;
                return msg;
            }
        }

        return null;
    }
}
