package forge.game;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class WarBattleUtil {
    private WarBattleUtil() {
    }

    public static boolean isWar(final Card card) {
        return card != null
                && card.isBattle()
                && card.getType().hasSubtype("War");
    }

    public static Player getDesignatedProtector(final Game game) {
        if (game == null || game.getPhaseHandler() == null) {
            return null;
        }

        final Player activePlayer = game.getPhaseHandler().getPlayerTurn();
        if (activePlayer == null) {
            return null;
        }

        return game.getNextPlayerAfter(activePlayer);
    }

    public static boolean assignControllerAndProtector(final GameAction action, final Card war) {
        return assignControllerAndProtector(action, war, getDesignatedProtector(war == null ? null : war.getGame()));
    }

    public static boolean assignControllerAndProtector(final GameAction action, final Card war, final Player designated) {
        if (action == null || !isWar(war) || designated == null || !war.isInZone(ZoneType.Battlefield)) {
            return false;
        }

        boolean changed = false;

        if (war.getController() != designated) {
            if (designated != war.getController()) {
                war.runChangeControllerCommands();
            }
            war.setController(designated, war.getGame().getNextTimestamp());
            action.controllerChangeZoneCorrection(war);
            changed = true;
        }

        if (war.getProtectingPlayer() != designated) {
            war.setProtectingPlayer(designated);
            changed = true;
        }

        return changed;
    }
}