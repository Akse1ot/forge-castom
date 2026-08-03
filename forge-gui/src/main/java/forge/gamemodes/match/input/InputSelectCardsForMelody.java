package forge.gamemodes.match.input;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.keyword.MelodyUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.spellability.SpellAbility;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InputSelectCardsForMelody
        extends InputSelectManyBase<Card> {
    private static final long serialVersionUID = 1L;

    private final Map<Card, List<ManaCost>> availablePayments;
    private final Map<Card, ManaCost> selectedPayments =
            new LinkedHashMap<>();

    private final ManaCost originalCost;
    private ManaCostBeingPaid remainingCost;

    public InputSelectCardsForMelody(
            final PlayerControllerHuman controller,
            final SpellAbility sa,
            final ManaCost cost,
            final Map<Card, List<ManaCost>> availablePayments) {
        super(controller, 0, availablePayments.size(), sa);

        this.originalCost = cost;
        this.remainingCost = new ManaCostBeingPaid(cost);
        this.availablePayments = availablePayments;
    }

    @Override
    protected String getMessage() {
        return TextUtil.concatNoSpace(
                "Choose permanents to tap for Melody.\n",
                "Remaining mana cost is ",
                remainingCost.toString()
        );
    }

    @Override
    protected boolean onCardSelected(
            final Card card,
            final List<Card> otherCardsToSelect,
            final ITriggerEvent triggerEvent) {
        final List<ManaCost> cardPayments =
                availablePayments.get(card);

        if (cardPayments == null) {
            return false;
        }

        if (selectedPayments.containsKey(card)) {
            selectedPayments.remove(card);
            rebuildRemainingCost();
            onSelectStateChanged(card, false);
            refresh();
            return true;
        }

        final List<ManaCost> payable = new ArrayList<>();
        for (final ManaCost payment : cardPayments) {
            if (MelodyUtil.canApplyPayment(remainingCost, payment)) {
                payable.add(payment);
            }
        }

        if (payable.isEmpty()) {
            showMessage(card + " cannot pay any part of "
                    + remainingCost + " with Melody.");
            return false;
        }

        final ManaCost chosen;
        if (payable.size() == 1) {
            chosen = payable.get(0);
        } else {
            chosen = getController().getGui().one(
                    "Choose a Melody cost for " + card,
                    payable
            );
        }

        if (chosen == null) {
            return false;
        }

        selectedPayments.put(card, chosen);
        rebuildRemainingCost();
        onSelectStateChanged(card, true);
        refresh();
        return true;
    }

    private void rebuildRemainingCost() {
        remainingCost = new ManaCostBeingPaid(originalCost);

        for (final ManaCost payment : selectedPayments.values()) {
            MelodyUtil.applyPayment(remainingCost, payment);
        }
    }

    @Override
    public String getActivateAction(final Card card) {
        return availablePayments.containsKey(card)
                ? "tap for Melody"
                : null;
    }

    public Map<Card, ManaCost> getMelodyPayments() {
        if (hasCancelled()) {
            return new LinkedHashMap<>();
        }
        return selectedPayments;
    }

    @Override
    protected boolean hasEnoughTargets() {
        return true;
    }

    @Override
    protected boolean hasAllTargets() {
        return false;
    }

    @Override
    public Collection<Card> getSelected() {
        return selectedPayments.keySet();
    }
}