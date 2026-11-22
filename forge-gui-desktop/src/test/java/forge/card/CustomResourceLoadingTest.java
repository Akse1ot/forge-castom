package forge.card;

import forge.StaticData;
import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.item.PaperCard;
import forge.item.PaperToken;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import static org.testng.Assert.*;

/** Guards custom scripts and digital printings against resource-loader changes from upstream. */
public class CustomResourceLoadingTest extends AITest {
    private static boolean custom(CardRules rules) {
        String path = rules.getPath();
        return rules.isCustom() || path != null && path.replace('\\', '/').toLowerCase(Locale.ROOT).contains("custom");
    }

    @Test
    public void loadedCustomCardsAndTokensStillBuildTheirGameObjects() {
        Player player = initAndCreateGame().getPlayers().get(1);
        Set<CardRules> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int cards = 0;
        for (PaperCard printed : StaticData.instance().getCommonCards().getAllCards()) {
            if (custom(printed.getRules()) && seen.add(printed.getRules())) {
                Card card = CardFactory.getCard(printed, player, player.getGame());
                assertEquals(card.getName(), printed.getName());
                assertSame(card.getOwner(), player);
                cards++;
            }
        }
        int tokens = 0;
        // Token printings are loaded on demand; getAllTokens() only lists cached printings.
        for (var entry : StaticData.instance().getAllTokens().getRules().entrySet()) {
            if (custom(entry.getValue()) && seen.add(entry.getValue())) {
                PaperToken printed = StaticData.instance().getAllTokens().getToken(entry.getKey());
                assertNotNull(printed, entry.getKey());
                Card card = CardFactory.getCard(printed, player, player.getGame());
                assertEquals(card.getName(), printed.getName());
                assertSame(card.getOwner(), player);
                tokens++;
            }
        }
        assertTrue(cards > 0, "custom cards must actually be loaded");
        assertTrue(tokens > 0, "custom tokens must actually be loaded");
        System.out.printf("CUSTOM RESOURCE OBJECTS cards=%d tokens=%d%n", cards, tokens);
    }

    @Test
    public void rebalancedEditionsStillExposeTheirDigitalPrintings() {
        int printings = 0;
        for (CardEdition edition : StaticData.instance().getEditions()) {
            for (CardEdition.EditionEntry entry : edition.getRebalancedCards()) {
                PaperCard card = StaticData.instance().getCommonCards().getCard(entry.name(), edition.getCode());
                assertNotNull(card, edition.getCode() + ": " + entry.name());
                assertEquals(card.getName(), entry.name());
                assertEquals(card.getEdition(), edition.getCode());
                assertTrue(StaticData.instance().isRebalanced(entry.name()), entry.name());
                printings++;
            }
        }
        assertTrue(printings > 0, "rebalanced sections must not silently disappear");
        System.out.printf("REBALANCED PRINTINGS %d%n", printings);
    }
}
