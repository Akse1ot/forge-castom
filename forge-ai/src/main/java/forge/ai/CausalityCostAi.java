package forge.ai;

import forge.card.CardType.CoreType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.CausalityCostHelper;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Comparator;

/** The choice strategy only; CausalityCostHelper owns all game-rule validation. */
public final class CausalityCostAi {
    private CausalityCostAi() { }

    public static CardCollectionView choose(Player ai, SpellAbility sa, CardCollectionView available) {
        CausalityCostHelper.Selection previous = sa.getCausalitySelection();
        try {
            sa.setCausalitySelection(null);
            CardCollection targets = new CardCollection();
            if (!sa.getTargets().getTargetCards().isEmpty()) {
                targets.addAll(sa.getTargets().getTargetCards());
            } else {
                for (Card card : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                    if (card.getController().isOpponentOf(ai) && card.isPermanent() && !card.isLand()) {
                        targets.add(card);
                    }
                }
            }
            while (!targets.isEmpty()) {
                Card target = ComputerUtilCard.getBestRemovalTargetAI(ai, targets);
                targets.remove(target);
                for (CoreType type : target.getType().getCoreTypes()) {
                    CardCollection pool = new CardCollection();
                    for (Card card : available) {
                        if (card.getType().hasType(type)) {
                            pool.add(card);
                        }
                    }
                    // Prefer a smaller payment, retaining more expensive graveyard cards on a tie.
                    pool.sort(Comparator.comparingInt(Card::getCMC));
                    CardCollection chosen = new CardCollection();
                    for (Card card : pool) {
                        chosen.add(card);
                        if (CausalityCostHelper.select(sa, chosen)
                                && sa.canTarget(target, true)
                                && CausalityCostHelper.otherCostsPayable(sa)
                                && ComputerUtilCost.canPayCost(sa, ai, false)) {
                            return new CardCollection(chosen);
                        }
                    }
                }
            }
            return new CardCollection();
        } finally {
            sa.setCausalitySelection(previous);
        }
    }
}
