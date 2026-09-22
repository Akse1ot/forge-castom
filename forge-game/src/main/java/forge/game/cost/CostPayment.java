/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.cost;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardZoneTable;
import forge.game.keyword.Keyword;
import forge.game.mana.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Cost_Payment class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class CostPayment extends ManaConversionMatrix {
    static final String COALESCE_PAYMENT = "Coalesce";
    public static final String PAY_GENERIC_WITH_RETURN = "PayGenericWithReturn";

    private final Cost cost;
    private Cost adjustedCost;
    private final SpellAbility ability;
    private final List<CostPart> paidCostParts = Lists.newArrayList();

    public static void handleCoalesce(
            final SpellAbility sa,
            final boolean costIsPaid,
            final Map<AbilityKey, Object> params) {
        final CardCollection coalesced =
                sa.getPaidList(COALESCE_PAYMENT, true);

        if (coalesced == null || coalesced.isEmpty()) {
            return;
        }

        final CardCollection selected =
                new CardCollection(coalesced);

        for (final Card card : selected) {
            card.setUsedToPay(false);
        }

        if (costIsPaid) {
            final Game game = sa.getHostCard().getGame();
            final Player payer = sa.getActivatingPlayer();

            for (final Card card : selected) {
                final Card gameCard =
                        game.getCardState(card, null);

                if (gameCard == null
                        || !card.equalsWithGameTimestamp(gameCard)
                        || !gameCard.isInZone(ZoneType.Exile)
                        || !gameCard.getOwner().equals(payer)) {
                    continue;
                }

                game.getAction().moveToGraveyard(
                        gameCard,
                        null,
                        params);
            }
        }

        coalesced.clear();
    }

    private void releasePayGenericWithReturn() {
        final CardCollection selected =
                ability.getPaidList(
                        PAY_GENERIC_WITH_RETURN,
                        true);

        if (selected == null || selected.isEmpty()) {
            return;
        }

        for (final Card card : selected) {
            card.setUsedToPay(false);
            card.setReservedForZoneChangePayment(false);
        }

        selected.clear();
    }

    private boolean commitPayGenericWithReturn() {
        final CardCollection paid =
                ability.getPaidList(
                        PAY_GENERIC_WITH_RETURN,
                        true);

        if (paid == null || paid.isEmpty()) {
            return true;
        }

        final Game game =
                ability.getHostCard().getGame();

        final CardCollection selected =
                new CardCollection(paid);
        final CardCollection current =
                new CardCollection();

        for (final Card card : selected) {
            final Card gameCard =
                    game.getCardState(card, null);

            if (gameCard == null
                    || !card.equalsWithGameTimestamp(gameCard)
                    || !gameCard.isInZone(ZoneType.Battlefield)) {
                return false;
            }

            current.add(gameCard);
        }

        final CardZoneTable table =
                new CardZoneTable(
                        game.getLastStateBattlefield(),
                        game.getLastStateGraveyard());

        final Map<AbilityKey, Object> params =
                AbilityKey.newMap();
        AbilityKey.addCardZoneTableParams(
                params,
                table);

        for (int i = 0; i < current.size(); i++) {
            final Card selectedCard =
                    selected.get(i);
            final Card gameCard =
                    current.get(i);

            selectedCard.setUsedToPay(false);
            selectedCard.setReservedForZoneChangePayment(false);

            gameCard.setUsedToPay(false);
            gameCard.setReservedForZoneChangePayment(false);

            game.getAction().moveToHand(
                    gameCard,
                    null,
                    params);
        }

        paid.clear();

        if (!table.isEmpty()) {
            table.triggerChangesZoneAll(
                    game,
                    ability);
        }

        return true;
    }

    /**
     * <p>
     * Getter for the field <code>cost</code>.
     * </p>
     * 
     * @return a {@link forge.game.cost.Cost} object.
     */
    public final Cost getCost() {
        return this.cost;
    }

    public final SpellAbility getAbility() {
        return this.ability;
    }

    /**
     * <p>
     * Constructor for Cost_Payment.
     * </p>
     * 
     * @param cost
     *            a {@link forge.game.cost.Cost} object.
     * @param abil
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public CostPayment(final Cost cost, final SpellAbility abil) {
        this.cost = cost;
        this.adjustedCost = cost;
        this.ability = abil;
        restoreColorReplacements();
    }

    /**
     * <p>
     * canPayAdditionalCosts.
     * </p>
     * 
     * @param cost
     *            a {@link forge.game.cost.Cost} object.
     * @param ability
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    public static boolean canPayAdditionalCosts(Cost cost, final SpellAbility ability, final boolean effect) {
        return canPayAdditionalCosts(cost, ability, effect, ability.getActivatingPlayer());
    }
    public static boolean canPayAdditionalCosts(Cost cost, final SpellAbility ability, final boolean effect, final Player payer) {
        if (cost == null) {
            return true;
        }

        cost = CostAdjustment.adjust(cost, ability, effect);
        return cost.canPay(ability, payer, effect);
    }

    /**
     * <p>
     * isAllPaid.
     * </p>
     * 
     * @return a boolean.
     */
    public final boolean isFullyPaid() {
        return paidCostParts.containsAll(adjustedCost.getCostParts());
    }

    /**
     * <p>
     * cancelPayment.
     * </p>
     */
    public final void refundPayment() {
        Card sourceCard = this.ability.getHostCard();
        for (final CostPart part : this.paidCostParts) {
            part.refund(sourceCard);
            // Clear lists to prevent accumulation across multiple cancelled activations
            if (part instanceof CostPartWithList) {
                ((CostPartWithList) part).resetLists();
            } else if (part instanceof CostOr) {
                ((CostOr) part).resetNestedLists();
            }
        }

        releasePayGenericWithReturn();

        new ManaRefundService(this.ability).refundManaPaid();
    }

    public boolean payCost(
            final CostDecisionMakerBase decisionMaker) {
        adjustedCost =
                CostAdjustment.adjust(
                        cost,
                        ability,
                        decisionMaker.isEffect());

        List<CostPart> costParts =
                adjustedCost.getCostPartsWithZeroMana();

        if (adjustedCost.getCostParts().size() > 1) {
            // if mana part is shown here it wouldn't include reductions,
            // but that's just a minor inconvenience
            costParts =
                    decisionMaker.getPlayer()
                            .getController()
                            .orderCosts(costParts);
        }

        final Game game =
                decisionMaker.getPlayer().getGame();

        boolean success = false;

        try {
            for (final CostPart part : costParts) {
                try {
                    // Wrap the cost and push onto the cost stack
                    game.costPaymentStack.push(
                            part,
                            this);

                    PaymentDecision pd =
                            part.accept(decisionMaker);

                    // Right before we start paying as decided, we need to transfer the CostPayments matrix over?
                    if (pd != null) {
                        pd.matrix = this;
                    }

                    if (pd == null
                            || !part.payAsDecided(
                            decisionMaker.getPlayer(),
                            pd,
                            ability,
                            decisionMaker.isEffect())) {
                        return false;
                    }

                    this.paidCostParts.add(part);
                } finally {
                    game.costPaymentStack.pop(); // cost is resolved
                }
            }

            if (!commitPayGenericWithReturn()) {
                return false;
            }

            // clear lists used for undo
            for (final CostPart part
                    : this.paidCostParts) {
                if (part instanceof CostPartWithList listCost) {
                    listCost.resetLists();
                } else if (part instanceof CostOr orCost) {
                    orCost.resetNestedLists();
                }
            }

            success = true;
            return true;
        } finally {
            if (!success) {
                releasePayGenericWithReturn();
            }
        }
    }

    public final boolean payComputerCosts(final CostDecisionMakerBase decisionMaker) {
        // Just in case it wasn't set, but honestly it shouldn't have gotten
        // here without being set
        if (this.ability.getActivatingPlayer() == null) {
            this.ability.setActivatingPlayer(decisionMaker.getPlayer());
        }

        final Map<CostPart, PaymentDecision> decisions = Maps.newHashMap();
        // for Trinisphere make sure to include Zero
        final List<CostPart> parts = CostAdjustment.adjust(
                cost,
                ability,
                decisionMaker.isEffect()
        ).getCostPartsWithZeroMana();

        // Set all of the decisions before attempting to pay anything
        final Game game = decisionMaker.getPlayer().getGame();

        boolean success = false;

        try {
            final Map<Card, Boolean> decisionZoneReservations =
                    new IdentityHashMap<>();

            try {
                for (final CostPart part : parts) {
                    final PaymentDecision decision =
                            part.accept(decisionMaker);

                    if (decision == null) {
                        return false;
                    }

                    decision.matrix = this;

                    try {
                        // wrap the payment and push onto the cost stack
                        game.costPaymentStack.push(part, this);

                        if (decisionMaker.paysRightAfterDecision()
                                && !part.payAsDecided(
                                decisionMaker.getPlayer(),
                                decision,
                                ability,
                                decisionMaker.isEffect())) {
                            return false;
                        }
                    } finally {
                        game.costPaymentStack.pop(); // cost is either paid or deferred
                    }

                    decisions.put(part, decision);

                    if (ability.hasParam(PAY_GENERIC_WITH_RETURN)
                            && !decisionMaker.paysRightAfterDecision()) {
                        reserveDecisionZoneChangeForPayGenericWithReturn(
                                part,
                                decision,
                                decisionZoneReservations);
                    }
                }
            } finally {
                for (final Map.Entry<Card, Boolean> entry
                        : decisionZoneReservations.entrySet()) {
                    entry.getKey().setReservedForZoneChangePayment(
                            entry.getValue());
                }
            }

            final Map<Card, Boolean> reservedUsedToPay =
                    new IdentityHashMap<>();

            if (ability.getHostCard().hasKeyword(Keyword.SWALLOW)
                    || (ability.isActivatedAbility()
                    && ability.getHostCard().hasKeyword(Keyword.ABYSSAL))) {
                for (final CostPart part : parts) {
                    reserveSacrificeForSwallow(
                            part,
                            decisions.get(part),
                            reservedUsedToPay);
                }
            }

            if (ability.hasParam(PAY_GENERIC_WITH_RETURN)) {
                for (final CostPart part : parts) {
                    reserveZoneChangeForPayGenericWithReturn(
                            part,
                            decisions.get(part),
                            reservedUsedToPay);
                }
            }

            try {
                for (final CostPart part : parts) {
                    // wrap the payment and push onto the cost stack
                    try {
                        game.costPaymentStack.push(part, this);

                        if (!part.payAsDecided(
                                decisionMaker.getPlayer(),
                                decisions.get(part),
                                this.ability,
                                decisionMaker.isEffect())) {
                            return false;
                        }

                        // abilities care what was used to pay for them
                        if (part instanceof CostPartWithList) {
                            ((CostPartWithList) part).resetLists();
                        } else if (part instanceof CostOr) {
                            ((CostOr) part).resetNestedLists();
                        }
                    } finally {
                        game.costPaymentStack.pop(); // cost is resolved
                    }
                }

                if (!commitPayGenericWithReturn()) {
                    return false;
                }

                success = true;
                return true;
            } finally {
                for (final Map.Entry<Card, Boolean> entry
                        : reservedUsedToPay.entrySet()) {
                    entry.getKey().setUsedToPay(entry.getValue());
                }
            }
        } finally {
            if (!success) {
                releasePayGenericWithReturn();
            }
        }
    }

    private static void reserveDecisionZoneChangeForPayGenericWithReturn(
            final CostPart part,
            final PaymentDecision decision,
            final Map<Card, Boolean> previousReservationState) {
        if (decision == null) {
            return;
        }

        if (part instanceof CostSacrifice
                || part instanceof CostReturn
                || part instanceof CostExile
                || part instanceof CostPutCardToLib) {
            for (final Card card : decision.cards) {
                if (card == null || !card.isInPlay()) {
                    continue;
                }

                previousReservationState.putIfAbsent(
                        card,
                        card.isReservedForZoneChangePayment());

                card.setReservedForZoneChangePayment(true);
            }
            return;
        }

        if (!(part instanceof CostOr orCost)
                || decision.nested == null
                || decision.type == null) {
            return;
        }

        final Cost chosenCost;

        if ("Left".equals(decision.type)) {
            chosenCost = orCost.getLeftCost();
        } else if ("Right".equals(decision.type)) {
            chosenCost = orCost.getRightCost();
        } else {
            return;
        }

        final List<CostPart> nestedParts =
                chosenCost.getCostPartsWithZeroMana();

        if (nestedParts.size() != decision.nested.size()) {
            return;
        }

        for (int i = 0; i < nestedParts.size(); i++) {
            reserveDecisionZoneChangeForPayGenericWithReturn(
                    nestedParts.get(i),
                    decision.nested.get(i),
                    previousReservationState);
        }
    }

    private static void reserveSacrificeForSwallow(
            final CostPart part,
            final PaymentDecision decision,
            final Map<Card, Boolean> previousUsedState) {
        if (decision == null) {
            return;
        }

        if (part instanceof CostSacrifice) {
            for (final Card card : decision.cards) {
                reserveCardForSwallow(
                        card,
                        previousUsedState);
            }
            return;
        }

        if (!(part instanceof CostOr orCost)
                || decision.nested == null
                || decision.type == null) {
            return;
        }

        final Cost chosenCost;
        if ("Left".equals(decision.type)) {
            chosenCost = orCost.getLeftCost();
        } else if ("Right".equals(decision.type)) {
            chosenCost = orCost.getRightCost();
        } else {
            return;
        }

        final List<CostPart> nestedParts =
                chosenCost.getCostPartsWithZeroMana();

        if (nestedParts.size() != decision.nested.size()) {
            return;
        }

        for (int i = 0; i < nestedParts.size(); i++) {
            reserveSacrificeForSwallow(
                    nestedParts.get(i),
                    decision.nested.get(i),
                    previousUsedState);
        }
    }

    private static void reserveCardForSwallow(
            final Card card,
            final Map<Card, Boolean> previousUsedState) {
        if (card == null || !card.isInPlay()) {
            return;
        }

        if (!previousUsedState.containsKey(card)) {
            previousUsedState.put(
                    card,
                    card.isUsedToPay());
        }

        card.setUsedToPay(true);
    }

    private static void reserveZoneChangeForPayGenericWithReturn(
            final CostPart part,
            final PaymentDecision decision,
            final Map<Card, Boolean> previousUsedState) {
        if (decision == null) {
            return;
        }

        if (part instanceof CostSacrifice
                || part instanceof CostReturn
                || part instanceof CostExile
                || part instanceof CostPutCardToLib) {
            if (decision.cards != null) {
                for (final Card card : decision.cards) {
                    reserveCardForPayGenericWithReturn(
                            card,
                            previousUsedState);
                }
            }
            return;
        }

        if (!(part instanceof CostOr orCost)
                || decision.nested == null
                || decision.type == null) {
            return;
        }

        final Cost chosenCost;

        if ("Left".equals(decision.type)) {
            chosenCost = orCost.getLeftCost();
        } else if ("Right".equals(decision.type)) {
            chosenCost = orCost.getRightCost();
        } else {
            return;
        }

        final List<CostPart> nestedParts =
                chosenCost.getCostPartsWithZeroMana();

        if (nestedParts.size() != decision.nested.size()) {
            return;
        }

        for (int i = 0; i < nestedParts.size(); i++) {
            reserveZoneChangeForPayGenericWithReturn(
                    nestedParts.get(i),
                    decision.nested.get(i),
                    previousUsedState);
        }
    }

    private static void reserveCardForPayGenericWithReturn(
            final Card card,
            final Map<Card, Boolean> previousUsedState) {
        if (card == null || !card.isInPlay()) {
            return;
        }

        if (!previousUsedState.containsKey(card)) {
            previousUsedState.put(
                    card,
                    card.isUsedToPay());
        }

        card.setUsedToPay(true);
    }

    /**
     * <p>
     * getManaFrom.
     * </p>
     *
     * @param saBeingPaidFor
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link forge.game.mana.Mana} object.
     */
    public static Mana getMana(final Player player, final ManaCostShard shard, final SpellAbility saBeingPaidFor,
            final byte colorsPaid, Map<String, Integer> xManaCostPaidByColor) {
        final List<Pair<Mana, Integer>> weightedOptions = selectManaToPayFor(player.getManaPool(), shard,
            saBeingPaidFor, colorsPaid, xManaCostPaidByColor);

        // Exclude border case
        if (weightedOptions.isEmpty()) {
            return null; // There is no matching mana in the pool
        }

        // select equal weight possibilities
        List<Mana> manaChoices = new ArrayList<>();
        int bestWeight = Integer.MIN_VALUE;
        for (Pair<Mana, Integer> option : weightedOptions) {
            int thisWeight = option.getRight();
            Mana thisMana = option.getLeft();

            if (thisWeight > bestWeight) {
                manaChoices.clear();
                bestWeight = thisWeight;
            }

            if (thisWeight == bestWeight) {
                // add only distinct Mana-s
                boolean haveDuplicate = false;
                for (Mana m : manaChoices) {
                    if (m.equals(thisMana)) {
                        haveDuplicate = true;
                        break;
                    }
                }
                if (!haveDuplicate) {
                    manaChoices.add(thisMana);
                }
            }
        }

        // got an only one best option?
        if (manaChoices.size() == 1) {
            return manaChoices.get(0);
        }

        // Let them choose then
        return player.getController().chooseManaFromPool(manaChoices);
    }

    private static List<Pair<Mana, Integer>> selectManaToPayFor(final ManaPool manapool, final ManaCostShard shard,
            final SpellAbility saBeingPaidFor, final byte colorsPaid, Map<String, Integer> xManaCostPaidByColor) {
        final List<Pair<Mana, Integer>> weightedOptions = new ArrayList<>();
        for (final Mana thisMana : Lists.newArrayList(manapool)) {
            if (shard == ManaCostShard.COLORED_X && !ManaCostBeingPaid.canColoredXShardBePaidByColor(MagicColor.toShortString(thisMana.getColor()), xManaCostPaidByColor)) {
                continue;
            }

            if (!manapool.canPayForShardWithColor(shard, thisMana.getColor())) {
                continue;
            }

            if (shard.isSnow() && !thisMana.isSnow()) {
                continue;
            }

            if (thisMana.getManaAbility() != null && !thisMana.getManaAbility().meetsSpellAndShardRestrictions(saBeingPaidFor, shard, thisMana.getColor())) {
                continue;
            }

            if (!saBeingPaidFor.allowsPayingWithShard(thisMana.getSourceCard(), thisMana.getColor())) {
                continue;
            }

            int weight = 0;
            if (colorsPaid == -1) {
                // prefer colorless mana to spend
                weight += thisMana.isColorless() ? 5 : 0;
            } else {
                // get more colors for converge
                weight += (thisMana.getColor() | colorsPaid) != colorsPaid ? 5 : 0;
            }

            // prefer restricted mana to spend
            if (thisMana.isRestricted()) {
                weight += 2;
            }

            // Spend non-snow mana first
            if (!thisMana.isSnow()) {
                weight += 1;
            }

            weightedOptions.add(Pair.of(thisMana, weight));
        }
        return weightedOptions;
    }

    public static boolean handleOfferings(final SpellAbility sa, boolean test, boolean costIsPaid) {
        final Game game = sa.getHostCard().getGame();
        final CardZoneTable table = new CardZoneTable(game.getLastStateBattlefield(), game.getLastStateGraveyard());
        Map<AbilityKey, Object> params = AbilityKey.newMap();
        AbilityKey.addCardZoneTableParams(params, table);

        if (sa.isOffering()) {
            if (sa.getSacrificedAsOffering() == null) {
                return false;
            }
            final Card offering = sa.getSacrificedAsOffering();
            offering.setUsedToPay(false);
            if (test) {
                sa.resetSacrificedAsOffering();
            } else if (costIsPaid) {
                game.getAction().sacrifice(new CardCollection(offering), sa, false, params);
            }
        }
        if (sa.isEmerge()) {
            if (sa.getSacrificedAsEmerge() == null) {
                return false;
            }
            final Card emerge = sa.getSacrificedAsEmerge();
            emerge.setUsedToPay(false);
            if (test) {
                sa.resetSacrificedAsEmerge();
            } else if (costIsPaid) {
                game.getAction().sacrifice(new CardCollection(emerge), sa, false, params);
                sa.setSacrificedAsEmerge(game.getChangeZoneLKIInfo(emerge));
            }
        }
        if (!test
                && !sa.getSacrificedForSwallow().isEmpty()) {
            final CardCollection swallowed =
                    new CardCollection(
                            sa.getSacrificedForSwallow());

            for (final Card card : swallowed) {
                card.setUsedToPay(false);
            }

            if (costIsPaid) {
                game.getAction().sacrifice(
                        swallowed,
                        sa,
                        false,
                        params);
            }

            sa.clearSacrificedForSwallow();
        }
        if (!test) {
            handleCoalesce(sa, costIsPaid, params);
        }
        if (!table.isEmpty()) {
            table.triggerChangesZoneAll(sa.getHostCard().getGame(), sa);
        }
        return true;
    }
}
