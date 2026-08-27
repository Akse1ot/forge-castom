package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.Map;

public class StoreSVarAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (sa instanceof WrappedAbility) {
            SpellAbility origSa = ((WrappedAbility)sa).getWrappedAbility();
            if (origSa.getHostCard().getName().equals("Maralen of the Mornsong Avatar")) {
                origSa.setXManaCostPaid(2);
            }
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode,
                                 String message, Map<String, Object> params) {
        if ("OpponentMayGrantPermanent".equals(sa.getParam("AILogic"))) {
            final Card host = sa.getHostCard();

            Card revealed = null;
            for (Object remembered : host.getRemembered()) {
                if (remembered instanceof Card card
                        && !card.isFaceDown()
                        && card.isInZone(ZoneType.Exile)) {
                    revealed = card;
                    break;
                }
            }

            if (revealed == null) {
                return false;
            }

            // The AI may use only public information here: it knows how many
            // face-down cards remain, but not their characteristics.
            int otherFaceDown = 0;
            for (Card card : player.getGame().getCardsIn(ZoneType.Exile)) {
                if (card.isFaceDown() && host.equals(card.getExiledWith())) {
                    otherFaceDown++;
                }
            }

            // If there are no other cards, refusing is always better:
            // the revealed permanent goes to the graveyard.
            if (otherFaceDown == 0) {
                return false;
            }

            final int revealedValue;
            if (revealed.isCreature()) {
                revealedValue = ComputerUtilCard.evaluateCreature(revealed);
            } else {
                int value = 100 + 50 * revealed.getCMC();
                if (revealed.isPlaneswalker()) {
                    value += 100;
                }
                revealedValue = value;
            }

            // The more unknown cards remain, the more willing the opponent
            // should be to grant this one permanent and stop the rest.
            final int acceptThreshold = 200 + 50 * otherFaceDown;

            return revealedValue <= acceptThreshold;
        }

        return super.confirmAction(player, sa, mode, message, params);
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        // Join Forces cards
        if (sa.hasParam("UnlessSwitched") && payers.size() > 1) {
            final Player p = sa.getActivatingPlayer();
            // not me or team mate
            if (!p.sameTeam(payer)) {
                return false;
            }
        }

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
