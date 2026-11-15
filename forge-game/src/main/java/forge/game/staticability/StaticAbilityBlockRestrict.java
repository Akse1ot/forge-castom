package forge.game.staticability;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class StaticAbilityBlockRestrict {

    static public int blockRestrictNum(Player defender) {
        return blockLimit(defender.getGame(), defender, false);
    }

    public static int turnBlockLimit(Game game) {
        return blockLimit(game, null, true);
    }

    private static int blockLimit(Game game, Player defender, boolean perTurn) {
        int num = Integer.MAX_VALUE;
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.BlockRestrict)
                        || "True".equals(stAb.getParam("PerTurn")) != perTurn) {
                    continue;
                }
                if (perTurn ? !stAb.hasParam("ValidDefender") : blockRestrict(stAb, defender)) {
                    int stNum = AbilityUtils.calculateAmount(stAb.getHostCard(),
                            stAb.getParamOrDefault("MaxBlockers", "1"), stAb);
                    if (stNum < num) {
                        num = stNum;
                    }
                }

            }
        }
        return num;
    }

    static public boolean blockRestrict(StaticAbility stAb, Player defender) {
        if (!stAb.matchesValidParam("ValidDefender", defender)) {
            return false;
        }
        return true;
    }
}
