package forge.ai;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CreaturePairing;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

final class PairAttackAi {
    private PairAttackAi() {
    }

    static void consider(final Player player, final Combat combat, final GameEntity defender,
            final Collection<Card> candidates, final Collection<Card> blockers) {
        if (combat.getAttackers().size() > 1) {
            return;
        }
        if (candidates.stream().noneMatch(PairAttackAi::hasPairAttack)) {
            return;
        }
        final Map<Card, GameEntity> proposal = new HashMap<>(combat.getAttackersAndDefenders());
        final int violations = combat.getAttackConstraints().countViolations(proposal);
        if (violations < 0) {
            return;
        }

        for (final Card leader : candidates) {
            if (!CreaturePairing.canPair(leader, player) || !canJoin(leader, combat, defender)) {
                continue;
            }
            final String bonus = pairAttackBonus(leader);
            if (bonus.isEmpty()) {
                continue;
            }
            for (final Card partner : candidates) {
                if (!CreaturePairing.canJoinAttack(leader, partner, player) || !canJoin(partner, combat, defender)) {
                    continue;
                }
                final int leaderBenefit = pairBenefit(leader, blockers, bonus);
                final int partnerBenefit = pairBenefit(partner, blockers, bonus);
                if (leaderBenefit < 0 || partnerBenefit < 0 || leaderBenefit + partnerBenefit == 0) {
                    continue;
                }
                final Map<Card, GameEntity> paired = new HashMap<>(proposal);
                paired.putIfAbsent(leader, defender);
                paired.putIfAbsent(partner, defender);
                if (paired.size() != 2) {
                    continue;
                }
                final int updated = combat.getAttackConstraints().countViolations(paired);
                if (updated >= 0 && updated <= violations) {
                    if (!combat.isAttacking(leader)) {
                        combat.addAttacker(leader, defender);
                    }
                    if (!combat.isAttacking(partner)) {
                        combat.addAttacker(partner, defender);
                    }
                    return;
                }
            }
        }
    }

    private static boolean hasPairAttack(final Card card) {
        return !pairAttackBonus(card).isEmpty();
    }

    private static String pairAttackBonus(final Card card) {
        for (final Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() == TriggerType.Attacks && trigger.hasParam("Execute")) {
                final SpellAbility effect = trigger.ensureAbility();
                if (effect != null && effect.getApi() == ApiType.Bond
                        && "True".equalsIgnoreCase(effect.getParam("PairUntilEndOfTurn"))) {
                    return card.getTriggers().stream().filter(t -> t.getMode() == TriggerType.BecomesPaired)
                            .map(t -> t.getParamOrDefault("PairAttackBonus", ""))
                            .filter(bonus -> "First Strike".equals(bonus) || "Trample".equals(bonus)).findFirst().orElse("");
                }
            }
        }
        return "";
    }

    private static boolean canJoin(final Card card, final Combat combat, final GameEntity defender) {
        return combat.isAttacking(card) ? defender.equals(combat.getDefenderByAttacker(card))
                : CombatUtil.canAttack(card, defender) && CombatUtil.getAttackCost(card.getGame(), card, defender) == null;
    }

    private static int pairBenefit(final Card card, final Collection<Card> blockers, final String bonus) {
        // Consider only single blocks that neither attacker is expected to lose.
        final boolean firstStrike = "First Strike".equals(bonus);
        int matchingBlockers = 0;
        int benefit = 0;
        if (card.getNetCombatDamage() <= 0) {
            return -1;
        }
        for (final Card blocker : blockers) {
            if (CombatUtil.canBlock(card, blocker)) {
                if (++matchingBlockers > 1 || blocker.hasFirstStrike() || blocker.hasDoubleStrike()
                        || blocker.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                    return -1;
                }
                if (firstStrike) {
                    if (card.getNetCombatDamage() < blocker.getNetToughness()) {
                        return -1;
                    }
                    if (!card.hasFirstStrike() && !card.hasDoubleStrike()
                            && blocker.getNetCombatDamage() >= card.getNetToughness()) {
                        benefit = 1;
                    }
                } else {
                    if (blocker.getNetCombatDamage() >= card.getNetToughness() || blocker.hasKeyword(Keyword.DEATHTOUCH)) {
                        return -1;
                    }
                    if (!card.hasKeyword(Keyword.TRAMPLE) && card.getNetCombatDamage() > blocker.getNetToughness()) {
                        benefit = 1;
                    }
                }
            }
        }
        return benefit;
    }
}
