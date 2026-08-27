package forge.game.keyword;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MelodyUtil {
    private MelodyUtil() {
    }

    public static boolean canUseMelody(final SpellAbility sa) {
        return sa != null
                && sa.isSpell()
                && !sa.isCopied()
                && sa.getHostCard() != null
                && sa.getHostCard().isCreature();
    }

    public static CardCollection getAvailableCards(final Player payer) {
        final CardCollection result = new CardCollection();
        final CardCollection available = CardLists.filter(payer.getCardsIn(ZoneType.Battlefield), CardPredicates.CAN_TAP);

        for (final Card card : available) {
            for (final KeywordInterface keyword : card.getKeywords(Keyword.MELODY)) {
                if (keyword instanceof Melody) {
                    result.add(card);
                    break;
                }
            }
        }

        return result;
    }

    public static List<ManaCost> getPaymentOptions(final Card card, final ManaCostBeingPaid remaining) {
        final List<ManaCost> result = new ArrayList<>();

        for (final KeywordInterface keyword : card.getKeywords(Keyword.MELODY)) {
            if (!(keyword instanceof Melody melody)) {
                continue;
            }

            switch (melody.getPaymentType()) {
                case ManaCost:
                    addPaymentOption(result, remaining, melody.getManaCost());
                    break;
                case Choice:
                    for (final ManaCost payment : melody.getManaCosts()) {
                        addPaymentOption(result, remaining, payment);
                    }
                    break;
                case AnyOneColor:
                    for (final byte color : MagicColor.WUBRG) {
                        addPaymentOption(result, remaining, createColoredPayment(color, melody.getPaymentAmount()));
                    }
                    break;
                case AnyColor:
                    for (final ManaCost payment : getAnyColorPayments(remaining, melody.getPaymentAmount())) {
                        addPaymentOption(result, remaining, payment);
                    }
                    break;
                default:
                    break;
            }
        }

        return result;
    }

    private static void addPaymentOption(final List<ManaCost> result, final ManaCostBeingPaid remaining, final ManaCost payment) {
        if (!canApplyPayment(remaining, payment)) {
            return;
        }

        for (final ManaCost existing : result) {
            if (existing.toString().equals(payment.toString())) {
                return;
            }
        }

        result.add(payment);
    }

    private static ManaCost createColoredPayment(final byte color, final int amount) {
        final String symbol = MagicColor.toShortString(color);
        return new ManaCost((symbol + " ").repeat(amount).trim());
    }

    private static List<ManaCost> getAnyColorPayments(final ManaCostBeingPaid remaining, final int amount) {
        Map<String, PaymentState> states = new LinkedHashMap<>();
        states.put(remaining.toString(), new PaymentState(new ManaCostBeingPaid(remaining), ""));

        for (int i = 0; i < amount; i++) {
            final Map<String, PaymentState> nextStates = new LinkedHashMap<>();

            for (final PaymentState state : states.values()) {
                for (final byte color : MagicColor.WUBRG) {
                    final ManaCostBeingPaid nextRemaining = new ManaCostBeingPaid(state.remaining);
                    if (!payColoredUnit(nextRemaining, color)) {
                        continue;
                    }

                    final String symbol = MagicColor.toShortString(color);
                    final String payment = state.payment.isEmpty() ? symbol : state.payment + " " + symbol;

                    nextStates.putIfAbsent(nextRemaining.toString(), new PaymentState(nextRemaining, payment));
                }
            }

            states = nextStates;
            if (states.isEmpty()) {
                break;
            }
        }

        final List<ManaCost> result = new ArrayList<>();
        for (final PaymentState state : states.values()) {
            if (!state.payment.isEmpty()) {
                result.add(new ManaCost(state.payment));
            }
        }

        return result;
    }

    private static final class PaymentState {
        private final ManaCostBeingPaid remaining;
        private final String payment;

        private PaymentState(final ManaCostBeingPaid remaining, final String payment) {
            this.remaining = remaining;
            this.payment = payment;
        }
    }

    private static boolean isSupportedPayment(final ManaCost payment) {
        return payment != null
                && !payment.isNoCost()
                && !payment.isZero()
                && payment.getShardCount(ManaCostShard.X) == 0;
    }

    public static boolean canApplyPayment(final ManaCostBeingPaid remaining, final ManaCost payment) {
        if (!isSupportedPayment(payment)) {
            return false;
        }

        final ManaCostBeingPaid test = new ManaCostBeingPaid(remaining);
        return applyPaymentInternal(test, payment);
    }

    public static boolean applyPayment(final ManaCostBeingPaid remaining, final ManaCost payment) {
        if (!canApplyPayment(remaining, payment)) {
            return false;
        }

        return applyPaymentInternal(remaining, payment);
    }

    private static boolean applyPaymentInternal(final ManaCostBeingPaid remaining, final ManaCost payment) {
        if (!isSimplePayment(payment)) {
            final int before = remaining.getConvertedManaCost();
            remaining.subtractManaCost(payment);
            return before - remaining.getConvertedManaCost() == payment.getCMC();
        }

        for (final ManaCostShard shard : payment) {
            if (!payColoredUnit(remaining, shard.getColorMask())) {
                return false;
            }
        }

        for (int i = 0; i < payment.getGenericCost(); i++) {
            if (!payGenericUnit(remaining)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSimplePayment(final ManaCost payment) {
        for (final ManaCostShard shard : payment) {
            if (!shard.isMonoColor() || shard.isPhyrexian() || shard.isOr2Generic() || shard.isSnow() || shard.isColorless()) {
                return false;
            }
        }

        return true;
    }

    private static boolean payGenericUnit(final ManaCostBeingPaid remaining) {
        if (remaining.getGenericManaAmount() > 0) {
            remaining.decreaseGenericMana(1);
            return true;
        }

        for (final ManaCostShard shard : remaining.getDistinctShards()) {
            if (!shard.isOr2Generic()) {
                continue;
            }

            remaining.decreaseShard(shard, 1);
            remaining.increaseGenericMana(1);
            return true;
        }

        return false;
    }

    private static boolean payColoredUnit(final ManaCostBeingPaid remaining, final byte color) {
        final List<ManaCostShard> payable = new ArrayList<>();

        for (final ManaCostShard shard : remaining.getDistinctShards()) {
            if (shard.isSnow() || shard.isColorless()) {
                continue;
            }

            if (shard.canBePaidWithManaOfColor(color)) {
                payable.add(shard);
            }
        }

        final ManaCostShard chosen = remaining.getShardToPayByPriority(payable, color);
        if (chosen == null) {
            return false;
        }

        remaining.decreaseShard(chosen, 1);

        if (chosen.isOr2Generic() && (chosen.getColorMask() & color) == 0) {
            remaining.increaseGenericMana(1);
        }

        return true;
    }

    public static boolean isPaymentAvailable(final Card card, final ManaCostBeingPaid remaining, final ManaCost payment) {
        if (card == null || payment == null) {
            return false;
        }

        for (final ManaCost available : getPaymentOptions(card, remaining)) {
            if (available.toString().equals(payment.toString())) {
                return true;
            }
        }

        return false;
    }

    public static boolean preventsUntap(final Card melodySource,
                                        final Player untappingPlayer) {
        if (melodySource == null
                || untappingPlayer == null
                || melodySource.getController() != untappingPlayer) {
            return false;
        }

        for (final Card creature : untappingPlayer.getCreaturesInPlay()) {
            if (creature.isPhasedOut()) {
                continue;
            }

            final SpellAbility castSA = creature.getCastSA();
            if (castSA == null
                    || castSA.getActivatingPlayer() != untappingPlayer) {
                continue;
            }

            for (final Card paidBy : castSA.getPaidByMelody()) {
                // Identity is intentional. A card that left and returned is
                // a new game object even if it retained the same card ID.
                if (paidBy == melodySource) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void copyCastData(final Card source,
                                    final Card destination,
                                    final Function<Card, Card> cardMapper,
                                    final Function<Player, Player> playerMapper) {
        final SpellAbility originalCast = source.getCastSA();
        if (originalCast == null || originalCast.getPaidByMelody().isEmpty()) {
            return;
        }

        final SpellAbility copiedCast = originalCast.copy(destination, true);
        if (copiedCast == null) {
            return;
        }

        copiedCast.setActivatingPlayer(
                playerMapper.apply(originalCast.getActivatingPlayer())
        );
        copiedCast.clearPaidByMelody();

        for (final Card melodySource : originalCast.getPaidByMelody()) {
            final Card mappedSource = cardMapper.apply(melodySource);
            if (mappedSource != null) {
                copiedCast.addPaidByMelody(mappedSource);
            }
        }

        if (!copiedCast.getPaidByMelody().isEmpty()) {
            destination.setCastSA(copiedCast);
        }
    }
}