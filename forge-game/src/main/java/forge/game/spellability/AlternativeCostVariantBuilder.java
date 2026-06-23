package forge.game.spellability;

import com.google.common.collect.Lists;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

import java.util.List;

public final class AlternativeCostVariantBuilder {

    private AlternativeCostVariantBuilder() {
    }

    public static List<SpellAbility> expandAlternativeCosts(final List<SpellAbility> candidates,
                                                            final Card source,
                                                            final Player activator) {
        if (candidates == null || candidates.isEmpty() || source == null || activator == null) {
            return candidates;
        }

        final List<SpellAbility> result = Lists.newArrayList(candidates);
        final List<SpellAbility> snapshot = Lists.newArrayList(candidates);

        for (final SpellAbility candidate : snapshot) {
            if (!AlternativeCostRuleUtil.isAltSpell(candidate)) {
                continue;
            }
            if (candidate.hasParam(AlternativeCostRuleUtil.MARK_DERIVED_VARIANT)) {
                continue;
            }

            addSelfVariant(candidate, result, activator, source);
            addExternalVariants(candidate, result, activator, source);
        }

        return result;
    }

    private static void addSelfVariant(final SpellAbility candidate,
                                       final List<SpellAbility> result,
                                       final Player activator,
                                       final Card source) {
        if (candidate == null || result == null || activator == null || source == null) {
            return;
        }

        if (!isApplicableSelfVariantRule(candidate)) {
            return;
        }

        final Card ruleHost = getCandidateHost(candidate, source);
        if (ruleHost == null) {
            return;
        }

        final SpellAbility derived = buildDerivedVariant(candidate, activator, ruleHost, candidate);
        if (derived != null) {
            result.add(derived);
        }
    }

    private static void addExternalVariants(final SpellAbility candidate,
                                            final List<SpellAbility> result,
                                            final Player activator,
                                            final Card source) {
        if (candidate == null || result == null || activator == null || source == null) {
            return;
        }

        final Card candidateHost = getCandidateHost(candidate, source);
        if (candidateHost == null) {
            return;
        }

        for (final Card ruleSource : getRuleSources(candidateHost)) {
            for (final StaticAbility st : ruleSource.getStaticAbilities()) {
                if (!isApplicableExternalVariantRule(st, candidate, candidateHost, activator)) {
                    continue;
                }

                final SpellAbility derived = buildDerivedVariant(candidate, activator, ruleSource, st);
                if (derived != null) {
                    result.add(derived);
                }
            }
        }
    }

    private static boolean isApplicableSelfVariantRule(final SpellAbility candidate) {
        if (candidate == null) {
            return false;
        }
        if (!"True".equalsIgnoreCase(candidate.getParam("SelfAltCostVariant"))) {
            return false;
        }
        if (!AlternativeCostRuleUtil.matchesAltCost(candidate, candidate.getParam("SelfAltCost"))) {
            return false;
        }
        if (!candidate.hasParam("VariantDescription")) {
            return false;
        }

        return hasAnyVariantAction(candidate);
    }

    private static boolean isApplicableExternalVariantRule(final StaticAbility st,
                                                           final SpellAbility candidate,
                                                           final Card candidateHost,
                                                           final Player activator) {
        if (st == null || candidate == null || candidateHost == null || activator == null) {
            return false;
        }
        if (!st.checkConditions()) {
            return false;
        }
        if (!"True".equalsIgnoreCase(st.getParam("AltCostVariant"))) {
            return false;
        }
        if (!st.hasParam("AltCost") || !AlternativeCostRuleUtil.matchesAltCost(candidate, st.getParam("AltCost"))) {
            return false;
        }
        if (!st.hasParam("VariantDescription")) {
            return false;
        }

        return matchesExternalRuleParams(st, candidate, candidateHost, activator)
                && hasAnyVariantAction(st);
    }

