package forge.game.ability.effects;

import java.util.Map;

import com.google.common.collect.Maps;

import forge.game.Game;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CreaturePairing;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;
import forge.util.Localizer;

public class BondEffect extends SpellAbilityEffect {
    @Override
    public void resolve(SpellAbility sa) {
        Card source = sa.getHostCard();
        Player p = sa.getActivatingPlayer();
        Game game = source.getGame();
        final boolean temporary = "True".equalsIgnoreCase(sa.getParam("PairUntilEndOfTurn"));
        for (Card tgtC : getTargetCards(sa)) {
            if (temporary && !tgtC.equalsWithGameTimestamp(source)) {
                continue;
            }
            Card gameCard = game.getCardState(tgtC, null);
            // gameCard is LKI in that case, the card is not in game anymore
            // or the timestamp did change
            // this should check Self too
            if (gameCard == null || !tgtC.equalsWithGameTimestamp(gameCard)) {
                continue;
            }
            if (gameCard.isPaired() || !gameCard.isCreature() || !gameCard.isInPlay() || gameCard.getController() != p
                    || (temporary && gameCard.isPhasedOut())) {
                continue;
            }

            // find list of valid cards to pair with
            CardCollectionView cards = CardLists.getValidCards(p.getCreaturesInPlay(), sa.getParam("ValidCards"), p, source, sa);
            if (temporary) {
                cards = CardLists.filter(cards, card -> CreaturePairing.canJoinAttack(gameCard, card, p)
                        && game.getCombat() != null && game.getCombat().isAttacking(card));
            }
            if (cards.isEmpty()) {
                continue;
            }

            Map<String, Object> params = Maps.newHashMap();
            params.put("Partner", gameCard); // info for AI to bond them

            Card partner = p.getController().chooseSingleEntityForEffect(cards, sa, Localizer.getInstance().getMessage("lblSelectACardPair"), true, params);

            if (partner != null && (!temporary || cards.contains(partner)
                    && gameCard.equalsWithGameTimestamp(game.getCardState(gameCard, null))
                    && partner.equalsWithGameTimestamp(game.getCardState(partner, null))
                    && CreaturePairing.canJoinAttack(gameCard, partner, p)
                    && game.getCombat() != null && game.getCombat().isAttacking(partner))) {
                CreaturePairing.pair(gameCard, partner, p, temporary);
            }
        }
    }

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        sb.append(Lang.joinHomogenous(getTargetCards(sa)));

        sb.append(" pairs with another unpaired creature you control.");
        return sb.toString();
    }

}
