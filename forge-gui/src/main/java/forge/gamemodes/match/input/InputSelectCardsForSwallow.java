package forge.gamemodes.match.input;

import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.TextUtil;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InputSelectCardsForSwallow
        extends InputSelectManyBase<Card> {
    private static final long serialVersionUID = 1L;

    private final Map<Card, ManaCostShard> chosenCards =
            new LinkedHashMap<>();
    private final Map<Card, Byte> chosenColors =
            new LinkedHashMap<>();

    private final ManaCost originalCost;
    private ManaCostBeingPaid remainingCost;

    private final Player player;
    private final CardCollectionView availableCards;
    private final String mechanicName;

    public InputSelectCardsForSwallow(
            final PlayerControllerHuman controller,
            final Player player,
            final SpellAbility sa,
            final ManaCost cost,
            final CardCollectionView availableCards) {
        super(controller, 0, availableCards.size(), sa);

        this.player = player;
        this.originalCost = cost;
        this.remainingCost = new ManaCostBeingPaid(cost);
        this.availableCards = availableCards;
        this.mechanicName =
                sa.isActivatedAbility()
                        && sa.getHostCard().hasKeyword(Keyword.ABYSSAL)
                        ? "Abyssal"
                        : "Swallow";
    }

    @Override
    protected String getMessage() {
        return TextUtil.concatNoSpace(
                "Choose creatures to sacrifice for ",
                mechanicName,
                ".\n",
                "Remaining mana cost is ",
                remainingCost.toString());
    }

    @Override
    protected boolean onCardSelected(
            final Card card,
            final List<Card> otherCardsToSelect,
            final ITriggerEvent triggerEvent) {
        if (!availableCards.contains(card)) {
            return false;
        }

        if (chosenColors.containsKey(card)) {
            chosenColors.remove(card);
            rebuildRemainingCost();

            onSelectStateChanged(card, false);
            refresh();
            return true;
        }

        final byte chosenColor = choosePaymentColor(card);
        final ManaCostShard shard =
                remainingCost.payManaViaSwallow(chosenColor);

        if (shard == null) {
            showMessage(TextUtil.concatNoSpace(
                    card.toString(),
                    " cannot pay any part of ",
                    remainingCost.toString(),
                    " with ",
                    mechanicName,
                    "."));
            return false;
        }

        chosenColors.put(card, chosenColor);
        chosenCards.put(card, shard);

        onSelectStateChanged(card, true);
        refresh();
        return true;
    }

    private byte choosePaymentColor(final Card card) {
        ColorSet colors = card.getColor();

        if (colors.isMulticolor()) {
            colors = ColorSet.fromMask(
                    colors.getColor()
                            & remainingCost.getUnpaidColors());
        }

        if (colors.isMulticolor()) {
            return player.getController()
                    .chooseColorAllowColorless(
                            mechanicName
                                    + " "
                                    + card.toString()
                                    + " for which color?",
                            card,
                            colors);
        }

        return colors.getColor();
    }

    private void rebuildRemainingCost() {
        remainingCost = new ManaCostBeingPaid(originalCost);
        chosenCards.clear();

        for (final Map.Entry<Card, Byte> entry
                : chosenColors.entrySet()) {
            final ManaCostShard shard =
                    remainingCost.payManaViaSwallow(
                            entry.getValue());

            if (shard == null) {
                throw new IllegalStateException(
                        "Unable to replay " + mechanicName + " payment");
            }

            chosenCards.put(entry.getKey(), shard);
        }
    }

    @Override
    public String getActivateAction(final Card card) {
        if (availableCards.contains(card)) {
            return "sacrifice creature for " + mechanicName;
        }
        return null;
    }

    public Map<Card, ManaCostShard> getSwallowMap() {
        if (hasCancelled()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(chosenCards);
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
        return chosenColors.keySet();
    }
}