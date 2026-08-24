package server.quest;

import client.Character;
import client.QuestStatus;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.NpcId;

/** Shared Zakum prequest state transition used by admin commands and bot automation. */
public final class ZakumPrequest {
    public static final int APPROVAL_QUEST = 100200;
    public static final int TRIALS_QUEST = 100201;
    public static final int EYE_OF_FIRE = 4001017;
    public static final short EYE_OF_FIRE_REWARD = 5;

    private ZakumPrequest() {}

    /**
     * Ensures the character can enter Zakum: approval started, trials completed, and an entry
     * ticket available. Returns false without changing quest state when the ticket cannot fit.
     */
    public static boolean complete(Character character) {
        if (character == null) {
            return false;
        }
        if (!character.haveItem(EYE_OF_FIRE) && !character.canHold(EYE_OF_FIRE, EYE_OF_FIRE_REWARD)) {
            return false;
        }

        startApproval(character);

        Quest trials = Quest.getInstance(TRIALS_QUEST);
        if (character.getQuest(trials).getStatus() != QuestStatus.Status.COMPLETED) {
            trials.forceComplete(character, NpcId.MAPLE_ADMINISTRATOR);
        }

        return character.haveItem(EYE_OF_FIRE)
                || InventoryManipulator.addById(character.getClient(), EYE_OF_FIRE, EYE_OF_FIRE_REWARD);
    }

    /** Starts the council-approval quest without advancing the later trial stages. */
    public static void startApproval(Character character) {
        Quest approval = Quest.getInstance(APPROVAL_QUEST);
        if (character.getQuest(approval).getStatus() == QuestStatus.Status.NOT_STARTED) {
            approval.forceStart(character, NpcId.MAPLE_ADMINISTRATOR);
        }
    }
}
