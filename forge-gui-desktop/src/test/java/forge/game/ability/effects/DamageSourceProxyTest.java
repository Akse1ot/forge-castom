package forge.game.ability.effects;

import com.google.common.eventbus.Subscribe;
import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.Player;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class DamageSourceProxyTest extends AITest {
    private Card sobki(Player p) {
        var rules = CardRules.fromScript(List.of("Name:Sobki proxy fixture", "ManaCost:1 G G",
                "Types:Planeswalker Sobki", "Loyalty:20", "Oracle:Proxy regression fixture.",
                "A:AB$ DealDamage | Cost$ 0 | ValidTgts$ Player | NumDmg$ Loyalty | SubAbility$ MakeProxy",
                "SVar:Loyalty:Count$CardCounters.LOYALTY",
                "SVar:Lands:PlayerCountDefinedTargeted$Valid Land.YouCtrl",
                "SVar:MakeProxy:DB$ Effect | EffectOwner$ Targeted | Name$ Sobki Retaliation Proxy | DamageSourceProxy$ True | Duration$ Permanent | ImprintOnHost$ True | SubAbility$ WithProxy",
                "SVar:WithProxy:DB$ RepeatEach | RepeatCards$ Effect.IsImprinted+namedSobki Retaliation Proxy | Zone$ Command | RepeatSubAbility$ UnimprintProxy",
                "SVar:UnimprintProxy:DB$ RepeatEach | DefinedCards$ RememberedLast | UseImprinted$ True | RepeatSubAbility$ Retaliate",
                "SVar:Retaliate:DB$ DealDamage | Defined$ Self | DamageSource$ RememberedLast | NumDmg$ Lands | ConditionCheckSVar$ Lands | ConditionSVarCompare$ GT0 | SubAbility$ RemoveProxy",
                "SVar:RemoveProxy:DB$ ChangeZone | Defined$ RememberedLast | Origin$ Command | Destination$ Exile"));
        Card c = CardFactory.getCard(new PaperCard(rules, "", CardRarity.MythicRare), p, p.getGame());
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        c.setCounters(CounterEnumType.LOYALTY, 20);
        return c;
    }

    private SpellAbility zero(Card c, Player p, Player target) {
        var a = c.getSpellAbilities().stream().filter(x -> x.getApi() == ApiType.DealDamage).findFirst().orElseThrow();
        a.setActivatingPlayer(p);
        a.getTargets().add(target);
        return a;
    }

    private void resolve(Game g, SpellAbility a) {
        g.getAction().checkStaticAbilities();
        g.getTriggerHandler().resetActiveTriggers();
        g.getStack().add(a);
        g.getStack().resolveStack();
        g.getTriggerHandler().runWaitingTriggers();
    }

    private void observe(Card observer, String valid) {
        observer.setSVar("Life", "DB$ GainLife | Defined$ You | LifeAmount$ 1");
        var t = TriggerHandler.parseTrigger("Mode$ DamageDone | ValidSource$ " + valid
                + " | ValidTarget$ Planeswalker | Static$ True | TriggerZones$ Battlefield | Execute$ Life", observer, true);
        t.ensureAbility();
        observer.addTrigger(t);
    }

    public static class DuringDamage {
        final Game game;
        final Card sobki;
        Card proxy;
        boolean resolving;
        boolean createdWhileResolving;
        int created;
        DuringDamage(Game g, Card c) { game = g; sobki = c; }
        @Subscribe public void zone(GameEventCardChangeZone e) {
            if (e.to().zoneType() == ZoneType.Command) {
                Card c = game.findById(e.card().getId());
                if (c != null && DamageSourceProxy.isProxy(c)) {
                    created++;
                    createdWhileResolving = game.getStack().isResolving() && game.getStack().size() == 1;
                }
            }
        }
        @Subscribe public void counters(GameEventCardCounters e) {
            if (e.card().getId() == sobki.getId() && e.type() == CounterEnumType.LOYALTY) {
                proxy = game.getCardsIn(ZoneType.Command).stream().filter(DamageSourceProxy::isProxy).findFirst().orElse(null);
                resolving = game.getStack().isResolving();
            }
        }
    }

    @DataProvider public Object[][] damageCases() {
        return new Object[][] {{3, false, false, false}, {3, false, true, false},
                {3, true, false, false}, {0, false, false, false}, {0, false, true, false}, {2, false, false, true}};
    }

    @Test(dataProvider = "damageCases")
    public void nativeDamagePipelineAndImmediateCleanup(int lands, boolean prevent, boolean furnace, boolean self) {
        Game g = initAndCreateGame();
        Player p = g.getPlayers().get(1), q = self ? p : g.getPlayers().get(0);
        q.setLife(100, null);
        Card c = sobki(p);
        for (int i = 0; i < lands; i++) addCard("Forest", q);
        Card old = addCardToZone("Island", p, ZoneType.Exile);
        c.addRemembered(old);
        c.addImprintedCard(old);
        Card observer = addCard("Sol Ring", q);
        observe(observer, "Card.YouCtrl");
        Card otherObserver = addCard("Sol Ring", self ? g.getPlayers().get(0) : p);
        observe(otherObserver, "Card.YouCtrl");
        if (prevent) {
            observer.addReplacementEffect(ReplacementHandler.parseReplacement(
                    "Event$ DamageDone | ActiveZones$ Battlefield | ValidSource$ Card.YouCtrl | ValidTarget$ Planeswalker | Prevent$ True | Description$ Prevent proxy damage", observer, true));
        }
        if (furnace) {
            Card f = addCard("Furnace of Rath", p);
            for (var r : f.getReplacementEffects()) r.ensureAbility();
        }
        int otherLife = otherObserver.getController().getLife();
        var watch = new DuringDamage(g, c);
        g.subscribeToEvents(watch);
        resolve(g, zero(c, p, q));
        int dmg = prevent ? 0 : lands * (furnace ? 2 : 1);
        assertEquals(c.getCounters(CounterEnumType.LOYALTY), 20 - dmg);
        assertEquals(c.getDamageReceivedThisTurn().size(), dmg > 0 ? 1 : 0);
        assertEquals(q.getLife(), 100 - 20 * (furnace ? 2 : 1) + (dmg > 0 ? 1 : 0));
        assertEquals(otherObserver.getController().getLife(), otherLife, "wrong controller's trigger fired");
        assertFalse(g.getCardsIn(ZoneType.Command).anyMatch(DamageSourceProxy::isProxy));
        assertEquals(c.getImprintedCards().size(), 1);
        assertTrue(c.hasImprintedCard(old));
        assertEquals(c.getRememberedCount(), 1);
        assertTrue(c.isRemembered(old));
        assertTrue(g.getStack().isEmpty(), "no additional stack object / priority boundary");
        assertEquals(watch.created, 1, "one temporary source even for zero damage");
        assertTrue(watch.createdWhileResolving);
        if (dmg > 0) {
            assertNotNull(watch.proxy, "proxy must exist during damage");
            assertTrue(watch.resolving, "damage must be inside the original resolution");
            Card source = g.getDamageLKI(c.getDamageReceivedThisTurn().get(0)).getLeft();
            assertTrue(DamageSourceProxy.isProxy(source));
            assertSame(source.getController(), q);
            assertSame(source.getOwner(), q);
            assertTrue(source.getColor().isColorless());
            assertTrue(source.getType().isEmpty());
            assertTrue(source.getSpellAbilities().isEmpty());
            assertEquals(source.getId(), watch.proxy.getId());
            assertEquals(source.getDamageHistory().getDamageDoneThisTurn(false, false,
                    "Card.YouCtrl", "Planeswalker", observer, q, null), dmg);
        }
    }

    private Card proxy(Card host, Player q, boolean optedIn) {
        var a = AbilityFactory.getAbility("AB$ Effect | Cost$ 0 | ValidTgts$ Player | EffectOwner$ Targeted | Duration$ Permanent"
                + (optedIn ? " | DamageSourceProxy$ True" : ""), host);
        a.setActivatingPlayer(host.getController());
        a.getTargets().add(q);
        AbilityUtils.resolve(a);
        return q.getCardsIn(ZoneType.Command).getLast();
    }

    @Test public void ordinaryCardFiltersAndUnrelatedTraitsNeverMatchProxy() {
        Game g = initAndCreateGame();
        Player p = g.getPlayers().get(1), q = g.getPlayers().get(0);
        Card host = sobki(p), effect = proxy(host, q, true), ordinary = proxy(host, q, false);
        var trigger = TriggerHandler.parseTrigger("Mode$ DamageDone | ValidSource$ Card.YouCtrl | ValidTarget$ Planeswalker", addCard("Sol Ring", q), true);
        assertTrue(trigger.matchesDamageSource(effect));
        assertTrue(trigger.matchesDamageSource(CardCopyService.getLKICopy(effect)));
        assertFalse(trigger.matchesValidParam("ValidSource", effect));
        assertFalse(trigger.matchesDamageSource(ordinary));
        assertFalse(effect.isValid("Card", q, host, trigger));
        assertTrue(CardLists.getValidCards(List.of(effect), "Card", q, host, trigger).isEmpty());
        host.addImprintedCard(effect);
        assertTrue(AbilityUtils.getDefinedCards(host, "Imprinted.named" + effect.getName(), trigger).isEmpty());
        assertTrue(ordinary.isGreen(), "ordinary Effect retains its original characteristics");
        trigger.putParam("ValidSource", "Card,Emblem");
        assertTrue(trigger.matchesDamageSource(effect));
        trigger.putParam("ValidSource", "Card.YouDontCtrl");
        assertFalse(trigger.matchesDamageSource(effect));
        trigger.putParam("ValidSource", "Card.Green");
        assertFalse(trigger.matchesDamageSource(effect));
        trigger.putParam("ValidSource", "Creature,Artifact,Permanent");
        assertFalse(trigger.matchesDamageSource(effect));
        trigger.putParam("ValidSource", "Card.YouCtrl");
        trigger.putParam("InvertValidSource", "True");
        assertFalse(trigger.matchesDamageSource(effect));
        var unrelated = TriggerHandler.parseTrigger("Mode$ AbilityResolves | ValidSource$ Card.YouCtrl", host, true);
        assertFalse(unrelated.matchesValidParam("ValidSource", effect));
        assertEquals(g.getCardsIn(ZoneType.Command).size(), 2);
        var table = new CardDamageTable();
        table.put(effect, host, 3);
        table.put(ordinary, host, 5);
        assertEquals(table.filteredMap("Card.YouCtrl", "Planeswalker", trigger.getHostCard(), trigger).totalAmount(), 3);
    }

    @Test public void proxyCharacteristicsSurviveLkiAndBothGameCopyPaths() {
        Game g = initAndCreateGame();
        Player p = g.getPlayers().get(1), q = g.getPlayers().get(0);
        Card host = sobki(p), original = proxy(host, q, true);
        Card lki = CardCopyService.getLKICopy(original);
        assertTrue(DamageSourceProxy.isProxy(lki));
        assertSame(lki.getController(), q);
        var snapshot = new GameSnapshot(g);
        for (Game copy : List.of(new GameCopier(g).makeCopy(), snapshot.makeCopy())) {
            checkCopy(copy, original, q);
        }
        g.getAction().exileEffect(original);
        snapshot.restoreGameState(g);
        checkCopy(g, original, q);
    }

    private void checkCopy(Game copy, Card original, Player controller) {
        Card c = copy.findById(original.getId());
        assertTrue(DamageSourceProxy.isProxy(c));
        assertTrue(c.getColor().isColorless());
        assertTrue(c.getType().isEmpty());
        assertTrue(c.getSpellAbilities().isEmpty());
        assertEquals(c.getController().getId(), controller.getId());
        assertSame(c.getController().getGame(), copy);
        assertSame(c.getEffectSource().getGame(), copy);
        assertEquals(c.getGameTimestamp(), original.getGameTimestamp());
    }

    @DataProvider public Object[][] departures() { return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}}; }

    @Test(dataProvider = "departures") public void sourceLeavesDuringDamageAndBlinkIsANewObject(boolean blink, boolean retaliation) {
        Game g = initAndCreateGame();
        Player p = g.getPlayers().get(1), q = g.getPlayers().get(0);
        q.setLife(100, null);
        Card c = sobki(p), observer = addCard("Sol Ring", q);
        addCard("Forest", q);
        observer.addRemembered(c);
        observer.setSVar("Keep", "ReplaceCount$DamageAmount");
        observer.setSVar("Replace", "DB$ ReplaceEffect | VarName$ DamageAmount | VarValue$ Keep | SubAbility$ Leave");
        observer.setSVar("Leave", "DB$ ChangeZone | Defined$ Remembered | Origin$ Battlefield | Destination$ Exile" + (blink ? " | SubAbility$ Return" : ""));
        observer.setSVar("Return", "DB$ ChangeZone | Defined$ Remembered | Origin$ Exile | Destination$ Battlefield");
        var r = ReplacementHandler.parseReplacement("Event$ DamageDone | ActiveZones$ Battlefield | ValidTarget$ "
                + (retaliation ? "Planeswalker" : "You")
                + " | ReplaceWith$ Replace | Description$ Remove Sobki during damage", observer, true);
        r.ensureAbility();
        observer.addReplacementEffect(r);
        resolve(g, zero(c, p, q));
        assertEquals(q.getLife(), 80);
        Card current = g.getCardState(c);
        assertTrue(current.isInZone(blink ? ZoneType.Battlefield : ZoneType.Exile));
        assertTrue(current.getDamageReceivedThisTurn().isEmpty());
        assertFalse(g.getCardsIn(ZoneType.Command).anyMatch(DamageSourceProxy::isProxy));
        if (blink) {
            assertFalse(c.equalsWithGameTimestamp(current));
            assertEquals(current.getCounters(CounterEnumType.LOYALTY), 20, "new Sobki must not receive old ability's damage");
        }
    }
}
