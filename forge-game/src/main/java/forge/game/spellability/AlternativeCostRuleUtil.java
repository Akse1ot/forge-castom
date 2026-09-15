package forge.game.spellability;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.cost.Cost;

public final class AlternativeCostRuleUtil {

    static final String MARK_DERIVED_VARIANT = "DerivedAltCostVariant";
    static final String MARK_POST_PROCESSED = "AltCostPostProcessed";
    public static final String PARAM_ADDITIONAL_REDUCE = "AltCostAdditionalReduce";
    public static final String PARAM_ADDITIONAL_RAISE = "AltCostAdditionalRaise";

    private AlternativeCostRuleUtil() {
    }

    static boolean isAltSpell(final SpellAbility sa) {
        return sa != null && sa.isSpell() && sa.getAlternativeCost() != null;
    }

    static boolean matchesAltCost(final SpellAbility sa, final String altCostName) {
        if (!isAltSpell(sa) || altCostName == null) {
            return false;
        }
        try {
            final AlternativeCost required = AlternativeCost.valueOf(altCostName);
            return sa.isAlternativeCost(required);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static Cost buildCost(final String costExpr, final SpellAbility sa, final Card ruleHost) {
        final boolean isAbility = sa != null && sa.isAbility();
        final Card host = sa == null ? null : sa.getHostCard();
        final boolean isFromSource = host != null && host.equals(ruleHost);

        return new Cost(costExpr, isAbility, isFromSource);
    }

    static Cost replaceCost(final SpellAbility sa, final Card ruleHost, final String costExpr) {
        return buildCost(costExpr, sa, ruleHost);
    }

    static Cost appendCost(final Cost base, final SpellAbility sa, final Card ruleHost, final String costExpr) {
        if (base == null) {
            return buildCost(costExpr, sa, ruleHost);
        }

        final Cost result = base.copy();
        result.add(buildCost(costExpr, sa, ruleHost));
        return result;
    }

    static Cost replaceManaPart(final Cost base, final ManaCost newMana) {
        if (base == null) {
            return null;
        }

        return base.copyWithDefinedMana(newMana);
    }

    static Cost replaceManaPart(final Cost base, final String manaExpr) {
        return replaceManaPart(base, new ManaCost(manaExpr));
    }

    /**
     * v1 implementation choice:
     * AltCostSet / SelfAltCostSet are implemented as "set mana-part exactly to this mana cost".
     */
    static Cost setManaPart(final Cost base, final String manaExpr) {
        return replaceManaPart(base, manaExpr);
    }

    static void addManaReduction(final SpellAbility sa, final String manaExpr) {
        if (sa == null || manaExpr == null || manaExpr.trim().isEmpty()) {
            return;
        }

        if (new ManaCost(manaExpr).countX() > 0) {
            throw new IllegalArgumentException("Alt-cost mana reduction does not support X: " + manaExpr);
        }

        final String existing = sa.getParamOrDefault(PARAM_ADDITIONAL_REDUCE, "");
        sa.putParam(
                PARAM_ADDITIONAL_REDUCE,
                joinCostExpressions(existing, manaExpr)
        );
    }

    static void addManaRaise(final SpellAbility sa, final String manaExpr) {
        if (sa == null || manaExpr == null || manaExpr.trim().isEmpty()) {
            return;
        }

        final String existing = sa.getParamOrDefault(PARAM_ADDITIONAL_RAISE, "");
        sa.putParam(
                PARAM_ADDITIONAL_RAISE,
                joinCostExpressions(existing, manaExpr)
        );
    }

    private static String joinCostExpressions(final String left, final String right) {
        final String l = left == null ? "" : left.trim();
        final String r = right == null ? "" : right.trim();

        if (l.isEmpty()) {
            return r;
        }
        if (r.isEmpty()) {
            return l;
        }

        if (isNonNegativeInteger(l) && isNonNegativeInteger(r)) {
            return Integer.toString(Integer.parseInt(l) + Integer.parseInt(r));
        }

        return l + " " + r;
    }

    private static boolean isNonNegativeInteger(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    static void appendVariantDescription(final SpellAbility derived, final String variantDescription) {
        if (derived == null || variantDescription == null || variantDescription.isEmpty()) {
            return;
        }

        final String description = derived.getDescription() == null ? "" : derived.getDescription();
        derived.setDescription(description + " (" + variantDescription + ")");
    }
}