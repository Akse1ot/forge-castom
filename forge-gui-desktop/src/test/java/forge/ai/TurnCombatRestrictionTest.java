package forge.ai;

import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class TurnCombatRestrictionTest extends AITest {
    @Test
    public void extraCombatAndCopiesKeepTheDeclaredAttackerUntilTurnChange() {
        Game game = initAndCreateGame();
        Player attacker = game.getPhaseHandler().getPlayerTurn();
        Player defender = attacker.getOpponents().get(0);
        fillLibrary(defender, 1);
        Card first = ready("Serra Angel", attacker);
        Card other = ready("Craw Wurm", attacker);
        mustAttack(first);
        other.setTapped(true);
        Combat initial = beginDeclaration(attacker);
        game.getPhaseHandler().devDeclareAttackers();
        assertTrue(initial.isAttacking(first), "The stock AI must declare the required attacker");

        addTurnLimits(attacker);
        other.setTapped(false);
        mustAttack(other);
        Combat extra = beginDeclaration(attacker);
        game.getPhaseHandler().devDeclareAttackers();
        assertEquals(extra.getAttackers().size(), 1);
        assertTrue(extra.isAttacking(first));
        assertFalse(CombatUtil.canAttack(other, defender), "A requirement cannot open a second slot");

        for (Game copy : copiesOf(game)) {
            assertNotSame(copy, game);
            Player copiedDefender = copy.getPlayer(defender.getId());
            assertTrue(CombatUtil.canAttack(copy.findById(first.getId()), copiedDefender));
            assertFalse(CombatUtil.canAttack(copy.findById(other.getId()), copiedDefender));
        }

        beginDeclaration(attacker);
        Card returned = game.getAction().moveToPlay(game.getAction().exile(first, null, null), null, null);
        returned.setSickness(false);
        assertEquals(returned.getId(), first.getId());
        assertFalse(returned.equalsWithGameTimestamp(first));
        assertFalse(CombatUtil.canAttack(returned, defender));

        game.getPhaseHandler().endCombat();
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.MAIN1, null));
        assertEquals(game.getPhaseHandler().getPlayerTurn(), defender);
        assertTrue(CombatUtil.canAttack(other, defender), "Turn advance clears the previous attack history");
    }

    @Test
    public void stockAiBlocksWithinOneSlotAcrossDefendersAndCombats() {
        Game game = initAndCreateThreePlayerGame();
        Player attacker = game.getPhaseHandler().getPlayerTurn();
        Player firstDefender = game.getPlayers().get(0);
        Player secondDefender = game.getPlayers().get(2);
        Card first = ready("Grizzly Bears", attacker);
        Card second = ready("Runeclaw Bear", attacker);
        Card blocker = addCard("Craw Wurm", firstDefender);
        Card otherBlocker = addCard("Craw Wurm", secondDefender);
        Card enchantment = addTurnLimits(attacker);

        Combat initial = beginDeclaration(attacker);
        initial.addAttacker(first, firstDefender);
        blocker.addMustBlockCard(game.getNextTimestamp(), first);
        assertTrue(game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(initial.isBlocking(blocker), "The stock AI must block when the slot is free");

        Combat extra = beginDeclaration(attacker);
        // A creature put into combat by an effect is independent of the declaration cap.
        extra.addAttacker(first, firstDefender);
        extra.addAttacker(second, secondDefender);
        otherBlocker.addMustBlockCard(game.getNextTimestamp(), second);
        assertTrue(CombatUtil.canBlock(first, blocker, extra));
        assertFalse(CombatUtil.canBlock(second, otherBlocker, extra));
        assertNull(CombatUtil.validateBlocks(extra, secondDefender));
        assertTrue(game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertEquals(extra.getAllBlockers().size(), 1);
        assertTrue(extra.isBlocking(blocker));

        for (Game copy : copiesOf(game)) {
            Combat copiedCombat = beginDeclaration(copy.getPlayer(attacker.getId()));
            Card copiedFirst = copy.findById(first.getId());
            Card copiedSecond = copy.findById(second.getId());
            copiedCombat.addAttacker(copiedFirst, copy.getPlayer(firstDefender.getId()));
            copiedCombat.addAttacker(copiedSecond, copy.getPlayer(secondDefender.getId()));
            assertTrue(CombatUtil.canBlock(copiedFirst, copy.findById(blocker.getId()), copiedCombat));
            assertFalse(CombatUtil.canBlock(copiedSecond, copy.findById(otherBlocker.getId()), copiedCombat));
        }

        extra.addBlocker(second, otherBlocker);
        assertNotNull(CombatUtil.validateBlocks(extra, secondDefender));
        extra.removeBlockAssignment(second, otherBlocker);
        game.getAction().moveToGraveyard(enchantment, null);
        assertTrue(CombatUtil.canBlock(second, otherBlocker, extra));
    }

    @DataProvider
    public Object[][] perCombatCards() {
        return new Object[][] {{"Silent Arbiter"}, {"Dueling Grounds"}};
    }

    @Test(dataProvider = "perCombatCards")
    public void stockCapsAllowDifferentCreaturesInTheNextCombat(String name) {
        Game game = initAndCreateGame();
        Player attacker = game.getPhaseHandler().getPlayerTurn();
        Player defender = attacker.getOpponents().get(0);
        addCard(name, defender);
        Card first = ready("Serra Angel", attacker);
        Card other = ready("Air Elemental", attacker);
        Card blocker = addCard("Mahamoti Djinn", defender);
        Card alternative = addCard("Wind Drake", defender);
        mustAttack(first);

        Combat initial = beginDeclaration(attacker);
        initial.addAttacker(first, defender);
        initial.addAttacker(other, defender);
        assertFalse(CombatUtil.validateAttackers(initial));
        initial.removeFromCombat(other);
        game.getPhaseHandler().devDeclareAttackers();
        assertEquals(initial.getAttackers().size(), 1);
        assertTrue(game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(initial.isBlocking(blocker));
        assertFalse(CombatUtil.canBlock(first, alternative, initial));

        Combat extra = beginDeclaration(attacker);
        first.setTapped(true);
        extra.initConstraints();
        extra.addAttacker(other, defender);
        assertTrue(CombatUtil.validateAttackers(extra));
        assertTrue(CombatUtil.canBlock(other, alternative, extra));
    }

    private Card ready(String name, Player player) {
        Card card = addCard(name, player);
        card.setSickness(false);
        return card;
    }

    private static void mustAttack(Card card) {
        card.addStaticAbility("Mode$ MustAttack | ValidCreature$ Card.Self");
    }

    private static Combat beginDeclaration(Player attacker) {
        var phase = attacker.getGame().getPhaseHandler();
        phase.devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker, true, phase.getTurn());
        Combat combat = new Combat(attacker);
        phase.setCombat(combat);
        return combat;
    }

    private static List<Game> copiesOf(Game game) {
        return List.of(new GameSnapshot(game).makeCopy(), new GameCopier(game).makeCopy());
    }

    private static Card addTurnLimits(Player owner) {
        CardRules rules = CardRules.fromScript(List.of("Name:High Noon I", "ManaCost:1 W", "Types:Enchantment",
                "S:Mode$ AttackRestrict | PerTurn$ True | MaxAttackers$ 1",
                "S:Mode$ BlockRestrict | PerTurn$ True | MaxBlockers$ 1", "Oracle:"));
        Game game = owner.getGame();
        Card card = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, game);
        card.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        return card;
    }
}
