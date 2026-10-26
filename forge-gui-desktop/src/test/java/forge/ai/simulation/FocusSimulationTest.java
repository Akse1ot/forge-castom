package forge.ai.simulation;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/** Exercises pending Focus through its paid activation, upkeep trigger and free AI cast. */
public class FocusSimulationTest extends AITest {
    @DataProvider
    public Object[][] copyBackends() {
        return new Object[][] {{false}, {true}};
    }

    @Test
    public void originalFocusCastsAtTheNextOwnUpkeep() throws ReflectiveOperationException {
        FocusFixture fixture = prepareFocus();
        assertFocusResolves(fixture.game(), fixture.player(), fixture.card());
    }

    @Test(dataProvider = "copyBackends")
    public void pendingFocusSurvivesCopy(boolean snapshot) throws ReflectiveOperationException {
        FocusFixture fixture = prepareFocus();
        Game original = fixture.game();
        OriginalState before = rememberOriginal(fixture);
        Game copied = copy(original, snapshot);
        Player copiedPlayer = copied.getPlayer(fixture.player().getId());
        Card copiedCard = copied.findById(fixture.card().getId());
        assertCopiedState(fixture, copied, copiedPlayer, copiedCard);

        String backend = snapshot ? "GameSnapshot.makeCopy" : "GameCopier.makeCopy";
        SoftAssert checks = new SoftAssert();
        List<Trigger> pending = delayedTriggers(copied);
        checks.assertEquals(pending.size(), 1, backend + " must preserve the pending Focus delayed trigger");
        if (pending.size() == 1) {
            assertFocusTrigger(pending.get(0), copiedPlayer, copiedCard);
            checks.assertNotSame(pending.get(0), fixture.trigger(), "The trigger must belong to the copy");
            checks.assertNotSame(pending.get(0).getTriggerRemembered(),
                    fixture.trigger().getTriggerRemembered(), "Remembered state must not be shared");
        }

        // Continue after the missing queue entry to demonstrate the actual lost cast and isolation.
        CastObservation result = advanceToOwnUpkeep(copied, copiedPlayer, copiedCard);
        checkResolvedFocus(checks, copied, copiedPlayer, copiedCard, result);
        assertOriginalUnchanged(fixture, before);
        // The same real activation must still work in the untouched source game.
        assertFocusResolves(original, fixture.player(), fixture.card());
        checks.assertAll();
    }

    @Test(dataProvider = "copyBackends")
    public void focusedFlagWithoutPendingTriggerDoesNotCreateACast(boolean snapshot)
            throws ReflectiveOperationException {
        Game original = initAndCreateGame();
        Player player = original.getPlayers().get(1);
        fillLibrary(player, 8);
        fillLibrary(original.getPlayers().get(0), 8);
        Card card = addCardToZone("Contemplate", player, ZoneType.Exile);
        // Negative control only: a flag is not an unconsumed delayed trigger.
        card.setFocused(true);
        assertTrue(delayedTriggers(original).isEmpty());
        Game copied = copy(original, snapshot);
        Player copiedPlayer = copied.getPlayer(player.getId());
        Card copiedCard = copied.findById(card.getId());
        assertNotSame(copiedCard, card);
        assertTrue(copiedCard.isFocused());
        assertTrue(delayedTriggers(copied).isEmpty());

        CastObservation result = advanceToOwnUpkeep(copied, copiedPlayer, copiedCard);
        assertEquals(result.casts, 0);
        assertSame(copied.findById(card.getId()), copiedCard);
        assertTrue(copiedCard.isInZone(ZoneType.Exile));
        assertEquals(copiedPlayer.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(copiedPlayer.getCardsIn(ZoneType.Hand).isEmpty());
        assertSame(original.findById(card.getId()), card);
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(delayedTriggers(original).isEmpty());
        assertEquals(original.getPhaseHandler().getPhase(), PhaseType.MAIN1);
    }

    private FocusFixture prepareFocus() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        assertEquals(game.getPlayers().size(), 2);
        assertTrue(player.getController().isAI());
        fillLibrary(player, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        Card card = addCardToZone("Contemplate", player, ZoneType.Hand);
        assertTrue(card.hasKeyword(Keyword.FOCUS));
        assertFalse(card.isFocused());
        assertTrue(delayedTriggers(game).isEmpty());
        SpellAbility focus = card.getSpellAbilities().stream()
                .filter(sa -> sa instanceof AbilityStatic && sa.getDescription().startsWith("Focus ("))
                .findFirst().orElseThrow(() -> new AssertionError("Missing printed Focus activation"));
        focus.setActivatingPlayer(player);
        assertTrue(focus.canPlay());
        // Seed only the activation's mana, with no lands that could pay for the upkeep spell.
        player.getManaPool().addMana(new Mana(MagicColor.BLUE, card, null, player),
                new Mana(MagicColor.BLUE, card, null, player),
                new Mana(MagicColor.BLUE, card, null, player));
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, focus, null));
        assertEquals(focus.getTotalManaSpent(), 3, "Focus must pay the printed {2}{U} cost");
        assertEquals(player.getManaPool().totalMana(), 0);
        assertTrue(game.getStack().isEmpty(), "The Focus special action resolves without a stack entry");
        Card exiled = game.findById(card.getId());
        assertTrue(exiled.isInZone(ZoneType.Exile));
        assertTrue(exiled.isFocused());
        assertTrue(player.getCardsIn(ZoneType.Hand).isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertSame(game.getPhaseHandler().getPlayerTurn(), player);
        List<Trigger> pending = delayedTriggers(game);
        assertEquals(pending.size(), 1, "Paid Focus must register its real delayed trigger");
        assertFocusTrigger(pending.get(0), player, exiled);
        return new FocusFixture(game, player, exiled, pending.get(0));
    }

