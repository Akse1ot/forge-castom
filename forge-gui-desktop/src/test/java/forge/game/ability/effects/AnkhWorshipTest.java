package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

/** Generic fixtures only: real ANKH resources remain in the external builder folder. */
public class AnkhWorshipTest extends AITest {
    private Card fixture(final Player p, final String name, final String... extra) {
        var lines = new java.util.ArrayList<>(List.of("Name:" + name, "ManaCost:1 W", "Types:Creature Human", "PT:2/2"));
        lines.addAll(List.of(extra));
        lines.add("Oracle:");
        Card c = CardFactoryForTest.create(lines, p);
        p.getZone(ZoneType.Battlefield).add(c);
        c.setSickness(false);
        return c;
    }

    private Card source(final Player p) {
        return fixture(p, "Worship source", "K:Worship:1");
    }

    private void observer(final Game g, final Player p) {
        fixture(p, "Worship observer",
                "T:Mode$ Worship | TriggerZones$ Battlefield | Execute$ SeenWorship | TriggerDescription$ Gain one life.",
                "SVar:SeenWorship:DB$ GainLife | Defined$ You | LifeAmount$ 1");
        g.getTriggerHandler().resetActiveTriggers();
    }

    private SpellAbility activation(final Card source, final Card target, final Player p) {
        SpellAbility a = source.getSpellAbilities().stream().filter(s -> s.getApi() == forge.game.ability.ApiType.Worship).findFirst().orElseThrow();
        a.setActivatingPlayer(p);
        a.getTargets().clear();
        a.getTargets().add(target);
        return a;
    }

    private void drain(final Game g) {
        int limit = 30;
        do {
            g.getTriggerHandler().runWaitingTriggers();
            g.getStack().addAllTriggeredAbilitiesToStack();
            if (!g.getStack().isEmpty()) {
                g.getStack().resolveStack();
            } else if (!g.getStack().hasSimultaneousStackEntries()) {
                return;
            }
        } while (--limit > 0);
        Assert.fail("unexpected recursive worship triggers");
    }

    @Test
    public void successfulWorshipIsOneEventAndDistinctState() {
        Game g = initAndCreateGame(); Player p = g.getPlayers().get(1);
        Card s = source(p), target = addCard("Grizzly Bears", p);
        observer(g, p); int life = p.getLife();
        g.getStack().add(activation(s, target, p)); drain(g);
        Assert.assertEquals(s.getCounters(CounterEnumType.P1P1), 1);
        Assert.assertEquals(target.getCounters(CounterEnumType.P1P1), 1);
        Assert.assertTrue(s.isDevout());
        Assert.assertEquals(p.getLife(), life + 1, "one success event, not one per counter");
        s.subtractCounter(CounterEnumType.P1P1, 1, null);
        Assert.assertTrue(s.isDevout(), "removing counters does not clear devout");
        s.setTapped(false);
        g.getStack().add(activation(s, target, p)); drain(g);
        Assert.assertEquals(p.getLife(), life + 1, "already-devout source has no successful event");
        Assert.assertEquals(target.getCounters(CounterEnumType.P1P1), 1);
    }

    @Test
    public void countersProhibitedStillSucceeds() {
        Game g = initAndCreateGame(); Player p = g.getPlayers().get(1);
        Card s = source(p), target = addCard("Grizzly Bears", p);
        addCard("Solemnity", p); observer(g, p);g.getAction().checkStaticAbilities();int life=p.getLife();
        g.getStack().add(activation(s,target,p));drain(g);
        Assert.assertTrue(s.isDevout());Assert.assertEquals(s.getCounters(CounterEnumType.P1P1),0);
        Assert.assertEquals(target.getCounters(CounterEnumType.P1P1),0);Assert.assertEquals(p.getLife(),life+1);
    }

