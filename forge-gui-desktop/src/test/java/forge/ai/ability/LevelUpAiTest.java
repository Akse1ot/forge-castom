package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class LevelUpAiTest extends AITest {
    @DataProvider
    public Object[][] levelCaps() {
        return new Object[][] {
                {"Transcendent Master", 6, true},
                {"Transcendent Master", 11, true},
                {"Transcendent Master", 12, false},
                {"Transcendent Master", 13, false},
                {"Abyssal Horseman", 3, true},
                {"Abyssal Horseman", 11, true},
                {"Abyssal Horseman", 12, false},
                {"Throne of the Ur-Kraken", 15, true},
                {"Throne of the Ur-Kraken", 16, false},
                {"Knight of Cliffhaven", 3, true},
                {"Knight of Cliffhaven", 4, false},
                {"Unpolished Pearl", 3, true},
                {"Unpolished Pearl", 4, false}
        };
    }

    @Test(dataProvider = "levelCaps")
    public void respectsTheCompleteLevelCap(String name, int counters, boolean expected) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCard(name, ai);
        card.setCounters(CounterEnumType.LEVEL, counters);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        assertEquals(new CountersPutAi().checkPhaseRestrictions(ai, levelUp(card, ai),
                game.getPhaseHandler()), expected);
    }

    @DataProvider
    public Object[][] highLevels() {
        return new Object[][] {
                {"Transcendent Master", 6},
                {"Transcendent Master", 9},
                {"Transcendent Master", 10},
                {"Transcendent Master", 11},
                {"Abyssal Horseman", 10},
                {"Abyssal Horseman", 11},
                {"Throne of the Ur-Kraken", 15}
        };
    }

    @Test(dataProvider = "highLevels")
    public void levelCountersDoNotCauseRandomRefusal(String name, int counters) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCard(name, ai);
        addCards("Plains", 1, ai);
        addCards("Swamp", 1, ai);
        card.setCounters(CounterEnumType.LEVEL, counters);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        SpellAbility ability = levelUp(card, ai);
        CountersPutAi logic = new CountersPutAi();
        for (int i = 0; i < 20; i++) {
            assertEquals(logic.canPlayWithSubs(ai, ability).decision(), AiPlayDecision.WillPlay,
                    "Level up must not randomly fail because of existing level counters");
        }
    }

    @Test
    public void unrelatedCountersKeepTheirExistingRefusalRule() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCard("Transcendent Master", ai);
        card.setCounters(CounterEnumType.CHARGE, 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ PutCounter | Cost$ 0 | Defined$ Self | CounterType$ CHARGE", card);
        ability.setActivatingPlayer(ai);
        assertEquals(new CountersPutAi().checkApiLogic(ai, ability).decision(), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void malformedLevelDescriptionsAreIgnored() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCard("Knight of Cliffhaven", ai);
        card.addStaticAbility("Mode$ Continuous | Affected$ Card.Self | Description$ LEVEL invalid");
        card.addStaticAbility("Mode$ Continuous | Affected$ Card.Self | Description$ LEVEL 999999999999+");
        card.setCounters(CounterEnumType.LEVEL, 4);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        assertFalse(new CountersPutAi().checkPhaseRestrictions(ai, levelUp(card, ai), game.getPhaseHandler()));
    }

    @Test
    public void resolvingLevelUpEnablesThePrintedTwelfthLevel() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCard("Transcendent Master", ai);
        card.setCounters(CounterEnumType.LEVEL, 11);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        assertEquals(card.getNetPower(), 6);
        assertTrue(card.hasKeyword(Keyword.LIFELINK));
        assertFalse(card.hasKeyword(Keyword.INDESTRUCTIBLE));
        SpellAbility ability = levelUp(card, ai);
        assertTrue(ability.canPlay());
        AbilityUtils.resolve(ability);
        game.getAction().checkStateEffects(true);
        assertEquals(card.getCounters(CounterEnumType.LEVEL), 12);
        assertEquals(card.getNetPower(), 9);
        assertEquals(card.getNetToughness(), 9);
        assertTrue(card.hasKeyword(Keyword.LIFELINK));
        assertTrue(card.hasKeyword(Keyword.INDESTRUCTIBLE));
        // The upper level is an AI stopping rule, not a rules restriction on activating level up.
        assertTrue(ability.canPlay());
        assertFalse(new CountersPutAi().checkPhaseRestrictions(ai, ability, game.getPhaseHandler()));
    }

    private SpellAbility levelUp(Card card, Player ai) {
        SpellAbility ability = card.getSpellAbilities().stream()
                .filter(sa -> sa.isKeyword(Keyword.LEVEL_UP)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing level up on " + card.getName()));
        ability.setActivatingPlayer(ai);
        return ability;
    }
}
