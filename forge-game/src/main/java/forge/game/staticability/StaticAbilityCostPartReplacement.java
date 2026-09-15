package forge.game.staticability;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

public final class StaticAbilityCostPartReplacement {
    private StaticAbilityCostPartReplacement() {
    }

    public static List<StaticAbility> getReplacements(final String costPart,
                                                      final SpellAbility sa,
                                                      final Player activator) {
        final List<StaticAbility> result = new ArrayList<>();

        if (costPart == null
                || sa == null
                || sa.getHostCard() == null
                || activator == null
                || activator.getGame() == null) {
            return result;
        }

        final Card affectedCard = sa.getHostCard();
        final List<Card> sources = new ArrayList<>();

        addSource(sources, affectedCard);

        for (final Card card : activator.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            addSource(sources, card);
        }

        for (final Card source : sources) {
            for (final StaticAbility st : source.getStaticAbilities()) {
                if (!isApplicable(st, costPart, sa, affectedCard, activator)) {
                    continue;
                }

                result.add(st);
            }
        }

        return result;
    }

    public static boolean hasReplacement(final String costPart,
                                         final SpellAbility sa,
                                         final Player activator) {
        return !getReplacements(costPart, sa, activator).isEmpty();
    }

    private static boolean isApplicable(final StaticAbility st,
                                        final String costPart,
                                        final SpellAbility sa,
                                        final Card affectedCard,
                                        final Player activator) {
        if (st == null || !st.checkConditions(StaticAbilityMode.ReplaceCostPart)) {
            return false;
        }

        if (!st.hasParam("CostPart") || !st.hasParam("Cost")) {
            return false;
        }

        if (!costPart.equalsIgnoreCase(st.getParam("CostPart"))) {
            return false;
        }

        if (!st.matchesValidParam("ValidCard", affectedCard)) {
            return false;
        }
        if (!st.matchesValidParam("ValidSA", sa)) {
            return false;
        }
        if (!st.matchesValidParam("ValidSpell", sa)) {
            return false;
        }
        if (!st.matchesValidParam("ValidPlayer", activator)) {
            return false;
        }
        if (!st.matchesValidParam("Activator", activator)) {
            return false;
        }

        if (st.hasParam("RequiredSpellParam")
                && !sa.hasParam(st.getParam("RequiredSpellParam"))) {
            return false;
        }

        if (st.hasParam("AltCost")) {
            final AlternativeCost required =
                    parseAlternativeCost(st.getParam("AltCost"));

            if (required == null || !sa.isAlternativeCost(required)) {
                return false;
            }
        }

        return true;
    }

    private static AlternativeCost parseAlternativeCost(final String value) {
        if (value == null) {
            return null;
        }

        try {
            return AlternativeCost.valueOf(value);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static void addSource(final List<Card> result, final Card card) {
        if (card != null && !result.contains(card)) {
            result.add(card);
        }
    }
}