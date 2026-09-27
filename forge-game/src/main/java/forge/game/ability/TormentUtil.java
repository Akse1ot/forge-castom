package forge.game.ability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardZoneTable;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TormentUtil {
    public static final String CHOICE_SACRIFICE = "Sacrifice a permanent";
    public static final String CHOICE_DISCARD = "Discard a card";
    public static final String CHOICE_LOSE_LIFE = "Lose 3 life";
    public static final String CHOICE_DEFERRED = "Deferred";

    public static final String CHOICE_KIND_EFFECT = "Torment";
    public static final String CHOICE_KIND_COST = "TormentCost";

    private TormentUtil() {
    }

    public static CardCollection getSacrificablePermanents(final Player player,
                                                           final SpellAbility sa,
                                                           final boolean effect) {
        if (player == null) {
            return new CardCollection();
        }

        return CardLists.filter(
                player.getCardsIn(ZoneType.Battlefield),
                CardPredicates.canBeSacrificedBy(sa, effect));
    }

    public static CardCollection getDiscardableCards(final Player player,
                                                     final SpellAbility sa,
                                                     final boolean effect) {
        final CardCollection result = new CardCollection();

        if (player == null || !player.canDiscardBy(sa, effect)) {
            return result;
        }

        result.addAll(CardLists.filter(
                player.getCardsIn(ZoneType.Hand),
                card -> card.canBeDiscardedBy(sa, effect)));

        if (sa != null
                && sa.isSpell()
                && sa.getHostCard() != null
                && sa.getHostCard().isInZone(ZoneType.Hand)) {
            result.remove(sa.getHostCard());
        }

        return result;
    }

    public static List<String> getAvailableChoices(final Player player,
                                                   final SpellAbility sa,
                                                   final boolean effect) {
        final List<String> choices = new ArrayList<>();

        if (player == null || !player.isInGame()) {
            return choices;
        }

        if (!getSacrificablePermanents(player, sa, effect).isEmpty()) {
            choices.add(CHOICE_SACRIFICE);
        }

        if (!getDiscardableCards(player, sa, effect).isEmpty()) {
            choices.add(CHOICE_DISCARD);
        }

        // Losing life is an outcome, not a life payment.
        // It remains a legal Torment choice even if the player can't lose life.
        choices.add(CHOICE_LOSE_LIFE);

        return choices;
    }

    public static boolean torment(final Player tormentor,
                                  final Player tormented,
                                  final SpellAbility sa,
                                  final boolean effect) {
        return torment(tormentor, tormented, sa, effect, null);
    }

    public static boolean torment(final Player tormentor,
                                  final Player tormented,
                                  final SpellAbility sa,
                                  final boolean effect,
                                  final String preferredChoice) {
        if (tormentor == null || tormented == null || !tormented.isInGame()) {
            return false;
        }

        final List<String> choices = getAvailableChoices(tormented, sa, effect);
        String choice = preferredChoice;

        while (!choices.isEmpty()) {
            if (choice == null || !choices.contains(choice)) {
                choice = tormented.getController().chooseSomeType(
                        effect ? CHOICE_KIND_EFFECT : CHOICE_KIND_COST,
                        sa,
                        choices,
                        false);
            }

            if (choice == null) {
                return false;
            }

            final boolean completed;

            if (CHOICE_SACRIFICE.equals(choice)) {
                completed = sacrifice(tormented, sa, effect);
            } else if (CHOICE_DISCARD.equals(choice)) {
                completed = discard(tormented, sa, effect);
            } else if (CHOICE_LOSE_LIFE.equals(choice)) {
                completed = loseLife(tormented, sa);
            } else {
                completed = false;
            }

            if (completed) {
                final Map<AbilityKey, Object> runParams =
                        AbilityKey.mapFromPlayer(tormented);
                runParams.put(AbilityKey.Activator, tormentor);
                runParams.put(AbilityKey.Cause, sa);

                tormented.getGame().getTriggerHandler().runTrigger(
                        TriggerType.Tormented,
                        runParams,
                        false);

                return true;
            }

            choices.remove(choice);
            choice = null;
        }

        return false;
    }

    private static boolean sacrifice(final Player player,
                                     final SpellAbility sa,
                                     final boolean effect) {
        final CardCollection valid =
                getSacrificablePermanents(player, sa, effect);

        if (valid.isEmpty()) {
            return false;
        }

        final CardCollectionView chosen =
                player.getController().choosePermanentsToSacrifice(
                        sa,
                        1,
                        1,
                        valid,
                        "permanent");

        if (chosen == null || chosen.size() != 1) {
            return false;
        }

        final Game game = player.getGame();
        final Map<AbilityKey, Object> params = AbilityKey.newMap();
        final CardZoneTable table =
                AbilityKey.addCardZoneTableParams(params, sa);

        final CardCollectionView sacrificed =
                game.getAction().sacrifice(chosen, sa, effect, params);

        table.triggerChangesZoneAll(game, sa);

        return sacrificed != null && !sacrificed.isEmpty();
    }

    private static boolean discard(final Player player,
                                   final SpellAbility sa,
                                   final boolean effect) {
        final CardCollection valid =
                getDiscardableCards(player, sa, effect);

        if (valid.isEmpty()) {
            return false;
        }

        final CardCollectionView chosen =
                player.getController().chooseCardsToDiscardFrom(
                        player,
                        sa,
                        valid,
                        1,
                        1);

        if (chosen == null || chosen.size() != 1) {
            return false;
        }

        final Card card = chosen.getFirst();
        final Game game = player.getGame();

        final Map<AbilityKey, Object> params = AbilityKey.newMap();
        final CardZoneTable table =
                AbilityKey.addCardZoneTableParams(params, sa);

        final Map<Player, CardCollectionView> discarded = new HashMap<>();
        discarded.put(player, new CardCollection(card));

        SpellAbilityEffect.discard(sa, effect, discarded, params);
        table.triggerChangesZoneAll(game, sa);

        return !player.getCardsIn(ZoneType.Hand).contains(card);
    }

    private static boolean loseLife(final Player player,
                                    final SpellAbility sa) {
        final int lost = player.loseLife(3, false, false, sa);

        if (lost > 0) {
            final Map<Player, Integer> lossMap = new HashMap<>();
            lossMap.put(player, lost);

            player.getGame().getTriggerHandler().runTrigger(
                    TriggerType.LifeLostAll,
                    AbilityKey.mapFromPIMap(lossMap),
                    false);
        }

        return true;
    }
}