    @Test
    public void sourceLeavesButOtherCardSeesSuccess() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p), target=addCard("Grizzly Bears",p);
        observer(g,p);int life=p.getLife();SpellAbility a=activation(s,target,p);g.getStack().add(a);
        Card departed=g.getAction().moveToGraveyard(s,a);drain(g);
        Assert.assertEquals(target.getCounters(CounterEnumType.P1P1),1);Assert.assertEquals(p.getLife(),life+1);
        Assert.assertFalse(departed.isDevout());Assert.assertEquals(departed.getCounters(CounterEnumType.P1P1),0);
    }

    @Test
    public void blinkedSourceIsNotModifiedByOldAbility() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p), target=addCard("Grizzly Bears",p);
        observer(g,p);int life=p.getLife();SpellAbility a=activation(s,target,p);g.getStack().add(a);
        Card departed=g.getAction().moveToGraveyard(s,a);Card returned=g.getAction().moveToPlay(departed,p,a,java.util.Map.of());
        returned.setDevout(true);drain(g);
        Assert.assertEquals(target.getCounters(CounterEnumType.P1P1),1);Assert.assertEquals(p.getLife(),life+1);
        Assert.assertEquals(returned.getCounters(CounterEnumType.P1P1),0);Assert.assertTrue(returned.isDevout());
    }

    @Test
    public void departedDevoutLkiDoesNotBecomeEligibleAgain() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p), target=addCard("Grizzly Bears",p);
        s.setDevout(true);observer(g,p);int life=p.getLife();SpellAbility a=activation(s,target,p);g.getStack().add(a);
        Card departed=g.getAction().moveToGraveyard(s,a);drain(g);
        Assert.assertFalse(departed.isDevout());Assert.assertEquals(target.getCounters(CounterEnumType.P1P1),0);Assert.assertEquals(p.getLife(),life);
    }

    @Test
    public void invalidTargetDoesNotWorship() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p), target=addCard("Grizzly Bears",p);
        observer(g,p);int life=p.getLife();SpellAbility a=activation(s,target,p);g.getStack().add(a);
        g.getAction().moveToGraveyard(target,a);drain(g);
        Assert.assertFalse(s.isDevout());Assert.assertEquals(s.getCounters(CounterEnumType.P1P1),0);Assert.assertEquals(p.getLife(),life);
    }

    @Test
    public void targetMayBelongToOpponentButNotHaveWorship() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card s=source(p), target=addCard("Grizzly Bears",q);
        SpellAbility a=activation(s,target,p);Assert.assertTrue(a.canTarget(target));Assert.assertFalse(a.canTarget(source(q)));
        observer(g,p);g.getStack().add(a);drain(g);Assert.assertTrue(s.isDevout());Assert.assertEquals(target.getCounters(CounterEnumType.P1P1),1);
    }

    @Test
    public void devoutSurvivesStateCopiesButNotZoneChanges() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p);s.setDevout(true);
        Assert.assertTrue(CardCopyService.getLKICopy(s).isDevout());
        Assert.assertFalse(new CardCopyService(s).copyCard(false).isDevout());
        Game aiCopy = new GameCopier(g).makeCopy();
        Assert.assertTrue(aiCopy.getCardState(s,null).isDevout());
        GameSnapshot snapshot=new GameSnapshot(g);Game copy=snapshot.makeCopy();
        Assert.assertTrue(copy.getCardState(s,null).isDevout());
        s.setDevout(false);snapshot.restoreGameState(g);Card restored=g.getCardState(s,null);Assert.assertTrue(restored.isDevout());
        Card departed=g.getAction().moveToGraveyard(restored,null);Assert.assertFalse(departed.isDevout());
    }

    @Test
    public void deathTriggerUsesDevoutLki() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);
        Card s=fixture(p,"Devout death source","K:Worship:PayLife<2>",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self+IsDevout | Execute$ Gain | TriggerDescription$ Devout death.",
                "SVar:Gain:DB$ GainLife | Defined$ You | LifeAmount$ 1");
        Card target=addCard("Grizzly Bears",p);observer(g,p);int life=p.getLife();
        SpellAbility a=activation(s,target,p);
        var lifeCost=(forge.game.cost.CostPayLife)a.getPayCosts().getCostParts().stream()
                .filter(x->x instanceof forge.game.cost.CostPayLife).findFirst().orElseThrow();
        Assert.assertTrue(lifeCost.payAsDecided(p,forge.game.cost.PaymentDecision.number(2),a,false));
        g.getStack().add(a);drain(g);Assert.assertEquals(p.getLife(),life-1);
        SpellAbility destroy=forge.game.ability.AbilityFactory.getAbility("AB$ Destroy | Cost$ 0 | ValidTgts$ Creature",target);
        destroy.setActivatingPlayer(p);destroy.getTargets().add(s);g.getStack().add(destroy);drain(g);
        Assert.assertEquals(p.getLife(),life);
        Assert.assertFalse(g.getCardState(s,null).isDevout());
    }

    @Test
    public void definedObjectFilterRejectsNewZoneObject() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card s=source(p), idol=addCard("Grizzly Bears",p);
        s.addImprintedCard(CardCopyService.getLKICopy(idol));
        SpellAbility a=activation(s,idol,p);
        Assert.assertTrue(idol.isValid("Card.IsDefined ImprintedLKI",p,s,a));
        Card departed=g.getAction().moveToGraveyard(idol,a);
        Card returned=g.getAction().moveToPlay(departed,p,a,java.util.Map.of());
        Assert.assertFalse(returned.isValid("Card.IsDefined ImprintedLKI",p,s,a));
    }

    @Test
    public void existingKeywordGenerationIsUnchanged() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card renowned=fixture(p,"Renown control","K:Renown:1");
        Assert.assertTrue(renowned.hasKeyword(Keyword.RENOWN));Assert.assertFalse(renowned.hasKeyword(Keyword.WORSHIP));
        Assert.assertFalse(renowned.getTriggers().isEmpty());Assert.assertFalse(renowned.isDevout());
    }

    private static final class CardFactoryForTest {
        static Card create(final List<String> lines,final Player p) {
            Card c=forge.game.card.CardFactory.getCard(new PaperCard(CardRules.fromScript(lines),"TST",CardRarity.Common),p,p.getGame());
            c.setGameTimestamp(p.getGame().getNextTimestamp());return c;
        }
    }
}
