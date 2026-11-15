package forge.ai.simulation;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class LongestManaValueRunTest extends AITest {
    private static final String RUN = "Count$LongestManaValueRun";
    private static final String RED_RUN = "Count$Valid Card.YouCtrl+nonLand+Red$LongestManaValueRun";

    @Test
    public void countsDistinctValuesWithGapsAndCardFilters() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card host = lifeSpell(player);
        SpellAbility ability = host.getFirstSpellAbility();
        ability.setActivatingPlayer(player);

        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 0);
        assertEquals(AbilityUtils.calculateAmount(host, RED_RUN + "/Plus.2", ability), 2);

        addPermanent(player, "0");
        addPermanent(player, "1");
        addPermanent(player, "1");
        addPermanent(player, "2");
        addFixture(player, ZoneType.Battlefield, "Run Land", "3", "Land");
        addPermanent(player, "4");
        addPermanent(player, "15 R");
        addPermanent(player, "16 R");
        addPermanent(player, "17");
        addPermanent(player, "18 R");
        addPermanent(player, "2147483646");
        addPermanent(player, "2147483647");
        addPermanent(player.getOpponents().getFirst(), "3");

        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 3);
        assertEquals(AbilityUtils.calculateAmount(host, RED_RUN, ability), 2);
        assertEquals(AbilityUtils.calculateAmount(host, RED_RUN + "/Plus.2", ability), 4);
        assertEquals(AbilityUtils.calculateAmount(host, "Count$ValidHand Card.YouOwn$LongestManaValueRun", ability), 1);

        assertEquals(AbilityUtils.calculateAmount(host, "Count$Valid Card.YouCtrl+nonLand", ability), 11);
        assertEquals(AbilityUtils.calculateAmount(host, "Count$Valid Card.YouCtrl+nonLand$DifferentCardManaCost", ability), 9);
        assertEquals(AbilityUtils.calculateAmount(host,
                "Count$Valid Card.YouCtrl+cmcGE2147483646$LongestManaValueRun", ability), 2);
        assertEquals(AbilityUtils.calculateAmount(host, "Count$Valid Card.YouCtrl+nonLand+cmcLE4$GreatestCardManaCost", ability), 4);
        assertEquals(AbilityUtils.calculateAmount(host, "Count$Valid Card.YouCtrl+nonLand+cmcLE4$CardManaCost", ability), 8);
    }

    @Test
    public void countsCurrentManaValueAndRecalculatesWhenSpellResolves() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card host = lifeSpell(player);
        SpellAbility ability = host.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        addPermanent(player, "1");
        Card bridge = addPermanent(player, "X 2");
        addPermanent(player, "3");
        assertEquals(bridge.getCMC(), 2);
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 3);

        bridge.turnFaceDown(true);
        assertEquals(bridge.getCMC(), 0);
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 2);
        bridge.forceTurnFaceUp();
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 3);
        bridge.phase(false);
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 1);
        bridge.phase(false);

        Player opponent = player.getOpponents().getFirst();
        bridge.setController(opponent, game.getNextTimestamp());
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 1);
        bridge.setController(player, game.getNextTimestamp());
        assertEquals(AbilityUtils.calculateAmount(host, RUN, ability), 3);

        int life = player.getLife();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, ability, null));
        game.getAction().moveToGraveyard(bridge, null);
        game.getStack().resolveStack();
        assertEquals(player.getLife(), life + 1);
    }

    @DataProvider
    public Object[][] backends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "backends")
    public void simulatedPlaysCountTheCopiedBattlefield(boolean snapshot) {
        Game game = initAndCreateGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = snapshot;
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card host = lifeSpell(player);
        Card bridge = addFixture(player, ZoneType.Hand, "Run Bridge", "2", "Artifact");
        addPermanent(player, "1");
        addPermanent(player, "3");
        SpellAbility bridgeSpell = bridge.getFirstSpellAbility();
        bridgeSpell.setActivatingPlayer(player);
        // Fund the simulated spell without adding a mana value to the run.
        addCard("Forest", player);
        addCard("Forest", player);

        SpellAbility lifeSpell = host.getFirstSpellAbility();
        lifeSpell.setActivatingPlayer(player);
        int life = player.getLife();
        assertEquals(AbilityUtils.calculateAmount(host, RUN, lifeSpell), 1);
        GameSimulator simulator = new GameSimulator(game, player);
        assertTrue(simulator.applySpellAbility(bridgeSpell));
        assertTrue(simulator.applySpellAbility(lifeSpell));
        assertEquals(simulator.getSimulatedAiPlayer().getLife(), life + 3);
        assertEquals(player.getLife(), life);
        assertEquals(AbilityUtils.calculateAmount(host, RUN, lifeSpell), 1);
        assertTrue(bridge.isInZone(ZoneType.Hand));
    }

    @Test(timeOut = 10000)
    public void handlesLargePoolsWithRepeatedManaValues() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card host = lifeSpell(player);
        List<Card> cards = new ArrayList<>();
        for (int value = 63; value >= 0; value--) {
            cards.addAll(Collections.nCopies(4096, addPermanent(player, Integer.toString(value))));
        }

        assertEquals(AbilityUtils.handlePaid(cards, "LongestManaValueRun", host, host.getFirstSpellAbility()), 64);
    }

    private Card lifeSpell(Player player) {
        return addFixture(player, ZoneType.Hand, "Run Life", "0", "Instant",
                "A:SP$ GainLife | Defined$ You | LifeAmount$ X | SpellDescription$ Gain X life.",
                "SVar:X:" + RUN);
    }

    private Card addPermanent(Player player, String manaCost) {
        return addFixture(player, ZoneType.Battlefield, "Run Permanent " + manaCost, manaCost, "Artifact Creature",
                "PT:2/2");
    }

    private Card addFixture(Player player, ZoneType zone, String name, String manaCost, String types, String... script) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:" + manaCost, "Types:" + types));
        lines.addAll(List.of(script));
        lines.add("Oracle:");
        Game game = player.getGame();
        Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "M21", CardRarity.Common), player, game);
        card.setGameTimestamp(game.getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }
}
