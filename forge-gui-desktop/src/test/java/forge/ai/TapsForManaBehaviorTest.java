package forge.ai;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameObservation;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.cost.CostPayment;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TapsForManaBehaviorTest extends AITest {
    @Test
    public void emptyOrchardDoesNotFireManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card orchard = orchard(player, false);
        Card observer = addObserver(player);

        activateMana(orchard);

        assertEquals(player.getManaPool().totalMana(), 0);
        assertEvents(observer, 0);
        assertTrue(player.hasTappedLandForManaThisTurn(), "The activation still counts for CR 106.12");
    }

    @Test
    public void loneReflectingPoolDoesNotFireManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card pool = addCard("Reflecting Pool", player);
        Card observer = addObserver(player);

        activateMana(pool);

        assertEquals(player.getManaPool().totalMana(), 0);
        assertEvents(observer, 0);
    }

    @Test
    public void productiveOrchardFiresEachManaEventOnce() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card orchard = orchard(player, true);
        Card observer = addObserver(player);

        activateMana(orchard);

        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.GREEN), 1);
        assertEquals(player.getManaPool().totalMana(), 1);
        assertEvents(observer, 1);
    }

    @DataProvider
    public Object[][] manaDoublers() {
        return new Object[][] {
            {"Nikya of the Old Ways"},
            {"Mana Flare"},
            {"Dictate of Karametra"},
        };
    }

    @Test(dataProvider = "manaDoublers")
    public void emptyOrchardDoesNotTriggerManaDoubler(String name) {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card orchard = orchard(player, false);
        Card doubler = addCard(name, player);

        activateMana(orchard);

        assertEquals(triggerCount(doubler, TriggerType.TapsForMana), 0);
        assertEquals(player.getManaPool().totalMana(), 0);
    }

    @Test(dataProvider = "manaDoublers")
    public void productiveOrchardTriggersManaDoublerAndAddsGreen(String name) {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card orchard = orchard(player, true);
        Card doubler = addCard(name, player);

        activateMana(orchard);

        assertEquals(triggerCount(doubler, TriggerType.TapsForMana), 1);
        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.GREEN), 2);
        assertEquals(player.getManaPool().totalMana(), 2);
    }

    @Test
    public void wastesProducesColorlessAndFiresBothManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card wastes = addCard("Wastes", player);
        Card observer = addObserver(player);

        activateMana(wastes);

        assertEquals(player.getManaPool().getAmountOfColor((byte) ManaAtom.COLORLESS), 1);
        assertEquals(player.getManaPool().totalMana(), 1);
        assertEvents(observer, 1);
    }

    @Test
    public void forestProducesGreenAndFiresBothManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", player);
        Card observer = addObserver(player);

        activateMana(forest);

        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.GREEN), 1);
        assertEquals(player.getManaPool().totalMana(), 1);
        assertEvents(observer, 1);
    }

    @Test
    public void nonManaTapCostDoesNotFireManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card land = addFixture(player, "Life land", "Land",
                "A:AB$ GainLife | Cost$ T | Defined$ You | LifeAmount$ 1");
        Card observer = addObserver(player);
        SpellAbility ability = land.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        assertFalse(ability.isManaAbility());
        int life = player.getLife();

        activate(ability);
        player.getGame().getStack().resolveStack();

        assertEquals(player.getLife(), life + 1);
        assertEquals(player.getManaPool().totalMana(), 0);
        assertEvents(observer, 0);
        assertFalse(player.hasTappedLandForManaThisTurn());
    }

    @Test
    public void ordinaryManaEffectWithZeroAmountDoesNotFireManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card land = addFixture(player, "Zero amount land", "Land", "A:AB$ Mana | Cost$ T | Produced$ G | Amount$ 0");
        Card observer = addObserver(player);

        activateMana(land);

        assertEquals(player.getManaPool().totalMana(), 0);
        assertEvents(observer, 0);
    }

    @Test
    public void replacingGreenWithZeroDoesNotFireManaEvents() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", player);
        addFixture(player, "Zero mana replacement", "Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land | ReplaceWith$ Replace",
                "SVar:Replace:DB$ ReplaceMana | ReplaceMana$ 0");
        Card observer = addObserver(player);

        activateMana(forest);

        assertEquals(player.getManaPool().totalMana(), 0);
        assertEvents(observer, 0);
    }

    @Test
    public void desolationCountsAnEmptyOrchardActivation() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card orchard = orchard(player, false);
        addCard("Desolation", player);

        activateMana(orchard);

        assertTrue(player.hasTappedLandForManaThisTurn());
        assertFalse(opponent.hasTappedLandForManaThisTurn());
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, player);
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(player), false);
        assertTrue(game.getStack().addAllTriggeredAbilitiesToStack());
        game.getStack().resolveStack();
        assertEquals(player.getCardsIn(ZoneType.Graveyard).get(0).getName(), "Exotic Orchard");
        assertEquals(opponent.getCardsIn(ZoneType.Battlefield).size(), 1);
    }

    @DataProvider
    public Object[][] mixedProduction() {
        return new Object[][] {
            {"W U U", 1, 2, 0},
            {"00 U 2", 0, 1, 2},
            {"00 0", 0, 0, 0},
            {"U  C", 0, 1, 1},
        };
    }

    @Test(dataProvider = "mixedProduction")
    public void mixedProductionFiresEventsInOrderOnlyWhenManaIsAdded(String output, int white, int blue, int colorless) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card forest = addCard("Forest", player);
        addFixture(player, "Mana replacement", "Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land | ReplaceWith$ Replace",
                "SVar:Replace:DB$ ReplaceMana | ReplaceMana$ " + output);
        addObserver(player);
        List<String> events = new ArrayList<>();
        game.setLocalObservation(new GameObservation() {
            @Override
            public void deckPrepared(Player recipient, Deck deck) {}

            @Override
            public void openingHand(Player recipient, boolean kept) {}

            @Override
            public void cardDrawn(Player recipient, Card card) {}

            @Override
            public void manaProduced(Card source, SpellAbility ability, Player recipient, String produced) {
                events.add("Observed: " + produced);
            }
        });

        player.runWithController(() -> activateMana(forest), new PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override
            public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) {
                events.add(ability.getTrigger().getMode().name());
                return super.playTrigger(host, ability, mandatory);
            }
        });

        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.WHITE), white);
        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.BLUE), blue);
        assertEquals(player.getManaPool().getAmountOfColor((byte) ManaAtom.COLORLESS), colorless);
        assertEquals(player.getManaPool().totalMana(), white + blue + colorless);
        assertEquals(events, white + blue + colorless > 0
                ? List.of("Observed: " + output, "ManaAdded", "TapsForMana")
                : List.of("Observed: " + output));
    }

    @Test
    public void oneRecipientCanProduceZeroWithoutSuppressingTheOther() {
        for (int blockedPlayerIndex : List.of(0, 1)) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(1);
            Player blocked = game.getPlayers().get(blockedPlayerIndex);
            Player recipient = game.getPlayers().get(1 - blockedPlayerIndex);
            Card land = addFixture(player, "Shared mana land", "Land", "A:AB$ Mana | Cost$ T | Defined$ Player | Produced$ G");
            addFixture(blocked, "Zero mana for you", "Enchantment",
                    "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land | ValidPlayer$ You | ReplaceWith$ Replace",
                    "SVar:Replace:DB$ ReplaceMana | ReplaceMana$ 0");
            Card observer = addObserver(player);

            activateMana(land);

            assertEquals(blocked.getManaPool().totalMana(), 0);
            assertEquals(recipient.getManaPool().getAmountOfColor(MagicColor.GREEN), 1);
            assertEvents(observer, 1);
        }
    }

    @Test
    public void preventedProductionClearsThePreviousResultAndPreservesTheManaAlreadyInThePool() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Card forest = addCard("Forest", player);
        Card observer = addObserver(player);
        SpellAbility ability = forest.getManaAbilities().get(0);
        activate(ability);
        assertEquals(ability.getManaPart().getLastManaProduced().size(), 1);
        assertEvents(observer, 1);

        forest.setTapped(false);
        player.setTappedLandForManaThisTurn(false);
        addFixture(player, "Prevent mana production", "Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land | Prevent$ True");
        activate(ability);

        assertEquals(player.getManaPool().totalMana(), 1);
        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.GREEN), 1);
        assertTrue(ability.getManaPart().getLastManaProduced().isEmpty());
        assertEvents(observer, 1);
        assertTrue(player.hasTappedLandForManaThisTurn());
    }

    private Card orchard(Player player, boolean withForest) {
        Player opponent = player.getGame().getPlayers().get(0);
        addCard("Exotic Orchard", opponent);
        Card orchard = addCard("Exotic Orchard", player);
        if (withForest) {
            addCard("Forest", player);
        }
        return orchard;
    }

    private static Card addObserver(Player player) {
        return addFixture(player, "Mana event observer", "Enchantment",
                "T:Mode$ TapsForMana | ValidCard$ Land | TriggerZones$ Battlefield | Static$ True | Execute$ Tapped",
                "SVar:Tapped:DB$ GainLife | Defined$ You | LifeAmount$ 1",
                "T:Mode$ ManaAdded | ValidSource$ Land | TriggerZones$ Battlefield | Static$ True | Execute$ Added",
                "SVar:Added:DB$ GainLife | Defined$ You | LifeAmount$ 2");
    }

    private static Card addFixture(Player player, String name, String types, String... script) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:no cost", "Types:" + types));
        lines.addAll(List.of(script));
        lines.add("Oracle:");
        Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "TST", CardRarity.Common),
                player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static void assertEvents(Card observer, int count) {
        assertEquals(triggerCount(observer, TriggerType.TapsForMana), count, "TapsForMana");
        assertEquals(triggerCount(observer, TriggerType.ManaAdded), count, "ManaAdded");
    }

    private static int triggerCount(Card card, TriggerType type) {
        Trigger trigger = card.getTriggers().stream().filter(t -> t.getMode() == type).findFirst().orElseThrow();
        return trigger.getOverridingAbility() == null ? 0 : trigger.getActivationsThisGame();
    }

    private static void activateMana(Card land) {
        SpellAbility ability = land.getManaAbilities().get(0);
        assertTrue(ability.isManaAbility());
        activate(ability);
    }

    private static void activate(SpellAbility ability) {
        Card source = ability.getHostCard();
        Player player = source.getController();
        Game game = player.getGame();
        game.getTriggerHandler().resetActiveTriggers();
        ability.setActivatingPlayer(player);
        assertTrue(ability.canPlay(), source.getName());
        assertTrue(new CostPayment(ability.getPayCosts(), ability)
                .payComputerCosts(new AiCostDecision(player, ability, false, true)), source.getName());
        assertTrue(source.isTapped(), source.getName());
        game.getStack().addAndUnfreeze(ability);
    }
}
