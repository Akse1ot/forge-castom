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
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

public class ResonanceNestedRollbackTest extends AITest {
    @DataProvider
    public Object[][] originsAndFailures() {
        return new Object[][] {
                {ZoneType.Hand, false, false}, {ZoneType.Hand, true, false},
                {ZoneType.Graveyard, false, false}, {ZoneType.Graveyard, true, false},
                {ZoneType.Exile, false, false}, {ZoneType.Exile, true, false},
                {ZoneType.Hand, false, true}, {ZoneType.Hand, true, true},
                {ZoneType.Graveyard, false, true}, {ZoneType.Graveyard, true, true},
                {ZoneType.Exile, false, true}, {ZoneType.Exile, true, true}
        };
    }

    @Test(dataProvider = "originsAndFailures")
    public void restoresOriginalCardAndPositionThenRetriesWithoutTouchingOuterWork(
            ZoneType origin, boolean playStack, boolean invalidTargets) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card outerMana = addCard("Forest", ai);
        Card watcher = addCard("Darksteel Relic", ai);
        SpellAbility outer = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ G | Defined$ You | LifeAmount$ 1", watcher);
        outer.setActivatingPlayer(ai);
        game.getAction().checkStateEffects(true);
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(ai, outer, null));
        assertTrue(outerMana.isTapped());
        assertEquals(outer.getTotalManaSpent(), 1);

        Card pending = addCardToZone("Fiery Bolero", ai, ZoneType.Exile);
        ai.addPendingResonance(pending);
        addCards("Mountain", invalidTargets ? 1 : 3, ai);
        Card before = addCardToZone("Ornithopter", ai, origin);
        Card original = addCardToZone(invalidTargets ? "Lightning Bolt" : "Fireball", ai, origin);
        Card after = addCardToZone("Memnite", ai, origin);
        Zone sourceZone = ai.getZone(origin);
        int position = sourceZone.getCards().indexOf(original);
        SpellAbility spell = original.getFirstSpellAbility();
        // Explicitly permit the selected fixture ability to be cast from its original zone.
        spell.getRestrictions().setZone(origin);
        spell.setActivatingPlayer(ai);
        if (!invalidTargets) {
            spell.setXManaCostPaid(2);
            spell.getTargets().add(opponent);
        }
        ai.setNextSpellAddColors(ColorSet.G);

        Card bear = addCard("Grizzly Bears", opponent);
        watcher.addRemembered(bear);
        Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.IsRemembered"
                        + " | TriggerZones$ Battlefield", watcher, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 2", watcher));
        watcher.addTrigger(trigger);
        game.getAction().checkStaticAbilities();
        game.getTriggerHandler().resetActiveTriggers();
        game.getStack().freezeStack(outer);
        SpellAbility queued = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", watcher);
        queued.setActivatingPlayer(ai);
        game.getStack().add(queued);
        assertTrue(game.getAction().destroy(bear, null, false, null));
        Card foreignGraveyardCard = game.findById(bear.getId());
        assertEquals(ai.getLife(), 20, "The external ChangesZone trigger must still be waiting");

        AtomicReference<Card> stackCopy = new AtomicReference<>();
        PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer()) {
            @Override
            public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                    List<String> options, Card cardToShow, Map<String, Object> params) {
                stackCopy.compareAndSet(null, sa.getHostCard());
                return true;
            }
        };
        ai.runWithController(() -> {
            assertFalse(cast(ai, spell, playStack));
            assertNotNull(stackCopy.get());
            assertNotSame(stackCopy.get(), original, "The fixture must exercise moveToStack's replacement object");
            assertSame(game.findById(original.getId()), original);
            assertSame(spell.getHostCard(), original);
            assertSame(game.getCardState(spell.getHostCard()), original);
            assertSame(sourceZone.getCards().get(position), original);
            assertTrue(sourceZone.contains(before));
            assertTrue(sourceZone.contains(after));
            assertEquals(countId(game, original.getId()), 1L, "There must be one live object with this ID");
            assertTrue(game.getStackZone().isEmpty(), "The outer abilities are not spell cards");
            assertEquals(original.getColor(), ColorSet.R);
            assertNull(original.getCastSA());
            assertNull(original.getCastFrom());
            assertNull(spell.getSubAbility());
            assertEquals(ai.getPendingResonance(), List.of(pending));
            assertTrue(pending.isInZone(ZoneType.Exile));
            assertEquals(ai.getNextSpellAddColors(), ColorSet.G);
            assertEquals(game.getStack().size(), 1);
            assertTrue(game.getStack().isFrozen());
            assertEquals(ai.getLife(), 20, "The waiting external trigger must not run during rollback");
            assertTrue(outerMana.isTapped(), "Refunding the inner cost must not undo the outer cost");
            assertEquals(outer.getTotalManaSpent(), 1);
            assertSame(game.findById(bear.getId()), foreignGraveyardCard);

            if (invalidTargets) {
                spell.getTargets().add(opponent);
            } else {
                spell.setXManaCostPaid(2);
                addCard("Mountain", ai);
            }
            assertTrue(cast(ai, spell, playStack), "The same restored SA must be castable again");
            assertEquals(countId(game, original.getId()), 1L);
            assertTrue(ai.getPendingResonance().isEmpty());
            assertTrue(game.getStack().isFrozen());
            assertTrue(outerMana.isTapped());
            assertEquals(outer.getTotalManaSpent(), 1);
            game.getStack().unfreezeStack();
            game.getStack().addAllTriggeredAbilitiesToStack();
            assertEquals(game.getStack().size(), 4, "Outer, queued, retried and triggered abilities must all survive");
            for (int remaining = 4; remaining > 0; remaining--) {
                game.getStack().resolveStack();
            }
            assertTrue(game.getStack().isEmpty());
            assertEquals(ai.getLife(), 24);
            assertEquals(opponent.getLife(), invalidTargets ? 14 : 15);
            assertTrue(game.findById(original.getId()).isInZone(ZoneType.Graveyard));
        }, controller);
    }

    private long countId(Game game, int id) {
        long count = 0;
        for (ZoneType zone : ZoneType.values()) {
            if (zone == ZoneType.Flashback) {
                continue; // This is a virtual view of other zones, not another physical copy.
            }
            count += game.getCardsIn(zone).stream().filter(card -> card.getId() == id).count();
        }
        return count;
    }

    private boolean cast(Player player, SpellAbility spell, boolean playStack) {
        Game game = player.getGame();
        game.copyLastState();
        game.getAction().checkStateEffects(true);
        return playStack ? ComputerUtil.playStack(spell, player, game)
                : ComputerUtil.handlePlayingSpellAbility(player, spell, null);
    }
}
