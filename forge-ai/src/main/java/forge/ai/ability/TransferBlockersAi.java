package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class TransferBlockersAi extends SpellAbilityAi {
    @Override protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        // Ordinary SwitchBlock previously had CannotPlayAi.
        return sa.hasParam("TransferBlockers") ? super.canPlay(ai, sa)
                : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }
    @Override public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        return sa.hasParam("TransferBlockers") ? checkApiLogic(ai,sa)
                : new AiAbilityDecision(0,AiPlayDecision.CantPlayAi);
    }
    @Override protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if(!sa.hasParam("TransferBlockers"))return super.checkApiLogic(ai,sa);
        sa.resetTargets();
        var combat=ai.getGame().getPhaseHandler().getCombat();Card source=sa.getHostCard();
        if(combat==null || !combat.isAttacking(source))return new AiAbilityDecision(0,AiPlayDecision.CantPlayAi);
        Card best=null;
        for(Card attacker:combat.getAttackers()) {
            if(attacker.getController()!=ai || !combat.isBlocked(attacker) || !sa.canTarget(attacker))continue;
            int damage=0;boolean dangerous=false;
            for(Card blocker:combat.getBlockers(attacker)) {
                damage+=Math.max(0,blocker.getNetCombatDamage());dangerous|=blocker.hasKeyword(Keyword.DEATHTOUCH);
            }
            for(Card blocker:combat.getBlockers(source)) {
                if(!combat.isBlocking(blocker,attacker)) {
                    damage+=Math.max(0,blocker.getNetCombatDamage());dangerous|=blocker.hasKeyword(Keyword.DEATHTOUCH);
                }
            }
            if(attacker!=source && (dangerous || damage>=source.getNetToughness()-source.getDamage()))continue;
            if(best==null || attacker.getNetPower()>best.getNetPower())best=attacker;
        }
        if(best==null)return new AiAbilityDecision(0,AiPlayDecision.TargetingFailed);
        sa.getTargets().add(best);return new AiAbilityDecision(100,AiPlayDecision.WillPlay);
    }
}
