package forge.ai;

import forge.ai.simulation.GameSimulator;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.testng.Assert.*;

public class ResonanceCastLifecycleTest extends AITest {
    @DataProvider
    public Object[][] castPaths() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "castPaths")
    public void addedDamagePreservesTheMainTargetAndX(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card pending = pending(ai, "Fiery Bolero");
        addCard("Mountain", ai);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        spell.getTargets().add(opponent);
        spell.setXManaCostPaid(4);

        assertTrue(cast(ai, spell, playStack));
        SpellAbility played = game.getStack().peekAbility();
        assertSame(played.getTargets().getFirstTargetedPlayer(), opponent);
        assertEquals(played.getXManaCostPaid(), Integer.valueOf(4));
        assertNotNull(played.getSubAbility());
        assertSame(played.getSubAbility().getTargets().getFirstTargetedPlayer(), opponent);
        assertTrue(ai.getPendingResonance().isEmpty());
        assertTrue(game.getCardState(pending).isInZone(ZoneType.Graveyard));
        game.getStack().resolveStack();
        assertEquals(opponent.getLife(), 14);
    }

    @Test(dataProvider = "castPaths")
    public void invalidMainTargetsDoNotSpendPendingOrManaAndCanBeRetried(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card pending = pending(ai, "Fiery Bolero");
        Card mountain = addCard("Mountain", ai);
        SpellAbility spell = spell(ai, "Lightning Bolt");

        assertFalse(cast(ai, spell, playStack));
        assertTrue(game.getStack().isEmpty());
        assertFalse(mountain.isTapped());
        assertEquals(ai.getPendingResonance().size(), 1);
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertNull(spell.getSubAbility());
        assertTrue(spell.getHostCard().isInZone(ZoneType.Hand));

        spell.getTargets().add(opponent);
        assertTrue(cast(ai, spell, playStack));
        assertNull(game.getStack().peekAbility().getSubAbility().getSubAbility(), "Retry must merge once");
        game.getStack().resolveStack();
        assertEquals(opponent.getLife(), 14);
        assertTrue(ai.getPendingResonance().isEmpty());
    }

    @Test(dataProvider = "castPaths")
    public void failedPaymentAndReusedAbilityDoNotDuplicateEffects(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        Card pending = pending(ai, "Ethereal Chord");
        SpellAbility spell = spell(ai, "Nourish");
        Card firstForest = addCard("Forest", ai);
        assertFalse(cast(ai, spell, playStack));
        assertNull(spell.getSubAbility());
        assertEquals(ai.getPendingResonance().size(), 1);
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertTrue(spell.getHostCard().isInZone(ZoneType.Hand));
        assertFalse(firstForest.isTapped(), "Partial mana payment must be refunded");

        addCard("Forest", ai);
        assertTrue(cast(ai, spell, playStack));
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 26);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
        assertTrue(ai.getPendingResonance().isEmpty());

        // Reuse the same SA, rather than a freshly generated ability, for another real cast.
        Card returned = game.getAction().moveToHand(game.getCardState(spell.getHostCard()), null);
        spell.setHostCard(returned);
        addCards("Forest", 2, ai);
        assertTrue(cast(ai, spell, playStack));
        assertNull(game.getStack().peekAbility().getSubAbility());
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 32);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7, "Committed effects must not replay");
    }

    @Test(dataProvider = "castPaths")
    public void unusableOptionalTargetsDoNotPreventTheMainSpell(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card pending = pending(ai, "Nightmarish Chord");
        addCards("Forest", 2, ai);
        SpellAbility spell = spell(ai, "Nourish");

        assertTrue(cast(ai, spell, playStack));
        assertNull(game.getStack().peekAbility().getSubAbility());
        assertTrue(ai.getPendingResonance().isEmpty());
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 26);
        assertTrue(game.getCardState(pending).isInZone(ZoneType.Graveyard));
    }

    @Test(dataProvider = "castPaths")
    public void multiplePendingSourcesCanBeAcceptedOrDeclinedIndependently(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        Card draw = pending(ai, "Ethereal Chord");
        Card damage = pending(ai, "Fiery Bolero");
        addCards("Forest", 2, ai);
        SpellAbility spell = spell(ai, "Nourish");
        PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer()) {
            @Override
            public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                    List<String> options, Card cardToShow, Map<String, Object> params) {
                return cardToShow != damage;
            }
        };

        ai.runWithController(() -> {
            assertTrue(cast(ai, spell, playStack));
            SpellAbility played = game.getStack().peekAbility();
            assertNotNull(played.getSubAbility());
            assertNull(played.getSubAbility().getSubAbility());
            assertTrue(played.getHostCard().getColor().hasBlue());
            assertFalse(played.getHostCard().getColor().hasRed());
            assertTrue(ai.getPendingResonance().isEmpty());
            game.getStack().resolveStack();
        }, controller);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
        assertEquals(ai.getLife(), 26);
        assertEquals(game.getPlayers().get(0).getLife(), 20);
        assertTrue(game.getCardState(draw).isInZone(ZoneType.Graveyard));
        assertTrue(game.getCardState(damage).isInZone(ZoneType.Graveyard));
    }

    @Test(dataProvider = "castPaths")
    public void monteCarloChoosesAddedTargetsOnlyInTheCopiedGame(boolean snapshot) {
        Game game = initAndCreateGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = snapshot;
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card pending = pending(ai, "Fiery Bolero");
        Card mountain = addCard("Mountain", ai);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        spell.getTargets().add(opponent);
        GameSimulator simulator = new GameSimulator(game, ai);
        Game simulation = simulator.getSimulatedGameState();
        Player copiedAi = simulator.getSimulatedAiPlayer();
        Player copiedOpponent = copiedAi.getOpponents().get(0);
        Card copiedPending = copiedAi.getPendingResonance().get(0);

        assertTrue(simulator.applySpellAbility(spell, false));
        SpellAbility played = simulation.getStack().peekAbility();
        assertSame(played.getTargets().getFirstTargetedPlayer(), copiedOpponent);
        assertSame(played.getSubAbility().getTargets().getFirstTargetedPlayer(), copiedOpponent);
        GameSimulator.resolveStack(simulation, copiedOpponent);
        assertEquals(copiedOpponent.getLife(), 14);
        assertTrue(copiedAi.getPendingResonance().isEmpty());
        assertTrue(simulation.getCardState(copiedPending).isInZone(ZoneType.Graveyard));
        assertEquals(opponent.getLife(), 20);
        assertFalse(mountain.isTapped());
        assertSame(ai.getPendingResonance().get(0), pending);
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertTrue(spell.getHostCard().isInZone(ZoneType.Hand));
        assertNull(spell.getSubAbility());
        assertSame(spell.getTargets().getFirstTargetedPlayer(), opponent);
    }

    @Test
    public void humanCastPathStillMergesAndRollsBackOnlyItsOwnEffects() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        Card pending = pending(ai, "Ethereal Chord");
        SpellAbility spell = spell(ai, "Nourish");
        AbilitySub original = (AbilitySub) AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 1", spell.getHostCard());
        spell.appendSubAbility(original);
        spell.setActivatingPlayer(ai);

        assertFalse(new PlaySpellAbility(ai.getController(), spell).playAbility(true, false, false));
        assertSame(spell.getSubAbility(), original);
        assertNull(original.getSubAbility());
        assertEquals(ai.getPendingResonance().size(), 1);
        assertTrue(pending.isInZone(ZoneType.Exile));
        addCards("Forest", 2, ai);
        assertTrue(new PlaySpellAbility(ai.getController(), spell).playAbility(true, false, false));
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 27);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
        assertTrue(ai.getPendingResonance().isEmpty());
    }

    @Test
    public void aNonSpellAbilityDoesNotConsumePending() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card pending = pending(ai, "Ethereal Chord");
        Card source = addCard("Forest", ai);
        SpellAbility ability = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", source);
        assertTrue(cast(ai, ability, false));
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 21);
        assertSame(ai.getPendingResonance().get(0), pending);
        assertTrue(pending.isInZone(ZoneType.Exile));
    }

    @Test(dataProvider = "castPaths")
    public void spliceAndResonanceBothResolve(boolean humanPath) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        pending(ai, "Ethereal Chord");
        addCard("Island", ai);
        addCards("Mountain", 2, ai);
        SpellAbility spell = spell(ai, "Reach Through Mists");
        // The test selects this cast for its spliced effect, independently of draw timing heuristics.
        spell.putParam("AILogic", "PlayForSub");
        spell.getOriginalMapParams().put("AILogic", "PlayForSub");
        Card ray = addCardToZone("Glacial Ray", ai, ZoneType.Hand);
        ai.runWithController(() -> {
            if (humanPath) {
                assertTrue(new PlaySpellAbility(ai.getController(), spell).playAbility(true, false, false));
            } else {
                SpellAbility preview = spell.copy();
                AbilityUtils.addSpliceEffect(preview, ray);
                assertEquals(((PlayerControllerAi) ai.getController()).getAi().canPlaySa(preview),
                        AiPlayDecision.WillPlay, "The selected Splice fixture must pass the existing AI recheck");
                assertTrue(cast(ai, spell, false));
            }
            game.getStack().resolveStack();
        }, spliceController(ai, ray));
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 6);
        assertEquals(game.getPlayers().get(0).getLife(), 18, "Splice damage must still resolve");
        assertTrue(ray.isInZone(ZoneType.Hand));
        assertTrue(ai.getPendingResonance().isEmpty());
    }

    @Test
    public void humanSpliceCopyDoesNotLeaveAnUncommittedMergeOnRetry() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        Card pending = pending(ai, "Ethereal Chord");
        SpellAbility spell = spell(ai, "Reach Through Mists");
        Card ray = addCardToZone("Glacial Ray", ai, ZoneType.Hand);
        ai.runWithController(() -> {
            assertFalse(new PlaySpellAbility(ai.getController(), spell).playAbility(true, false, false));
            assertNull(spell.getSubAbility(), "The pre-Splice original must also be rolled back");
            assertTrue(pending.isInZone(ZoneType.Exile));
            assertEquals(ai.getPendingResonance().size(), 1);
            addCard("Island", ai);
            addCards("Mountain", 2, ai);
            assertTrue(new PlaySpellAbility(ai.getController(), spell).playAbility(true, false, false));
            game.getStack().resolveStack();
        }, spliceController(ai, ray));
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 6);
        assertEquals(game.getPlayers().get(0).getLife(), 18);
        assertTrue(ai.getPendingResonance().isEmpty());
    }

    @Test
    public void chosenModalEffectAndItsOriginalSubAbilitySurvive() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        pending(ai, "Fiery Bolero");
        addCard("Plains", ai);
        addCard("Swamp", ai);
        addCard("Forest", ai);
        SpellAbility spell = spell(ai, "Abzan Charm");
        AbilitySub chosen = spell.getAdditionalAbilityList("Choices").stream()
                .filter(mode -> "Draw".equals(mode.getApi().toString())).findFirst().orElseThrow();
        spell.setChosenList(new ArrayList<>(List.of(chosen)));
        assertTrue(cast(ai, spell, false));
        assertEquals(game.getStack().peekAbility().getChosenList(), List.of(chosen));
        game.getStack().resolveStack();
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 6);
        assertEquals(ai.getLife(), 18, "The chosen draw mode's life loss must remain");
        assertEquals(game.getPlayers().get(0).getLife(), 17);
    }

    @Test
    public void actualXCostAndDamageAreNotRechosenForTheAddedEffect() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        pending(ai, "Fiery Bolero");
        addCards("Mountain", 4, ai);
        SpellAbility spell = spell(ai, "Fireball");
        spell.getTargets().add(opponent);
        spell.setXManaCostPaid(2);
        assertTrue(cast(ai, spell, false));
        assertEquals(game.getStack().peekAbility().getXManaCostPaid(), Integer.valueOf(2));
        // Fireball's existing surcharge also sees the target added by Resonance.
        assertEquals(game.getStack().peekAbility().getTotalManaSpent(), 4);
        game.getStack().resolveStack();
        assertEquals(opponent.getLife(), 15);
    }

    @Test
    public void additionalLifeCostIsStillPaid() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 8);
        pending(ai, "Ethereal Chord");
        addCard("Forest", ai);
        SpellAbility spell = spell(ai, "Nourish");
        spell.setPayCosts(new Cost("G PayLife<2>", false));
        assertTrue(cast(ai, spell, false));
        assertEquals(ai.getLife(), 18);
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 24);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
    }

    @Test
    public void copiedSpellDoesNotConsumeNewPendingSources() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card pending = pending(ai, "Ethereal Chord");
        SpellAbility copy = spell(ai, "Lightning Bolt");
        copy.getHostCard().setGamePieceType(GamePieceType.COPIED_SPELL);
        copy.setCopied(true);
        copy.setPayCosts(Cost.Zero);
        copy.getTargets().add(opponent);
        assertTrue(cast(ai, copy, false));
        assertNull(game.getStack().peekAbility().getSubAbility());
        game.getStack().resolveStack();
        assertEquals(opponent.getLife(), 17);
        assertSame(ai.getPendingResonance().get(0), pending);
        assertTrue(pending.isInZone(ZoneType.Exile));
    }

    @Test(dataProvider = "castPaths")
    public void aNestedCastMergesOnceWithoutUnfreezingItsCaller(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        pending(ai, "Fiery Bolero");
        Card source = addCard("Mountain", ai);
        SpellAbility outer = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", source);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        spell.getTargets().add(opponent);
        game.getStack().freezeStack(outer);
        assertTrue(cast(ai, spell, playStack));
        assertTrue(game.getStack().isFrozen());
        assertTrue(game.getStack().isEmpty());
        assertTrue(ai.getPendingResonance().isEmpty());
        game.getStack().unfreezeStack();
        assertEquals(game.getStack().size(), 1);
        game.getStack().resolveStack();
        assertEquals(opponent.getLife(), 14);
    }

    @Test(dataProvider = "castPaths")
    public void rejectedNestedCastDoesNotClearItsCallersFrozenStack(boolean playStack) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card pending = pending(ai, "Fiery Bolero");
        Card source = addCard("Mountain", ai);
        SpellAbility outer = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1", source);
        game.getStack().freezeStack(outer);
        SpellAbility spell = spell(ai, "Lightning Bolt");
        assertFalse(cast(ai, spell, playStack));
        assertTrue(game.getStack().isFrozen());
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getPendingResonance().size(), 1);
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertNull(spell.getSubAbility());
        game.getStack().unfreezeStack();
    }

    private PlayerControllerAi spliceController(Player ai, Card ray) {
        return new PlayerControllerAi(ai.getGame(), ai, ai.getLobbyPlayer()) {
            @Override
            public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
                return List.of(ray);
            }
        };
    }

    private Card pending(Player player, String name) {
        Card card = addCardToZone(name, player, ZoneType.Exile);
        player.addPendingResonance(card);
        return card;
    }

    private SpellAbility spell(Player player, String name) {
        SpellAbility spell = addCardToZone(name, player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        return spell;
    }

    private boolean cast(Player ai, SpellAbility spell, boolean playStack) {
        Game game = ai.getGame();
        game.copyLastState();
        game.getAction().checkStateEffects(true);
        return playStack ? ComputerUtil.playStack(spell, ai, game)
                : ComputerUtil.handlePlayingSpellAbility(ai, spell, null);
    }
}
