package forge.game.ability.effects;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.event.GameEventCombatChanged;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import java.util.List;

/** Block-transfer resolution only; ordinary SwitchBlock retains its existing path. */
public final class TransferBlockersSupport {
    private TransferBlockersSupport() { }

    public static void resolve(SpellAbility sa) {
        var game = sa.getHostCard().getGame();
        var combat = game.getPhaseHandler().getCombat();
        Card source = game.getCardState(sa.getHostCard(), null);
        if (combat == null || source == null || !source.isInPlay()
                || !source.equalsWithGameTimestamp(sa.getHostCard()) || !combat.isAttacking(source)
                || sa.getTargets().getTargetCards().size() != 1) {
            return;
        }
        Card selected = sa.getTargets().getTargetCards().getFirst();
        Card target = game.getCardState(selected, null);
        if (target == null || !target.isInPlay() || !target.equalsWithGameTimestamp(selected)
                || !combat.isAttacking(target) || !combat.isBlocked(target) || !sa.canTarget(target)) {
            return;
        }
        boolean wasBlocked = combat.isBlocked(source);
        CardCollection previous = new CardCollection(combat.getBlockers(source));
        CardCollection added = combat.transferBlockers(target, source);
        for (Card blocker : added) {
            blocker.addBlockedThisTurn(CardCopyService.getLKICopy(source));
            source.addBlockedByThisTurn(CardCopyService.getLKICopy(blocker));
            var pair = AbilityKey.newMap();
            pair.put(AbilityKey.Attacker, source);
            pair.put(AbilityKey.Blocker, blocker);
            game.getTriggerHandler().runTrigger(TriggerType.AttackerBlockedByCreature, pair, false);
            // These creatures were already blocking: no new CR 509.3a Blocks event.
        }
        if (!added.isEmpty()) {
            source.getDamageHistory().setCreatureGotBlockedThisCombat(true);
            source.getDamageHistory().clearNotBeenBlockedSinceLastUpkeepOf();
            var event = AbilityKey.newMap();
            event.put(AbilityKey.Attacker, source);
            event.put(AbilityKey.Blockers, combat.getBlockers(source));
            event.put(AbilityKey.PreviousBlockers, previous);
            event.put(AbilityKey.WasBlocked, wasBlocked);
            event.put(AbilityKey.Defender, combat.getDefenderByAttacker(source));
            event.put(AbilityKey.DefendingPlayer, combat.getDefenderPlayerByAttacker(source));
            game.getTriggerHandler().runTrigger(TriggerType.AttackerBlocked, event, false);
            if (!wasBlocked) {
                var once = AbilityKey.newMap();
                once.put(AbilityKey.Attackers, List.of(source));
                game.getTriggerHandler().runTrigger(TriggerType.AttackerBlockedOnce, once, false);
            }
        }
        game.updateCombatForView();
        game.fireEvent(new GameEventCombatChanged());
    }
}
