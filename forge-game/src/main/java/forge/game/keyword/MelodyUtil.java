package forge.game.keyword;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
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

    public static Map<Card, List<ManaCost>> getAvailablePayments(final Player payer) {
        final Map<Card, List<ManaCost>> result = new LinkedHashMap<>();

        final CardCollection available = CardLists.filter(
                payer.getCardsIn(ZoneType.Battlefield),
                CardPredicates.CAN_TAP
        );

        for (final Card card : available) {
            for (final KeywordInterface keyword : card.getKeywords(Keyword.MELODY)) {
                if (!(keyword instanceof KeywordWithCostInterface melody)) {
                    continue;
                }

                final Cost cost = melody.getCost();
                if (!cost.hasManaCost() || !cost.isOnlyManaCost()) {
                    continue;
                }

                final ManaCost manaCost = cost.getCostMana().getMana();
                if (!isSupportedPayment(manaCost)) {
                    continue;
                }

                final List<ManaCost> costs =
                        result.computeIfAbsent(card, ignored -> new ArrayList<>());

                boolean duplicate = false;
                for (final ManaCost existing : costs) {
                    if (existing.toString().equals(manaCost.toString())) {
                        duplicate = true;
                        break;
                    }
                }

                if (!duplicate) {
                    costs.add(manaCost);
                }
            }
        }

        return result;
    }

    private static boolean isSupportedPayment(final ManaCost payment) {
        return payment != null
                && !payment.isNoCost()
                && !payment.isZero()
                && payment.getShardCount(ManaCostShard.X) == 0;
    }

    public static boolean canApplyPayment(final ManaCostBeingPaid remaining,
                                          final ManaCost payment) {
        if (!isSupportedPayment(payment)) {
            return false;
        }

        final ManaCostBeingPaid test = new ManaCostBeingPaid(remaining);
        final int before = test.getConvertedManaCost();

        test.subtractManaCost(payment);

        return before - test.getConvertedManaCost() == payment.getCMC();
    }

    public static boolean applyPayment(final ManaCostBeingPaid remaining,
                                       final ManaCost payment) {
        if (!canApplyPayment(remaining, payment)) {
            return false;
        }

        remaining.subtractManaCost(payment);
        return true;
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