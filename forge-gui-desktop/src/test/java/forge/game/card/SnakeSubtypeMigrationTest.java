package forge.game.card;

import forge.ai.AITest;
import forge.card.CardType;
import forge.game.ability.AbilityUtils;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Set;

import static org.testng.Assert.*;

public class SnakeSubtypeMigrationTest extends AITest {
    @DataProvider
    public Object[][] editions() {
        return new Object[][] {
                {"IAMR", Map.ofEntries(
                        Map.entry("Abyssal Mistress", "Wizard"),
                        Map.entry("Chorus of Oblivion", "Wizard"),
                        Map.entry("Ctali Scout", "Scout"),
                        Map.entry("Ctali Warmonger", "Warrior"),
                        Map.entry("Deadwater Tyrant", "Warrior"),
                        Map.entry("Lady Kaladrin", "Wizard"),
                        Map.entry("Lord of Ctalis", "Advisor"),
                        Map.entry("Memory Thief I", "Wizard"),
                        Map.entry("Moonfathom Witch", "Shaman"),
                        Map.entry("Naga Tempest", "Wizard"),
                        Map.entry("Noble of Blood Bottom", "Wizard"),
                        Map.entry("Pearlreef Mystic", "Wizard"),
                        Map.entry("Traitorous Beauty", "Shaman"))},
                {"GMTG", Map.of(
                        "Charmer of the Abyss", "Wizard",
                        "Ctali Enforcer", "Warrior",
                        "Deadwater Shade", "Shade",
                        "Deephollow Stalker", "Rogue",
                        "Guard of Kaladrin", "Rogue",
                        "Murktide Invoker", "Wizard",
                        "Pearlreef Sage", "Wizard",
                        "Queen of the Depths", "Wizard",
                        "Screeching Witch", "Shaman",
                        "Stormtide Invoker", "Wizard")}
        };
    }

    @Test(dataProvider = "editions")
    public void editionPrintingsLoadWithMigratedTypes(String code, Map<String, String> migratedCards) {
        assertTrue(CardType.isACreatureType("Snake"));
        assertFalse(CardType.isACreatureType("Naga"));
        var game = initAndCreateGame();
        var player = game.getPlayers().get(1);
        var database = FModel.getMagicDb();
        var edition = database.getEditions().get(code);
        assertNotNull(edition, code);
        for (var entry : edition.getAllCardsInSet()) {
            PaperCard printing = database.getCommonCards().getCard(entry.name(), code, entry.collectorNumber());
            assertNotNull(printing, code + " #" + entry.collectorNumber() + " " + entry.name());
            assertEquals(printing.getEdition(), code);
            assertEquals(printing.getCollectorNumber(), entry.collectorNumber());
        }

        for (var entry : migratedCards.entrySet()) {
            String name = entry.getKey();
            PaperCard printing = database.getCommonCards().getCard(name, code);
            assertNotNull(printing, name);
            assertEquals(printing.getName(), name);
            var card = CardFactory.getCard(printing, player, game);
            assertTrue(card.isCreature(), name);
            assertEquals(card.getType().isLegendary(), name.equals("Lady Kaladrin"), name);
            assertEquals(card.getType().getCreatureTypes(), Set.of("Snake", entry.getValue()), name);
            card.getTriggers().forEach(trigger -> trigger.ensureAbility());
            card.getReplacementEffects().forEach(replacement -> replacement.ensureAbility());
        }
    }

    @Test
    public void migratedCardsInteractWithSnakeTargetsCountsAndLord() {
        var game = initAndCreateGame();
        var player = game.getPlayers().get(1);
        var opponent = game.getPlayers().get(0);
        var lord = addCard("Lord of Ctalis", player);
        var sanctuary = addCard("Animal Sanctuary", player);
        var butch = addCard("Butch DeLoria, Tunnel Snake", player);
        var bear = addCard("Grizzly Bears", player);
        var opposingSnake = addCard("Naga Vitalist", opponent);
        var mana = lord.getManaAbilities().get(0);
        mana.setActivatingPlayer(player);
        var sacrifice = (CostSacrifice) mana.getPayCosts().getCostParts().get(0);
        assertFalse(sacrifice.canPay(mana, player, false), "The Lord cannot sacrifice itself or an opposing Snake");

        var scout = addCard("Ctali Scout", player);
        var invoker = addCard("Stormtide Invoker", player);
        var officialSnake = addCard("Naga Vitalist", player);
        game.getAction().checkStaticAbilities();

        SpellAbility counter = sanctuary.getSpellAbilities().stream().filter(SpellAbility::usesTargeting)
                .findFirst().orElseThrow();
        counter.setActivatingPlayer(player);
        assertTrue(counter.canTarget(scout));
        assertTrue(counter.canTarget(invoker));
        assertFalse(counter.canTarget(bear));
        var pump = butch.getTriggers().get(0).ensureAbility();
        pump.setActivatingPlayer(player);
        assertEquals(AbilityUtils.calculateAmount(butch, "X", pump), 4);
        assertEquals(sacrifice.getMaxAmountX(mana, player, false), Integer.valueOf(3));
        assertTrue(sacrifice.canPay(mana, player, false));

        for (var snake : new Card[] {scout, invoker, officialSnake}) {
            assertEquals(snake.getNetPower(), snake.getBasePower() + 1, snake.getName());
            assertEquals(snake.getNetToughness(), snake.getBaseToughness() + 1, snake.getName());
            assertTrue(snake.hasKeyword(Keyword.INTIMIDATE), snake.getName());
        }
        for (var unaffected : new Card[] {lord, bear, opposingSnake}) {
            assertEquals(unaffected.getNetPower(), unaffected.getBasePower(), unaffected.getName());
            assertFalse(unaffected.hasKeyword(Keyword.INTIMIDATE), unaffected.getName());
        }
    }
}
