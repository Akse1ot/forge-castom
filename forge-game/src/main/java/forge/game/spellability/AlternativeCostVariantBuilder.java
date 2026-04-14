package forge.game.spellability;

import com.google.common.collect.Lists;
import forge.game.Game;
import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.card.CardCollection;
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

            addSelfVariant(candidate, result, activator);
            addExternalVariants(candidate, result, activator);
        }

        return result;
    }

    private static void addSelfVariant(final SpellAbility candidate,
                                       final List<SpellAbility> result,
                                       final Player activator) {
        if (!isApplicableSelfVariantRule(candidate)) {
            return;
        }

        final SpellAbility derived = buildDerivedVariant(candidate, activator, candidate.getHostCard(), candidate);
        if (derived != null) {
            result.add(derived);
        }
    }

    private static void addExternalVariants(final SpellAbility candidate,
                                            final List<SpellAbility> result,
                                            final Player activator) {
        final Game game = candidate.getHostCard().getGame();
        final CardCollection sources = new CardCollection(candidate.getHostCard());
        sources.addAll(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));

        for (final Card ruleSource : sources) {
            for (final StaticAbility st : ruleSource.getStaticAbilities()) {
                if (!isApplicableExternalVariantRule(st, candidate, activator)) {
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
                                                           final Player activator) {
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

        if (!st.matchesValidParam("ValidSA", candidate)) {
            return false;
        }
        if (!st.matchesValidParam("ValidCard", candidate.getHostCard())) {
            return false;
        }
        if (!st.matchesValidParam("ValidPlayer", activator)) {
            return false;
        }
        if (st.hasParam("RequiredSpellParam") && !candidate.hasParam(st.getParam("RequiredSpellParam"))) {
            return false;
        }

        return hasAnyVariantAction(st);
    }

    private static boolean hasAnyVariantAction(final CardTraitBase rule) {
        return rule.hasParam("VariantReplaceCost")
                || rule.hasParam("VariantAppendCost")
                || rule.hasParam("VariantReplaceMana")
                || rule.hasParam("VariantReduceMana")
                || rule.hasParam("VariantRaiseMana");
    }

    private static SpellAbility buildDerivedVariant(final SpellAbility candidate,
                                                    final Player activator,
                                                    final Card ruleHost,
                                                    final CardTraitBase rule) {
        final SpellAbility derived = candidate.copy(activator);
        if (derived == null) {
            return null;
        }

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
            current = AlternativeCostRuleUtil.reduceManaPart(current, rule.getParam("VariantReduceMana"));
        }
        if (rule.hasParam("VariantRaiseMana")) {
            current = AlternativeCostRuleUtil.raiseManaPart(current, rule.getParam("VariantRaiseMana"));
        }
        return current;
    }
}