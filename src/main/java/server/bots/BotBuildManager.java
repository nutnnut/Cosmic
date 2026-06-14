package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.processor.stat.AssignAPProcessor;
import constants.game.GameConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import server.bots.build.BowmanBuilds;
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
        return requestApBuildPrompt(entry, bot);
    }

    static String requestApBuildPrompt(BotEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        entry.apPromptSent = true;
        return prompt;
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
        if (oldJob == Job.BEGINNER && oldJob != newJob && entry.apBuild != null) {
            reallocateAp(entry, bot);
        }

        autoAssignSp(entry, bot);
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

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
        List<BuildStep> warriorBuild = WarriorBuilds.getBuildOrder(job, variant);
        if (warriorBuild != null) {
            return warriorBuild;
        }
        List<BuildStep> bowmanBuild = BowmanBuilds.getBuildOrder(job);
        if (bowmanBuild != null) {
            return bowmanBuild;
        }
        List<BuildStep> thiefBuild = ThiefBuilds.getBuildOrder(job);
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
            return "what AP build? type 'dexless'/'pure' or e.g. '25 dex' to set a dex target";
        }
        if (job.isA(Job.MAGICIAN)) {
            return "what AP build? type 'lukless'/'pure' or e.g. '25 luk' to set a luk target";
        }
        if (job.isA(Job.BOWMAN)) {
            return "what AP build? type 'strless'/'pure' or e.g. '25 str' to set a str target";
        }
        if (job.isA(Job.THIEF)) {
            return "what AP build? type 'dexless'/'pure' or e.g. '25 dex' to set a dex target";
        }
        return null;
    }

    /** Response-overlay labels for the AP prompt — kept in sync with {@link #apPromptForJob}. */
    static List<String> apBuildOptions(Job job) {
        if (job == null) {
            return List.of();
        }
        if (job.isA(Job.MAGICIAN)) {
            return List.of("pure", "lukless", "25 luk");
        }
        if (job.isA(Job.BOWMAN)) {
            return List.of("pure", "strless", "25 str");
        }
        if (job.isA(Job.WARRIOR) || job.isA(Job.THIEF)) {
            return List.of("pure", "dexless", "25 dex");
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
            autoAssignAp(entry, bot);
            return;
        }

        // Job-advancement milestone reached. Robust to a level that skips the exact milestone value:
        // fire while the bot is at or past a milestone it hasn't been advanced/prompted through yet
        // (jobPromptSent is the SSOT tracker, advanced by buildJobPrompt). Threshold list mirrors
        // buildJobPrompt. Supervised bots get pulled back to the owner to be handy for the prompt;
        // autopilot bots are handled inside checkBotStatus -> buildJobPrompt (auto-advance 3rd/4th,
        // leave the cohort and follow/town for the 1st/2nd job choice).
        int milestoneFloor = lvl >= 120 ? 120 : lvl >= 70 ? 70 : lvl >= 30 ? 30 : lvl >= 10 ? 10 : lvl >= 8 ? 8 : 0;
        if (milestoneFloor > 0 && entry.jobPromptSent < milestoneFloor) {
            if (!BotManager.isAutopilotActive(entry)) {
                BotManager.getInstance().issueFollowOwner(entry);
            }
            BotChatManager.checkBotStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
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
        BotManager.after(BotManager.randMs(900, 1100), () -> BotStarterKitManager.advanceJob(entry, target));
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
                entry.jobPromptSent = 10;
                parkIfAutopilot(entry); // 1st job is a choice: autopilot bot stops + the owner still decides
                return new JobPrompt("hey i can change jobs now!! warrior, mage, bowman, thief, or pirate?",
                        List.of("warrior", "mage", "bowman", "thief", "pirate"));
            } else if (lvl >= 8 && prompted < 8) {
                entry.jobPromptSent = 8;
                parkIfAutopilot(entry);
                return new JobPrompt("i can become a mage already if u want, or wait til lv10 for other jobs",
                        List.of("mage"));
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
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
