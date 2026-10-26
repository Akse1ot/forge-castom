package forge.ai.simulation;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetEitherFaceUtil;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.testng.Assert.*;

public class TargetEitherFaceSimulationTest extends AITest {
    @DataProvider
    public Object[][] facesAndPaths() {
        return new Object[][] {
                {false, CardStateName.Original, false}, {false, CardStateName.Backside, false},
                {true, CardStateName.Original, false}, {true, CardStateName.Backside, false},
                {false, CardStateName.Original, true}, {false, CardStateName.Backside, true},
                {true, CardStateName.Original, true}, {true, CardStateName.Backside, true}
        };
    }

    @DataProvider
    public Object[][] backends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "facesAndPaths")
    public void rolloutRetainsChosenFaceAndResolvesIt(boolean snapshot, CardStateName state, boolean subAbility) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        Card target = addCardToZone("Halvar, God of Battle", p, ZoneType.Graveyard);
        Card host = returnSpell(p, "Card.YouOwn", subAbility);
        SpellAbility source = host.getFirstSpellAbility();
        source.setActivatingPlayer(p);
        SpellAbility targeted = targetingPart(source);
        targeted.getTargets().setTargetedCardState(target, state);
        assertTrue(targeted.canTarget(target));
        targeted.getTargets().add(target);

        GameSimulator simulator = new GameSimulator(original, p);
        assertTrue(simulator.applySpellAbility(source, false));
        Game simulation = simulator.getSimulatedGameState();
        SpellAbility copied = simulation.getStack().peekAbility();
        Card copiedTarget = targetingPart(copied).getTargets().getFirstTargetedCard();
        assertCopiedChoice(target, copiedTarget, state, targeted, targetingPart(copied), simulation);
        GameSimulator.resolveStack(simulation, simulator.getSimulatedAiPlayer().getOpponents().get(0));

        assertReturnedFace(copiedTarget, state);
        assertTrue(target.isInZone(ZoneType.Graveyard));
        assertEquals(target.getCurrentStateName(), CardStateName.Original);
        assertSame(targeted.getTargets().getFirstTargetedCard(), target);
        assertEquals(targeted.getTargets().getTargetedCardState(target), state);
    }

    @Test(dataProvider = "facesAndPaths")
    public void stackCopyRetainsChosenFaceAndSubAbilityTargets(boolean snapshot, CardStateName state, boolean subAbility) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        Card target = addCardToZone("Halvar, God of Battle", p, ZoneType.Graveyard);
        SpellAbility source = returnSpell(p, "Card.YouOwn", subAbility).getFirstSpellAbility();
        source.setActivatingPlayer(p);
        SpellAbility targeted = targetingPart(source);
        targeted.getTargets().setTargetedCardState(target, state);
        targeted.getTargets().add(target);
        putOnStack(source);
        original.copyLastState();

        GameCopier copier = new GameCopier(original).withStack(true);
        Game simulation = copier.makeCopy(null, p);
        SpellAbility copied = simulation.getStack().peekAbility();
        Card copiedTarget = copier.find(target);
        assertCopiedChoice(target, copiedTarget, state, targeted, targetingPart(copied), simulation);
        GameSimulator.resolveStack(simulation, copier.find(p).getOpponents().get(0));

        assertReturnedFace(copiedTarget, state);
        assertTrue(target.isInZone(ZoneType.Graveyard));
        assertSame(original.getStack().peekAbility(), source);
        assertEquals(targeted.getTargets().getTargetedCardState(target), state);
    }

    @Test(dataProvider = "backends")
    public void copiedIllegalBacksideDoesNotBecomeLegalFrontside(boolean snapshot) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        Card target = addCardToZone("Halvar, God of Battle", p, ZoneType.Graveyard);
        SpellAbility source = returnSpell(p, "Creature.YouOwn", false).getFirstSpellAbility();
        source.setActivatingPlayer(p);
        assertTrue(TargetEitherFaceUtil.canTargetInState(source, target, CardStateName.Original));
        assertFalse(TargetEitherFaceUtil.canTargetInState(source, target, CardStateName.Backside));
        source.getTargets().setTargetedCardState(target, CardStateName.Backside);
        source.getTargets().add(target);
        assertFalse(source.canTarget(target));

        GameCopier copier = new GameCopier(original);
        copier.makeCopy(null, p);
        SpellAbility copied = copier.find(source.getHostCard()).getFirstSpellAbility();
        copied.setActivatingPlayer(copier.find(p));
        SpellAbilityChoiceCopier.copyTargets(source, copied, copier::find);
        Card mapped = copier.find(target);

        assertEquals(copied.getTargets().getTargetedCardState(mapped), CardStateName.Backside);
        assertFalse(copied.canTarget(mapped));
        assertFalse(FlatMonteCarloPicker.applyTarget(copied, mapped));
        assertEquals(copied.getTargets().getTargetedCardState(mapped), CardStateName.Backside);
    }

    @Test(dataProvider = "backends")
    public void ordinaryCardsPlayersSpellsAndDividedValuesAreMappedIndependently(boolean snapshot) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        Card host = returnSpell(p, "Card.YouOwn", false);
        Card card = addCardToZone("Runeclaw Bear", p, ZoneType.Graveyard);
        Card spellCard = addCardToZone("Nourish", p, ZoneType.Hand);
        SpellAbility otherSpell = spellCard.getFirstSpellAbility();
        otherSpell.setActivatingPlayer(p);
        TargetChoices choices = new TargetChoices();
        choices.add(card);
        choices.add(p);
        choices.add(otherSpell);
        choices.addDividedAllocation(card, 2);
        choices.addDividedAllocation(p, 3);
        GameCopier copier = new GameCopier(original);
        copier.makeCopy(null, p);
        SpellAbility mappedSpell = copier.find(spellCard).getFirstSpellAbility();
        Function<GameObject, GameObject> mapper = o -> o == otherSpell ? mappedSpell : copier.find(o);

        TargetChoices copied = choices.copyMapped(mapper);

        assertSame(copied.getFirstTargetedCard(), copier.find(card));
        assertSame(copied.getFirstTargetedPlayer(), copier.find(p));
        assertSame(copied.getFirstTargetedSpell(), mappedSpell);
        assertNotSame(mappedSpell, otherSpell);
        assertNull(copied.getTargetedCardState(copier.find(card)));
        assertEquals(copied.getDividedValue(copier.find(card)), Integer.valueOf(2));
        assertEquals(copied.getDividedValue(copier.find(p)), Integer.valueOf(3));
        copied.remove(copier.find(card));
        assertEquals(choices.size(), 3);
        assertEquals(choices.getDividedValue(card), Integer.valueOf(2));
        assertTrue(choices.copyMapped(o -> null).isEmpty());

        SpellAbility source = host.getFirstSpellAbility();
        source.setTargets(choices);
        SpellAbility destination = copier.find(host).getFirstSpellAbility();
        // A stale choice must be replaced, including its side metadata.
        destination.getTargets().add(copier.find(card));
        destination.getTargets().setTargetedCardState(copier.find(card), CardStateName.Backside);
        SpellAbilityChoiceCopier.copyTargets(source, destination, mapper);
        assertEquals(destination.getTargets().size(), 3);
        assertNull(destination.getTargets().getTargetedCardState(copier.find(card)));
    }

    @Test(dataProvider = "backends")
    public void stackSpellTargetsKeepIdentityAndStackOrder(boolean snapshot) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        SpellAbility first = addCardToZone("Nourish", p, ZoneType.Hand).getFirstSpellAbility();
        first.setActivatingPlayer(p);
        putOnStack(first);
        SpellAbility counter = addCardToZone("Counterspell", p, ZoneType.Hand).getFirstSpellAbility();
        counter.setActivatingPlayer(p);
        counter.getTargets().add(first);
        putOnStack(counter);
        assertSame(original.getStack().peekAbility(), counter);

        GameCopier copier = new GameCopier(original).withStack(true);
        Game simulation = copier.makeCopy(null, p);
        SpellAbility copiedCounter = simulation.getStack().peekAbility();
        SpellAbility copiedFirst = copiedCounter.getTargets().getFirstTargetedSpell();

        assertEquals(copiedCounter.getHostCard().getName(), "Counterspell");
        assertNotSame(copiedFirst, first);
        assertSame(copiedFirst.getHostCard().getGame(), simulation);
        assertNotNull(simulation.getStack().getInstanceMatchingSpellAbilityID(copiedFirst));
        GameSimulator.resolveStack(simulation, copier.find(p).getOpponents().get(0));
        assertEquals(copier.find(p).getLife(), 20, "Nourish must be countered before resolving");
        assertEquals(original.getStack().size(), 2);
        assertSame(counter.getTargets().getFirstTargetedSpell(), first);
    }

    @Test(dataProvider = "backends")
    public void historicalTargetControllerIsMappedAndIndependent(boolean snapshot) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        Player opponent = p.getOpponents().get(0);
        Card target = addCard("Runeclaw Bear", p);
        TargetChoices choices = new TargetChoices();
        choices.add(target);
        target.setController(opponent, original.getNextTimestamp());
        assertTrue(choices.forEachControllerChanged(target));
        GameCopier copier = new GameCopier(original);
        copier.makeCopy(null, p);
        List<GameObject> mappedInputs = new ArrayList<>();

        TargetChoices copied = choices.copyMapped(o -> {
            mappedInputs.add(o);
            return copier.find(o);
        });

        assertTrue(mappedInputs.stream().anyMatch(o -> o == p), "The historical controller must use the mapper");
        Card copiedTarget = copier.find(target);
        assertTrue(copied.forEachControllerChanged(copiedTarget));
        copiedTarget.setController(copier.find(p), copiedTarget.getGame().getNextTimestamp());
        assertFalse(copied.forEachControllerChanged(copiedTarget));
        assertSame(target.getController(), opponent);
        assertTrue(choices.forEachControllerChanged(target));
    }

    @Test(dataProvider = "backends")
    public void retargetedOlderSpellCanReferToANewerStackEntry(boolean snapshot) {
        Game original = newGame(snapshot);
        Player p = original.getPlayers().get(0);
        SpellAbility first = addCardToZone("Nourish", p, ZoneType.Hand).getFirstSpellAbility();
        first.setActivatingPlayer(p);
        putOnStack(first);
        SpellAbility counter = addCardToZone("Counterspell", p, ZoneType.Hand).getFirstSpellAbility();
        counter.setActivatingPlayer(p);
        counter.getTargets().add(first);
        putOnStack(counter);
        SpellAbility newer = addCardToZone("Nourish", p, ZoneType.Hand).getFirstSpellAbility();
        newer.setActivatingPlayer(p);
        putOnStack(newer);
        // Model a change-targets effect after both spells are already on the stack.
        counter.resetTargets();
        counter.getTargets().add(newer);

        GameCopier copier = new GameCopier(original).withStack(true);
        Game simulation = copier.makeCopy(null, p);
        SpellAbility copiedCounter = copier.find(counter);
        SpellAbility copiedNewer = copier.find(newer);

        assertSame(simulation.getStack().peekAbility(), copiedNewer);
        assertSame(copiedCounter.getTargets().getFirstTargetedSpell(), copiedNewer);
        assertSame(copiedNewer.getHostCard().getGame(), simulation);
        GameSimulator.resolveStack(simulation, copier.find(p).getOpponents().get(0));
        assertEquals(copier.find(p).getLife(), 32);
        assertEquals(original.getStack().size(), 3);
        assertSame(counter.getTargets().getFirstTargetedSpell(), newer);
    }

    @DataProvider
    public Object[][] uniqueFaces() {
        return new Object[][] {
                {"Creature.YouOwn", CardStateName.Original},
                {"Artifact.YouOwn", CardStateName.Backside},
                {"Card.YouOwn+cmcLE3", CardStateName.Backside}
        };
    }

    @Test(dataProvider = "uniqueFaces")
    public void aiRecordsTheOnlyLegalFace(String valid, CardStateName expected) {
        Game game = newGame(false);
        Player p = game.getPlayers().get(0);
        Card target = addCardToZone("Halvar, God of Battle", p, ZoneType.Graveyard);
        SpellAbility sa = returnSpell(p, valid, false).getFirstSpellAbility();
        sa.setActivatingPlayer(p);

        assertEquals(TargetEitherFaceUtil.getAutoTargetState(sa, target), expected);
        assertTrue(FlatMonteCarloPicker.applyTarget(sa, target));
        assertEquals(sa.getTargets().getTargetedCardState(target), expected);
        assertEquals(sa.getTargets().getTargetedCMC(target), expected == CardStateName.Backside ? 2 : 4);
        assertEquals(target.getCurrentStateName(), CardStateName.Original);
    }

    @Test
    public void aiKeepsExplicitFaceButDoesNotInventAnAmbiguousChoice() {
        Game game = newGame(false);
        Player p = game.getPlayers().get(0);
        Card target = addCardToZone("Halvar, God of Battle", p, ZoneType.Graveyard);
        Card another = addCardToZone("Valki, God of Lies", p, ZoneType.Graveyard);
        SpellAbility sa = returnSpell(p, "Card.YouOwn", false).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.getTargets().setTargetedCardState(target, CardStateName.Backside);
        sa.getTargets().add(target);

        assertTrue(FlatMonteCarloPicker.applyTarget(sa, target));
        assertEquals(sa.getTargets().getTargetedCardState(target), CardStateName.Backside);
        assertNull(TargetEitherFaceUtil.getAutoTargetState(sa, another));
        assertFalse(FlatMonteCarloPicker.applyTarget(sa, another));
        assertSame(sa.getTargets().getFirstTargetedCard(), target);
        assertEquals(sa.getTargets().getTargetedCardState(target), CardStateName.Backside);
    }

    @Test
    public void aiStillTargetsOrdinaryCardsAndPlayers() {
        Game game = newGame(false);
        Player p = game.getPlayers().get(0);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Graveyard);
        SpellAbility reanimate = returnSpell(p, "Creature.YouOwn", false).getFirstSpellAbility();
        reanimate.setActivatingPlayer(p);
        assertTrue(FlatMonteCarloPicker.applyTarget(reanimate, bear));
        assertNull(reanimate.getTargets().getTargetedCardState(bear));
        SpellAbility bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(p);
        assertTrue(FlatMonteCarloPicker.applyTarget(bolt, p.getOpponents().get(0)));
        assertSame(bolt.getTargets().getFirstTargetedPlayer(), p.getOpponents().get(0));
    }

    private Game newGame(boolean snapshot) {
        Game game = initAndCreateGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = snapshot;
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }

    private Card returnSpell(Player player, String valid, boolean subAbility) {
        String returning = "ChangeZone | Origin$ Graveyard | Destination$ Battlefield | TgtZone$ Graveyard"
                + " | ValidTgts$ " + valid + " | TargetEitherFace$ True | SpellDescription$ Return the selected face.";
        CardRules rules = CardRules.fromScript(Arrays.asList(
                "Name:P3 Return", "ManaCost:0", "Types:Sorcery", "Oracle:Test return spell.",
                subAbility ? "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SubAbility$ Return" : "A:SP$ " + returning,
                "SVar:Return:DB$ " + returning));
        Card card = CardFactory.getCard(new PaperCard(rules, "M21", CardRarity.Common), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Hand).add(card);
        return card;
    }

    private SpellAbility targetingPart(SpellAbility ability) {
        return ability.usesTargeting() ? ability : ability.getSubAbility();
    }

    private void putOnStack(SpellAbility ability) {
        Game game = ability.getHostCard().getGame();
        ability.setHostCard(game.getAction().moveToStack(ability.getHostCard(), ability));
        game.getStack().add(ability);
    }

    private void assertCopiedChoice(Card original, Card copied, CardStateName state,
            SpellAbility source, SpellAbility destination, Game simulation) {
        assertNotSame(copied, original);
        assertSame(copied.getGame(), simulation);
        assertSame(destination.getTargets().getFirstTargetedCard(), copied);
        assertEquals(destination.getTargets().getTargetedCardState(copied), state);
        assertEquals(destination.getTargets().getTargetedCMC(copied), state == CardStateName.Backside ? 2 : 4);
        assertEquals(source.getTargets().getTargetedCardState(original), state);
    }

    private void assertReturnedFace(Card card, CardStateName expected) {
        assertTrue(card.isInPlay());
        assertEquals(card.getCurrentStateName(), expected);
        assertEquals(card.getName(), expected == CardStateName.Backside ? "Sword of the Realms" : "Halvar, God of Battle");
    }
}
