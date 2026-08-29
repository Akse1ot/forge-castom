package forge.game.staticability;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;

import forge.card.mana.ManaCostParser;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityAlternativeCost {

    public static List<SpellAbility> alternativeCosts(final SpellAbility sa, final Card source, final Player pl) {
        List<SpellAbility> result = Lists.newArrayList();
        // add source first in case it's LKI (alternate host)
        CardCollection list = new CardCollection(source);
        list.addAll(source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        for (final Card ca : list) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.AlternativeCost)) {
                    continue;
                }

                if (!apply(stAb, sa, source, pl)) {
                    continue;
                }

                String costTemplate = stAb.getParam("Cost");
                costTemplate = costTemplate.replace("ConvertedManaCost", Integer.toString(source.getCMC()));

                Cost cost = new Cost(costTemplate, sa.isAbility());

                final boolean replaceManaCostOnly =
                        sa.isActivatedAbility()
                                && "True".equalsIgnoreCase(
                                stAb.getParamOrDefault("ReplaceManaCostOnly", "False")
                        );

                if (replaceManaCostOnly) {
                    // This mode replaces only the mana part of an activation cost.
                    // The replacement itself therefore must contain mana only.
                    if (!cost.isOnlyManaCost() || cost.getTotalMana().isNoCost()) {
                        continue;
                    }

                    final Cost originalCost = sa.getPayCosts();

                    // No reason to create an alternative if the ability already
                    // has no mana cost or its mana cost is already {0}.
                    if (originalCost == null
                            || !originalCost.hasManaCost()
                            || originalCost.getTotalMana().isZero()) {
                        continue;
                    }
                }

                final SpellAbility newSA;

                if (replaceManaCostOnly) {
                    // Keep the original activation cost on the copied ability.
                    // This is important for X: Forge must still announce X normally
                    // before the mana part is replaced during total-cost calculation.
                    newSA = sa.copy(pl);

                    // Internal marker used later by CostAdjustment.
                    newSA.putParam(
                            "AlternativeManaCost",
                            cost.getTotalMana().getShortString()
                    );
                } else {
                    // Preserve the existing Forge behaviour for all old scripts.
                    newSA = sa.isAbility()
                            ? sa.copyWithDefinedCost(cost)
                            : sa.copyWithManaCostReplaced(pl, cost);
                }

                newSA.setActivatingPlayer(pl);
                newSA.setBasicSpell(false);

                if (stAb.hasParam("XAlternative")) {
                    newSA.putParam("XAlternative", stAb.getParam("XAlternative"));
                }

                if (stAb.hasParam("Announce")) {
                    newSA.putParam("Announce", stAb.getParam("Announce"));
                }

                if (stAb.hasParam("ManaRestriction")) {
                    newSA.putParam("ManaRestriction", stAb.getParam("ManaRestriction"));
                }

                if (stAb.hasParam("AlternativeCost")) {
                    final AlternativeCost alternativeCost = parseAlternativeCost(stAb.getParam("AlternativeCost"));
                    if (alternativeCost == null) {
                        continue;
                    }
                    newSA.setAlternativeCost(alternativeCost);
                }

                if (!stAb.getHostCard().isImmutable()) {
                    Set<ZoneType> zones = stAb.getActiveZone();
                    if (zones != null && zones.size() == 1) {
                        newSA.getRestrictions().setZone(zones.stream().findFirst().get());
                    }
                }

                if (stAb.hasParam("StackDescription")) {
                    newSA.putParam("StackDescription", stAb.getParam("StackDescription"));
                }

                // makes new SpellDescription
                final StringBuilder sb = new StringBuilder();

                // CostDesc only for ManaCost?
                if (sa.isAbility()) {
                    final Cost displayCost = replaceManaCostOnly
                            ? sa.getPayCosts().copyWithDefinedMana(cost.getTotalMana())
                            : cost;

                    newSA.putParam(
                            "CostDesc",
                            stAb.hasParam("CostDesc")
                                    ? ManaCostParser.parse(stAb.getParam("CostDesc"))
                                    : displayCost.toSimpleString()
                    );

                    sb.append(newSA.getCostDescription());

                    if (replaceManaCostOnly) {
                        sb.append(newSA.getParamOrDefault("SpellDescription", ""));
                    }
                }

                // skip reminder text for now, Keywords might be too complicated
                //sb.append("(").append(newKi.getReminderText()).append(")");
                if (sa.isSpell()) {
                    sb.append(sa.getDescription());
                    if (source.equals(stAb.getHostCard())) {
                        newSA.addOptionalCost(OptionalCost.AltCost);
                        sb.append(" ("+ stAb.getParam("Description") +") ");
                    } else {
                        sb.append(" (by paying " + cost.toSimpleString() + " instead of its mana cost)");
                    }
                }
                newSA.setDescription(sb.toString());

                // Support for custom cards alternative costs targeting
                if (stAb.hasParam("Named")) {
                    newSA.setName(stAb.getParam("Named"));
                }

                result.add(newSA);
            }
        }
        return result;
    }

    private static boolean apply(final StaticAbility stAb, final SpellAbility sa, final Card source, final Player pl) {
        final Player oldActivatingPlayer = sa.getActivatingPlayer();
        final boolean temporarilySetActivatingPlayer = oldActivatingPlayer == null && pl != null;

        if (temporarilySetActivatingPlayer) {
            sa.setActivatingPlayer(pl);
        }

        try {
            if (!stAb.matchesValidParam("ValidSA", sa)) {
                return false;
            }
            if (!stAb.matchesValidParam("ValidCard", source)) {
                return false;
            }
            if (!stAb.matchesValidParam("ValidPlayer", pl)) {
                return false;
            }

            if (stAb.hasParam("AltCost")) {
                if (!sa.isSpell()) {
                    return false;
                }
                final AlternativeCost required = parseAlternativeCost(stAb.getParam("AltCost"));
                if (required == null) {
                    return false;
                }
                if (!sa.isAlternativeCost(required)) {
                    return false;
                }
            }

            if (stAb.hasParam("RequiredSpellParam")) {
                final String required = stAb.getParam("RequiredSpellParam");
                if (!sa.hasParam(required)) {
                    return false;
                }
            }

            return true;
        } finally {
            if (temporarilySetActivatingPlayer) {
                sa.setActivatingPlayer(null);
            }
        }
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

}
