package forge.game.ability.effects;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameSimulator;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.GamePieceType;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.player.LobbyPlayerHuman;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class SubjugateEffectTest extends AITest {
    private static final String SCRIPT = "vicious_nightmare";
    private static final String NAME = "Vicious Nightmare";

    @Test
    public void reportsLibraryMovementIndependentlyOfAmount() {
        Game game = initAndCreateGame();
        Card host = addCard("Forest", game.getPlayers().get(0));
        for (int amount : new int[] {0, 3}) {
            SpellAbility sa = subjugate(host, amount);
            assertTrue(sa.getApi().getSpellEffect().movesCardToOrFromLibrary(sa));
        }
    }

    @Test
    public void subjugateInManaChainPreventsManaAbilityClassification() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card forest = addCard("Forest", player);
        SpellAbility mana = AbilityFactory.getAbility(
                "AB$ Mana | Cost$ T | Produced$ G | Amount$ 1", forest);
        mana.setActivatingPlayer(player);
        assertTrue(mana.isManaAbility(), "The standalone mana part is a mana ability");

        mana.setSubAbility((AbilitySub) subjugate(forest, 3));

        assertFalse(mana.isManaAbility(), "The whole chain moves cards into a library");
        assertFalse(forest.getManaAbilities().isEmpty());
        assertTrue(forest.getManaAbilities().get(0).isManaAbility(),
                "Forest's ordinary printed mana ability is unchanged");
    }

    // A minimal in-memory fixture keeps the regression independent of user folders.
    // An installed custom token is used unchanged when present.
    private void ensureNightmareTemplate() {
        if (!StaticData.instance().getAllTokens().containsRule(SCRIPT)) {
            CardRules rules = new CardRules.Reader().readCard(List.of(
                    "Name:" + NAME, "ManaCost:no cost", "Types:Instant", "K:Cycling:2",
                    "T:Mode$ Cycled | ValidCard$ Card.Self | Execute$ TrigDraw",
                    "SVar:TrigDraw:DB$ Draw | Defined$ Opponent | NumCards$ 1"), SCRIPT);
            StaticData.instance().getAllTokens().getRules().put(SCRIPT, rules);
        }
        assertNull(StaticData.instance().getCommonCards().getUniqueByName(NAME),
                "The regression must not have a CommonCards fallback");
        assertNotNull(StaticData.instance().getAllTokens().getToken(SCRIPT));
    }

    @DataProvider
    public Object[][] controllers() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "controllers")
    public void createsPersistentCardsForEachOpponentAndShufflesOnce(boolean human) {
        Game game = threePlayers(human);
        ensureNightmareTemplate();
        Player caster = game.getPlayers().get(0);
        assertEquals(caster.getController().isAI(), !human);
        Card host = addCard("Forest", caster);
        watch(host, "Mode$ Shuffled | ValidPlayer$ Opponent", 1);
        watch(host, "Mode$ Subjugated | ValidPlayer$ You", 10);
        prepare(game);
        int life = caster.getLife();
        SpellAbility sa = subjugate(host, 3);

        AbilityUtils.resolve(sa);

        assertTrue(caster.getCardsIn(ZoneType.Library).isEmpty());
        for (Player opponent : caster.getOpponents()) {
            assertEquals(opponent.getCardsIn(ZoneType.Library).size(), 3);
            for (Card card : opponent.getCardsIn(ZoneType.Library)) {
                assertNightmare(card, opponent, ZoneType.Library);
            }
        }
        assertEquals(caster.getLife(), life + 12, "Two shuffles and one Subjugated event");
        assertEquals(caster.getNumTokenCreatedThisTurn(), 6);
        assertEquals(caster.getSubjugatedNumThisTurn(), 1);
        assertTrue(StaticData.instance().getAllTokens().getToken(SCRIPT).isToken());
    }

    @Test
    public void generatedCardSurvivesZoneChangesAndCanBeDrawn() {
        Game game = initAndCreateGame();
        ensureNightmareTemplate();
        Player caster = game.getPlayers().get(0);
        Player recipient = game.getPlayers().get(1);
        SpellAbility sa = subjugate(addCard("Forest", caster), 1);
        prepare(game);
        AbilityUtils.resolve(sa);
        Card card = recipient.getCardsIn(ZoneType.Library).get(0);
        int id = card.getId();

        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library)) {
            card = game.getAction().moveTo(zone, card, sa, AbilityKey.newMap());
            game.getAction().checkStateEffects(false);
            assertNightmare(card, recipient, zone);
            assertEquals(card.getId(), id);
            assertSame(game.findById(id), card);
        }
        Card drawn = recipient.drawCard().get(0);
        assertNightmare(drawn, recipient, ZoneType.Hand);
        assertEquals(drawn.getId(), id);
    }

    @Test
    public void cyclingPaysItsCostAndBothPlayersDraw() {
        Game game = initAndCreateGame();
        ensureNightmareTemplate();
        Player caster = game.getPlayers().get(0);
        Player recipient = game.getPlayers().get(1);
        Card host = addCard("Forest", caster);
        SpellAbility sa = subjugate(host, 1);
        prepare(game);
        AbilityUtils.resolve(sa);
        Card card = recipient.drawCard().get(0);
        for (int i = 0; i < 5; i++) {
            addCardToZone("Forest", caster, ZoneType.Library);
            addCardToZone("Forest", recipient, ZoneType.Library);
        }
        SpellAbility cycling = card.getSpellAbilities().stream()
                .filter(SpellAbility::isCycling).findFirst().orElseThrow();
        cycling.setActivatingPlayer(recipient);
        recipient.getManaPool().addMana(new Mana(MagicColor.BLUE, host, null, recipient),
                new Mana(MagicColor.BLUE, host, null, recipient));
        int casterHand = caster.getCardsIn(ZoneType.Hand).size();
        int recipientHand = recipient.getCardsIn(ZoneType.Hand).size();
        prepare(game);

        assertTrue(ComputerUtil.handlePlayingSpellAbility(recipient, cycling, null));
        GameSimulator.resolveStack(game, caster);

        assertEquals(recipient.getManaPool().totalMana(), 0);
        assertEquals(cycling.getTotalManaSpent(), 2);
        assertNightmare(game.findById(card.getId()), recipient, ZoneType.Graveyard);
        assertEquals(recipient.getCardsIn(ZoneType.Hand).size(), recipientHand,
                "Cycling discards one card and draws one card");
        assertEquals(caster.getCardsIn(ZoneType.Hand).size(), casterHand + 1,
                "The printed Cycled trigger makes the opponent draw");
    }

    @Test
    public void ordinaryCardsAndTokensKeepTheirCreationPaths() {
        Game game = initAndCreateGame();
        ensureNightmareTemplate();
        Player caster = game.getPlayers().get(0);
        Card forest = addCard("Forest", caster);
        AbilityUtils.resolve(subjugate(forest, 1));
        Card ordinary = createCard("Runeclaw Bear", caster);
        assertEquals(ordinary.getGamePieceType(), GamePieceType.CARD);
        assertFalse(ordinary.isTokenCard());
        assertFalse(ordinary.getPaperCard().isToken());

        SpellAbility tokens = AbilityFactory.getAbility(
                "DB$ Token | TokenScript$ w_1_1_warrior | TokenAmount$ 2", forest);
        tokens.setActivatingPlayer(caster);
        AbilityUtils.resolve(tokens);
        assertEquals(caster.getCreaturesInPlay().size(), 2);
        for (Card token : caster.getCreaturesInPlay()) {
            assertTrue(token.isRealToken());
            assertTrue(token.getPaperCard().isToken());
        }
    }

    @Test
    public void resolvesAsASpellInAiSimulation() {
        Game original = initAndCreateGame();
        ensureNightmareTemplate();
        Player caster = original.getPlayers().get(1);
        CardRules rules = new CardRules.Reader().readCard(List.of(
                "Name:Subjugate Regression Spell", "ManaCost:0", "Types:Sorcery",
                "A:SP$ Subjugate | Amount$ 2 | SpellDescription$ Subjugate 2."));
        Card source = Card.fromPaperCard(new PaperCard(rules, CardEdition.UNKNOWN_CODE, CardRarity.Special), caster);
        caster.getZone(ZoneType.Hand).add(source);
        SpellAbility spell = source.getSpellAbilities().get(0);
        spell.setActivatingPlayer(caster);
        GameSimulator simulation = new GameSimulator(original, caster);

        assertTrue(simulation.applySpellAbility(spell));

        Player simCaster = simulation.getSimulatedAiPlayer();
        Player recipient = simCaster.getOpponents().get(0);
        assertEquals(recipient.getCardsIn(ZoneType.Library).size(), 2);
        for (Card card : recipient.getCardsIn(ZoneType.Library)) {
            assertNightmare(card, recipient, ZoneType.Library);
        }
        assertTrue(original.getPlayers().get(0).getCardsIn(ZoneType.Library).isEmpty());
        assertTrue(source.isInZone(ZoneType.Hand));
    }

    private Game threePlayers(boolean human) {
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(human
                ? new LobbyPlayerHuman("human") : new LobbyPlayerAi("caster", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opponent1", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opponent2", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "Subjugate test"));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private SpellAbility subjugate(Card host, int amount) {
        SpellAbility sa = AbilityFactory.getAbility("DB$ Subjugate | Amount$ " + amount, host);
        sa.setActivatingPlayer(host.getController());
        return sa;
    }

    private void assertNightmare(Card card, Player owner, ZoneType zone) {
        assertNotNull(card);
        assertEquals(card.getName(), NAME);
        assertSame(card.getOwner(), owner);
        assertTrue(card.isInZone(zone));
        assertEquals(card.getGamePieceType(), GamePieceType.CARD);
        assertFalse(card.isRealToken());
        assertTrue(card.isTokenCard());
        assertFalse(card.getPaperCard().isToken());
        assertTrue(card.getSpellAbilities().stream().anyMatch(SpellAbility::isCycling));
    }

    private void watch(Card host, String params, int life) {
        Trigger trigger = TriggerHandler.parseTrigger(params
                + " | TriggerZones$ Battlefield | Static$ True", host, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ " + life, host));
        host.addTrigger(trigger);
    }

    private void prepare(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }
}
