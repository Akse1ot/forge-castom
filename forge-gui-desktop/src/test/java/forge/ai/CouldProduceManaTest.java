package forge.ai;

import java.util.Set;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.card.CardRules;
import forge.card.CardRarity;
import forge.game.card.CardFactory;
import forge.item.PaperCard;

/** CR 106.7: the types of mana a permanent could produce take replacement effects into account, in every order. */
public class CouldProduceManaTest extends AITest {

    @DataProvider
    public Object[][] terminalManaReplacements() {
        return new Object[][] {{"Prevent"}, {"Skip"}};
    }

    @Test(dataProvider = "terminalManaReplacements")
    public void replacingTheWholeManaEventHasNoProducibleType(String action) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card forest = addCard("Forest", ai);
        addCard("Mana Reflection", ai);
        addCard("Pulse of Llanowar", ai);
        scriptedReplacement(ai, action + "$ True", null);
        SpellAbility mana = forest.getManaAbilities().get(0);
        mana.setActivatingPlayer(ai);

        AssertJUnit.assertTrue(forest.getProducibleColors().isEmpty());
        for (String color : forge.card.MagicColor.Constant.COLORS_AND_COLORLESS) {
            AssertJUnit.assertFalse(forest.canProduceColorMana(Set.of(color)));
        }
        game.getStack().addAndUnfreeze(mana);
        AssertJUnit.assertTrue(ai.getManaPool().isEmpty());
    }

    @Test
    public void amountOnlyReplacementPreservesTheTypeAndDoublesActualMana() {
        Player ai = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", ai);
        scriptedReplacement(ai, "", "ReplaceAmount$ 2");
        AssertJUnit.assertEquals(Set.of("green"), forest.getProducibleColors());
        SpellAbility mana = forest.getManaAbilities().get(0);
        mana.setActivatingPlayer(ai);
        ai.getGame().getStack().addAndUnfreeze(mana);
        AssertJUnit.assertEquals(2, ai.getManaPool().totalMana());
    }

    @Test
    public void paymentFollowsAReplacementThatOnlyBecomesApplicableAfterDoubling() {
        Player ai = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", ai);
        addCard("Mana Reflection", ai);
        addCard("Damping Sphere", ai);
        AssertJUnit.assertEquals(Set.of("colorless"), forest.getProducibleColors());
        SpellAbility spell = addCardToZone("Grizzly Bears", ai, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        forge.card.mana.ManaCost manaCost = new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("C"));
        AssertJUnit.assertTrue(ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(manaCost), spell, ai, false));
        AssertJUnit.assertFalse(forest.isTapped());
        ManaCostBeingPaid cost = new ManaCostBeingPaid(manaCost);
        AssertJUnit.assertTrue(ComputerUtilMana.payManaCost(cost, spell, ai, false));
        AssertJUnit.assertTrue(cost.isPaid());
        AssertJUnit.assertTrue(forest.isTapped());
        AssertJUnit.assertTrue(ai.getManaPool().isEmpty());
    }

    @Test
    public void anyColorReplacementProducesTheColorThePaymentPlanned() {
        Player ai = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", ai);
        addCard("Pulse of Llanowar", ai);
        // Most of the hand is green, but the replacement must choose blue to pay this cost.
        SpellAbility spell = addCardToZone("Grizzly Bears", ai, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        forge.card.mana.ManaCost manaCost = new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("U"));
        AssertJUnit.assertTrue(ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(manaCost), spell, ai, false));
        AssertJUnit.assertFalse(forest.isTapped());
        ManaCostBeingPaid cost = new ManaCostBeingPaid(manaCost);
        AssertJUnit.assertTrue(ComputerUtilMana.payManaCost(cost, spell, ai, false));
        AssertJUnit.assertTrue(cost.isPaid());
        AssertJUnit.assertTrue(ai.getManaPool().isEmpty());
    }

    @Test
    public void replacementChoiceAlsoPaysTheColoredCostOfAFilter() {
        Player ai = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", ai);
        Card gate = addCard("Mystic Gate", ai);
        addCard("Pulse of Llanowar", ai);
        SpellAbility spell = addCardToZone("Grizzly Bears", ai, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        forge.card.mana.ManaCost manaCost = new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("U U"));
        AssertJUnit.assertTrue(ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(manaCost), spell, ai, false));
        AssertJUnit.assertFalse(forest.isTapped());
        AssertJUnit.assertFalse(gate.isTapped());
        ManaCostBeingPaid cost = new ManaCostBeingPaid(manaCost);
        AssertJUnit.assertTrue(ComputerUtilMana.payManaCost(cost, spell, ai, false));
        AssertJUnit.assertTrue(cost.isPaid());
        AssertJUnit.assertTrue(forest.isTapped());
        AssertJUnit.assertTrue(gate.isTapped());
        AssertJUnit.assertTrue(ai.getManaPool().isEmpty());
    }

    private Card scriptedReplacement(Player player, String flags, String effect) {
        List<String> lines = new java.util.ArrayList<>(List.of("Name:Mana replacement fixture", "ManaCost:0", "Types:Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land.YouCtrl"
                        + (flags.isEmpty() ? "" : " | " + flags) + (effect == null ? "" : " | ReplaceWith$ Replace"), "Oracle:"));
        if (effect != null) {
            lines.add("SVar:Replace:DB$ ReplaceMana | " + effect);
        }
        Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "TST", CardRarity.Common), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    @Test
    public void reflectedManaSeesAReplacedType() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        addCard("Plains", opponent);
        addCard("Naked Singularity", opponent);
        Card stone = addCard("Fellwar Stone", ai);

        // Naked Singularity makes the Plains produce red instead of white
        AssertJUnit.assertEquals(Set.of("red"), CardUtil.getReflectableManaColors(stone.getManaAbilities().get(0)));
    }

    @Test
    public void reflectedManaFollowsAnotherReflectingLand() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        Card mine = addCard("Exotic Orchard", ai);
        addCard("Forest", ai);
        Card theirs = addCard("Exotic Orchard", opponent);

        // The rule's own example: the opposing Orchard reflects the Forest, and this Orchard reflects the opposing one
        AssertJUnit.assertEquals(Set.of("green"), CardUtil.getReflectableManaColors(mine.getManaAbilities().get(0)));
        AssertJUnit.assertEquals(Set.of("green"), CardUtil.getReflectableManaColors(theirs.getManaAbilities().get(0)));
    }

    @Test
    public void everyOrderOfReplacementEffectsCounts() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card plains = addCard("Plains", ai);
        addCard("Naked Singularity", ai);
        addCard("Contamination", ai);

        // whichever of the two effects applies last sets the type
        AssertJUnit.assertEquals(Set.of("red", "black"), plains.getProducibleColors());
    }

    @Test
    public void effectThatOnlyAppliesAfterAnotherIsFollowed() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card forest = addCard("Forest", ai);
        addCard("Mana Reflection", ai);
        addCard("Damping Sphere", ai);

        // Damping Sphere needs two or more mana, which the Forest only makes once Mana Reflection has applied
        AssertJUnit.assertEquals(Set.of("colorless"), forest.getProducibleColors());
    }

    @Test
    public void colorChosenByAReplacementEffectCountsEveryColor() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card forest = addCard("Forest", ai);
        addCard("Pulse of Llanowar", ai);

        AssertJUnit.assertEquals(Set.of("white", "blue", "black", "red", "green"), forest.getProducibleColors());
    }

    @Test
    public void colorChosenOnResolutionCountsOnlyWhereManaIsProduced() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card nykthos = addCard("Nykthos, Shrine to Nyx", ai);
        AssertJUnit.assertEquals(Set.of("colorless"), nykthos.getProducibleColors());

        addCard("Llanowar Elves", ai);
        AssertJUnit.assertEquals(Set.of("colorless", "green"), nykthos.getProducibleColors());
        AssertJUnit.assertFalse(nykthos.hasChosenColor());

        // Rhystic Cave adds one mana of the chosen color, whatever it is
        AssertJUnit.assertEquals(Set.of("white", "blue", "black", "red", "green"), addCard("Rhystic Cave", ai).getProducibleColors());
    }

    @Test
    public void combinationManaCountsByItsAmount() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card wastes = addCard("Adarkar Wastes", ai);
        Card gate = addCard("Mystic Gate", ai);
        addCard("Damping Sphere", game.getPlayers().get(0));

        // Adarkar Wastes adds one mana, which Damping Sphere leaves alone
        AssertJUnit.assertEquals(Set.of("white", "blue", "colorless"), wastes.getProducibleColors());
        // Mystic Gate's colored ability adds two
        AssertJUnit.assertEquals(Set.of("colorless"), gate.getProducibleColors());
    }

    @Test
    public void abilityWithNoActivatorIsCheckedAgainstAnEffectThatAsksForOne() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        Card falseDawn = createCard("False Dawn", opponent);
        falseDawn.getFirstSpellAbility().setActivatingPlayer(opponent);
        AbilityUtils.resolve(falseDawn.getFirstSpellAbility());

        // False Dawn only changes mana from its controller's spells and abilities
        AssertJUnit.assertEquals(Set.of("green"), addCard("Forest", ai).getProducibleColors());
    }

    @Test
    public void amountPaidAsACostCountsAsSomeMana() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        // Calciform Pools adds X mana for X storage counters removed, and the cost is ignored
        AssertJUnit.assertEquals(Set.of("white", "blue", "colorless"), addCard("Calciform Pools", ai).getProducibleColors());
    }

    @Test
    public void amountSetWhileResolvingCountsAsSomeMana() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        // Sacellum Godspeaker adds {G} for each card revealed as its ability resolves
        AssertJUnit.assertEquals(Set.of("green"), addCard("Sacellum Godspeaker", ai).getProducibleColors());
    }

    @Test
    public void noManaMeansNoType() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card coffers = addCard("Cabal Coffers", ai);
        AssertJUnit.assertTrue(coffers.getProducibleColors().isEmpty());

        addCard("Swamp", ai);
        AssertJUnit.assertEquals(Set.of("black"), coffers.getProducibleColors());
    }

    @Test
    public void predictedManaKeepsTheCombinationMarkerUnderATypeReplacement() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card gate = addCard("Mystic Gate", ai);
        addCard("Infernal Darkness", ai);
        SpellAbility filter = gate.getManaAbilities().get(1);
        filter.setActivatingPlayer(ai);

        // no color is chosen yet, so the mana is still the list of colors to choose from
        AssertJUnit.assertEquals("Combo B B", ComputerUtilMana.predictMana(filter, ai, ManaCostShard.BLACK));
    }

    @Test
    public void painlandStillPaysUnderDampingSphere() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        addCard("Adarkar Wastes", ai);
        addCard("Plains", ai);
        addCard("Damping Sphere", game.getPlayers().get(0));
        SpellAbility charm = addCardToZone("Azorius Charm", ai, ZoneType.Hand).getFirstSpellAbility(); // {W}{U}
        charm.setActivatingPlayer(ai);
        int life = ai.getLife();

        ManaCostBeingPaid cost = new ManaCostBeingPaid(charm.getPayCosts().getTotalMana());
        AssertJUnit.assertTrue(ComputerUtilMana.payManaCost(cost, charm, ai, false) && cost.isPaid());
        AssertJUnit.assertEquals(life - 1, ai.getLife());
    }
}
