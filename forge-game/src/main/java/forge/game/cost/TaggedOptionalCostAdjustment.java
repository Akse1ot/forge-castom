package forge.game.cost;

import com.google.common.collect.Lists;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public final class TaggedOptionalCostAdjustment {
    private TaggedOptionalCostAdjustment() {
    }

    public static Cost adjust(final Cost cost, final SpellAbility sa) {
        if (cost == null || sa == null || !sa.hasParam("CostTag")) {
            return cost;
        }
        if (sa.getActivatingPlayer() == null || sa.getHostCard() == null) {
            return cost;
        }

        final String costTag = sa.getParam("CostTag");
        if (StringUtils.isBlank(costTag)) {
            return cost;
        }

        final SpellAbility affectedSA = getAffectedSA(sa);
        final Card affectedCard = affectedSA == null ? sa.getHostCard() : affectedSA.getHostCard();

        final Player activator = sa.getActivatingPlayer();
        final Game game = activator.getGame();
        if (game == null) {
            return cost;
        }

        for (final Card source : getSources(game, sa.getHostCard())) {
            for (final StaticAbility st : source.getStaticAbilities()) {
                if (!isApplicable(st, costTag, affectedSA, affectedCard, activator)) {
                    continue;
                }

                final Cost alternative = new Cost(
                        st.getParam("OptionalCostAlternative"),
                        cost.isAbility(),
                        affectedCard != null && affectedCard.equals(st.getHostCard())
                );

                if ("Alternative".equals(st.getParamOrDefault("OptionalCostAIPreference", ""))) {
                    return Cost.or(alternative, cost);
                }

                return Cost.or(cost, alternative);
            }
        }

        return cost;
    }

    private static SpellAbility getAffectedSA(final SpellAbility sa) {
        if (sa.hasParam("Defined")) {
            final CardCollection cards = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
            for (final Card c : cards) {
                if (c != null && c.getCastSA() != null) {
                    return c.getCastSA();
                }
            }
        }

        return sa;
    }

    private static List<Card> getSources(final Game game, final Card host) {
        final List<Card> result = Lists.newArrayList();

        addSource(result, host);

        for (final Card c : game.getCardsIn(ZoneType.Battlefield)) {
            addSource(result, c);
        }
        for (final Card c : game.getCardsIn(ZoneType.Stack)) {
            addSource(result, c);
        }
        for (final Card c : game.getCardsIn(ZoneType.Command)) {
            addSource(result, c);
        }

        return result;
    }

    private static void addSource(final List<Card> result, final Card card) {
        if (card != null && !result.contains(card)) {
            result.add(card);
        }
    }

    private static boolean isApplicable(final StaticAbility st,
                                        final String costTag,
                                        final SpellAbility affectedSA,
                                        final Card affectedCard,
                                        final Player activator) {
        if (st == null || !st.checkConditions()) {
            return false;
        }
        if (!st.hasParam("OptionalCostTag") || !st.hasParam("OptionalCostAlternative")) {
            return false;
        }
        if (!costTag.equals(st.getParam("OptionalCostTag"))) {
            return false;
        }

        if (affectedCard != null && !st.matchesValidParam("ValidCard", affectedCard)) {
            return false;
        }
        if (affectedSA != null && !st.matchesValidParam("ValidSA", affectedSA)) {
            return false;
        }
        if (affectedSA != null && !st.matchesValidParam("ValidSpell", affectedSA)) {
            return false;
        }
        if (!st.matchesValidParam("ValidPlayer", activator)) {
            return false;
        }
        if (!st.matchesValidParam("Activator", activator)) {
            return false;
        }

        return true;
    }
}