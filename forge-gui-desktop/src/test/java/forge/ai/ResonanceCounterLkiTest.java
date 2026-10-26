package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.player.Player;
import forge.game.keyword.Keyword;
import forge.game.replacement.ReplaceMoved;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.NextSpellColorHelper;
import forge.game.spellability.ResonanceHelper;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerCountered;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

public class ResonanceCounterLkiTest extends AITest {
    @DataProvider
    public Object[][] colorsAndTiming() {
        return new Object[][] {
                {false, false, false}, {false, true, false}, {true, false, false}, {true, true, false},
                {false, false, true}, {false, true, true}, {true, false, true}, {true, true, true}
        };
    }

    @DataProvider
    public Object[][] withNextColor() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "colorsAndTiming")
    public void counteredObjectsKeepTheirCharacteristicsThroughCleanup(
            boolean resonance, boolean nextColor, boolean deferred) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        if (resonance) {
            pending(ai);
        }
        if (nextColor) {
            ai.setNextSpellAddColors(ColorSet.R);
        }
        Card watcher = addCard("Darksteel Relic", ai);
        AtomicReference<Map<AbilityKey, Object>> event = new AtomicReference<>();
        AtomicReference<SpellAbility> triggered = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger();
        Map<String, String> captureParams = Map.of("Mode", "Countered", "ValidCard", "Card",
                "ValidSA", "Spell", "TriggerZones", "Battlefield", "Static", "True");
        TriggerCountered standard = new TriggerCountered(captureParams, watcher, true);
        Trigger capture = new Trigger(captureParams, watcher, true) {
            @Override
            public TriggerType getMode() {
                return TriggerType.Countered;
            }

            @Override
            public boolean performTest(Map<AbilityKey, Object> params) {
                event.compareAndSet(null, new EnumMap<>(params));
                return standard.performTest(params);
            }

            @Override
            public void setTriggeringObjects(SpellAbility sa, Map<AbilityKey, Object> params) {
                standard.setTriggeringObjects(sa, params);
                triggered.set(sa);
                eventCount.incrementAndGet();
            }

            @Override
            public String getImportantStackObjects(SpellAbility sa) {
                return standard.getImportantStackObjects(sa);
            }
        };
        capture.setOverridingAbility(AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 1", watcher));
        watcher.addTrigger(capture);
        colorWatcher(watcher, "Blue", 2);
        colorWatcher(watcher, "Red", 4);
        colorWatcher(watcher, "White", 8); // Negative control, including after the old host is changed to white.
        SpellAbility original = spell(ai, "Nourish");
        original.setXManaCostPaid(3);
        assertTrue(cast(ai, original));
        Card oldHost = original.getHostCard();
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(original);
        if (deferred) {
            SpellAbility outer = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", watcher);
            outer.setActivatingPlayer(ai);
            game.getStack().freezeStack(outer);
        }
        // Isolate immediate versus frozen Countered delivery; the target itself was cast and paid normally.
        AbilityUtils.resolve(counter);
        assertTrue(game.getStack().isEmpty());
        assertTrue(blank(original, "ResonanceColorTS"));
        assertTrue(blank(original, "_NextSpellAddColorTS"));
        assertEquals(oldHost.getColor(), ColorSet.G);
        Card current = game.findById(oldHost.getId());
        assertTrue(current.isInZone(ZoneType.Graveyard));
        assertEquals(current.getColor(), ColorSet.G);
        if (deferred) {
            assertNull(event.get(), "Countered must really be waiting until after cleanup");
            assertEquals(ai.getLife(), 20);
        }
        if (resonance || nextColor) {
            oldHost.setColor(ColorSet.W);
            original.setXManaCostPaid(99);
        }
        // A second counter attempt cannot emit a second event for an already removed target.
        AbilityUtils.resolve(counter);
        if (deferred) {
            game.getStack().unfreezeStack();
        }
        assertEquals(ai.getLife(), 21 + (resonance ? 2 : 0) + (nextColor ? 4 : 0));
        assertEquals(eventCount.get(), 1);
        Card eventCard = (Card) event.get().get(AbilityKey.Card);
        SpellAbility eventSA = (SpellAbility) event.get().get(AbilityKey.SpellAbility);
        assertSame(event.get().get(AbilityKey.Cause), counter);
        assertEquals(eventCard.getId(), oldHost.getId());
        assertEquals(eventSA.getId(), original.getId());
        assertSame(eventSA.getActivatingPlayer(), ai);
        assertSame(eventCard.getController(), ai);
        assertSame(eventSA.getHostCard(), eventCard);
        assertTrue(eventSA.isSpell());
        assertEquals(eventSA.getXManaCostPaid(), Integer.valueOf(3));
        assertEquals(eventSA.getTotalManaSpent(), 2);
        assertEquals(eventCard.getCMC(), 2);
        assertEquals(eventCard.getColor().hasBlue(), resonance);
        assertEquals(eventCard.getColor().hasRed(), nextColor);
        assertFalse(eventCard.getColor().hasWhite());
        assertSame(triggered.get().getTriggeringObject(AbilityKey.Card), eventCard);
        assertSame(triggered.get().getTriggeringObject(AbilityKey.SpellAbility), eventSA);
        if (resonance || nextColor) {
            assertNotSame(eventCard, oldHost);
            assertNotSame(eventSA, original);
        } else {
            assertSame(eventCard, oldHost, "Ordinary Countered Card identity must remain unchanged");
            assertSame(eventSA, original, "Ordinary Countered SA identity must remain unchanged");
        }
        assertEquals(current.getColor(), ColorSet.G);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 8);
    }

    @Test(dataProvider = "withNextColor")
    public void standardSummoningTrapConsumerStillTracksCounteredCreatures(boolean temporaryColor) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        Card trap = addCardToZone("Summoning Trap", ai, ZoneType.Hand);
        if (temporaryColor) {
            ai.setNextSpellAddColors(ColorSet.R);
        }
        SpellAbility creature = spell(ai, "Grizzly Bears");
        assertTrue(cast(ai, creature));
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(creature);
        assertTrue(cast(opponent, counter));
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(trap.getSVar("SetTrap"), "Number$1");
        assertEquals(game.findById(creature.getHostCard().getId()).getColor(), ColorSet.G);
    }

    @Test
    public void movementReplacementStillSeesTheColoredStackAbility() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        pending(ai);
        Card observer = addCard("Darksteel Relic", ai);
        AtomicBoolean sawBlue = new AtomicBoolean();
        observer.addReplacementEffect(new ReplaceMoved(Map.of("Event", "Moved", "ActiveZones", "Battlefield",
                "Origin", "Stack", "Destination", "Graveyard", "ValidStackSa", "Spell.Blue"), observer, true) {
            @Override
            public ReplacementType getMode() {
                return ReplacementType.Moved;
            }

            @Override
            public boolean canReplace(Map<AbilityKey, Object> params) {
                if (super.canReplace(params)) {
                    sawBlue.set(true);
                }
                return false; // Observe the real replacement check without changing its result.
            }
        });
        SpellAbility target = spell(ai, "Nourish");
        assertTrue(cast(ai, target));
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(target);
        assertTrue(cast(opponent, counter));
        game.getStack().resolveStack();
        assertTrue(sawBlue.get(), "Moved replacement must read StackSa before Resonance is cleaned");
        assertTrue(blank(target, "ResonanceColorTS"));
        assertEquals(target.getHostCard().getColor(), ColorSet.G);
    }

    @Test(dataProvider = "withNextColor")
    public void multanisPresenceStillRecognizesTheCounteredCardsCaster(boolean temporaryColors) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        addCard("Multani's Presence", ai);
        if (temporaryColors) {
            pending(ai);
            ai.setNextSpellAddColors(ColorSet.R);
        }
        SpellAbility target = spell(ai, "Nourish");
        assertTrue(cast(ai, target));
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(target);
        assertTrue(cast(opponent, counter));
        game.getStack().resolveStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(game.getStack().size(), 1);
        game.getStack().resolveStack();
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7, "Only Multani's Presence draws a card");
        assertEquals(ai.getLife(), 20);
    }

    @Test
    public void delayStillUpdatesTheActualRememberedCardInExile() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        pending(ai);
        SpellAbility target = spell(ai, "Nourish");
        assertTrue(cast(ai, target));
        SpellAbility counter = spell(opponent, "Delay");
        counter.getTargets().add(target);
        assertTrue(cast(opponent, counter));
        game.getStack().resolveStack();
        Card exiled = game.findById(target.getHostCard().getId());
        assertTrue(exiled.isInZone(ZoneType.Exile));
        assertEquals(exiled.getCounters(CounterEnumType.TIME), 3);
        assertTrue(exiled.hasKeyword(Keyword.SUSPEND));
        assertEquals(exiled.getColor(), ColorSet.G);
        assertEquals(target.getHostCard().getColor(), ColorSet.G);
        assertTrue(blank(target, "ResonanceColorTS"));
    }

    @Test
    public void restInPeaceStillReplacesTheCounteredCardsDestination() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        addCard("Rest in Peace", opponent);
        pending(ai);
        SpellAbility target = spell(ai, "Nourish");
        assertTrue(cast(ai, target));
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(target);
        assertTrue(cast(opponent, counter));
        game.getStack().resolveStack();
        Card exiled = game.findById(target.getHostCard().getId());
        assertTrue(exiled.isInZone(ZoneType.Exile));
        assertEquals(exiled.getColor(), ColorSet.G);
        assertTrue(blank(target, "ResonanceColorTS"));
    }

    @Test
    public void repeatedRemovalDoesNotCleanANewerCastOrEmitAnotherRemoval() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        addCards("Forest", 2, ai);
        Card firstPending = pending(ai);
        SpellAbility spell = spell(ai, "Nourish");
        assertTrue(cast(ai, spell));
        SpellAbilityStackInstance oldEntry = game.getStack().peek();
        RemovalObserver observer = new RemovalObserver();
        game.subscribeToEvents(observer);
        game.getStack().remove(oldEntry);
        assertEquals(observer.count.get(), 1);
        assertTrue(blank(spell, "ResonanceColorTS"));
        assertEquals(spell.getHostCard().getColor(), ColorSet.G);
        game.getStack().remove(oldEntry);
        game.getStack().remove(spell.getHostCard());
        ResonanceHelper.clearDirectColorOverrideForSpellCast(spell);
        NextSpellColorHelper.clearDirectColorOverrideForSpellCast(spell);
        assertEquals(observer.count.get(), 1);

        Card returned = game.getAction().moveToHand(spell.getHostCard(), null);
        spell.setHostCard(returned);
        addCards("Forest", 2, ai);
        Card secondPending = pending(ai);
        assertTrue(cast(ai, spell));
        SpellAbilityStackInstance newEntry = game.getStack().peek();
        assertNotSame(newEntry, oldEntry);
        assertSame(newEntry.getSpellAbility(), spell);
        game.getStack().remove(oldEntry);
        assertEquals(observer.count.get(), 1);
        assertSame(game.getStack().peek(), newEntry);
        assertFalse(blank(spell, "ResonanceColorTS"));
        assertTrue(spell.getHostCard().getColor().hasBlue());
        game.getStack().resolveStack();
        assertEquals(observer.count.get(), 2);
        assertEquals(ai.getLife(), 26);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
        assertTrue(blank(spell, "ResonanceColorTS"));
        assertEquals(game.getCardState(firstPending).getColor(), ColorSet.U);
        assertEquals(game.getCardState(secondPending).getColor(), ColorSet.U);
    }

    public static final class RemovalObserver {
        private final AtomicInteger count = new AtomicInteger();

        @Subscribe
        public void removed(GameEventSpellRemovedFromStack event) {
            count.incrementAndGet();
        }
    }

    private void colorWatcher(Card host, String color, int life) {
        Trigger trigger = TriggerHandler.parseTrigger("Mode$ Countered | ValidCard$ Card." + color
                + " | ValidSA$ Spell." + color + " | TriggerZones$ Battlefield | Static$ True", host, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ " + life, host));
        host.addTrigger(trigger);
    }

    private boolean blank(SpellAbility spell, String key) {
        String value = spell.getSVar(key);
        return value == null || value.isEmpty();
    }

    private Card pending(Player player) {
        Card card = addCardToZone("Ethereal Chord", player, ZoneType.Exile);
        player.addPendingResonance(card);
        return card;
    }

    private SpellAbility spell(Player player, String name) {
        SpellAbility sa = addCardToZone(name, player, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        return sa;
    }

    private boolean cast(Player player, SpellAbility sa) {
        player.getGame().copyLastState();
        player.getGame().getAction().checkStateEffects(true);
        return ComputerUtil.handlePlayingSpellAbility(player, sa, null);
    }
}
