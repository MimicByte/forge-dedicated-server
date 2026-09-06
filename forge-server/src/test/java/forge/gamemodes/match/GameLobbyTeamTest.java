package forge.gamemodes.match;

import forge.game.GameType;
import java.util.EnumSet;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GameLobbyTeamTest {
    @Test public void archenemyOpponentsShareOneTeamInThreeAndFourPlayerRooms() {
        Set<GameType> variants = EnumSet.of(GameType.Commander, GameType.Archenemy);
        LobbySlot archenemy = slot(0, true);
        LobbySlot opponentOne = slot(1, false);
        LobbySlot opponentTwo = slot(2, false);
        LobbySlot opponentThree = slot(3, false);

        Assert.assertEquals(GameLobby.resolveTeam(variants, archenemy), 0);
        Assert.assertEquals(GameLobby.resolveTeam(variants, opponentOne), 1);
        Assert.assertEquals(GameLobby.resolveTeam(variants, opponentTwo), 1);
        Assert.assertEquals(GameLobby.resolveTeam(variants, opponentThree), 1);
    }

    @Test public void nonArchenemyVariantsKeepDedicatedTeams() {
        Assert.assertEquals(GameLobby.resolveTeam(EnumSet.of(GameType.Commander), slot(3, false)), 3);
    }

    private static LobbySlot slot(int team, boolean archenemy) {
        return new LobbySlot(LobbySlotType.REMOTE, "Player", -1, -1, team, archenemy, false, Set.of());
    }
}
