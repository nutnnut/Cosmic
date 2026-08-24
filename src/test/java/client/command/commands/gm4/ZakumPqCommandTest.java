package client.command.commands.gm4;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZakumPqCommandTest {

    @Test
    void targetsSelfWhenNotInAParty() {
        Character player = mock(Character.class);
        when(player.getParty()).thenReturn(null);

        assertEquals(List.of(player), ZakumPqCommand.targets(player));
    }

    @Test
    void targetsEveryOnlinePartyMemberIncludingBots() {
        Character player = mock(Character.class);
        Character human = mock(Character.class);
        Character bot = mock(Character.class);
        when(player.getParty()).thenReturn(mock(Party.class));
        when(player.getPartyMembersOnline()).thenReturn(List.of(player, human, bot));

        assertEquals(List.of(player, human, bot), ZakumPqCommand.targets(player));
    }
}
