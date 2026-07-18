package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardZoneTable;
import forge.game.event.GameEventCombatChanged;
import forge.game.event.GameEventTokenCreated;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates an Equipment token whose granted properties are defined by the
 * resolving Forge ability.
 */
public class ForgeEffect extends TokenEffectBase {

    @Override
    public void resolve(final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Game game = source.getGame();

        for (final Player player : getTargetPlayers(sa)) {
            if (!player.isInGame()) {
                continue;
            }

            final List<SpellAbility> selectedChoices =
                    chooseForgeAbilities(player, sa);

            final Card prototype = ForgeTokenUtil.createPrototype(
                    sa,
                    player,
                    selectedChoices
            );

            /*
             * Keep each Forge action in its own table. A parent ability may
             * also create or move cards, but those objects must not become
             * part of this action's TriggeredCards collection.
             */
            final CardZoneTable triggerList = new CardZoneTable();
            final MutableBoolean combatChanged = new MutableBoolean(false);

            makeTokenTable(
                    makeTokenTableInternal(player, prototype, 1),
                    false,
                    triggerList,
                    combatChanged,
                    sa
            );

            final CardCollection createdTokens =
                    collectCreatedTokens(triggerList);

            final Map<AbilityKey, Object> runParams =
                    AbilityKey.mapFromPlayer(player);

            runParams.put(AbilityKey.Cards, createdTokens);
            runParams.put(AbilityKey.Cause, sa);

            /*
             * Forge is one keyword action even when a replacement effect
             * causes more than one Equipment token to be created.
             */
            game.getTriggerHandler().runTrigger(
                    TriggerType.Forged,
                    runParams,
                    false
            );

            game.fireEvent(new GameEventTokenCreated());

            triggerList.triggerChangesZoneAll(game, sa);

            if (combatChanged.isTrue()) {
                game.updateCombatForView();
                game.fireEvent(new GameEventCombatChanged());
            }
        }
    }

    private static List<SpellAbility> chooseForgeAbilities(
            final Player player,
            final SpellAbility sa) {
        final List<AbilitySub> definedChoices =
                sa.getAdditionalAbilityList("Choices");

        if (definedChoices.isEmpty()) {
            if (sa.hasParam("ChoiceAmount")) {
                throw scriptError(sa,
                        "ChoiceAmount was provided without Choices");
            }

            return List.of();
        }

        final int choiceAmount = AbilityUtils.calculateAmount(
                sa.getHostCard(),
                sa.getParamOrDefault("ChoiceAmount", "1"),
                sa
        );

        if (choiceAmount < 1 || choiceAmount > definedChoices.size()) {
            throw scriptError(
                    sa,
                    "ChoiceAmount is "
                            + choiceAmount
                            + ", but "
                            + definedChoices.size()
                            + " choices are available"
            );
        }

        final List<SpellAbility> options =
                new ArrayList<>(definedChoices);

        final List<SpellAbility> selected =
                player.getController().chooseSpellAbilitiesForEffect(
                        options,
                        sa,
                        sa.getParamOrDefault("ChoicePrompt", "Choose"),
                        choiceAmount,
                        Map.of()
                );

        validateSelection(sa, options, selected, choiceAmount);

        return new ArrayList<>(selected);
    }

    private static void validateSelection(final SpellAbility sa,
                                          final List<SpellAbility> options,
                                          final List<SpellAbility> selected,
                                          final int choiceAmount) {
        if (selected == null || selected.size() != choiceAmount) {
            throw scriptError(
                    sa,
                    "controller returned "
                            + (selected == null ? 0 : selected.size())
                            + " choices instead of "
                            + choiceAmount
            );
        }

        final Set<SpellAbility> unique = new HashSet<>(selected);

        if (unique.size() != selected.size()) {
            throw scriptError(sa,
                    "the same Forge choice was selected more than once");
        }

        if (!options.containsAll(selected)) {
            throw scriptError(sa,
                    "controller returned a choice that was not offered");
        }
    }

    private static CardCollection collectCreatedTokens(
            final CardZoneTable triggerList) {
        final CardCollection result = new CardCollection();

        /*
         * TokenEffectBase records each successfully created token as a
         * movement from ZoneType.None. The table contains the actual moved
         * objects, unlike CardZoneTable's TokenCreatedOnce collection, which
         * intentionally stores LKI copies.
         */
        for (final CardCollection cards
                : triggerList.row(ZoneType.None).values()) {
            for (final Card card : cards) {
                if (card != null && card.getZone() != null) {
                    result.add(card);
                }
            }
        }

        return result;
    }

    private static IllegalStateException scriptError(
            final SpellAbility sa,
            final String message) {
        return new IllegalStateException(
                "Invalid Forge definition for "
                        + sa.getHostCard().getName()
                        + ": "
                        + message
        );
    }
}