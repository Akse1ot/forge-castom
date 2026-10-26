package forge.game.replacement;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.*;

public class RedirectCounterEffectTest extends AITest {
    @DataProvider
    public Object[][] placementTriggers() {
        return new Object[][] {
                {"CounterAdded"}, {"CounterAddedOnce"},
                {"CounterPlayerAddedAll"}, {"CounterAddedAll"}
        };
    }

    @Test(dataProvider = "placementTriggers")
    public void forbiddenCreatureDoesNotReceiveCountersOrTrigger(String mode) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card from = addCard("Forest", p);
        Card to = addCard("Runeclaw Bear", p);
        forbidP1P1(to);
        redirect(from, "Card.Self", "Remembered", to);
        watchPlacement(p, mode, CounterEnumType.P1P1);
        prepare(game);
        int life = p.getLife();

        GameEntityCounterTable table = putCounters(from, CounterEnumType.P1P1, 2);

        assertEquals(from.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(to.getCounters(CounterEnumType.P1P1), 0);
        assertTrue(table.getLastResult().isEmpty());
        assertEquals(p.getLife(), life, "Forbidden counters must not trigger " + mode);

        // Positive control: the same registered watcher must observe a legal placement.
        Card allowed = addCard("Runeclaw Bear", p);
        putCounters(allowed, CounterEnumType.P1P1, 2);
        assertEquals(p.getLife(), life + (mode.equals("CounterAdded") ? 2 : 1));
    }

    @Test(dataProvider = "placementTriggers")
    public void meliraPreventsRedirectedPoisonAndTriggers(String mode) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card from = addCard("Forest", p);
        addCard("Melira, Sylvok Outcast", p);
        redirect(from, "Card.Self", "You");
        watchPlacement(p, mode, CounterEnumType.POISON);
        prepare(game);
        int life = p.getLife();

        GameEntityCounterTable table = putCounters(from, CounterEnumType.POISON, 2);

        assertEquals(p.getCounters(CounterEnumType.POISON), 0);
        assertEquals(from.getCounters(CounterEnumType.POISON), 0);
        assertTrue(table.getLastResult().isEmpty());
        assertEquals(p.getLife(), life, "Forbidden poison must not trigger " + mode);

