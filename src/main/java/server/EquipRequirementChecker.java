package server;

import client.Job;

import java.util.Map;

final class EquipRequirementChecker {
    private EquipRequirementChecker() {
    }

    static boolean meetsRequirements(Job job, Map<String, Integer> stats, int level, int reqLevel,
                                     int dex, int str, int int_, int luk, int fame) {
        if (stats == null) {
            return false;
        }
        if (!matchesReqJob(job, stats.get("reqJob"))) {
            return false;
        }
        if (reqLevel > level) {
            return false;
        }
        if (stats.get("reqDEX") > dex) {
            return false;
        }
        if (stats.get("reqSTR") > str) {
            return false;
        }
        if (stats.get("reqINT") > int_) {
            return false;
        }
        if (stats.get("reqLUK") > luk) {
            return false;
        }
        // Fame (reqPOP) requirement intentionally ignored — equips can be worn at any fame.
        return true;
    }

    static boolean matchesReqJob(Job job, int reqJob) {
        if (reqJob == 0) {
            return true;
        }

        int jobMask = getJobMask(job);
        return jobMask != 0 && (reqJob & jobMask) != 0;
    }

    static int getJobMask(Job job) {
        if (job == null) {
            return 0;
        }

        return switch (job.getJobNiche()) {
            case 1 -> 0x1;
            case 2 -> 0x2;
            case 3 -> 0x4;
            case 4 -> 0x8;
            case 5 -> 0x10;
            default -> 0;
        };
    }
}
