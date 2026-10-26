package forge.ai;

import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

/** Boundary regressions: keep failures visible until the corresponding production defects are fixed. */
public class ResonanceBoundaryReviewTest extends AITest {
    @DataProvider
    public Object[][] targetingFailures() {
        return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
    }

    @DataProvider
    public Object[][] castPaths() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "targetingFailures")
    public void earlyTargetingFailureClearsPreparedNextSpellColor(boolean playStack, boolean nested) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        Card pending = pending(ai, "Ethereal Chord");
        Card mountain = addCard("Mountain", ai);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        ai.setNextSpellAddColors(ColorSet.G);
        AtomicReference<Card> preparedHost = new AtomicReference<>();
        AtomicInteger confirmations = new AtomicInteger();
        PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer()) {
            @Override
            public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                    List<String> options, Card cardToShow, Map<String, Object> params) {
                confirmations.incrementAndGet();
                preparedHost.set(sa.getHostCard());
                assertTrue(sa.getHostCard().getColor().hasGreen(), "NextSpellColor must really be prepared");
                assertFalse(blank(sa, "_NextSpellAddColorTS"));
                return true;
            }
        };
        if (nested) {
            game.getStack().freezeStack(outerAbility(ai));
        }
        ai.runWithController(() -> assertFalse(cast(ai, spell, playStack)), controller);
        assertEquals(confirmations.get(), 1, "The failure must occur after Resonance preparation");
        assertNotNull(preparedHost.get());
        assertFalse(spell.isTargetNumberValid(), "The main Bolt intentionally has no target");
        assertFalse(mountain.isTapped(), "Target failure must not attempt mana payment");
        assertEquals(ai.getPendingResonance(), List.of(pending));
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertEquals(ai.getNextSpellAddColors(), ColorSet.G, "The unspent player effect must survive");
        assertEquals(game.getStack().isFrozen(), nested);
        assertTrue(game.getStack().isEmpty());
        assertNull(spell.getSubAbility(), "Resonance effects themselves must be rolled back");

        SoftAssert checks = new SoftAssert();
        checks.assertTrue(blank(spell, "_NextSpellAddColorTS"),
                "Rejected cast must clear NextSpellColor timestamp; actual=" + spell.getSVar("_NextSpellAddColorTS"));
        checks.assertTrue(blank(spell, "_NextSpellAddColorMask"),
                "Rejected cast must clear NextSpellColor mask; actual=" + spell.getSVar("_NextSpellAddColorMask"));
        checks.assertEquals(preparedHost.get().getColor(), ColorSet.R,
                "The actual prepared host must lose the temporary green override");
        checks.assertEquals(spell.getHostCard().getColor(), ColorSet.R,
                "The current SA host must also have its printed color");
        if (nested) {
            game.getStack().unfreezeStack();
        }
        checks.assertAll();
    }

    @Test(dataProvider = "castPaths")
    public void nestedPaymentFailureRestoresTheSourceAndPreservesTheOuterStack(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card pending = pending(ai, "Fiery Bolero");
        List<Card> mountains = addCards("Mountain", 3, ai);
        SpellAbility spell = spell(ai, "Fireball");
        spell.setXManaCostPaid(2);
        spell.getTargets().add(opponent);
        game.getAction().checkStateEffects(true);
        assertTrue(ComputerUtilCost.canPayCost(spell, ai, false),
                "The pre-Resonance Fireball must pass playStack's affordability check");
        ai.setNextSpellAddColors(ColorSet.G);

        SpellAbility outer = outerAbility(ai);
        game.getStack().freezeStack(outer);
        SpellAbility queued = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", outer.getHostCard());
        queued.setActivatingPlayer(ai);
        game.getStack().add(queued);
        assertTrue(game.getStack().isEmpty(), "The outer ability must really be waiting in the frozen stack");
        AtomicInteger preparedEffects = new AtomicInteger();
        PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer()) {
            @Override
            public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                    List<String> options, Card cardToShow, Map<String, Object> params) {
                return true;
            }

            @Override
            public boolean chooseTargetsFor(SpellAbility effects) {
                boolean result = super.chooseTargetsFor(effects);
                assertTrue(result);
                assertEquals(effects.getTargets().size(), 1);
                assertTrue(game.getStack().hasLegalTargeting(effects.getRootAbility()),
                        "All target counts must be legal before the failed payment");
                preparedEffects.incrementAndGet();
                return result;
            }
        };
        ai.runWithController(() -> assertFalse(cast(ai, spell, playStack)), controller);
        assertEquals(preparedEffects.get(), 1, "The added target must cause Fireball's fourth-mana surcharge");
        assertTrue(spell.isTargetNumberValid(), "This is payment failure, not a missing main target");
        assertSame(spell.getTargets().getFirstTargetedPlayer(), opponent);

        SoftAssert checks = new SoftAssert();
        checks.assertTrue(game.getCardState(spell.getHostCard()).isInZone(ZoneType.Hand),
                "Failed nested payment must return the source to Hand; actual=" + spell.getHostCard().getZone());
        checks.assertEquals(ai.getCardsIn(ZoneType.Stack).size(), 0,
                "A failed cast must not leave an orphaned Fireball in the Stack zone");
        checks.assertEquals(spell.getTotalManaSpent(), 0, "Spent-mana records must be refunded");
        checks.assertEquals(ai.getManaPool().totalMana(), 0, "Reversed basic mana abilities must not leave floating mana");
        for (Card mountain : mountains) {
            checks.assertFalse(mountain.isTapped(), "Each reversible mana source must be untapped");
        }
        checks.assertEquals(ai.getLife(), 20);
        checks.assertEquals(opponent.getLife(), 20, "Neither Fireball nor Resonance may resolve");
        checks.assertEquals(ai.getPendingResonance(), List.of(pending));
        checks.assertTrue(pending.isInZone(ZoneType.Exile));
        checks.assertNull(spell.getSubAbility());
        checks.assertTrue(blank(spell, "ResonanceCommitIds"));
        checks.assertTrue(blank(spell, "ResonancePrepared"));
        checks.assertTrue(blank(spell, "_NextSpellAddColorTS"), "Payment-failure path explicitly rolls back NextSpellColor");
        checks.assertFalse(spell.getHostCard().getColor().hasGreen());
        checks.assertEquals(ai.getNextSpellAddColors(), ColorSet.G);
        checks.assertTrue(game.getStack().isFrozen(), "Outer freeze must survive");
        game.getStack().unfreezeStack();
        assertEquals(game.getStack().size(), 1, "The pre-existing queued ability must survive the rejection");
        assertEquals(game.getStack().peekAbility().getHostCard().getId(), outer.getHostCard().getId());
        game.getStack().resolveStack();
        checks.assertEquals(ai.getLife(), 21, "The preserved outer ability must really resolve");
        checks.assertAll();
    }

    @Test(dataProvider = "castPaths")
    public void leavingTheStackClearsResonanceColorOverride(boolean countered) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        Card pending = pending(ai, "Ethereal Chord");
        addCards("Forest", 2, ai);
        SpellAbility spell = spell(ai, "Nourish");
        ai.setNextSpellAddColors(ColorSet.R);
        assertTrue(cast(ai, spell, false));
        Card stackHost = spell.getHostCard();
        assertTrue(stackHost.getColor().hasBlue());
        assertTrue(stackHost.getColor().hasRed());
        assertFalse(blank(spell, "ResonanceColorTS"));
        assertFalse(blank(spell, "_NextSpellAddColorTS"));
        assertTrue(ai.getPendingResonance().isEmpty());
        if (countered) {
            addCards("Island", 2, opponent);
            SpellAbility counter = spell(opponent, "Counterspell");
            counter.getTargets().add(spell);
            assertTrue(cast(opponent, counter, false));
            assertEquals(game.getStack().size(), 2);
        }
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getLife(), countered ? 20 : 26, "Positive control for Counterspell versus normal resolution");
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), countered ? 8 : 7);
        Card current = game.getCardState(stackHost);
        assertTrue(current.isInZone(ZoneType.Graveyard));
        assertTrue(game.getCardState(pending).isInZone(ZoneType.Graveyard), "Countering must not rearm Resonance");

        SoftAssert checks = new SoftAssert();
        checks.assertEquals(current.getColor(), ColorSet.G, "The current graveyard object must have printed colors");
        checks.assertTrue(blank(spell, "_NextSpellAddColorTS"), "NextSpellColor cleanup is the positive control");
        checks.assertFalse(stackHost.getColor().hasRed());
        checks.assertTrue(blank(spell, "ResonanceColorTS"),
                "Removed SA must release its Resonance override timestamp; actual=" + spell.getSVar("ResonanceColorTS"));
        checks.assertFalse(stackHost.getColor().hasBlue(),
                "The removed SA host must release its direct Resonance color override");
        checks.assertAll();
    }

    @Test
    public void aFizzledSpellUsesTheResolutionCleanupPath() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        pending(ai, "Ethereal Chord");
        addCard("Mountain", ai);
        Card bear = addCard("Grizzly Bears", opponent);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        spell.getTargets().add(bear);
        ai.setNextSpellAddColors(ColorSet.G);
        assertTrue(cast(ai, spell, false));
        Card stackHost = spell.getHostCard();
        assertFalse(blank(spell, "ResonanceColorTS"));
        assertTrue(game.getAction().destroy(bear, null, false, null));
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 8, "The whole spell must fizzle, including the draw");
        assertTrue(blank(spell, "ResonanceColorTS"));
        assertTrue(blank(spell, "_NextSpellAddColorTS"));
        assertEquals(stackHost.getColor(), ColorSet.R);
        assertEquals(game.getCardState(stackHost).getColor(), ColorSet.R);
        assertTrue(ai.getPendingResonance().isEmpty());
    }

    @Test
    public void counteredEventMustStillObserveTheSpellsResonanceColors() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        fillLibrary(ai, 8);
        pending(ai, "Ethereal Chord");
        addCards("Forest", 2, ai);
        addCards("Island", 2, opponent);
        Card watcher = addCard("Darksteel Relic", ai);
        Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ Countered | ValidCard$ Card.Blue | ValidSA$ Spell | TriggerZones$ Battlefield | Static$ True",
                watcher, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 1", watcher));
        watcher.addTrigger(trigger);
        SpellAbility spell = spell(ai, "Nourish");
        assertTrue(cast(ai, spell, false));
        assertTrue(spell.getHostCard().getColor().hasBlue());
        SpellAbility counter = spell(opponent, "Counterspell");
        counter.getTargets().add(spell);
        assertTrue(cast(opponent, counter, false));
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getLife(), 21, "Countered must see the blue stack object, not the green graveyard object");
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 8, "Nourish and its Resonance draw must not resolve");
        assertEquals(game.getCardState(spell.getHostCard()).getColor(), ColorSet.G);
    }

    private boolean blank(SpellAbility spell, String name) {
        String value = spell.getSVar(name);
        return value == null || value.isEmpty();
    }

    private SpellAbility outerAbility(Player player) {
        Card host = addCard("Darksteel Relic", player);
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", host);
        ability.setActivatingPlayer(player);
        return ability;
    }

    private Card pending(Player player, String name) {
        Card card = addCardToZone(name, player, ZoneType.Exile);
        player.addPendingResonance(card);
        return card;
    }

    private SpellAbility spell(Player player, String name) {
        SpellAbility ability = addCardToZone(name, player, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        return ability;
    }

    private boolean cast(Player player, SpellAbility ability, boolean playStack) {
        player.getGame().copyLastState();
        player.getGame().getAction().checkStateEffects(true);
        return playStack ? ComputerUtil.playStack(ability, player, player.getGame())
                : ComputerUtil.handlePlayingSpellAbility(player, ability, null);
    }
}