        Player opponent = p.getOpponents().get(0);
        GameEntityCounterTable control = new GameEntityCounterTable();
        opponent.addCounter(CounterEnumType.POISON, 2, p, control);
        control.replaceCounterEffect(game, null);
        assertEquals(opponent.getCounters(CounterEnumType.POISON), 2);
        assertEquals(p.getLife(), life + (mode.equals("CounterAdded") ? 2 : 1));
    }

    @Test
    public void mixedRecipientsKeepOnlyAllowedCountersInResult() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card from = addCard("Forest", p);
        Card blocked = addCard("Runeclaw Bear", p);
        Card allowed = addCard("Runeclaw Bear", p);
        forbidP1P1(blocked);
        redirect(from, "Card.Self", "Remembered", blocked, allowed);
        prepare(game);

        GameEntityCounterTable table = putCounters(from, CounterEnumType.P1P1, 2);

        assertEquals(blocked.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(allowed.getCounters(CounterEnumType.P1P1), 2);
        assertEquals(table.getLastResult().totalValues(), 2);
        assertFalse(table.getLastResult().containsColumn(blocked));
        assertTrue(table.getLastResult().containsColumn(allowed));
    }

    @DataProvider
    public Object[][] finalRecipient() {
        return new Object[][] {{false}, {true}};
    }

    @DataProvider
    public Object[][] chainPaths() {
        return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
    }

    @Test(dataProvider = "chainPaths")
    public void chainedRedirectChecksFinalRecipient(boolean blocked, boolean deferred) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card a = addCard("Forest", p);
        Card b = addCard("Runeclaw Bear", p);
        Card c = addCard("Runeclaw Bear", p);
        redirect(a, "Card.Self", "Remembered", b);
        redirect(b, "Card.Self", "Remembered", c);
        if (blocked) {
            forbidP1P1(c);
        }
        prepare(game);

        GameEntityCounterTable table;
        if (deferred) {
            GameEntityCounterTable initial = new GameEntityCounterTable();
            a.addCounter(CounterEnumType.P1P1, 2, p, initial);
            table = new GameEntityCounterTable();
            initial.replaceCounterEffectDeferred(game, null, true, table, AbilityKey.newMap());
            assertEquals(c.getCounters(CounterEnumType.P1P1), 0, "Placement must remain deferred");
            assertEquals(table.totalValues(), blocked ? 0 : 2);
            table.replaceCounterEffect(game, null, true, true, AbilityKey.newMap());
        } else {
            table = putCounters(a, CounterEnumType.P1P1, 2);
        }

        assertEquals(a.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(b.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(c.getCounters(CounterEnumType.P1P1), blocked ? 0 : 2);
        assertEquals(table.getLastResult().totalValues(), blocked ? 0 : 2);
    }

    @Test
    public void redirectedCountersAreDoubledOnce() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card from = addCard("Forest", p);
        Card to = addCard("Runeclaw Bear", p);
        redirect(from, "Card.Self", "Remembered", to);
        doubleCounters(to);
        watchPlacement(p, "CounterAddedOnce", CounterEnumType.P1P1);
        prepare(game);
        int life = p.getLife();

        GameEntityCounterTable table = putCounters(from, CounterEnumType.P1P1, 2);

        assertEquals(to.getCounters(CounterEnumType.P1P1), 4);
        assertEquals(from.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(table.getLastResult().totalValues(), 4);
        assertEquals(p.getLife(), life + 1);
    }

    @Test(dataProvider = "placementTriggers")
    public void etbKeepsEnteringShareAndDefersAllowedDoubledShare(String mode) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card entering = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        Card blocked = addCard("Runeclaw Bear", p);
        Card allowed = addCard("Runeclaw Bear", p);
        Card router = addCard("Forest", p);
        forbidP1P1(blocked);
        redirect(router, "Card.IsRemembered", "Valid Creature.YouCtrl", entering);
        doubleCounters(allowed);
        watchPlacement(p, mode, CounterEnumType.P1P1);
        prepare(game);
        int life = p.getLife();
        GameEntityCounterTable table = new GameEntityCounterTable();
        table.put(p, entering, CounterEnumType.P1P1, 2);
        Map<AbilityKey, Object> params = AbilityKey.newMap();
        params.put(AbilityKey.CounterTable, table);

        Card moved = game.getAction().moveToPlay(entering, p, null, params);

        assertTrue(moved.isInPlay());
        assertEquals(moved.getCounters(CounterEnumType.P1P1), 2);
        assertEquals(blocked.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(allowed.getCounters(CounterEnumType.P1P1), 4);
        assertEquals(table.getLastResult().totalValues(), 6);
        assertFalse(table.getLastResult().containsColumn(blocked));
        int expectedTriggers = mode.equals("CounterAdded") ? 6 : mode.equals("CounterAddedAll") ? 1 : 2;
        assertEquals(p.getLife(), life + expectedTriggers, "Only permitted recipients trigger " + mode);
    }

    @Test(dataProvider = "finalRecipient")
    public void etbPoisonRedirectDoesNotCancelEntry(boolean melira) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card entering = addCardToZone("Forest", p, ZoneType.Hand);
        Card router = addCard("Forest", p);
        if (melira) {
            addCard("Melira, Sylvok Outcast", p);
        }
        redirect(router, "Card.IsRemembered", "You", entering);
        watchPlacement(p, "CounterAddedOnce", CounterEnumType.POISON);
        prepare(game);
        int life = p.getLife();
        GameEntityCounterTable table = new GameEntityCounterTable();
        table.put(p, entering, CounterEnumType.POISON, 2);
        Map<AbilityKey, Object> params = AbilityKey.newMap();
        params.put(AbilityKey.CounterTable, table);

        Card moved = game.getAction().moveToPlay(entering, p, null, params);

        assertTrue(moved.isInPlay());
        assertEquals(moved.getCounters(CounterEnumType.POISON), 0);
        assertEquals(p.getCounters(CounterEnumType.POISON), melira ? 0 : 2);
        assertEquals(table.getLastResult().totalValues(), melira ? 0 : 2);
        assertEquals(p.getLife(), life + (melira ? 0 : 1));
    }

    private void forbidP1P1(Card card) {
        card.addStaticAbility("Mode$ CantPutCounter | ValidCard$ Card.Self | CounterType$ P1P1");
    }

    private void redirect(Card host, String valid, String defined, Card... remembered) {
        for (Card card : remembered) {
            host.addRemembered(card);
        }
        ReplacementEffect re = ReplacementHandler.parseReplacement(
                "Event$ AddCounter | ActiveZones$ Battlefield | ValidCard$ " + valid, host, true);
        re.setOverridingAbility(AbilityFactory.getAbility("DB$ ReplaceCounter | RedirectTo$ " + defined, host));
        host.addReplacementEffect(re);
    }

    private void doubleCounters(Card card) {
        card.setSVar("RedirectTestDouble", "ReplaceCount$CounterNum/Twice");
        ReplacementEffect re = ReplacementHandler.parseReplacement(
                "Event$ AddCounter | ActiveZones$ Battlefield | ValidCard$ Card.Self", card, true);
        re.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ ReplaceCounter | Amount$ RedirectTestDouble", card));
        card.addReplacementEffect(re);
    }

    private void watchPlacement(Player player, String mode, CounterEnumType type) {
        Card host = addCard("Forest", player);
        // Static test triggers resolve immediately through the real trigger machinery.
        Trigger trigger = TriggerHandler.parseTrigger("Mode$ " + mode
                + " | TriggerZones$ Battlefield | Static$ True | CounterType$ " + type
                + (mode.equals("CounterAddedAll")
                        ? " | Valid$ " + (type == CounterEnumType.POISON ? "Player" : "Card") : ""), host, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 1", host));
        host.addTrigger(trigger);
    }

    private void prepare(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private GameEntityCounterTable putCounters(Card card, CounterEnumType type, int amount) {
        GameEntityCounterTable table = new GameEntityCounterTable();
        card.addCounter(type, amount, card.getController(), table);
        table.replaceCounterEffect(card.getGame(), null);
        return table;
    }
}
