package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import org.testng.annotations.DataProvider;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class AnkhEffectCopyTest extends AITest {
    @DataProvider
    public Object[][] permanentBackends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "permanentBackends")
    public void sleykinPermanentEffectKeepsDynamicTriggersAndReturnsOnlyInCopiedWorld(boolean snapshot) {
        Game original = sleykinGame();
        Player player = original.getPlayers().get(1);
        Card exiled = original.getCardsIn(ZoneType.Exile).getFirst();
        Card effect = player.getCardsIn(ZoneType.Command).getFirst();
        Game copy = snapshot ? new GameSnapshot(original).makeCopy(null, false)
                : new GameCopier(original).withStack(false).makeCopy();
        Player copiedPlayer = copy.getPlayer(player.getId());
        Card copiedEffect = copy.findById(effect.getId());
        Assert.assertEquals(copiedEffect.getTriggers().size(), 3);
        Assert.assertEquals(copiedEffect.getSVar("CopyForSimulation"), "Permanent");
        Assert.assertEquals(copy.findById(exiled.getId()).getCounters(CounterType.getType("REBIRTH")), 3);
        Assert.assertSame(copiedEffect.getEffectSource().getGame(), copy);
        for (Object remembered : copiedEffect.getRemembered()) {
            Assert.assertSame(((Card) remembered).getGame(), copy);
        }
        for (var trigger : copiedEffect.getTriggers()) {
            Assert.assertSame(trigger.getHostCard().getGame(), copy);
            Assert.assertSame(trigger.getOverridingAbility().getHostCard().getGame(), copy);
            if (trigger.getOverridingAbility().getCardState() != null) {
                Assert.assertSame(trigger.getOverridingAbility().getCardState().getCard().getGame(), copy);
            }
        }
        copy.getEndOfTurn().executeUntil();
        Assert.assertTrue(copiedPlayer.getCardsIn(ZoneType.Command).contains(copiedEffect));
        for (int remaining = 2; remaining >= 0; remaining--) {
            nextOwnUpkeep(copy, copiedPlayer);
            Card current = copy.findById(exiled.getId());
            Assert.assertEquals(current.getCounters(CounterType.getType("REBIRTH")), remaining);
            Assert.assertTrue(current.isInZone(remaining == 0 ? ZoneType.Battlefield : ZoneType.Exile));
        }
        Assert.assertTrue(copiedPlayer.getCardsIn(ZoneType.Command).isEmpty());
        Assert.assertEquals(exiled.getCounters(CounterType.getType("REBIRTH")), 3);
        Assert.assertTrue(exiled.isInZone(ZoneType.Exile));
        Assert.assertTrue(player.getCardsIn(ZoneType.Command).contains(effect));
    }

    @Test(dataProvider = "permanentBackends")
    public void movingRememberedCardRemovesOnlyItsCopiedPermanentEffect(boolean snapshot) {
        Game original = sleykinGame();
        Card exiled = original.getCardsIn(ZoneType.Exile).getFirst();
        Game copy = snapshot ? new GameSnapshot(original).makeCopy(null, false)
                : new GameCopier(original).withStack(false).makeCopy();
        copy.getTriggerHandler().resetActiveTriggers();
        Card departed = copy.getAction().moveToHand(copy.findById(exiled.getId()), null);
        drainPermanent(copy);
        Assert.assertTrue(copy.getCardsIn(ZoneType.Command).isEmpty());
        Card returned = copy.getAction().exile(departed, null, null);
        Assert.assertFalse(returned.equalsWithGameTimestamp(exiled));
        Assert.assertTrue(copy.getCardsIn(ZoneType.Command).isEmpty());
        Assert.assertEquals(original.getCardsIn(ZoneType.Command).size(), 1);
    }

    @Test
    public void sleykinSnapshotRestoresThePermanentEffectAfterExternalLastCounterRemoval() {
        Game game = sleykinGame();
        Card exiled = game.getCardsIn(ZoneType.Exile).getFirst();
        GameSnapshot snapshot = new GameSnapshot(game);
        snapshot.makeCopy(null, false);
        exiled.subtractCounter(CounterType.getType("REBIRTH"), 3, exiled.getOwner());
        drainPermanent(game);
        Assert.assertTrue(game.findById(exiled.getId()).isInPlay());
        snapshot.restoreGameState(game);
        Card restored = game.findById(exiled.getId());
        Assert.assertTrue(restored.isInZone(ZoneType.Exile));
        Assert.assertEquals(restored.getCounters(CounterType.getType("REBIRTH")), 3);
        Assert.assertEquals(game.getCardsIn(ZoneType.Command).size(), 1);
        game.getEndOfTurn().executeUntil();
        Assert.assertEquals(game.getCardsIn(ZoneType.Command).size(), 1);
    }

    private Game sleykinGame() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        for (Player p : game.getPlayers()) {
            for (int i = 0; i < 10; i++) {
                addCardToZone("Forest", p, ZoneType.Library);
            }
        }
        Card sleykin = addCard("Sleykin, Curious Wanderer", player);
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
        var dies = AbilityFactory.getAbility("DB$ Destroy | Defined$ Self", sleykin);
        dies.setActivatingPlayer(player);
        AbilityUtils.resolve(dies);
        drainPermanent(game);
        Assert.assertEquals(player.getCardsIn(ZoneType.Command).size(), 1,
                "Sleykin ended in " + game.findById(sleykin.getId()).getZone());
        Assert.assertEquals(game.findById(sleykin.getId()).getCounters(CounterType.getType("REBIRTH")), 3);
        return game;
    }

    private void nextOwnUpkeep(Game game, Player player) {
        do {
            Assert.assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP,
                    () -> drainPermanent(game)));
            drainPermanent(game);
        } while (game.getPhaseHandler().getPlayerTurn() != player);
    }

    private void drainPermanent(Game game) {
        for (int i = 0; i < 30; i++) {
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
        Assert.fail("Permanent effect stack did not drain");
    }
    private Card effect(Game g, Player p) {
        var rules = CardRules.fromScript(List.of("Name:Copy effect fixture", "Types:Instant", "ManaCost:W",
                "A:SP$ Effect | CopyForSimulation$ True | RememberObjects$ Targeted & You | ValidTgts$ Creature | ImprintCards$ Self | ReplacementEffects$ Prevent | Triggers$ Seen",
                "SVar:Prevent:Event$ DamageDone | ValidTarget$ You | PreventionEffect$ True",
                "SVar:Seen:Mode$ Worship | Execute$ Life | TriggerZones$ Command",
                "SVar:Life:DB$ GainLife | Defined$ You | LifeAmount$ 1", "Oracle:"));
        Card source=CardFactory.getCard(new PaperCard(rules,"",CardRarity.Common),p,g);
        p.getZone(ZoneType.Graveyard).add(source);
        var a=source.getFirstSpellAbility();a.setActivatingPlayer(p);a.getTargets().add(addCard("Grizzly Bears",p));AbilityUtils.resolve(a);
        return p.getCardsIn(ZoneType.Command).stream().filter(c->c.getEffectSource()==source).findFirst().orElseThrow();
    }
    private void check(Game copy,Card original) {
        Card c=copy.findById(original.getId());
        Assert.assertEquals(c.getReplacementEffects().size(),1);Assert.assertEquals(c.getTriggers().size(),1);
        Assert.assertEquals(c.getRememberedCount(),2);Assert.assertEquals(c.getSVar("Life"),"DB$ GainLife | Defined$ You | LifeAmount$ 1");
        Assert.assertSame(c.getEffectSource().getGame(),copy);Assert.assertSame(c.getImprintedCards().getFirst().getGame(),copy);
        for(Object o:c.getRemembered()) {
            if(o instanceof Card card)Assert.assertSame(card.getGame(),copy);
            if(o instanceof Player player)Assert.assertSame(player.getGame(),copy);
        }
        copy.getEndOfTurn().executeUntil();Assert.assertFalse(copy.getCardsIn(ZoneType.Command).contains(c));
    }
    @Test public void dynamicEffectTraitsAndRelationshipsSurviveBothCopyPathsAndExpire() {
        Game g=initAndCreateGame();Card c=effect(g,g.getPlayers().get(1));
        check(new GameCopier(g).makeCopy(),c);check(new GameSnapshot(g).makeCopy(),c);
        Assert.assertTrue(c.isInZone(ZoneType.Command),"copied expiry must not expire original");
    }
    @Test public void snapshotRestoresEffectAfterItsOriginalExpired() {
        Game g=initAndCreateGame();Card c=effect(g,g.getPlayers().get(1));var snapshot=new GameSnapshot(g);snapshot.makeCopy();
        g.getEndOfTurn().executeUntil();snapshot.restoreGameState(g);check(g,c);
    }
    @Test public void grantedWorshipSurvivesSnapshotAndRollbackWithoutBecomingPrinted() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=addCard("Grizzly Bears",p);
        var grant=AbilityFactory.getAbility("AB$ Pump | Cost$ 0 | Defined$ Self | KW$ Worship:2 | Duration$ Permanent",c);grant.setActivatingPlayer(p);AbilityUtils.resolve(grant);
        var snapshot=new GameSnapshot(g);Game copy=snapshot.makeCopy();Assert.assertTrue(copy.findById(c.getId()).hasKeyword(Keyword.WORSHIP));
        var moved=g.getAction().moveToGraveyard(c,null);Assert.assertFalse(moved.hasKeyword(Keyword.WORSHIP));
        snapshot.restoreGameState(g);Assert.assertTrue(g.findById(c.getId()).hasKeyword(Keyword.WORSHIP));
    }
}
