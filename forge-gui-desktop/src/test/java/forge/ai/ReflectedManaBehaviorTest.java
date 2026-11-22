package forge.ai;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardFactory;
import forge.game.card.CardUtil;
import forge.game.cost.CostPayment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.PlayerControllerForTests;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ReflectedManaBehaviorTest extends AITest {
    private record Board(Player active, Player opponent) {
    }

    private Board board() {
        Game game = initAndCreateGame();
        return new Board(game.getPlayers().get(1), game.getPlayers().get(0));
    }

    @Test
    public void twoOrchardsWithoutAnAnchor() {
        Board board = board();
        Card first = addCard("Exotic Orchard", board.active());
        Card second = addCard("Exotic Orchard", board.opponent());

        assertSource(first, Set.of());
        assertSource(second, Set.of());
    }

    @Test
    public void twoOrchardsWithForestOnOneSide() {
        Board board = board();
        addCard("Forest", board.active());
        Card first = addCard("Exotic Orchard", board.active());
        Card second = addCard("Exotic Orchard", board.opponent());

        assertSource(first, Set.of("green"));
        assertSource(second, Set.of("green"));
    }

    @Test
    public void orchardCyclesWithMultipleBranches() {
        Board board = board();
        List<Card> first = addCards("Exotic Orchard", 3, board.active());
        List<Card> second = addCards("Exotic Orchard", 3, board.opponent());
        for (Card orchard : first) {
            assertSource(orchard, Set.of());
        }
        for (Card orchard : second) {
            assertSource(orchard, Set.of());
        }

        addCard("Forest", board.active());
        addCard("Island", board.opponent());
        for (Card orchard : first) {
            assertSource(orchard, Set.of("green", "blue"));
        }
        for (Card orchard : second) {
            assertSource(orchard, Set.of("green", "blue"));
        }
    }

    @Test
    public void reflectingPoolWithBasicAndDualLands() {
        Board board = board();
        addCard("Forest", board.active()).setTapped(true);
        addCard("Azorius Guildgate", board.active());
        addCard("Mountain", board.opponent());

        assertSource(addCard("Reflecting Pool", board.active()), Set.of("green", "white", "blue"));
    }

    @Test
    public void landWithMultipleManaAbilities() {
        Board board = board();
        Card coast = addCard("Yavimaya Coast", board.active());
        assertEquals(coast.getManaAbilities().size(), 2);
        assertSource(coast, Set.of("green", "blue", "colorless"));
        assertSource(addCard("Reflecting Pool", board.active()), Set.of("green", "blue", "colorless"));
    }

    @Test
    public void grantedManaAbilityUpdatesTheLandAndPool() {
        Board board = board();
        Card forest = addCard("Forest", board.active());
        Card pool = addCard("Reflecting Pool", board.active());
        assertSource(forest, Set.of("green"));
        assertSource(pool, Set.of("green"));

        addCard("Chromatic Lantern", board.active());
        board.active().getGame().getAction().checkStaticAbilities();
        assertEquals(forest.getManaAbilities().size(), 2);
        assertSource(forest, Set.copyOf(MagicColor.Constant.ONLY_COLORS));
        assertSource(pool, Set.copyOf(MagicColor.Constant.ONLY_COLORS));
    }

    @Test
    public void landThatMakesAnyColor() {
        Board board = board();
        Set<String> all = Set.copyOf(MagicColor.Constant.ONLY_COLORS);
        assertSource(addCard("Mana Confluence", board.active()), all);
        assertSource(addCard("Exotic Orchard", board.opponent()), all);
    }

    @Test
    public void attendantDraftScriptBindsTheTriggeringLand() {
        Board board = board();
        CardRules rules = CardRules.fromScript(List.of(
                "Name:Hollowground Attendant", "ManaCost:3 G G", "Types:Creature Spirit", "PT:3/3", "K:Vigilance",
                "T:Mode$ TapsForMana | ValidCard$ Land | Activator$ You | Execute$ TrigMana | TriggerZones$ Battlefield"
                        + " | Static$ True | TriggerDescription$ Whenever you tap a land for mana, add one mana of a color that land can't produce.",
                "SVar:TrigMana:DB$ ManaReflected | ColorOrType$ Color | Valid$ Defined.TriggeredCard"
                        + " | ReflectProperty$ CannotProduce | Defined$ You",
                "Oracle:Vigilance\\nWhenever you tap a land for mana, add one mana of a color that land can't produce."));
        Card attendant = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), board.active(), board.active().getGame());
        Card forest = addCard("Forest", board.active());
        Trigger trigger = attendant.getTriggers().get(0);
        assertTrue(trigger.isStatic());
        Map<AbilityKey, Object> event = AbilityKey.mapFromCard(forest);
        event.put(AbilityKey.Activator, board.active());
        event.put(AbilityKey.Produced, "G");
        assertTrue(trigger.performTest(event));
        SpellAbility effect = trigger.ensureAbility();
        effect.setTriggeringObjects(trigger.buildTriggeringObjects(event));
        assertEquals(effect.getApi(), ApiType.ManaReflected);
        assertEquals(effect.getParam("ReflectProperty"), "CannotProduce");
        assertEquals(AbilityUtils.getDefinedCards(attendant, effect.getParam("Valid").substring("Defined.".length()), effect).get(0), forest);
    }

    @DataProvider
    public Object[][] replacements() {
        return new Object[][] {
            {"Forest", "Contamination", "black", "black"},
            {"Exotic Orchard", "Contamination", "black", "black"},
            {"Reflecting Pool", "Contamination", "black", "black"},
            {"Forest", "Infernal Darkness", "black", "black"},
            {"Forest", "Color replacement", "white", "white"},
        };
    }

    @Test(dataProvider = "replacements")
    public void compareCapabilityToActualReplacement(String land, String replacement,
            String declaredColor, String actualColor) {
        Board board = board();
        if (!land.equals("Forest")) {
            addCard("Forest", land.equals("Exotic Orchard") ? board.opponent() : board.active());
        }
        Card source = addCard(land, board.active());
        if (replacement.equals("Color replacement")) {
            addReplacement(board.active(), replacement, "ReplaceColor$ White");
        } else {
            addCard(replacement, board.opponent());
        }

        // CR 106.7 includes replacements in the declared capability, as well as in direct production.
        assertDeclaredColors(source, Set.of(declaredColor));
        activate(source.getManaAbilities().get(0));
        assertEquals(poolColors(board.active()), Set.of(actualColor));
    }

    @Test
    public void replacementOrderChangesTheSetOfPossibleColors() {
        Board board = board();
        Card forest = addCard("Forest", board.active());
        Card black = addCard("Contamination", board.opponent());
        Card white = addReplacement(board.active(), "Color replacement", "ReplaceColor$ White");
        assertDeclaredColors(forest, Set.of("black", "white"));

        for (Card first : List.of(black, white)) {
            board.active().runWithController(() -> {
                SpellAbility mana = forest.getManaAbilities().get(0);
                mana.setActivatingPlayer(board.active());
                assertEquals(ComputerUtilMana.predictMana(mana, board.active(),
                        first == black ? forge.card.mana.ManaCostShard.WHITE : forge.card.mana.ManaCostShard.BLACK),
                        first == black ? "W" : "B");
                assertFalse(forest.isTapped(), "forecast must not activate the source");
                activate(mana);
                assertEquals(poolColors(board.active()), Set.of(first == black ? "white" : "black"));
            }, new PlayerControllerAi(forest.getGame(), board.active(), board.active().getLobbyPlayer()) {
                @Override
                public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> effects) {
                    if (effects.size() == 1) {
                        return effects.get(0);
                    }
                    return effects.stream().filter(effect -> effect.getHostCard() == first).findFirst().orElseThrow();
                }
            });
            forest.setTapped(false);
            board.active().getManaPool().clearPool(false);
        }
    }

    @Test
    public void orchardReadsTheReplacedColorFromAnOpposingForest() {
        Board board = board();
        Card forest = addCard("Forest", board.opponent());
        Card orchard = addCard("Exotic Orchard", board.active());
        addReplacement(board.opponent(), "Opponent's color replacement", "ReplaceColor$ White");

        activate(forest.getManaAbilities().get(0));
        assertEquals(poolColors(board.opponent()), Set.of("white"));
        // Scoped to the opponent's lands: Orchard must reflect white without being replaced itself.
        assertSource(orchard, Set.of("white"));
    }

    @DataProvider
    public Object[][] payments() {
        return new Object[][] {
            {"Exotic Orchard", "Island", "U", false, true},
            {"Exotic Orchard", "Island", "R", false, false},
            {"Exotic Orchard", null, "1", false, false},
            {"Reflecting Pool", "Azorius Guildgate", "U", false, true},
            {"Reflecting Pool", "Wastes", "C", false, true},
            {"Reflecting Pool", "Azorius Guildgate", "R", false, false},
            {"Forest", null, "B", true, true},
            // Reflected sources can pay black after Contamination, but cannot still pay their former color.
            {"Exotic Orchard", "Island", "B", true, true},
            {"Exotic Orchard", "Island", "U", true, false},
            {"Reflecting Pool", "Forest", "B", true, true},
        };
    }

    @Test(dataProvider = "payments")
    public void predictAndPayUsingTheSamePlanner(String land, String anchor, String cost,
            boolean contaminated, boolean expected) {
        Board board = board();
        Player player = board.active();
        Card source = addCard(land, player);
        if (anchor != null) {
            addCard(anchor, land.equals("Exotic Orchard") ? board.opponent() : player).setTapped(true);
        }
        if (contaminated) {
            addCard("Contamination", board.opponent());
        }
        SpellAbility spell = addCardToZone("Grizzly Bears", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        ManaCost manaCost = new ManaCost(new ManaCostParser(cost));
        boolean predicted = ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(manaCost), spell, player, false);

        ManaCostBeingPaid payment = new ManaCostBeingPaid(manaCost);
        boolean paid = ComputerUtilMana.payManaCost(payment, spell, player, false);
        assertEquals(predicted, expected, "payment forecast");
        assertEquals(paid, expected, "actual payment");
        assertEquals(payment.isPaid(), expected, "remaining cost");
        if (expected) {
            assertTrue(source.isTapped());
        } else if (contaminated) {
            assertFalse(source.isTapped());
        }
    }

    @Test
    public void humanAutoPaymentUsesTheChosenReflectedColor() {
        Board board = board();
        Player player = board.active();
        Card orchard = addCard("Exotic Orchard", player);
        addCard("Island", board.opponent());
        addCard("Mountain", board.opponent());
        SpellAbility spell = addCardToZone("Grizzly Bears", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        ManaCost cost = new ManaCost(new ManaCostParser("U"));
        player.dangerouslySetController(new PlayerControllerForTests(player.getGame(), player, player.getLobbyPlayer()));
        assertFalse(player.isAI());
        player.runWithController(() -> {
            CardCollection plan = ComputerUtilMana.getManaSourcesToPayCost(new ManaCostBeingPaid(cost), spell, player, false);
            assertEquals(plan.size(), 1);
            assertEquals(plan.get(0), orchard);
            ManaCostBeingPaid payment = new ManaCostBeingPaid(cost);
            assertTrue(ComputerUtilMana.payManaCost(payment, spell, player, false));
            assertTrue(payment.isPaid());
        }, new PlayerControllerAi(player.getGame(), player, player.getLobbyPlayer()));
        assertTrue(orchard.isTapped());
    }

    private static Card addReplacement(Player player, String name, String params) {
        CardRules rules = CardRules.fromScript(List.of("Name:" + name, "ManaCost:0", "Types:Enchantment",
                "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land.YouCtrl | ReplaceWith$ Change",
                "SVar:Change:DB$ ReplaceMana | " + params, "Oracle:"));
        Card card = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static void assertSource(Card card, Set<String> expected) {
        assertDeclaredColors(card, expected);
        Set<String> produced = new HashSet<>();
        Player player = card.getController();
        for (SpellAbility ability : List.copyOf(card.getManaAbilities())) {
            boolean hasChoice = ability.getApi() == ApiType.ManaReflected
                    || ability.getManaPart().isAnyMana() || ability.getManaPart().isComboMana();
            byte[] preferences = hasChoice ? MagicColor.WUBRGC : new byte[] {MagicColor.COLORLESS};
            for (byte preference : preferences) {
                player.runWithController(() -> activate(ability), new PlayerControllerAi(card.getGame(), player, player.getLobbyPlayer()) {
                    @Override
                    public byte chooseColor(String message, SpellAbility sa, ColorSet options) {
                        return (options.getColor() & preference) != 0
                                ? preference : super.chooseColor(message, sa, options);
                    }

                    @Override
                    public byte chooseColorAllowColorless(String message, Card source, ColorSet options) {
                        return preference == MagicColor.COLORLESS || (options.getColor() & preference) != 0
                                ? preference : super.chooseColorAllowColorless(message, source, options);
                    }
                });
                produced.addAll(poolColors(player));
                player.getManaPool().clearPool(false);
                card.setTapped(false);
            }
        }
        assertEquals(produced, expected, card.getName());
    }

    private static void assertDeclaredColors(Card card, Set<String> expected) {
        assertEquals(card.getProducibleColors(), expected, card.getName());
        for (SpellAbility ability : card.getManaAbilities()) {
            if (ability.getApi() == ApiType.ManaReflected) {
                assertEquals(CardUtil.getReflectableManaColors(ability), expected, card.getName());
            }
        }
        for (String color : MagicColor.Constant.COLORS_AND_COLORLESS) {
            assertEquals(card.canProduceColorMana(Set.of(color)), expected.contains(color), card.getName() + ": " + color);
        }
    }

    private static void activate(SpellAbility ability) {
        Card source = ability.getHostCard();
        Player player = source.getController();
        ability.setActivatingPlayer(player);
        assertTrue(ability.canPlay(), source.getName());
        ability.getManaPart().clearExpressChoice();
        assertTrue(new CostPayment(ability.getPayCosts(), ability)
                .payComputerCosts(new AiCostDecision(player, ability, false, true)), source.getName());
        assertTrue(source.isTapped(), source.getName());
        source.getGame().getStack().addAndUnfreeze(ability);
    }

    private static Set<String> poolColors(Player player) {
        Set<String> colors = new HashSet<>();
        player.getManaPool().forEach(mana -> colors.add(mana.isColorless()
                ? MagicColor.Constant.COLORLESS : MagicColor.toLongString(mana.getColor())));
        return colors;
    }
}
