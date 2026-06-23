package forge.game.spellability;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;

final class AlternativeCostRuleUtil {

    static final String MARK_DERIVED_VARIANT = "DerivedAltCostVariant";
    static final String MARK_POST_PROCESSED = "AltCostPostProcessed";

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

        final Cost result = base.copyWithNoMana();
        removeManaParts(result);
        result.setMandatory(base.isMandatory());

        final CostPartMana oldMana = base.getCostMana();
        final CostPartMana replacement;

        if (oldMana != null && (oldMana.isExiledCreatureCost() || oldMana.isEnchantedCreatureCost() || oldMana.getXMin() > 0)) {
            replacement = new CostPartMana(
                    newMana,
                    oldMana.isExiledCreatureCost(),
                    oldMana.isEnchantedCreatureCost(),
                    oldMana.getXMin()
            );
        } else {
            replacement = new CostPartMana(newMana, null);
        }

        if (oldMana != null) {
            replacement.setMaxWaterbend(oldMana.getMaxWaterbend());
        }

        result.getCostParts().add(replacement);
        result.sort();
        return result;
    }

    private static void removeManaParts(final Cost cost) {
        if (cost == null) {
            return;
        }

        cost.getCostParts().removeIf(part -> part instanceof CostPartMana);
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

        // Keep the same v1 limitation as before: reducing X is not supported.
        if (new ManaCost(manaExpr).countX() > 0) {
            throw new IllegalArgumentException("Alt-cost mana reduction does not support X: " + manaExpr);
        }

        if (!sa.hasParam("ReduceCost")) {
            sa.putParam("ReduceCost", manaExpr);
            sa.putParam("ReduceAmount", "1");
            return;
        }

        final String existingCost = sa.getParam("ReduceCost");
        final String existingReduction = expandExistingReduction(sa, existingCost);
        sa.putParam("ReduceCost", joinCostExpressions(existingReduction, manaExpr));
        sa.putParam("ReduceAmount", "1");
    }

    static void addManaRaise(final SpellAbility sa, final String manaExpr) {
        if (sa == null || manaExpr == null || manaExpr.trim().isEmpty()) {
            return;
        }

        if (!sa.hasParam("RaiseCost")) {
            sa.putParam("RaiseCost", manaExpr);
            return;
        }

        final String existing = sa.getParam("RaiseCost");
        if (existing != null && sa.hasSVar(existing)) {
            throw new IllegalStateException("Cannot combine alt-cost mana raise with dynamic RaiseCost: " + existing);
        }

        sa.putParam("RaiseCost", joinCostExpressions(existing, manaExpr));
    }

    private static String expandExistingReduction(final SpellAbility sa, final String existingCost) {
        if (existingCost == null || existingCost.trim().isEmpty()) {
            return "";
        }

        if (!sa.hasParam("ReduceAmount")) {
            if (isNonNegativeInteger(existingCost)) {
                return existingCost;
            }
            throw new IllegalStateException("Cannot combine alt-cost mana reduction with dynamic ReduceCost: " + existingCost);
        }

        final String existingAmount = sa.getParam("ReduceAmount");
        if (!isNonNegativeInteger(existingAmount)) {
            throw new IllegalStateException("Cannot combine alt-cost mana reduction with dynamic ReduceAmount: " + existingAmount);
        }

        final int count = Integer.parseInt(existingAmount);
        if (count <= 0) {
            return "";
        }

        return repeatCostExpression(existingCost, count);
    }

    private static String repeatCostExpression(final String costExpr, final int count) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(costExpr.trim());
        }
        return sb.toString();
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