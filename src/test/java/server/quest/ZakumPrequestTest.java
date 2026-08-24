package server.quest;

import client.Character;
import client.Client;
import client.QuestStatus;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZakumPrequestTest {

    @Test
    void completesApprovalTrialsAndGrantsEntryTicket() {
        Character character = mock(Character.class);
        Client client = mock(Client.class);
        Quest approval = mock(Quest.class);
        Quest trials = mock(Quest.class);
        QuestStatus approvalStatus = mock(QuestStatus.class);
        QuestStatus trialsStatus = mock(QuestStatus.class);

        when(character.haveItem(ZakumPrequest.EYE_OF_FIRE)).thenReturn(false);
        when(character.canHold(ZakumPrequest.EYE_OF_FIRE, ZakumPrequest.EYE_OF_FIRE_REWARD)).thenReturn(true);
        when(character.getClient()).thenReturn(client);
        when(character.getQuest(approval)).thenReturn(approvalStatus);
        when(character.getQuest(trials)).thenReturn(trialsStatus);
        when(approvalStatus.getStatus()).thenReturn(QuestStatus.Status.NOT_STARTED);
        when(trialsStatus.getStatus()).thenReturn(QuestStatus.Status.STARTED);

        try (var quests = mockStatic(Quest.class);
             var inventory = mockStatic(InventoryManipulator.class)) {
            quests.when(() -> Quest.getInstance(ZakumPrequest.APPROVAL_QUEST)).thenReturn(approval);
            quests.when(() -> Quest.getInstance(ZakumPrequest.TRIALS_QUEST)).thenReturn(trials);
            inventory.when(() -> InventoryManipulator.addById(
                    client, ZakumPrequest.EYE_OF_FIRE, ZakumPrequest.EYE_OF_FIRE_REWARD)).thenReturn(true);

            assertTrue(ZakumPrequest.complete(character));

            verify(approval).forceStart(character, constants.id.NpcId.MAPLE_ADMINISTRATOR);
            verify(trials).forceComplete(character, constants.id.NpcId.MAPLE_ADMINISTRATOR);
        }
    }

    @Test
    void leavesQuestStateUntouchedWhenEntryTicketCannotFit() {
        Character character = mock(Character.class);
        when(character.haveItem(ZakumPrequest.EYE_OF_FIRE)).thenReturn(false);
        when(character.canHold(ZakumPrequest.EYE_OF_FIRE, ZakumPrequest.EYE_OF_FIRE_REWARD)).thenReturn(false);

        assertFalse(ZakumPrequest.complete(character));
    }
}
