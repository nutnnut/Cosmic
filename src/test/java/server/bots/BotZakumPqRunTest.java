package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic for the SSOT Zakum PQ run machine: the key-room partition every roster member derives
 * independently. WZ/DB-free.
 */
class BotZakumPqRunTest {

    @Test
    void everyRoomIsAssignedExactlyOnce() {
        int rooms = BotZakumPqRun.KEY_ROOMS.size();
        for (int workers = 1; workers <= 6; workers++) {
            for (int room = 0; room < rooms; room++) {
                int owners = 0;
                for (int w = 0; w < workers; w++) {
                    if (BotZakumPqRun.roomAssignedTo(room, w, workers)) {
                        owners++;
                    }
                }
                assertEquals(1, owners, "room " + room + " with " + workers + " workers");
            }
        }
    }

    @Test
    void soloWorkerOwnsAllRooms() {
        for (int room = 0; room < BotZakumPqRun.KEY_ROOMS.size(); room++) {
            assertTrue(BotZakumPqRun.roomAssignedTo(room, 0, 1));
        }
    }

    @Test
    void zeroWorkersAssignsNothing() {
        assertFalse(BotZakumPqRun.roomAssignedTo(0, 0, 0));
    }

    @Test
    void sevenRoomsSpreadAcrossFullParty() {
        // 6 workers (max party): worker 0 gets rooms 0+6, workers 1-5 one each — nobody idle.
        for (int w = 0; w < 6; w++) {
            int mine = 0;
            for (int room = 0; room < BotZakumPqRun.KEY_ROOMS.size(); room++) {
                if (BotZakumPqRun.roomAssignedTo(room, w, 6)) {
                    mine++;
                }
            }
            assertTrue(mine >= 1, "worker " + w + " has no room");
        }
    }
}
