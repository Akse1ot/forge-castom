package forge.ai;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.cost.CostPayment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.PlayerControllerForTests;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class HollowgroundAttendantTest extends AITest {
    private static final String ATTENDANT = "Hollowground Attendant";
    private static final String TRIGGER_TEXT = "Whenever you tap a land for mana, add one mana of a color that land can't produce.";

    @DataProvider
    public Object[][] landChoices() {
        return new Object[][] {
            {"Forest", "", "", 'G', "WUBR"},
            {"Island", "", "", 'U', "WBRG"},
            {"Wastes", "", "", 'C', "WUBRG"},
            {"Azorius Guildgate", "", "", 'W', "BRG"},
            {"Mana Confluence", "", "", 'G', ""},
            {"Exotic Orchard", "Forest", "Exotic Orchard", 'G', "WUBR"},
            {"Exotic Orchard", "", "Exotic Orchard", '\0', ""},
            {"Reflecting Pool", "Azorius Guildgate", "", 'U', "BRG"},
            {"Yavimaya Coast", "", "", 'C', "WBR"},
            {"Forest", "Urborg, Tomb of Yawgmoth", "", 'G', "WUR"},
            {"Forest", "Chromatic Lantern", "", 'G', ""},
            {"Forest", "", "Contamination", 'B', "WUBR"},
        };
    }

    @Test(dataProvider = "landChoices")
    public void tappingLandOffersAndProducesEachAllowedColor(String landName, String support, String opposingSupport,
            char produced, String choices) {
        for (char choice : (choices.isEmpty() ? new char[] {'\0'} : choices.toCharArray())) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(1);
            Card land = addCard(landName, player);
            if (!support.isEmpty()) {
                addCard(support, player);
            }
            if (!opposingSupport.isEmpty()) {
                addCard(opposingSupport, game.getPlayers().get(0));
            }
            Card attendant = addAttendant(player);
            ManaChoices controller = new ManaChoices(player, produced, mask(choices), Map.of(attendant, choice), null);

            player.runWithController(() -> activate(land, produced), controller);

            assertEquals(controller.menus, choice == '\0' ? List.of() : List.of(mask(choices)));
            assertEquals(countManaTriggers(attendant), produced == '\0' ? 0 : 1);
            assertPool(player, produced, choice);
            assertTrue(player.hasTappedLandForManaThisTurn());
            assertEmptyStack(game);
        }
    }

    @DataProvider
    public Object[][] expressChoices() {
        return new Object[][] {
            {"G", "WUBR", 'U', 'U'},
            {"UG", "U", 'U', 'U'},
            {"C", "WUBR", 'W', 'W'},
            {"U", "U", 'G', '\0'},
            {"U", "U", 'C', '\0'},
        };
    }

    @Test(dataProvider = "expressChoices")
    public void expressChoiceAndControllerCannotForceAnExcludedColor(String express, String menu, char answer, char added) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = addCard("Forest", player);
        Card attendant = addAttendant(player);
        ManaChoices controller = new ManaChoices(player, 'G', mask(menu), Map.of(attendant, answer), express);

        player.runWithController(() -> activate(land), controller);

        assertEquals(controller.menus, List.of(mask(menu)));
        assertEquals(countManaTriggers(attendant), 1);
        assertPool(player, 'G', added);
        assertEmptyStack(game);
    }

    @Test
    public void allColorsLandIgnoresExpressChoiceWithoutPrompting() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = addCard("Mana Confluence", player);
        Card attendant = addAttendant(player);
        ManaChoices controller = new ManaChoices(player, 'G', (byte) 0, Map.of(attendant, 'U'), "U");

        player.runWithController(() -> activate(land), controller);

        assertTrue(controller.menus.isEmpty());
        assertEquals(countManaTriggers(attendant), 1);
        assertPool(player, 'G');
        assertEmptyStack(game);
    }

    @Test
    public void onlyRemainingColorIsAddedWithoutPrompting() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = fixture(player, List.of("Name:Four-color land", "ManaCost:no cost", "Types:Land",
                "A:AB$ Mana | Cost$ T | Produced$ W U B R", "Oracle:"));
        Card attendant = addAttendant(player);
        ManaChoices controller = new ManaChoices(player, 'W', (byte) 0, Map.of(attendant, 'G'), null);

        player.runWithController(() -> activate(land), controller);

        assertTrue(controller.menus.isEmpty());
        assertEquals(countManaTriggers(attendant), 1);
        assertPool(player, 'W', 'U', 'B', 'R', 'G');
        assertEmptyStack(game);
    }

    @Test
    public void opponentTappingLandDoesNotTriggerAttendant() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card forest = addCard("Forest", opponent);
        Card attendant = addAttendant(player);

        activate(forest);

        assertEquals(countManaTriggers(attendant), 0);
        assertPool(opponent, 'G');
        assertPool(player);
        assertEmptyStack(game);
    }

    @Test
    public void twoAttendantsAndNikyaResolveIndependentlyWithoutAnotherTapEvent() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = addCard("Forest", player);
        Card first = addAttendant(player);
        Card second = addAttendant(player);
        Card nikya = addCard("Nikya of the Old Ways", player);
        Card observer = fixture(player, List.of("Name:Mana tap observer", "ManaCost:0", "Types:Enchantment",
                "T:Mode$ TapsForMana | TriggerZones$ Battlefield | Static$ True | Execute$ Observe",
                "SVar:Observe:DB$ GainLife | Defined$ You | LifeAmount$ 1", "Oracle:"));
        byte menu = mask("WUBR");
        ManaChoices controller = new ManaChoices(player, 'G', menu, Map.of(first, 'W', second, 'U'), null);

        player.runWithController(() -> activate(land), controller);

        assertEquals(controller.menus, List.of(menu, menu));
        for (Card card : List.of(first, second, nikya, observer)) {
            assertEquals(countManaTriggers(card), 1, card.getName());
        }
        assertPool(player, 'G', 'G', 'W', 'U');
        assertEmptyStack(game);
    }

    @Test
    public void meteorCraterStillReflectsTheColorOfAPermanent() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Grizzly Bears", player);
        Card crater = addCard("Meteor Crater", player);

        activate(crater);

        assertPool(player, 'G');
        assertEmptyStack(game);
    }

    @DataProvider
    public Object[][] autoPayments() {
        return new Object[][] {
            {"Forest", "", "", 1, "U", true},
            {"Forest", "", "", 1, "G U", true},
            {"Forest", "", "", 2, "G W U", true},
            {"Forest", "", "", 1, "G G", false},
            {"Island", "", "", 1, "R", true},
            {"Wastes", "", "", 1, "C B", true},
            {"Wastes", "", "", 1, "C C", false},
            {"Azorius Guildgate", "", "", 1, "B U", true},
            {"Reflecting Pool", "Azorius Guildgate", "", 1, "B U", true},
            {"Exotic Orchard", "", "Forest", 1, "R G", true},
            {"Exotic Orchard", "", "Exotic Orchard", 1, "U", false},
            {"Mana Confluence", "", "", 1, "U G", false},
            {"Forest", "", "Contamination", 1, "B U", true},
        };
    }

    @Test
    public void preventedProductionIsNotAColoredSourceThroughAttendant() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card forest = addCard("Forest", player);
        addAttendant(player);
        fixture(player, List.of("Name:Prevent land mana", "ManaCost:0", "Types:Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land | Prevent$ True", "Oracle:"));

        checkAutoPayment(player, forest, "U", false);
        assertFalse(forest.isTapped());
    }

    @Test
    public void aiConsidersASpellPayableWithAttendantMana() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Forest", player);
        addAttendant(player);
        Card spell = addCardToZone("River Hoopoe", player, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, player);
        game.getTriggerHandler().resetActiveTriggers();

        assertEquals(ComputerUtilMana.getAvailableManaEstimate(player), 2);
        List<SpellAbility> plays = ((PlayerControllerAi) player.getController()).getAi().chooseSpellAbilityToPlay();
        assertNotNull(plays);
        assertFalse(plays.isEmpty());
        assertEquals(plays.get(0).getHostCard(), spell);
    }

    @Test(dataProvider = "autoPayments")
    public void aiForecastAndActualPaymentAgree(String landName, String support, String opposingSupport,
            int attendants, String cost, boolean expected) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = addCard(landName, player);
        if (!support.isEmpty()) {
            addCard(support, player).setTapped(true);
        }
        if (!opposingSupport.isEmpty()) {
            addCard(opposingSupport, game.getPlayers().get(0));
        }
        for (int i = 0; i < attendants; i++) {
            addAttendant(player);
        }
        checkAutoPayment(player, source, cost, expected);
    }

    @Test
    public void humanAutoPaymentUsesTheBonusWithoutChangingTheManualChoice() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = addCard("Forest", player);
        Card attendant = addAttendant(player);
        player.dangerouslySetController(new PlayerControllerForTests(game, player, player.getLobbyPlayer()));
        PlayerControllerAi auto = new PlayerControllerAi(game, player, player.getLobbyPlayer());
        player.runWithController(() -> checkAutoPayment(player, source, "U", true), auto);

        assertTrue(attendant.getTriggers().get(0).ensureAbility().getManaPart().getExpressChoice().isEmpty());
        source.setTapped(false);
        player.getManaPool().clearPool(false);
        ManaChoices manual = new ManaChoices(player, 'G', mask("WUBR"), Map.of(attendant, 'R'), null);
        player.runWithController(() -> activate(source), manual);
        assertPool(player, 'G', 'R');
    }

    private void checkAutoPayment(Player player, Card source, String cost, boolean expected) {
        SpellAbility spell = addCardToZone("Grizzly Bears", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        player.getGame().getTriggerHandler().resetActiveTriggers();
        ManaCost manaCost = new ManaCost(new ManaCostParser(cost));
        assertEquals(ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(manaCost), spell, player, false), expected, "forecast");
        assertFalse(source.isTapped(), "forecast must not activate the land");

        ManaCostBeingPaid payment = new ManaCostBeingPaid(manaCost);
        assertEquals(ComputerUtilMana.payManaCost(payment, spell, player, false), expected, "payment");
        assertEquals(payment.isPaid(), expected);
        if (expected) {
            assertTrue(source.isTapped());
        }
    }

    private static Card addAttendant(Player player) {
        return fixture(player, List.of(
                "Name:" + ATTENDANT, "ManaCost:3 G G", "Types:Creature Spirit", "PT:3/3", "K:Vigilance",
                "T:Mode$ TapsForMana | ValidCard$ Land | Activator$ You | Execute$ TrigMana | TriggerZones$ Battlefield"
                        + " | Static$ True | TriggerDescription$ " + TRIGGER_TEXT,
                "SVar:TrigMana:DB$ ManaReflected | ColorOrType$ Color | Valid$ Defined.TriggeredCard"
                        + " | ReflectProperty$ CannotProduce | Defined$ You",
                "Oracle:Vigilance\\n" + TRIGGER_TEXT));
    }

    private static Card fixture(Player player, List<String> script) {
        CardRules rules = CardRules.fromScript(script);
        Card card = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static void activate(Card land) {
        activate(land, '\0');
    }

    private static void activate(Card land, char preferredProduction) {
        Player player = land.getController();
        Game game = land.getGame();
        game.getAction().checkStaticAbilities();
        game.getTriggerHandler().resetActiveTriggers();
        var abilities = land.getManaAbilities();
        SpellAbility ability = abilities.stream()
                .filter(candidate -> candidate.getManaPart().getOrigProduced().equals(String.valueOf(preferredProduction)))
                .findFirst().orElse(abilities.get(0));
        ability.setActivatingPlayer(player);
        assertTrue(ability.canPlay(), land.getName());
        assertTrue(new CostPayment(ability.getPayCosts(), ability)
                .payComputerCosts(new AiCostDecision(player, ability, false, true)), land.getName());
        assertTrue(land.isTapped(), land.getName());
        game.getStack().addAndUnfreeze(ability);
    }

    private static int countManaTriggers(Card card) {
        Trigger trigger = card.getTriggers().stream().filter(t -> t.getMode() == TriggerType.TapsForMana).findFirst().orElseThrow();
        return trigger.getOverridingAbility() == null ? 0 : trigger.getActivationsThisGame();
    }

    private static void assertPool(Player player, char... symbols) {
        Map<Byte, Integer> expected = new HashMap<>();
        for (char symbol : symbols) {
            if (symbol != '\0') {
                expected.merge(ManaAtom.fromName(symbol), 1, Integer::sum);
            }
        }
        Map<Byte, Integer> actual = new HashMap<>();
        player.getManaPool().forEach(mana -> actual.merge(mana.getColor(), 1, Integer::sum));
        assertEquals(actual, expected);
    }

    private static void assertEmptyStack(Game game) {
        assertTrue(game.getStack().isEmpty());
        assertFalse(game.getStack().hasSimultaneousStackEntries());
    }

    private static byte mask(String colors) {
        byte result = 0;
        for (char color : colors.toCharArray()) {
            result |= MagicColor.fromName(color);
        }
        return result;
    }

    private static final class ManaChoices extends PlayerControllerAi {
        private final char landColor;
        private final byte expectedMenu;
        private final Map<Card, Character> attendantChoices;
        private final String expressChoice;
        private final List<Byte> menus = new ArrayList<>();

        private ManaChoices(Player player, char landColor, byte expectedMenu, Map<Card, Character> attendantChoices, String expressChoice) {
            super(player.getGame(), player, player.getLobbyPlayer());
            this.landColor = landColor;
            this.expectedMenu = expectedMenu;
            this.attendantChoices = attendantChoices;
            this.expressChoice = expressChoice;
        }

        @Override
        public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) {
            if (attendantChoices.containsKey(host)) {
                SpellAbility effect = ability.getWrappedAbility();
                assertTrue(effect.isManaAbility());
                assertEmptyStack(host.getGame());
                if (expressChoice != null) {
                    effect.getManaPart().setExpressChoice(expressChoice);
                }
            }
            return super.playTrigger(host, ability, mandatory);
        }

        @Override
        public byte chooseColor(String message, SpellAbility ability, ColorSet options) {
            Card host = ability.getHostCard();
            if (attendantChoices.containsKey(host)) {
                menus.add(options.getColor());
                assertEquals(options.getColor(), expectedMenu);
                return MagicColor.fromName(attendantChoices.get(host));
            }
            if (host.isLand()) {
                byte choice = MagicColor.fromName(landColor);
                assertTrue(options.hasAnyColor(choice), host.getName());
                return choice;
            }
            return super.chooseColor(message, ability, options);
        }
    }
}
