package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.SpellApiToAi;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class AnkhTransferBlockersTest extends AITest {
    private Combat combat(Game g,Player p) {g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,p);var c=new Combat(p);g.getPhaseHandler().setCombat(c);return c;}
    private Card attack(Combat c,Player p,Player q,String name) {Card a=addCard(name,p);c.addAttacker(a,q);return a;}
    private Card block(Combat c,Card attacker,Player p,String name) {Card b=addCard(name,p);c.addBlocker(attacker,b);c.setBlocked(attacker,true);return b;}
    private SpellAbility ability(Card source,Card target) {var a=AbilityFactory.getAbility("AB$ SwitchBlock | Cost$ 3 | TransferBlockers$ True | ValidTgts$ Creature.blocked | IsPresent$ Card.Self+attacking",source);a.setActivatingPlayer(source.getController());a.getTargets().add(target);return a;}
    private void drain(Game g) {for(int i=0;i<30;i++){g.getTriggerHandler().runWaitingTriggers();g.getStack().addAllTriggeredAbilitiesToStack();if(g.getStack().isEmpty())return;g.getStack().resolveStack();}Assert.fail("stack did not drain");}
    private Card observer(Game g,Player p) {
        Card c=addCard("Island",p);c.setSVar("One","DB$ GainLife | Defined$ You | LifeAmount$ 1");c.setSVar("Ten","DB$ GainLife | Defined$ You | LifeAmount$ 10");c.setSVar("Hundred","DB$ GainLife | Defined$ You | LifeAmount$ 100");
        for(String text:List.of("Mode$ AttackerBlocked | ValidCard$ Creature.YouCtrl | Execute$ One","Mode$ AttackerBlockedByCreature | ValidCard$ Creature.YouCtrl | Execute$ Ten","Mode$ Blocks | ValidCard$ Creature.OppCtrl | Execute$ Hundred"))c.addTrigger(TriggerHandler.parseTrigger(text,c,true));
        g.getTriggerHandler().resetActiveTriggers();return c;
    }
    @Test public void allBlockersTransferIgnoringDeclarationRestrictionsAndTargetDealsUnblockedDamage() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Serra Angel"),target=attack(c,p,q,"Grizzly Bears");source.addIntrinsicKeyword("Menace");
        Card b=block(c,target,q,"Grizzly Bears"),b2=block(c,target,q,"Grizzly Bears");int life=p.getLife();observer(g,p);
        AbilityUtils.resolve(ability(source,target));drain(g);Assert.assertEquals(p.getLife(),life+21,"two new pairs, one newly blocked attacker, no repeat Blocks");
        Assert.assertFalse(c.isBlocked(target));Assert.assertTrue(c.getBlockers(target).isEmpty());Assert.assertEquals(c.getBlockers(source).size(),2);Assert.assertTrue(c.isBlocking(b2,source));
        Assert.assertEquals(source.getBlockedByThisTurn().size(),2);Assert.assertTrue(b.getBlockedThisTurn().stream().anyMatch(a->a.getId()==source.getId()));
        int defendingLife=q.getLife();g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE,p,false);c.assignCombatDamage(false);c.dealAssignedDamage();Assert.assertEquals(q.getLife(),defendingLife-2);
    }
    @Test public void existingSourceAssignmentsAndOtherAssignmentsOfSameBlockerSurvive() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Serra Angel"),target=attack(c,p,q,"Grizzly Bears"),third=attack(c,p,q,"Grizzly Bears");
        Card existing=block(c,source,q,"Grizzly Bears"),shared=block(c,target,q,"Grizzly Bears"),fresh=block(c,target,q,"Grizzly Bears");c.addBlocker(source,shared);c.addBlocker(third,shared);c.setBlocked(third,true);
        int life=p.getLife();observer(g,p);AbilityUtils.resolve(ability(source,target));drain(g);
        Assert.assertEquals(p.getLife(),life+10,"only fresh source/blocker pair");Assert.assertEquals(c.getBlockers(source).size(),3);Assert.assertTrue(c.isBlocking(existing,source));Assert.assertTrue(c.isBlocking(shared,third));Assert.assertFalse(c.isBlocking(shared,target));Assert.assertTrue(c.isBlocking(fresh,source));
    }
    @Test public void bothAffectedBandsSplitWithoutChangingTheirPeers() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Benalish Hero"),sourcePeer=addCard("Grizzly Bears",p);c.addAttacker(sourcePeer,q,c.getBandOfAttacker(source));
        Card target=attack(c,p,q,"Benalish Hero"),targetPeer=addCard("Grizzly Bears",p);c.addAttacker(targetPeer,q,c.getBandOfAttacker(target));
        Card old=block(c,source,q,"Grizzly Bears"),moved=block(c,target,q,"Grizzly Bears");AbilityUtils.resolve(ability(source,target));
        Assert.assertNotSame(c.getBandOfAttacker(source),c.getBandOfAttacker(sourcePeer));Assert.assertNotSame(c.getBandOfAttacker(target),c.getBandOfAttacker(targetPeer));
        Assert.assertFalse(c.isBlocked(target));Assert.assertTrue(c.isBlocked(targetPeer));Assert.assertTrue(c.isBlocking(moved,targetPeer));Assert.assertTrue(c.isBlocking(old,sourcePeer));Assert.assertFalse(c.isBlocking(moved,sourcePeer));Assert.assertTrue(c.isBlocking(moved,source));
        for(Game copy:List.of(new GameCopier(g).makeCopy(),new GameSnapshot(g).makeCopy())) {
            Combat cc=copy.getPhaseHandler().getCombat();Assert.assertFalse(cc.isBlocked(copy.findById(target.getId())));Assert.assertTrue(cc.isBlocking(copy.findById(moved.getId()),copy.findById(targetPeer.getId())));
        }
    }
    @Test public void sameBandDoesNotInventNewBlockPairsAndSelfTargetBecomesUnblocked() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Benalish Hero"),target=addCard("Grizzly Bears",p);c.addAttacker(target,q,c.getBandOfAttacker(source));Card b=block(c,target,q,"Grizzly Bears");
        int life=p.getLife();observer(g,p);AbilityUtils.resolve(ability(source,target));drain(g);Assert.assertEquals(p.getLife(),life);Assert.assertTrue(c.isBlocking(b,source));Assert.assertFalse(c.isBlocked(target));
        AbilityUtils.resolve(ability(source,source));Assert.assertFalse(c.isBlocked(source));Assert.assertTrue(c.getBlockers(source).isEmpty());
    }
    @Test public void absentSourceNewIncarnationOrAbsentTargetDoNotTransfer() {
        for(int mode=0;mode<4;mode++) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
            Card source=attack(c,p,q,"Serra Angel"),target=attack(c,p,q,"Grizzly Bears"),b=block(c,target,q,"Grizzly Bears");var a=ability(source,target);
            if(mode==0)c.removeFromCombat(source);else if(mode==1){var departed=g.getAction().moveToGraveyard(source,null);var returned=g.getAction().moveToPlay(departed,p,null,null);c.addAttacker(returned,q);}else if(mode==2)c.removeFromCombat(target);else c.setBlocked(target,false);
            AbilityUtils.resolve(a);Assert.assertFalse(c.isBlocking(b,source));
        }
    }
    @Test public void targetWithNoRemainingBlockersStillBecomesUnblocked() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Serra Angel"),target=attack(c,p,q,"Grizzly Bears"),b=block(c,target,q,"Grizzly Bears");c.removeFromCombat(b);Assert.assertTrue(c.isBlocked(target));AbilityUtils.resolve(ability(source,target));Assert.assertFalse(c.isBlocked(target));Assert.assertFalse(c.isBlocked(source));
    }
    @Test public void brimazGetsNewPairTriggerButSpincrusherDoesNotBlockAgain() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Serra Angel"),target=attack(c,p,q,"Grizzly Bears");block(c,source,q,"Grizzly Bears");Card spin=block(c,target,q,"Spincrusher");block(c,target,q,"Brimaz, King of Oreskos");g.getTriggerHandler().resetActiveTriggers();
        AbilityUtils.resolve(ability(source,target));drain(g);Assert.assertEquals(q.getCreaturesInPlay().stream().filter(Card::isToken).count(),1L);Assert.assertTrue(spin.getCounters().isEmpty());
    }
    @Test public void ordinarySwitchBlockAndItsAiRemainOnExistingPaths() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Grizzly Bears"),target=attack(c,p,q,"Grizzly Bears"),b1=block(c,source,q,"Grizzly Bears"),b2=block(c,target,q,"Grizzly Bears");source.addRemembered(List.of(b1,b2));
        var a=AbilityFactory.getAbility("AB$ SwitchBlock | Cost$ 0 | DefinedAttacker$ Targeted | DefinedBlocker$ Remembered | ValidTgts$ Creature.attacking | TargetMin$ 2 | TargetMax$ 2",source);a.setActivatingPlayer(p);a.getTargets().add(source);a.getTargets().add(target);
        Assert.assertFalse(SpellApiToAi.Converter.get(a).canPlayWithSubs(p,a).willingToPlay());Assert.assertFalse(SpellApiToAi.Converter.get(a).chkDrawback(p,a).willingToPlay());AbilityUtils.resolve(a);Assert.assertTrue(c.isBlocking(b1,target));Assert.assertTrue(c.isBlocking(b2,source));
    }
    @Test public void thresholdTriggerFiresWhenCrossedAndNotAgainForOneMoreBlocker() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);
        Card source=attack(c,p,q,"Serra Angel"),first=attack(c,p,q,"Grizzly Bears"),second=attack(c,p,q,"Grizzly Bears");block(c,source,q,"Grizzly Bears");block(c,first,q,"Grizzly Bears");block(c,second,q,"Grizzly Bears");
        Card observer=addCard("Island",p);observer.setSVar("Life","DB$ GainLife | Defined$ You | LifeAmount$ 1");observer.addTrigger(TriggerHandler.parseTrigger("Mode$ AttackerBlocked | ValidCard$ Creature.YouCtrl | ValidBlocker$ Creature | ValidBlockerAmount$ GE2 | Execute$ Life",observer,true));g.getTriggerHandler().resetActiveTriggers();int life=p.getLife();
        AbilityUtils.resolve(ability(source,first));drain(g);Assert.assertEquals(p.getLife(),life+1);AbilityUtils.resolve(ability(source,second));drain(g);Assert.assertEquals(p.getLife(),life+1);
    }
    @Test public void optInAiSelectsLegalSelfUnblockAndActivationRequiresAttacking() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Combat c=combat(g,p);Card source=attack(c,p,q,"Serra Angel");block(c,source,q,"Grizzly Bears");var a=ability(source,source);
        Assert.assertTrue(SpellApiToAi.Converter.get(a).canPlayWithSubs(p,a).willingToPlay());Assert.assertSame(a.getTargets().getTargetCards().getFirst(),source);
        c.removeFromCombat(source);Assert.assertFalse(a.getRestrictions().canPlay(source,a));
    }
}
