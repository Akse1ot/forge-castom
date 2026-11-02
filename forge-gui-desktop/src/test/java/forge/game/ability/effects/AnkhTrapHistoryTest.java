package forge.game.ability.effects;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardZoneTable;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AnkhTrapHistoryTest extends AITest {
    private Card gain(Game g,Card permanent,Player player) {
        var a=AbilityFactory.getAbility("AB$ GainControl | Cost$ 0 | ValidTgts$ Permanent",addCard("Island",player));
        a.setActivatingPlayer(player);a.getTargets().add(permanent);AbilityUtils.resolve(a);return g.getCardState(permanent);
    }
    private Card enter(Game g,Player player) {
        return g.getAction().moveToPlay(addCardToZone("Grizzly Bears",player,ZoneType.Hand),player,null,null);
    }
    private SpellAbility cast(Game g,Player player) {
        Card c=addCardToZone("Dark Ritual",player,ZoneType.Hand);var a=c.getFirstSpellAbility();a.setActivatingPlayer(player);a.setHostCard(g.getAction().moveToStack(c,a));g.getStack().add(a);return a;
    }
    private void mana(Game g,Player player,boolean copied) {
        var a=addCard("Island",player).getManaAbilities().get(0);a.setActivatingPlayer(player);a.setCopied(copied);g.getStack().add(a);
    }
    private void all(Game g,Player p,boolean expected) {
        Assert.assertEquals(g.getTrapTurnHistory().ownedPermanentTaken(p),expected);
        Assert.assertEquals(g.getTrapTurnHistory().opponentCombatAction(p),expected);
        Assert.assertEquals(g.getTrapTurnHistory().opponentOffMainEntry(p),expected);
    }
    @Test public void controlHistoryPrecedesTrapAndSurvivesReturnButNotInformationalZoneMoves() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card c=addCard("Sol Ring",p);
        c=gain(g,c,q);Assert.assertTrue(g.getTrapTurnHistory().ownedPermanentTaken(p));c=gain(g,c,p);Assert.assertSame(c.getController(),p);Assert.assertTrue(g.getTrapTurnHistory().ownedPermanentTaken(p));
        Card trap=addCardToZone("Archive Trap",p,ZoneType.Hand);var a=trap.getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertEquals(AbilityUtils.calculateAmount(trap,"Count$OpponentGainedControlOfOwnedPermanentThisTurn",a),1);
        c=gain(g,c,q);g.clearCountersThisTurn();g.getAction().moveToGraveyard(c,null);Assert.assertFalse(g.getTrapTurnHistory().ownedPermanentTaken(p),"a move to the owner's graveyard is not a new control gain");
    }
    @Test public void spellCombatContextIsCapturedBeforeWaitingAndBeforeCombatEnds() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);cast(g,q);Assert.assertFalse(g.getTrapTurnHistory().opponentCombatAction(p));
        g.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,p);cast(g,q);Assert.assertTrue(g.getTrapTurnHistory().opponentCombatAction(p));Assert.assertFalse(g.getTrapTurnHistory().opponentCombatAction(q));
        g.getPhaseHandler().devModeSet(PhaseType.MAIN2,p);Assert.assertTrue(g.getTrapTurnHistory().opponentCombatAction(p));
    }
    @Test public void manaActivationsCountButCopiedManaAbilitiesDoNot() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,p);mana(g,q,false);
        Assert.assertTrue(g.getTrapTurnHistory().opponentCombatAction(p));Assert.assertEquals(q.getManaPool().totalMana(),1);
        g.clearCountersThisTurn();mana(g,q,true);Assert.assertFalse(g.getTrapTurnHistory().opponentCombatAction(p));
    }
    @Test public void entryControllerAndPhaseAreNotRecomputedFromLaterBatchState() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);g.getPhaseHandler().devModeSet(PhaseType.MAIN1,q);Card c=enter(g,q);
        Assert.assertFalse(g.getTrapTurnHistory().opponentOffMainEntry(p));c=gain(g,c,p);
        CardZoneTable table=new CardZoneTable();table.put(ZoneType.Hand,ZoneType.Battlefield,c);var event=AbilityKey.newMap();event.put(AbilityKey.Cards,table);g.getTriggerHandler().runTrigger(TriggerType.ChangesZoneAll,event,false);
        Assert.assertFalse(g.getTrapTurnHistory().opponentOffMainEntry(q),"same entry cannot be reclassified after changing controller");
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);enter(g,q);Assert.assertTrue(g.getTrapTurnHistory().opponentOffMainEntry(p),"another player's main phase qualifies");
    }
    @Test public void suppressionAndActualEntryReplacementAreRespected() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);g.getTriggerHandler().suppressMode(TriggerType.ChangesZone);Card c=enter(g,q);Assert.assertFalse(g.getTrapTurnHistory().opponentOffMainEntry(p));g.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
        CardZoneTable table=new CardZoneTable();table.put(ZoneType.Hand,ZoneType.Battlefield,c);var event=AbilityKey.newMap();event.put(AbilityKey.Cards,table);g.getTriggerHandler().runTrigger(TriggerType.ChangesZoneAll,event,false);Assert.assertTrue(g.getTrapTurnHistory().opponentOffMainEntry(p),"simultaneous-only event also works");
        g.clearCountersThisTurn();addCard("Grafdigger's Cage",p);Card grave=addCardToZone("Grizzly Bears",q,ZoneType.Graveyard);g.getAction().moveToPlay(grave,q,null,null);Assert.assertFalse(g.getTrapTurnHistory().opponentOffMainEntry(p));
        g.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,p);g.getTriggerHandler().suppressMode(TriggerType.AbilityCast);mana(g,q,false);Assert.assertFalse(g.getTrapTurnHistory().opponentCombatAction(p));g.getTriggerHandler().clearSuppression(TriggerType.AbilityCast);
    }
    @Test public void aggregatesCopyRestoreAndUseSharedCleanupRestartReset() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);gain(g,addCard("Sol Ring",p),q);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,p);mana(g,q,false);g.getPhaseHandler().devModeSet(PhaseType.MAIN2,p);enter(g,q);all(g,p,true);
        Game copied=new forge.ai.simulation.GameCopier(g).makeCopy();all(copied,copied.getPlayer(p.getId()),true);
        var snapshot=new GameSnapshot(g);snapshot.makeCopy();g.onCleanupPhase();all(g,p,false);all(copied,copied.getPlayer(p.getId()),true);snapshot.restoreGameState(g);all(g,g.getPlayer(p.getId()),true);
        g.clearCountersThisTurn();all(g,g.getPlayer(p.getId()),false);
    }
    @Test public void multiplayerTracksOwnerAndOpponentsAtTheEvent() {
        Game g=initAndCreateThreePlayerGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0),r=g.getPlayers().get(2);Card foreign=gain(g,addCard("Sol Ring",r),p);g.clearCountersThisTurn();gain(g,foreign,q);
        Assert.assertTrue(g.getTrapTurnHistory().ownedPermanentTaken(r));Assert.assertFalse(g.getTrapTurnHistory().ownedPermanentTaken(p));
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,r);enter(g,q);Assert.assertTrue(g.getTrapTurnHistory().opponentOffMainEntry(p));Assert.assertTrue(g.getTrapTurnHistory().opponentOffMainEntry(r));Assert.assertFalse(g.getTrapTurnHistory().opponentOffMainEntry(q));
    }
}