    private static boolean matchesExternalRuleParams(final StaticAbility st,
                                                     final SpellAbility candidate,
                                                     final Card candidateHost,
                                                     final Player activator) {
        final Player oldActivatingPlayer = candidate.getActivatingPlayer();

        if (oldActivatingPlayer != null && !oldActivatingPlayer.equals(activator)) {
            return false;
        }

        final boolean temporarilySetActivatingPlayer = oldActivatingPlayer == null;

        if (temporarilySetActivatingPlayer) {
            candidate.setActivatingPlayer(activator);
        }

        try {
            if (!st.matchesValidParam("ValidSA", candidate)) {
                return false;
            }
            if (!st.matchesValidParam("ValidCard", candidateHost)) {
                return false;
            }
            if (!st.matchesValidParam("ValidPlayer", activator)) {
                return false;
            }
            if (st.hasParam("RequiredSpellParam") && !candidate.hasParam(st.getParam("RequiredSpellParam"))) {
                return false;
            }

            return true;
        } finally {
            if (temporarilySetActivatingPlayer) {
                candidate.setActivatingPlayer(null);
            }
        }
    }

    private static boolean hasAnyVariantAction(final CardTraitBase rule) {
        return rule != null && (
                rule.hasParam("VariantReplaceCost")
                        || rule.hasParam("VariantAppendCost")
                        || rule.hasParam("VariantReplaceMana")
                        || rule.hasParam("VariantReduceMana")
                        || rule.hasParam("VariantRaiseMana")
        );
    }

    private static SpellAbility buildDerivedVariant(final SpellAbility candidate,
                                                    final Player activator,
                                                    final Card ruleHost,
                                                    final CardTraitBase rule) {
        if (candidate == null || activator == null || ruleHost == null || rule == null) {
            return null;
        }

        final SpellAbility derived = candidate.copy(activator);
        if (derived == null || derived.getPayCosts() == null) {
            return null;
        }

        derived.setActivatingPlayer(activator);
        derived.putParam(AlternativeCostRuleUtil.MARK_DERIVED_VARIANT, "True");
        derived.removeParam(AlternativeCostRuleUtil.MARK_POST_PROCESSED);

        final Cost newCost = applyVariantActions(derived.getPayCosts().copy(), derived, ruleHost, rule);
        derived.setPayCosts(newCost);
        AlternativeCostRuleUtil.appendVariantDescription(derived, rule.getParam("VariantDescription"));

        return derived;
    }

    private static Cost applyVariantActions(Cost current,
                                            final SpellAbility derived,
                                            final Card ruleHost,
                                            final CardTraitBase rule) {
        if (current == null || derived == null || ruleHost == null || rule == null) {
            return current;
        }

        if (rule.hasParam("VariantReplaceCost")) {
            current = AlternativeCostRuleUtil.replaceCost(derived, ruleHost, rule.getParam("VariantReplaceCost"));
        }
        if (rule.hasParam("VariantReplaceMana")) {
            current = AlternativeCostRuleUtil.replaceManaPart(current, rule.getParam("VariantReplaceMana"));
        }
        if (rule.hasParam("VariantAppendCost")) {
            current = AlternativeCostRuleUtil.appendCost(current, derived, ruleHost, rule.getParam("VariantAppendCost"));
        }
        if (rule.hasParam("VariantReduceMana")) {
            AlternativeCostRuleUtil.addManaReduction(derived, rule.getParam("VariantReduceMana"));
        }
        if (rule.hasParam("VariantRaiseMana")) {
            AlternativeCostRuleUtil.addManaRaise(derived, rule.getParam("VariantRaiseMana"));
        }

        return current;
    }

    private static Card getCandidateHost(final SpellAbility candidate, final Card source) {
        if (candidate != null && candidate.getHostCard() != null) {
            return candidate.getHostCard();
        }
        return source;
    }

    private static List<Card> getRuleSources(final Card candidateHost) {
        final List<Card> sources = Lists.newArrayList();
        addRuleSource(sources, candidateHost);

        final Game game = candidateHost.getGame();
        if (game == null) {
            return sources;
        }

        for (final Card card : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            addRuleSource(sources, card);
        }

        return sources;
    }

    private static void addRuleSource(final List<Card> sources, final Card card) {
        if (card != null && !sources.contains(card)) {
            sources.add(card);
        }
    }
}