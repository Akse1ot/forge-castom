package forge.game.staticability;

import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The Class StaticAbility_NumLoyaltyAct.
 *  - used to modify how many times a planeswalker may activate loyalty abilities per turn
 */
public class StaticAbilityNumLoyaltyAct {

    public static boolean limitIncrease(final Card card) {
        for (final Card ca : card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.NumLoyaltyAct)) {
                    continue;
                }

                if (applyLimitIncrease(stAb, card)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyLimitIncrease(final StaticAbility stAb, final Card card) {
        if (!stAb.matchesValidParam("ValidCard", card)) {
            return false;
        }

        if (!stAb.hasParam("Twice")) {
            return false;
        }

        return true;
    }

    public static int additionalActivations(final Card card, final SpellAbility sa) {
        int addl = 0;
        for (final Card ca : card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.NumLoyaltyAct)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidCard", card)) {
                    continue;
                }
                if (stAb.hasParam("Additional")) {
                    if (stAb.hasParam("OnlySourceAbs") && !matchesSourceAbility(stAb, sa)) {
                        continue;
                    }
                    addl += AbilityUtils.calculateAmount(card, stAb.getParam("Additional"), stAb);
                }
            }
        }
        return addl;
    }

    public static boolean eachAbilityOnce(final Card card, final SpellAbility sa) {
        for (final Card ca : card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.NumLoyaltyAct)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidCard", card)) {
                    continue;
                }
                if (!stAb.hasParam("EachAbilityOnce")) {
                    continue;
                }
                if (stAb.hasParam("OnlySourceAbs") && !matchesSourceAbility(stAb, sa)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSourceAbility(final StaticAbility stAb, final SpellAbility sa) {
        final SpellAbility sourceSa = stAb.getHostCard().getEffectSourceAbility();
        if (sourceSa == null) {
            return false;
        }

        final SpellAbility sourceRoot = sourceSa.getRootAbility();
        final SpellAbility sourceOriginal = sourceRoot != null ? sourceRoot.getOriginalAbility() : null;

        final SpellAbility checkSa = sa != null && sa.getOriginalAbility() != null ? sa.getOriginalAbility() : sa;

        if (sourceOriginal != null) {
            return sourceOriginal.equals(checkSa);
        }
        return sourceRoot != null && sourceRoot.equals(checkSa);
    }
}