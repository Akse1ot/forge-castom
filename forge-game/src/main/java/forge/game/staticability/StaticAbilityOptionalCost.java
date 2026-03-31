package forge.game.staticability;

import java.util.List;

import com.google.common.collect.Lists;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class StaticAbilityOptionalCost {

    private StaticAbilityOptionalCost() {
    }

    public static Cost getOptionalCostReplacement(final OptionalCostValue opt, final SpellAbility sa) {
        if (opt == null || sa == null || !sa.isSpell()) {
            return null;
        }

        // Offering обрабатывается отдельно через sacrificedAsOffering / isOffering(),
        // поэтому в общий replacement-механизм его не включаем.
        if (opt.getType() == OptionalCost.Offering) {
            return null;
        }

        final Card source = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        if (source == null || activator == null) {
            return null;
        }

        final List<StaticAbility> matches = Lists.newArrayList();

        final CardCollection sources = new CardCollection(source);
        sources.addAll(source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));

        for (final Card ca : sources) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.ReplaceOptionalCost)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidCard", source)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidSA", sa)) {
                    continue;
                }
                if (!stAb.matchesValidParam("Activator", activator)) {
                    continue;
                }
                if (!matchesOptionalCost(stAb, opt.getType())) {
                    continue;
                }

                matches.add(stAb);
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        final StaticAbility chosen;
        if (matches.size() == 1) {
            chosen = matches.get(0);
        } else {
            chosen = activator.getController().chooseSingleStaticAbility(matches);
        }

        if (chosen == null) {
            return null;
        }

        final String message;
        if (chosen.hasParam("Description")) {
            message = chosen.getParam("Description");
        } else {
            message = "Use " + chosen.getHostCard().getName()
                    + " to replace " + opt.toString() + "?";
        }

        final String logic = chosen.getParamOrDefault("AILogic", "");

        if (!activator.getController().confirmStaticApplication(chosen.getHostCard(), null, message, logic)) {
            return null;
        }

        return new Cost(chosen.getParam("Cost"), false);
    }

    private static boolean matchesOptionalCost(final StaticAbility stAb, final OptionalCost type) {
        final String valid = stAb.getParamOrDefault("ValidOptionalCost", "Any");

        for (final String s : valid.split(",")) {
            final String v = s.trim();
            if (v.equalsIgnoreCase("Any")) {
                return true;
            }
            if (v.equalsIgnoreCase(type.name())) {
                return true;
            }
        }
        return false;
    }
}