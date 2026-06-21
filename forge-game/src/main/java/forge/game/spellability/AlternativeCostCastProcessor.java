package forge.game.spellability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

public final class AlternativeCostCastProcessor {

    private AlternativeCostCastProcessor() {
    }

    public static SpellAbility process(final SpellAbility sa) {
        if (!AlternativeCostRuleUtil.isAltSpell(sa)) {
            return sa;
        }
        if (sa.hasParam(AlternativeCostRuleUtil.MARK_POST_PROCESSED)) {
            return sa;
        }
        if (sa.getHostCard() == null || sa.getPayCosts() == null || sa.getActivatingPlayer() == null) {
            return sa;
        }

        Cost current = sa.getPayCosts().copy();
        if (current == null) {
            return sa;
        }

        // Step 1: full replace / replace mana
        current = applyStep1Self(sa, current);
        current = applyStep1External(sa, current);

        // Step 2: append
        current = applyStep2Self(sa, current);
        current = applyStep2External(sa, current);

        // Step 3: reduce / raise / set
        current = applyStep3Self(sa, current);
        current = applyStep3External(sa, current);

        if (current == null) {
            return sa;
        }

        sa.setPayCosts(current);
        sa.putParam(AlternativeCostRuleUtil.MARK_POST_PROCESSED, "True");
        return sa;
    }

    private static Cost applyStep1Self(final SpellAbility sa, Cost current) {
        if (sa == null || current == null) {
            return current;
        }
        if (!AlternativeCostRuleUtil.matchesAltCost(sa, sa.getParam("SelfAltCost"))) {
            return current;
        }

        if (sa.hasParam("SelfAltCostReplace")) {
            current = AlternativeCostRuleUtil.replaceCost(sa, sa.getHostCard(), sa.getParam("SelfAltCostReplace"));
        }
        if (current != null && sa.hasParam("SelfAltCostReplaceMana")) {
            current = AlternativeCostRuleUtil.replaceManaPart(current, sa.getParam("SelfAltCostReplaceMana"));
        }

        return current;
    }

    private static Cost applyStep1External(final SpellAbility sa, Cost current) {
        if (sa == null || current == null || sa.getHostCard() == null) {
            return current;
        }

        boolean fullReplaceApplied = false;
        boolean manaReplaceApplied = false;

        final Player activator = sa.getActivatingPlayer();

        for (final Card ruleSource : getRuleSources(sa.getHostCard())) {
            for (final StaticAbility st : ruleSource.getStaticAbilities()) {
                if (!isApplicableExternalPostRule(st, sa, activator)) {
                    continue;
                }

                if (!fullReplaceApplied && st.hasParam("AltCostReplace")) {
                    current = AlternativeCostRuleUtil.replaceCost(sa, ruleSource, st.getParam("AltCostReplace"));
                    fullReplaceApplied = true;
                }

                if (current != null && !manaReplaceApplied && st.hasParam("AltCostReplaceMana")) {
                    current = AlternativeCostRuleUtil.replaceManaPart(current, st.getParam("AltCostReplaceMana"));
                    manaReplaceApplied = true;
                }
            }
        }

        return current;
    }

    private static Cost applyStep2Self(final SpellAbility sa, Cost current) {
        if (sa == null || current == null) {
            return current;
        }
        if (!AlternativeCostRuleUtil.matchesAltCost(sa, sa.getParam("SelfAltCost"))) {
            return current;
        }

        if (sa.hasParam("SelfAltCostAppend")) {
            current = AlternativeCostRuleUtil.appendCost(current, sa, sa.getHostCard(), sa.getParam("SelfAltCostAppend"));
        }
        return current;
    }

    private static Cost applyStep2External(final SpellAbility sa, Cost current) {
        if (sa == null || current == null || sa.getHostCard() == null) {
            return current;
        }

        final Player activator = sa.getActivatingPlayer();

        for (final Card ruleSource : getRuleSources(sa.getHostCard())) {
            for (final StaticAbility st : ruleSource.getStaticAbilities()) {
                if (!isApplicableExternalPostRule(st, sa, activator)) {
                    continue;
                }

                if (st.hasParam("AltCostAppend")) {
                    current = AlternativeCostRuleUtil.appendCost(current, sa, ruleSource, st.getParam("AltCostAppend"));
                }
            }
        }

        return current;
    }

