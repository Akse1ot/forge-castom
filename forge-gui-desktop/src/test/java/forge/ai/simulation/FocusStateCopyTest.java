package forge.ai.simulation;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.ApiType;
import forge.game.card.Card;
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

import java.lang.reflect.Field;
import java.util.List;

import static org.testng.Assert.*;

public class FocusStateCopyTest extends AITest {
    @DataProvider
    public Object[][] backends() {
        return new Object[][] {{false}, {true}};
    }

    @DataProvider
    public Object[][] stackCases() {
        return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
    }

    @DataProvider
    public Object[][] focusedChoices() {
        return new Object[][] {{false}, {true}};
    }

    @DataProvider
    public Object[][] variants() {
        return new Object[][] {
                {"Absence of Gravity", false}, {"Absence of Gravity", true},
                {"Change of Perspective", false}, {"Change of Perspective", true},
                {"Circular Rage", false}, {"Circular Rage", true}
        };
    }

    @Test(dataProvider = "backends")
    public void secondCopyRetainsOneIndependentPendingEvent(boolean snapshot) throws ReflectiveOperationException {
        Game original = prepareGame();
        Player player = original.getPlayers().get(1);
        Card card = focus(original, player);
        Game first = copy(original, snapshot, false);
        Game second = copy(first, snapshot, false);
        Player copied = second.getPlayer(player.getId());
        Trigger sourceTrigger = pending(original).get(0);
        Trigger firstTrigger = pending(first).get(0);
        Trigger secondTrigger = pending(second).get(0);
        assertNotSame(secondTrigger, firstTrigger);
        assertNotSame(secondTrigger.getSpawningAbility(), firstTrigger.getSpawningAbility());
        assertNotSame(secondTrigger.getOverridingAbility(), firstTrigger.getOverridingAbility());
        assertSame(secondTrigger.getHostCard().getGame(), second);
        assertSame(secondTrigger.getSpawningAbility().getCardState().getCard().getGame(), second);
        assertSame(secondTrigger.getSpawningAbility().getAdditionalAbility("Execute").getHostCard().getGame(), second);
        assertNotSame(secondTrigger.getTriggerRemembered(), firstTrigger.getTriggerRemembered());

        nextOwnUpkeep(second, copied, false);
        assertEquals(copied.getCardsIn(ZoneType.Hand).size(), 2);
        assertTrue(second.findById(card.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(pending(second).isEmpty());
        nextOwnUpkeep(second, copied, false);
        assertEquals(copied.getCardsIn(ZoneType.Hand).size(), 3, "Only the normal draw step adds a card");
        assertEquals(copied.getCardsIn(ZoneType.Library).size(), 5);
        assertTrue(pending(second).isEmpty());
        assertSame(pending(original).get(0), sourceTrigger);
        assertSame(pending(first).get(0), firstTrigger);
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertEquals(first.getPlayer(player.getId()).getCardsIn(ZoneType.Library).size(), 8);
    }

    @Test(dataProvider = "backends")
    public void oldExileObjectCannotGrantANewObjectTheSameCast(boolean snapshot) throws ReflectiveOperationException {
        Game original = prepareGame();
        Player player = original.getPlayers().get(1);
        Card old = focus(original, player);
        Card returned = original.getAction().moveToHand(old, null);
        Card exiledAgain = original.getAction().exile(returned, null, null);
        assertEquals(exiledAgain.getId(), old.getId());
        assertNotSame(exiledAgain, old);
        assertFalse(old.equalsWithGameTimestamp(exiledAgain));
        assertEquals(pending(original).size(), 1);

        Game copied = copy(original, snapshot, false);
        Player copiedPlayer = copied.getPlayer(player.getId());
        nextOwnUpkeep(copied, copiedPlayer, false);
        assertTrue(copied.findById(old.getId()).isInZone(ZoneType.Exile));
        assertEquals(copiedPlayer.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(copiedPlayer.getCardsIn(ZoneType.Hand).isEmpty());
        assertEquals(pending(original).size(), 1);
    }

    @Test(dataProvider = "stackCases")
    public void castMarkerSurvivesStackCopyAndResolution(boolean snapshot, boolean focused)
            throws ReflectiveOperationException {
        Game original = prepareGame();
        Player player = original.getPlayers().get(1);
        Card card;
        if (focused) {
            card = focus(original, player);
            nextOwnUpkeep(original, player, true);
        } else {
            card = addCardToZone("Contemplate", player, ZoneType.Hand);
            addActivationMana(player, card);
            SpellAbility spell = card.getFirstSpellAbility();
            spell.setActivatingPlayer(player);
            original.copyLastState();
            assertTrue(ComputerUtil.handlePlayingSpellAbility(player, spell, null));
        }
        assertEquals(original.getStack().size(), 1);
        SpellAbility originalSpell = original.getStack().peekAbility();
        assertEquals(originalSpell.isFocused(), focused);
        Game copied = copy(original, snapshot, true);
        Player copiedPlayer = copied.getPlayer(player.getId());
        assertEquals(copied.getStack().size(), 1, "The copied Focus cast must actually be on the stack");
        SpellAbility copiedSpell = copied.getStack().peekAbility();
        assertNotSame(copiedSpell, originalSpell);
        assertEquals(copiedSpell.isFocused(), focused);
        assertSame(copiedSpell.getHostCard().getGame(), copied);
        assertSame(copiedSpell.getActivatingPlayer(), copiedPlayer);
        assertSame(copiedSpell.getSubAbility().getParent(), copiedSpell);
        drain(copied, false);
        assertEquals(copiedPlayer.getCardsIn(ZoneType.Library).size(), 6);
        assertEquals(copiedPlayer.getCardsIn(ZoneType.Hand).size(), focused ? 2 : 1);
        assertTrue(copied.findById(card.getId()).isInZone(ZoneType.Graveyard));
        assertEquals(original.getStack().size(), 1);
        assertSame(original.getStack().peekAbility(), originalSpell);
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(player.getCardsIn(ZoneType.Hand).isEmpty());
    }

    @Test(dataProvider = "focusedChoices")
    public void simulatorReconstructsFocusedFreeCastChoices(boolean focused) {
        Game original = prepareGame();
        Player player = original.getPlayers().get(1);
        Card card = addCardToZone("Contemplate", player, ZoneType.Hand);
        SpellAbility choice = card.getFirstSpellAbility().copyWithNoManaCost(player);
        // A selected cast descriptor, as supplied by a Play effect, not an already-cast stack object.
        choice.setFocused(focused);
        GameSimulator simulator = new GameSimulator(original, player);
        assertTrue(simulator.applySpellAbility(choice));
        Player simulated = simulator.getSimulatedAiPlayer();
        assertEquals(simulated.getCardsIn(ZoneType.Library).size(), 6);
        assertEquals(simulated.getCardsIn(ZoneType.Hand).size(), focused ? 2 : 1);
        assertTrue(simulator.getSimulatedGameState().findById(card.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(card.isInZone(ZoneType.Hand));
        assertFalse(card.isFocused());
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
        assertTrue(original.getStack().isEmpty());
    }

    @Test(dataProvider = "variants")
    public void aiChoosesTheMatchingFocusVariant(String name, boolean focused) {
        Game game = prepareGame();
        Player player = game.getPlayers().get(1);
        Card card = addCardToZone(name, player, ZoneType.Hand);
        List<SpellAbility> choices = card.getSpells().stream().map(sa -> {
            SpellAbility choice = sa.copyWithNoManaCost(player);
            choice.setFocused(focused);
            return choice;
        }).toList();
        SpellAbility selected = player.getController().getAbilityToPlay(card, choices);
        assertNotNull(selected);
        assertEquals(selected.getParam("Condition"), focused ? "Focused" : "NotFocused");
        if (name.equals("Circular Rage")) {
            Player opponent = game.getPlayers().get(0);
            Card bear = addCard("Runeclaw Bear", opponent);
            selected.setActivatingPlayer(player);
            game.copyLastState();
            assertTrue(ComputerUtil.handlePlayingSpellAbility(player, selected, sa -> {
                if (!focused) {
                    sa.getTargets().add(bear);
                    sa.addDividedAllocation(bear, 3);
                }
            }));
            assertEquals(game.getStack().peekAbility().getApi(), focused ? ApiType.DamageAll : ApiType.DealDamage);
            drain(game, false);
            assertTrue(game.findById(bear.getId()).isInZone(ZoneType.Graveyard));
        }
    }

    @Test
    public void pendingExileFlagDoesNotMarkAnUnrelatedCast() {
        Game game = prepareGame();
        Player player = game.getPlayers().get(1);
        Card card = addCardToZone("Circular Rage", player, ZoneType.Exile);
        card.setFocused(true);
        List<SpellAbility> choices = card.getSpells().stream()
                .map(sa -> sa.copyWithNoManaCost(player)).toList();
        SpellAbility selected = player.getController().getAbilityToPlay(card, choices);
        assertNotNull(selected);
        assertFalse(selected.isFocused());
        assertEquals(selected.getParam("Condition"), "NotFocused");
    }

    @Test
    public void playEffectMarksLibraryFocusBeforeAiVariantSelection() {
        Game game = prepareGame();
        Player player = game.getPlayers().get(1);
        addCard("Runeclaw Bear", game.getPlayers().get(0));
        Card spell = addCardToZone("Read the Future", player, ZoneType.Hand);
        // Only one eligible card among the revealed three; ordinary lands are not cast candidates.
        player.getZone(ZoneType.Library).removeAllCards(true);
        addCardToZone("Circular Rage", player, ZoneType.Library);
        addCardToZone("Island", player, ZoneType.Library);
        addCardToZone("Mountain", player, ZoneType.Library);
        fillLibrary(player, 5);
        player.getManaPool().addMana(new Mana(MagicColor.BLUE, spell, null, player),
                new Mana(MagicColor.RED, spell, null, player));
        SpellAbility ability = spell.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, ability, null));
        // Read the Future does not waive the chosen card's mana cost.
        player.getManaPool().addMana(new Mana(MagicColor.RED, spell, null, player),
                new Mana(MagicColor.RED, spell, null, player),
                new Mana(MagicColor.RED, spell, null, player));
        drain(game, false);
        assertTrue(game.getPlayers().get(0).getCreaturesInPlay().isEmpty(),
                "Read the Future must actually cast and resolve the Focused DamageAll variant");
        assertTrue(player.getCardsIn(ZoneType.Graveyard).anyMatch(c -> c.getName().equals("Circular Rage")));
    }

    private Game prepareGame() {
        Game game = initAndCreateGame();
        for (Player player : game.getPlayers()) {
            fillLibrary(player, 8);
        }
        return game;
    }

    private Card focus(Game game, Player player) {
        Card card = addCardToZone("Contemplate", player, ZoneType.Hand);
        addActivationMana(player, card);
        SpellAbility ability = card.getSpellAbilities().stream().filter(sa -> sa instanceof AbilityStatic
                && sa.getDescription().startsWith("Focus (")).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, ability, null));
        assertEquals(ability.getTotalManaSpent(), 3);
        return game.findById(card.getId());
    }

    private void addActivationMana(Player player, Card card) {
        for (int i = 0; i < 3; i++) {
            player.getManaPool().addMana(new Mana(MagicColor.BLUE, card, null, player));
        }
    }

    private Game copy(Game game, boolean snapshot, boolean stack) {
        return snapshot ? new GameSnapshot(game).makeCopy(null, stack)
                : new GameCopier(game).withStack(stack).makeCopy();
    }

    private void nextOwnUpkeep(Game game, Player player, boolean stopAtSpell) {
        Runnable resolver = () -> drain(game, stopAtSpell);
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, resolver));
        assertNotSame(game.getPhaseHandler().getPlayerTurn(), player);
        resolver.run();
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, resolver));
        assertSame(game.getPhaseHandler().getPlayerTurn(), player);
        resolver.run();
    }

    private void drain(Game game, boolean stopAtSpell) {
        game.getAction().checkStateEffects(false);
        game.getStack().addAllTriggeredAbilitiesToStack();
        int guard = 0;
        while (!game.getStack().isEmpty() && !game.isGameOver()) {
            if (stopAtSpell && game.getStack().peekAbility().isSpell()) {
                return;
            }
            assertTrue(++guard < 20);
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(false);
            game.getStack().addAllTriggeredAbilitiesToStack();
        }
    }

    private List<Trigger> pending(Game game) throws ReflectiveOperationException {
        Field field = TriggerHandler.class.getDeclaredField("delayedTriggers");
        field.setAccessible(true);
        return ((List<?>) field.get(game.getTriggerHandler())).stream().map(Trigger.class::cast).toList();
    }
}
