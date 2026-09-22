package forge.game.ability.effects;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;

import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class ReplaceCounterEffect extends SpellAbilityEffect {

    private static boolean matchesRedirectCounter(
            final SpellAbility sa,
            final Player source,
            final CounterType type) {

        final ReplacementEffect replacement = sa.getReplacementEffect();

        // First respect filters of the AddCounter replacement itself.
        if (replacement != null) {
            if (!replacement.matchesValidParam("ValidSource", source)) {
                return false;
            }

            if (replacement.hasParam("ValidCounterType")) {
                final CounterType validType =
                        CounterType.getType(
                                replacement.getParam("ValidCounterType"));

                if (!validType.equals(type)) {
                    return false;
                }
            }
        }

        // Also allow RedirectTo ReplaceCounter to narrow the filter further.
        if (!sa.matchesValidParam("ValidSource", source)) {
            return false;
        }

        if (sa.hasParam("ValidCounterType")) {
            final CounterType validType =
                    CounterType.getType(sa.getParam("ValidCounterType"));

            if (!validType.equals(type)) {
                return false;
            }
        }

        return true;
    }

    private static boolean futureAffectedMatchesRedirect(
            final Card host,
            final SpellAbility sa,
            final Card affected) {

        final String defined = sa.getParam("RedirectTo");

        // AbilityUtils "Valid ..." normally searches the current battlefield.
        // During Moved/ETB the entering card is not there yet, so validate
        // the prepared future-state card explicitly.
        if (defined == null || !defined.startsWith("Valid ")) {
            return false;
        }

        final CardCollection candidate = new CardCollection(affected);

        return !CardLists.getValidCards(
                candidate,
                defined.substring("Valid ".length()),
                sa.getActivatingPlayer(),
                host,
                sa).isEmpty();
    }

    private static boolean hasRemainingCounters(
            final Map<Optional<Player>, Multiset<CounterType>> counterTable) {

        for (Multiset<CounterType> counters : counterTable.values()) {
            if (!counters.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static void copyCounterContext(
            final Map<AbilityKey, Object> source,
            final Map<AbilityKey, Object> destination) {

        if (source.containsKey(AbilityKey.LastStateBattlefield)) {
            destination.put(
                    AbilityKey.LastStateBattlefield,
                    source.get(AbilityKey.LastStateBattlefield));
        }

        if (source.containsKey(AbilityKey.LastStateGraveyard)) {
            destination.put(
                    AbilityKey.LastStateGraveyard,
                    source.get(AbilityKey.LastStateGraveyard));
        }

        if (source.containsKey(AbilityKey.InternalTriggerTable)) {
            destination.put(
                    AbilityKey.InternalTriggerTable,
                    source.get(AbilityKey.InternalTriggerTable));
        }

        if (source.containsKey(AbilityKey.SimultaneousETB)) {
            destination.put(
                    AbilityKey.SimultaneousETB,
                    source.get(AbilityKey.SimultaneousETB));
        }
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card card = sa.getHostCard();

        // outside of Replacement Effect, unwanted result
        if (!sa.isReplacementAbility()) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<AbilityKey, Object> originalParams = (Map<AbilityKey, Object>) sa.getReplacingObject(AbilityKey.OriginalParams);
        @SuppressWarnings("unchecked")
        Map<Optional<Player>, Multiset<CounterType>> counterTable = (Map<Optional<Player>, Multiset<CounterType>>) sa.getReplacingObject(AbilityKey.CounterMap);

        if (sa.hasParam("RedirectTo")) {
            final Object pendingObject =
                    originalParams.get(AbilityKey.CounterTable);

            final boolean deferred =
                    pendingObject instanceof GameEntityCounterTable;

            final boolean movedToBattlefield =
                    deferred
                            && (ZoneType.Battlefield.equals(
                            originalParams.get(AbilityKey.Destination))
                            || ZoneType.Battlefield.name().equals(
                            String.valueOf(
                                    originalParams.get(
                                            AbilityKey.Destination))));

            GameEntityCounterTable resultTable = null;

            if (!deferred) {
                final Object resultObject =
                        originalParams.get(AbilityKey.CounterResultTable);

                if (!(resultObject instanceof GameEntityCounterTable)) {
                    originalParams.put(
                            AbilityKey.ReplacementResult,
                            ReplacementResult.NotReplaced);
                    return;
                }

                resultTable = (GameEntityCounterTable) resultObject;
            }

            final List<GameEntity> recipients =
                    Lists.newArrayList(
                            AbilityUtils.getDefinedEntities(
                                    card,
                                    sa.getParam("RedirectTo"),
                                    sa));

            final GameEntity affected =
                    originalParams.get(AbilityKey.Affected)
                            instanceof GameEntity
                            ? (GameEntity) originalParams.get(
                            AbilityKey.Affected)
                            : null;

            boolean includeFutureAffected = false;

            if (movedToBattlefield && affected != null) {
                // Definitions such as Self may already return the host.
                while (recipients.remove(affected)) {
                    includeFutureAffected = true;
                }

                // "Valid Creature.YouCtrl" normally searches only objects already
                // on the battlefield. Explicitly test the entering future-state card.
                if (!includeFutureAffected && affected instanceof Card) {
                    includeFutureAffected =
                            futureAffectedMatchesRedirect(
                                    card, sa, (Card) affected);
                }
            }

            final GameEntityCounterTable redirected =
                    new GameEntityCounterTable();

            boolean redirectedAny = false;

            for (Map.Entry<Optional<Player>, Multiset<CounterType>> e
                    : counterTable.entrySet()) {

                final Player source = e.getKey().orElse(null);

                for (Multiset.Entry<CounterType> ec
                        : Lists.newArrayList(
                        e.getValue().entrySet())) {

                    if (!matchesRedirectCounter(
                            sa, source, ec.getElement())) {
                        continue;
                    }

                    final int count = ec.getCount();

                    if (count <= 0) {
                        continue;
                    }

                    for (GameEntity recipient : recipients) {
                        redirected.put(
                                source,
                                recipient,
                                ec.getElement(),
                                count);
                    }

                    /*
                     * During Moved/ETB the entering permanent is not yet on the
                     * battlefield. If it is one of RedirectTo's recipients, keep
                     * its share in the original ETB CounterMap. This allows the
                     * remaining Moved replacement chain to process those counters
                     * normally before they are physically placed.
                     *
                     * For ordinary AddCounter events the original event must be
                     * removed even when the affected object is also a recipient;
                     * a fresh AddCounter event is created for it through redirected.
                     */
                    if (!(movedToBattlefield && includeFutureAffected)) {
                        e.getValue().remove(ec.getElement(), count);
                    }

                    redirectedAny = true;
                }
            }

            if (!redirectedAny) {
                originalParams.put(
                        AbilityKey.ReplacementResult,
                        ReplacementResult.NotReplaced);
                return;
            }

            final SpellAbility cause =
                    originalParams.get(AbilityKey.Cause)
                            instanceof SpellAbility
                            ? (SpellAbility) originalParams.get(
                            AbilityKey.Cause)
                            : null;

            final boolean effectOnly =
                    Boolean.TRUE.equals(
                            originalParams.get(AbilityKey.EffectOnly));

            final Map<AbilityKey, Object> redirectParams =
                    AbilityKey.newMap();

            copyCounterContext(originalParams, redirectParams);

            if (deferred) {
                final GameEntityCounterTable pendingTable =
                        (GameEntityCounterTable) pendingObject;

                pendingTable.markRedirectedCounters();

                if (!redirected.isEmpty()) {
                    redirected.replaceCounterEffectDeferred(
                            card.getGame(),
                            cause,
                            effectOnly,
                            pendingTable,
                            redirectParams);
                }

                /*
                 * Moved must NEVER be returned as Replaced merely because its
                 * CounterMap became empty. That would replace the zone change itself
                 * and prevent the permanent from entering the battlefield.
                 */
                if (movedToBattlefield) {
                    originalParams.put(
                            AbilityKey.ReplacementResult,
                            ReplacementResult.Updated);
                } else {
                    originalParams.put(
                            AbilityKey.ReplacementResult,
                            hasRemainingCounters(counterTable)
                                    ? ReplacementResult.Updated
                                    : ReplacementResult.Replaced);
                }

                return;
            }

            resultTable.markRedirectedCounters();

            redirectParams.put(
                    AbilityKey.CounterResultTable,
                    resultTable);

            redirected.replaceCounterEffect(
                    card.getGame(),
                    cause,
                    effectOnly,
                    false,
                    redirectParams);

            originalParams.put(
                    AbilityKey.ReplacementResult,
                    hasRemainingCounters(counterTable)
                            ? ReplacementResult.Updated
                            : ReplacementResult.Replaced);

            return;
        }

        if (counterTable.size() > 1 && sa.hasParam("ChooseCounter")) {
            // ChooseCounter is for ones that only adds one counter, when that is coming from multiple sources, the affected player needs to choose

            GameEntity ge = (GameEntity) sa.getReplacingObject(AbilityKey.Object);
            Player chooser = ge instanceof Player ? (Player) ge : ((Card) ge).getController();

            // for some effects, the Player -> CounterType Table needs to be flip into a CounterType -> [Player] list for the player to select
            Multimap<CounterType, Player> playerMap = HashMultimap.create();
            for (Map.Entry<Optional<Player>, Multiset<CounterType>> e : counterTable.entrySet()) {
                for (CounterType ct : e.getValue().elementSet()) {
                    playerMap.put(ct, e.getKey().orElse(null));
                }
            }

            // there shouldn't be a case where one of the players is null, and the other is not

            for (Map.Entry<CounterType, Collection<Player>> e : playerMap.asMap().entrySet()) {
                Optional<Player> p = Optional.ofNullable(chooser.getController().chooseSingleEntityForEffect(new PlayerCollection(e.getValue()), sa, "Choose Player for " + e.getKey().getName(), null));

                sa.setReplacingObject(AbilityKey.CounterNum, counterTable.get(p).count(e.getKey()));
                counterTable.get(p).setCount(e.getKey(), AbilityUtils.calculateAmount(card, sa.getParam("Amount"), sa));
            }
        } else {
            for (Map.Entry<Optional<Player>, Multiset<CounterType>> e : counterTable.entrySet()) {
                if (!sa.matchesValidParam("ValidSource", e.getKey().orElse(null))) {
                    continue;
                }

                if (sa.hasParam("ValidCounterType")) {
                    CounterType ct = CounterType.getType(sa.getParam("ValidCounterType"));
                    if (e.getValue().contains(ct)) {
                        sa.setReplacingObject(AbilityKey.CounterNum, e.getValue().count(ct));
                        e.getValue().setCount(ct, AbilityUtils.calculateAmount(card, sa.getParam("Amount"), sa));
                    }
                } else {
                    for (Multiset.Entry<CounterType> ec : Lists.newArrayList(e.getValue().entrySet())) {
                        sa.setReplacingObject(AbilityKey.CounterNum, ec.getCount());
                        e.getValue().setCount(ec.getElement(), AbilityUtils.calculateAmount(card, sa.getParam("Amount"), sa));
                    }
                }
            }
        }

        originalParams.put(AbilityKey.ReplacementResult, ReplacementResult.Updated);
    }
}