    private Game copy(Game original, boolean snapshot) {
        return snapshot ? new GameSnapshot(original).makeCopy() : new GameCopier(original).makeCopy();
    }

    private void assertCopiedState(FocusFixture fixture, Game copied, Player player, Card card) {
        assertNotSame(copied, fixture.game());
        assertNotSame(player, fixture.player());
        assertNotSame(card, fixture.card());
        assertEquals(card.getId(), fixture.card().getId());
        assertSame(card.getGame(), copied);
        assertSame(player.getGame(), copied);
        assertSame(card.getOwner(), player);
        assertTrue(card.isInZone(ZoneType.Exile));
        assertTrue(card.isFocused());
        assertEquals(player.getManaPool().totalMana(), 0);
        assertTrue(player.getCardsIn(ZoneType.Battlefield).isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(player.getCardsIn(ZoneType.Hand).isEmpty());
        assertTrue(copied.getStack().isEmpty());
        assertEquals(copied.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertSame(copied.getPhaseHandler().getPlayerTurn(), player);
    }

    private void assertFocusTrigger(Trigger trigger, Player player, Card card) {
        assertSame(trigger.getHostCard(), card);
        assertEquals(trigger.getParam("Phase"), "Upkeep");
        assertEquals(trigger.getParam("ValidPlayer"), "You");
        assertEquals(trigger.getTriggerRemembered().size(), 1);
        assertSame(trigger.getTriggerRemembered().get(0), card);
        assertSame(trigger.getSpawningAbility().getHostCard(), card);
        assertSame(trigger.getSpawningAbility().getActivatingPlayer(), player);
        SpellAbility execute = trigger.getOverridingAbility();
        assertSame(execute.getHostCard(), card);
        assertSame(execute.getActivatingPlayer(), player);
        assertEquals(execute.getParam("Focus"), "True");
        assertEquals(execute.getParam("WithoutManaCost"), "True");
        assertEquals(execute.getParam("Defined"), "DelayTriggerRememberedLKI");
    }

    private CastObservation advanceToOwnUpkeep(Game game, Player player, Card card) {
        CastObservation result = new CastObservation();
        Runnable resolver = () -> resolveStack(game, card, result);
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, resolver));
        assertNotSame(game.getPhaseHandler().getPlayerTurn(), player);
        resolver.run();
        assertEquals(result.casts, 0, "The opponent's upkeep must not cast our Focus card");
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, resolver));
        assertSame(game.getPhaseHandler().getPlayerTurn(), player);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.UPKEEP);
        resolver.run();
        return result;
    }

    private void resolveStack(Game game, Card card, CastObservation result) {
        game.getAction().checkStateEffects(false);
        game.getStack().addAllTriggeredAbilitiesToStack();
        int guard = 0;
        while (!game.getStack().isEmpty() && !game.isGameOver()) {
            assertTrue(++guard < 20, "Focus must not create a repeated cast loop");
            SpellAbility top = game.getStack().peekAbility();
            if (top.isSpell() && top.getHostCard().getId() == card.getId()) {
                result.casts++;
                result.focused = top.isFocused();
                result.free = top.hasParam("WithoutManaCost") && top.getTotalManaSpent() == 0;
            }
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(false);
            game.getStack().addAllTriggeredAbilitiesToStack();
        }
    }

    private void assertFocusResolves(Game game, Player player, Card card) throws ReflectiveOperationException {
        CastObservation result = advanceToOwnUpkeep(game, player, card);
        SoftAssert checks = new SoftAssert();
        checkResolvedFocus(checks, game, player, card, result);
        checks.assertAll();
    }

    private void checkResolvedFocus(SoftAssert checks, Game game, Player player, Card card,
            CastObservation result) throws ReflectiveOperationException {
        checks.assertEquals(result.casts, 1, "The pending Focus must produce exactly one actual spell cast");
        checks.assertTrue(result.focused, "The spell on the stack must carry SpellAbility.focused");
        checks.assertTrue(result.free, "The spell must be cast without mana payment or mana sources");
        checks.assertEquals(player.getCardsIn(ZoneType.Library).size(), 6, "Contemplate must draw two cards");
        checks.assertEquals(player.getCardsIn(ZoneType.Hand).size(), 2,
                "Focused Contemplate must skip its NotFocused discard effect");
        checks.assertTrue(game.findById(card.getId()).isInZone(ZoneType.Graveyard),
                "The resolved Focus spell must leave Exile for the graveyard");
        checks.assertTrue(delayedTriggers(game).isEmpty(), "Focus must consume its delayed trigger");
        checks.assertTrue(game.getStack().isEmpty());
        checks.assertEquals(player.getManaPool().totalMana(), 0);
    }

    private OriginalState rememberOriginal(FocusFixture fixture) throws ReflectiveOperationException {
        List<List<Card>> zones = new ArrayList<>();
        for (Player player : fixture.game().getPlayers()) {
            for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
                zones.add(List.copyOf(player.getCardsIn(zone)));
            }
        }
        return new OriginalState(zones, fixture.game().getTimestamp(), delayedTriggers(fixture.game()),
                fixture.game().getPhaseHandler().getTurn());
    }

    private void assertOriginalUnchanged(FocusFixture fixture, OriginalState before)
            throws ReflectiveOperationException {
        OriginalState after = rememberOriginal(fixture);
        assertEquals(after.timestamp(), before.timestamp());
        assertEquals(after.turn(), before.turn());
        assertEquals(after.zones(), before.zones());
        for (int i = 0; i < before.zones().size(); i++) {
            for (int j = 0; j < before.zones().get(i).size(); j++) {
                assertSame(after.zones().get(i).get(j), before.zones().get(i).get(j));
            }
        }
        assertEquals(after.pending().size(), 1);
        assertSame(after.pending().get(0), fixture.trigger());
        assertFocusTrigger(fixture.trigger(), fixture.player(), fixture.card());
        assertTrue(fixture.card().isFocused());
        assertSame(fixture.game().findById(fixture.card().getId()), fixture.card());
        assertEquals(fixture.game().getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertSame(fixture.game().getPhaseHandler().getPlayerTurn(), fixture.player());
        assertTrue(fixture.game().getStack().isEmpty());
        for (Player player : fixture.game().getPlayers()) {
            assertEquals(player.getManaPool().totalMana(), 0);
            assertEquals(player.getLife(), 20);
        }
    }

    private List<Trigger> delayedTriggers(Game game) throws ReflectiveOperationException {
        // No read-only production accessor exists. Never expose or mutate the handler's list.
        Field field = TriggerHandler.class.getDeclaredField("delayedTriggers");
        field.setAccessible(true);
        List<?> pending = (List<?>) field.get(game.getTriggerHandler());
        List<Trigger> result = new ArrayList<>();
        for (Object trigger : pending) {
            result.add((Trigger) trigger);
        }
        return List.copyOf(result);
    }

    private record FocusFixture(Game game, Player player, Card card, Trigger trigger) { }

    private record OriginalState(List<List<Card>> zones, long timestamp, List<Trigger> pending, int turn) { }

    private static class CastObservation {
        private int casts;
        private boolean focused;
        private boolean free;
    }
}
