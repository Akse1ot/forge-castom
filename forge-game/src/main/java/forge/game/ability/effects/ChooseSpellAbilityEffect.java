package forge.game.ability.effects;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

public class ChooseSpellAbilityEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        if (sa.hasParam("SpellDescription")) {
            return sa.getParam("SpellDescription");
        }
        if (sa.hasParam("ChoiceTitle")) {
            return sa.getParam("ChoiceTitle");
        }
        return "Choose a spell ability.";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Card host = sa.getHostCard();

        final List<Player> choosers = AbilityUtils.getDefinedPlayers(
                host,
                sa.getParamOrDefault("Chooser", "You"),
                sa
        );
        if (choosers.isEmpty()) {
            return;
        }

        final List<Card> sourceCards = Lists.newArrayList();
        if (sa.hasParam("DefinedSource")) {
            sourceCards.addAll(AbilityUtils.getDefinedCards(host, sa.getParam("DefinedSource"), sa));
        } else {
            sourceCards.addAll(getTargetCards(sa));
        }
        if (sourceCards.isEmpty()) {
            return;
        }

        final String[] valid = sa.hasParam("ValidSA") ? sa.getParam("ValidSA").split(",") : null;
        final String title = sa.getParamOrDefault("ChoiceTitle", "Choose a spell ability");
        final int amount = sa.hasParam("Amount")
                ? AbilityUtils.calculateAmount(host, sa.getParam("Amount"), sa)
                : 1;

        final List<SpellAbility> rememberedChoices = Lists.newArrayList();

        for (final Player chooser : choosers) {
            final List<SpellAbility> choices = Lists.newArrayList();

            for (final Card sourceCard : sourceCards) {
                for (final SpellAbility candidate : sourceCard.getCurrentState().getSpellAbilities()) {
                    if (candidate == null) {
                        continue;
                    }
                    if (valid != null && !candidate.isValid(valid, chooser, host, sa)) {
                        continue;
                    }
                    choices.add(candidate);
                }
            }

            if (choices.isEmpty()) {
                continue;
            }

            if (amount <= 1) {
                final SpellAbility chosen = chooser.getController().chooseSingleSpellForEffect(
                        choices,
                        sa,
                        title,
                        ImmutableMap.of()
                );
                if (chosen != null) {
                    rememberedChoices.add(chosen);
                }
            } else {
                final List<SpellAbility> chosen = chooser.getController().chooseSpellAbilitiesForEffect(
                        choices,
                        sa,
                        title,
                        amount,
                        ImmutableMap.of()
                );
                if (chosen != null) {
                    for (final SpellAbility spellAbility : chosen) {
                        if (spellAbility != null) {
                            rememberedChoices.add(spellAbility);
                        }
                    }
                }
            }
        }

        if (!rememberedChoices.isEmpty()) {
            sa.setChosenSpellAbilities(rememberedChoices);
        } else {
            sa.clearChosenSpellAbilities();
        }
    }
}