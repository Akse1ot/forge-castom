package forge.game.cost;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.CostPartVariantBuilder;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityCostPartReplacement;
import forge.game.zone.ZoneType;

import java.util.Map;

public class CostTraverse extends CostPartWithList {
    private static final long serialVersionUID = 1L;

    public CostTraverse() {
        super("1", "Land", "land card");
    }

    public static boolean hasPayableLand(final Player payer) {
        if (payer == null) {
            return false;
        }

        return payer.getCardsIn(ZoneType.Hand).anyMatch(card ->
                card.isLand() && !card.isUsedToPay());
    }

    @Override
    public int paymentOrder() {
        return 15;
    }

    @Override
    public boolean canPay(final SpellAbility ability, final Player payer, final boolean effect) {
        if (hasPayableLand(payer)) {
            return true;
        }

        // During early canPlay/discovery the concrete Traverse payment variant
        // may not have been selected yet. A legal replacement must keep the
        // spell discoverable so CostPartVariantBuilder can create that variant.
        if (ability == null || CostPartVariantBuilder.isPreparedForCurrentContext(ability)) {
            return false;
        }

        return StaticAbilityCostPartReplacement.hasReplacement(
                "Traverse",
                ability,
                payer
        );
    }

    @Override
    public String toString() {
        return "Put a land card from your hand onto the battlefield tapped";
    }

    @Override
    protected Card doPayment(final Player payer, final SpellAbility ability,
                             final Card targetCard, final boolean effect) {
        final boolean wasTapped = targetCard.isTapped();

        final Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        AbilityKey.addCardZoneTableParams(moveParams, table);

        targetCard.setUsedToPay(true);
        targetCard.setController(payer, 0);
        targetCard.setTapped(true);

        final Card movedCard = targetCard.getGame().getAction().moveToPlay(
                targetCard,
                payer,
                ability,
                moveParams
        );

        targetCard.setUsedToPay(false);

        if (movedCard == null || payer.getCardsIn(ZoneType.Hand).contains(targetCard)) {
            targetCard.setTapped(wasTapped);
            return null;
        }

        movedCard.setUsedToPay(true);
        return movedCard;
    }

    @Override
    public boolean payAsDecided(final Player payer, final PaymentDecision decision,
                                final SpellAbility ability, final boolean effect) {
        if (decision == null || decision.cards == null || decision.cards.size() != 1) {
            return false;
        }

        final Card card = decision.cards.getFirst();

        if (!card.isLand()
                || card.isUsedToPay()
                || !payer.getCardsIn(ZoneType.Hand).contains(card)) {
            return false;
        }

        executePayment(payer, ability, decision.cards, effect);

        if (payer.getCardsIn(ZoneType.Hand).contains(card)) {
            card.setUsedToPay(false);
            resetLists();
            return false;
        }

        reportPaidCardsTo(ability);
        return true;
    }

    @Override
    public void resetLists() {
        for (final Card card : cardList) {
            card.setUsedToPay(false);
        }
        super.resetLists();
    }

    @Override
    public String getHashForLKIList() {
        return "Traversed";
    }

    @Override
    public String getHashForCardList() {
        return "TraversedCards";
    }

    @Override
    public <T> T accept(final ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }
}