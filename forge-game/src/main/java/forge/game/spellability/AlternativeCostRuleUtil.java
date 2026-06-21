package forge.game.spellability;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaCostBeingPaid;

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

    static Cost reduceManaPart(final Cost base, final String manaExpr) {
        if (base == null) {
            return null;
        }

        final CostPartMana mana = base.getCostMana();
        if (mana == null) {
            return base.copy();
        }

        final ManaCost reduction = new ManaCost(manaExpr);

        // Do not support reducing X in v1.
        // ManaCostBeingPaid.subtractManaCost can decrement cntX below zero.
        if (reduction.countX() > 0) {
            return base.copy();
        }

        final ManaCostBeingPaid working = new ManaCostBeingPaid(mana.getMana());
        working.subtractManaCost(reduction);
        return replaceManaPart(base, working.toManaCost());
    }

    static Cost raiseManaPart(final Cost base, final String manaExpr) {
        final CostPartMana mana = base.getCostMana();
        final ManaCostBeingPaid working = new ManaCostBeingPaid(mana == null ? ManaCost.ZERO : mana.getMana());
        working.addManaCost(new ManaCost(manaExpr));
        return replaceManaPart(base, working.toManaCost());
    }

    /**
     * v1 implementation choice:
     * AltCostSet / SelfAltCostSet are implemented as "set mana-part exactly to this mana cost".
     */
    static Cost setManaPart(final Cost base, final String manaExpr) {
        return replaceManaPart(base, manaExpr);
    }

    static void appendVariantDescription(final SpellAbility derived, final String variantDescription) {
        if (derived == null || variantDescription == null || variantDescription.isEmpty()) {
            return;
        }

        final String description = derived.getDescription() == null ? "" : derived.getDescription();
        derived.setDescription(description + " (" + variantDescription + ")");
    }
}