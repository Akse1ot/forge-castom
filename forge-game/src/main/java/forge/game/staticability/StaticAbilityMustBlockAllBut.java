package forge.game.staticability;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StaticAbilityMustBlockAllBut {

    public static String validateBlocks(final Combat combat, final Player defending, final List<Card> freeBlockers) {
        if (combat == null || defending == null) {
            return null;
        }

        final Game game = defending.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.MustBlockAllBut)) {
                    continue;
                }

                final String result = validateBlocks(stAb, combat, defending, freeBlockers);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static String validateBlocks(final StaticAbility stAb, final Combat combat, final Player defending,
                                         final List<Card> freeBlockers) {
        if (stAb.hasParam("ValidDefender") && !stAb.matchesValidParam("ValidDefender", defending)) {
            return null;
        }

        final List<Card> affectedAttackers = getAffectedAttackers(stAb, combat, defending);
        if (affectedAttackers.isEmpty()) {
            return null;
        }

        final int exceptAmount = Math.max(0, AbilityUtils.calculateAmount(stAb.getHostCard(),
                stAb.getParamOrDefault("ExceptAmount", "1"), stAb));
        final int requiredBlocked = Math.max(0, affectedAttackers.size() - exceptAmount);

        if (requiredBlocked == 0) {
            return null;
        }

        int alreadyBlocked = 0;
        final List<Card> unblockedAttackers = new ArrayList<>();

        for (final Card attacker : affectedAttackers) {
            if (combat.getBlockers(attacker).isEmpty()) {
                unblockedAttackers.add(attacker);
            } else {
                alreadyBlocked++;
            }
        }

        if (alreadyBlocked >= requiredBlocked) {
            return null;
        }

        final int missingBlocks = requiredBlocked - alreadyBlocked;
        final List<Card> availableBlockers = new ArrayList<>(freeBlockers != null ? freeBlockers : defending.getCreaturesInPlay());

        // Try the most constrained attackers first, so a rare blocker is not spent on an easy block.
        unblockedAttackers.sort(Comparator.comparingInt(attacker ->
                countPotentialBlockers(attacker, defending, availableBlockers, combat)));

        int additionalBlocksPossible = 0;
        for (final Card attacker : unblockedAttackers) {
            if (canAssignBlockersToAttacker(attacker, defending, availableBlockers, combat)) {
                additionalBlocksPossible++;
                if (additionalBlocksPossible >= missingBlocks) {
                    return "More attacking creatures must be blocked if able.";
                }
            }
        }

        return null;
    }

    private static List<Card> getAffectedAttackers(final StaticAbility stAb, final Combat combat, final Player defending) {
        final List<Card> result = new ArrayList<>();

        for (final Card attacker : combat.getAttackers()) {
            if (combat.getDefenderPlayerByAttacker(attacker) != defending) {
                continue;
            }
            if (!stAb.matchesValidParam("ValidAttacker", attacker)) {
                continue;
            }
            result.add(attacker);
        }

        return result;
    }

    private static int countPotentialBlockers(final Card attacker, final Player defending, final List<Card> availableBlockers,
                                              final Combat combat) {
        if (!CombatUtil.canBeBlocked(attacker, combat, defending)) {
            return 0;
        }

        int count = 0;
        for (final Card blocker : availableBlockers) {
            if (CombatUtil.getBlockCost(blocker.getGame(), blocker, attacker) != null) {
                continue;
            }
            if (CombatUtil.canBlock(attacker, blocker)) {
                count++;
            }
        }
        return count;
    }

    private static boolean canAssignBlockersToAttacker(final Card attacker, final Player defending,
                                                       final List<Card> availableBlockers, final Combat combat) {
        if (!CombatUtil.canBeBlocked(attacker, combat, defending)) {
            return false;
        }

        final int minBlockers = CombatUtil.getMinNumBlockersForAttacker(attacker, defending);
        final List<Card> chosenBlockers = new ArrayList<>();

        for (final Card blocker : new ArrayList<>(availableBlockers)) {
            if (CombatUtil.getBlockCost(blocker.getGame(), blocker, attacker) != null) {
                continue;
            }
            if (!CombatUtil.canBlock(attacker, blocker)) {
                continue;
            }

            chosenBlockers.add(blocker);
            if (chosenBlockers.size() >= minBlockers && CombatUtil.canBeBlocked(attacker, chosenBlockers, combat)) {
                availableBlockers.removeAll(chosenBlockers);
                return true;
            }
        }

        return false;
    }
}