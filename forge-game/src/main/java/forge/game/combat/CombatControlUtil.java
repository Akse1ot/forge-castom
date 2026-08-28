package forge.game.combat;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public final class CombatControlUtil {
    private static final String ASSIGN_COMBAT_DAMAGE_BY_ORIGINAL_CONTROLLER = "AssignCombatDamageByOriginalController";

    private CombatControlUtil() {
    }

    public static PlayerController getDamageAssignmentController(final Player assigningPlayer, final Card combatant) {
        if (assigningPlayer == combatant.getController() && combatant.hasKeyword(ASSIGN_COMBAT_DAMAGE_BY_ORIGINAL_CONTROLLER)
                && combatant.hasChosenPlayer() && assigningPlayer.getControllingPlayer() == combatant.getChosenPlayer()) {
            return assigningPlayer.getOriginalController();
        }
        return assigningPlayer.getController();
    }
}