    private static Cost applyStep3Self(final SpellAbility sa, Cost current) {
        if (sa == null || current == null) {
            return current;
        }
        if (!AlternativeCostRuleUtil.matchesAltCost(sa, sa.getParam("SelfAltCost"))) {
            return current;
        }

        if (sa.hasParam("SelfAltCostReduce")) {
            current = AlternativeCostRuleUtil.reduceManaPart(current, sa.getParam("SelfAltCostReduce"));
        }
        if (current != null && sa.hasParam("SelfAltCostRaise")) {
            current = AlternativeCostRuleUtil.raiseManaPart(current, sa.getParam("SelfAltCostRaise"));
        }
        if (current != null && sa.hasParam("SelfAltCostSet")) {
            current = AlternativeCostRuleUtil.setManaPart(current, sa.getParam("SelfAltCostSet"));
        }

        return current;
    }

    private static Cost applyStep3External(final SpellAbility sa, Cost current) {
        if (sa == null || current == null || sa.getHostCard() == null) {
            return current;
        }

        boolean setApplied = false;
        final Player activator = sa.getActivatingPlayer();

        for (final Card ruleSource : getRuleSources(sa.getHostCard())) {
            for (final StaticAbility st : ruleSource.getStaticAbilities()) {
                if (!isApplicableExternalPostRule(st, sa, activator)) {
                    continue;
                }

                if (st.hasParam("AltCostReduce")) {
                    current = AlternativeCostRuleUtil.reduceManaPart(current, st.getParam("AltCostReduce"));
                }
                if (current != null && st.hasParam("AltCostRaise")) {
                    current = AlternativeCostRuleUtil.raiseManaPart(current, st.getParam("AltCostRaise"));
                }
                if (current != null && !setApplied && st.hasParam("AltCostSet")) {
                    current = AlternativeCostRuleUtil.setManaPart(current, st.getParam("AltCostSet"));
                    setApplied = true;
                }
            }
        }

        return current;
    }

    private static boolean isApplicableExternalPostRule(final StaticAbility st,
                                                        final SpellAbility sa,
                                                        final Player activator) {
        if (st == null || sa == null || sa.getHostCard() == null || activator == null) {
            return false;
        }
        if (!st.checkConditions()) {
            return false;
        }
        if (!st.hasParam("AltCost") || !AlternativeCostRuleUtil.matchesAltCost(sa, st.getParam("AltCost"))) {
            return false;
        }

        return matchesExternalRuleParams(st, sa, activator)
                && hasAnyPostAction(st);
    }

    private static boolean matchesExternalRuleParams(final StaticAbility st,
                                                     final SpellAbility sa,
                                                     final Player activator) {
        final Player oldActivatingPlayer = sa.getActivatingPlayer();

        if (oldActivatingPlayer != null && !oldActivatingPlayer.equals(activator)) {
            return false;
        }

        final boolean temporarilySetActivatingPlayer = oldActivatingPlayer == null;

        if (temporarilySetActivatingPlayer) {
            sa.setActivatingPlayer(activator);
        }

        try {
            if (!st.matchesValidParam("ValidSA", sa)) {
                return false;
            }
            if (!st.matchesValidParam("ValidCard", sa.getHostCard())) {
                return false;
            }
            if (!st.matchesValidParam("ValidPlayer", activator)) {
                return false;
            }
            if (st.hasParam("RequiredSpellParam") && !sa.hasParam(st.getParam("RequiredSpellParam"))) {
                return false;
            }

            return true;
        } finally {
            if (temporarilySetActivatingPlayer) {
                sa.setActivatingPlayer(null);
            }
        }
    }

    private static boolean hasAnyPostAction(final StaticAbility st) {
        return st != null && (
                st.hasParam("AltCostReplace")
                        || st.hasParam("AltCostAppend")
                        || st.hasParam("AltCostReplaceMana")
                        || st.hasParam("AltCostReduce")
                        || st.hasParam("AltCostRaise")
                        || st.hasParam("AltCostSet")
        );
    }

    private static List<Card> getRuleSources(final Card host) {
        final List<Card> sources = new ArrayList<>();
        addRuleSource(sources, host);

        if (host == null) {
            return sources;
        }

        final Game game = host.getGame();
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