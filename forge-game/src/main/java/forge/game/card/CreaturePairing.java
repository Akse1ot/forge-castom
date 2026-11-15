package forge.game.card;

import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;

public final class CreaturePairing {
    private CreaturePairing() {
    }

    public static boolean canPair(final Card card, final Player player) {
        return card != null && card.isInPlay() && card.isCreature() && !card.isPhasedOut()
                && !card.isPaired() && card.getController() == player;
    }

    public static boolean canJoinAttack(final Card first, final Card second, final Player player) {
        return first != second && canPair(first, player) && canPair(second, player);
    }

    public static void pair(final Card first, final Card second, final Player player, final boolean temporary) {
        final Game game = first.getGame();
        final int expiry = temporary ? game.getPhaseHandler().getTurn() : -1;
        first.setPairedWith(second, expiry);
        second.setPairedWith(first, expiry);
        final Map<AbilityKey, Object> event = AbilityKey.mapFromPlayer(player);
        event.put(AbilityKey.Cards, List.of(CardCopyService.getLKICopy(first), CardCopyService.getLKICopy(second)));
        game.getTriggerHandler().runTrigger(TriggerType.BecomesPaired, event, false);
        // Record the listeners while both participants still have their pairing and controller.
        game.getTriggerHandler().collectTriggerForWaiting();
    }

    public static void expire(final Card card, final int turn) {
        if (card.getPairExpiryTurn() >= 0 && card.getPairExpiryTurn() <= turn) {
            final Card partner = card.getPairedWith();
            card.setPairedWith(null);
            if (partner != null && partner.getPairedWith() == card) {
                partner.setPairedWith(null);
            }
        }
    }
}
