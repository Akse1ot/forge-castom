package forge.gamemodes.limited;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.item.PaperCard;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static forge.gamemodes.limited.ChaosDeckTunerTest.card;
import static org.testng.Assert.*;

public class ChaosMatchReportTest extends AITest {
    @Test
    public void eightDecksPersistAndTwoRealGamesRetainOrderAndRandomness() throws IOException {
        Path directory = outputDirectory();
        Deck[] decks = draft(directory);
        Path persisted = directory.resolve("human.dck");
        DeckSerializer.writeDeck(decks[0], persisted.toFile());
        Deck reloaded = DeckSerializer.fromFile(persisted.toFile());
        assertEquals(ChaosDraftReport.seat(reloaded), ChaosDraftReport.seat(decks[0]));

        var draftRows = rows(directory, "draft_");
        assertEquals(draftRows.size(), 8);
        String draftId = field(draftRows.get(0), "draftId");
        for (int seat = 0; seat < 8; seat++) {
            var row = draftRows.get(seat);
            assertEquals(field(row, "draftId"), draftId);
            assertEquals(field(row, "deckId"), draftId + "/" + seat);
            assertEquals(field(row, "stage"), seat == 0 ? "draft_pool" : "draft_ai_built");
            var sections = row.getAsJsonObject("sections");
            assertEquals(sections.getAsJsonArray("Main").get(0).getAsJsonObject().get("count").getAsInt(), 16);
            assertEquals(sections.getAsJsonArray("Sideboard").size(), 1);
            assertTrue(sections.getAsJsonArray("Main").get(0).getAsJsonObject().has("collector"));
        }
        assertEquals(draftRows.get(7).getAsJsonObject("sections").getAsJsonArray("Attractions").size(), 1);

        Random previous = MyRandom.getRandom();
        try {
            Run baseline = play(directory, decks, false);
            Run watched = play(directory, decks, true);
            assertEquals(watched.events(), baseline.events());
            assertEquals(watched.randomCalls(), baseline.randomCalls());
        } finally {
            MyRandom.setRandom(previous);
        }

        List<JsonObject> rows = rows(directory, "match_");
        assertEquals(field(rows.get(0), "event"), "match_start");
        assertEquals(field(rows.get(rows.size() - 1), "event"), "match_end");
        String matchId = field(rows.get(0), "matchId");
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i);
            assertEquals(row.get("seq").getAsInt(), i + 1);
            assertEquals(field(row, "draftId"), draftId);
            assertEquals(field(row, "matchId"), matchId);
            if (row.has("gameId")) {
                assertTrue(field(row, "gameId").startsWith(matchId + "/"));
            }
        }
        assertEquals(events(rows, "game_start").size(), 2);
        assertEquals(events(rows, "game_end").size(), 2);
        assertEquals(events(rows, "match_end").get(0).get("gamesCompleted").getAsInt(), 2);
        assertEquals(events(rows, "match_end").get(0).get("winnerSeat").getAsInt(), 0);
        assertEquals(events(rows, "deck_snapshot").size(), 3);
        assertEquals(events(rows, "deck_reference").size(), 1);
        var revised = events(rows, "deck_snapshot").stream().filter(r -> r.get("changed").getAsBoolean()).findFirst().orElseThrow();
        assertEquals(field(revised, "stage"), "human_confirmed_for_game");
        assertEquals(revised.get("revision").getAsInt(), 2);
        assertTrue(field(revised, "gameId").endsWith("/2"));
        assertTrue(revised.getAsJsonObject("sections").getAsJsonArray("Main").toString().contains("Shock"));

        assertEquals(events(rows, "opening_hand").size(), 4);
        assertEquals(events(rows, "kept_hand").size(), 4);
        assertEquals(events(rows, "mulligan").size(), 2);
        assertFalse(events(rows, "draw").isEmpty());
        assertFalse(events(rows, "land_played").isEmpty());
        assertFalse(events(rows, "spell_cast").isEmpty());
        assertFalse(events(rows, "mana_produced").isEmpty());
        var snapshots = events(rows, "game_state_snapshot");
        assertFalse(snapshots.isEmpty());
        assertTrue(snapshots.stream().anyMatch(row -> field(row, "boundary").equals("start")
                && field(row, "phase").equals("MAIN1") && row.getAsJsonArray("aiHand").size() > 0));
        assertTrue(snapshots.stream().flatMap(row -> row.getAsJsonArray("players").asList().stream())
                .map(player -> player.getAsJsonObject()).anyMatch(player -> player.get("untappedSimpleManaSources").getAsInt() > 0
                        && player.getAsJsonArray("possibleColors").toString().contains("\"R\"")));
        assertTrue(snapshots.stream().filter(row -> field(row, "boundary").equals("end")).allMatch(end ->
                snapshots.stream().anyMatch(start -> field(start, "boundary").equals("start")
                        && start.get("gameId").equals(end.get("gameId")) && start.get("turn").equals(end.get("turn"))
                        && start.get("activeSeat").equals(end.get("activeSeat")))));
        assertTrue(snapshots.stream().anyMatch(row -> field(row, "boundary").equals("end")));
    }

    @Test
    public void scopeSummaryAndAbortedGame() throws IOException {
        Path directory = outputDirectory();
        Deck[] decks = draft(directory);
        Match draftMatch = match(decks, GameType.Draft);
        String property = "forge.chaosDraft.matchReport";
        String original = System.getProperty(property);
        try {
            System.setProperty(property, "false");
            assertNull(ChaosMatchReport.open(draftMatch, true));
            System.setProperty(property, "true");
            assertNull(ChaosMatchReport.open(draftMatch, false));
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
        assertNull(ChaosMatchReport.open(match(decks, GameType.Sealed), directory, true));
        assertNull(ChaosMatchReport.open(match(new Deck[]{new Deck(), new Deck()}, GameType.Draft), directory, true));
        assertTrue(rows(directory, "match_").isEmpty());
        assertNull(draftMatch.createGame().getLocalObservation());

        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(49));
            ChaosMatchReport summary = ChaosMatchReport.open(draftMatch, directory, false);
            assertNotNull(summary);
            Game game = draftMatch.createGame();
            summary.attach(game);
            draftMatch.startGame(game, null);
            summary.attach(draftMatch.createGame());
            summary.finish(draftMatch, "closed");
            summary.finish(draftMatch, "restart");
        } finally {
            MyRandom.setRandom(previous);
        }
        List<JsonObject> rows = rows(directory, "match_");
        assertTrue(events(rows, "draw").isEmpty());
        assertTrue(events(rows, "game_state_snapshot").isEmpty());
        assertTrue(events(rows, "opening_hand").isEmpty());
        assertEquals(events(rows, "game_end").size(), 1);
        assertEquals(events(rows, "game_abort").size(), 1);
        var abort = events(rows, "match_abort").get(0);
        assertEquals(abort.get("gamesStarted").getAsInt(), 2);
        assertEquals(abort.get("gamesCompleted").getAsInt(), 1);
        assertTrue(events(rows, "deck_snapshot").stream().allMatch(row -> row.has("metrics") && !row.has("sections")));
    }

    private record Run(List<String> events, long randomCalls) {}

    private static Run play(Path directory, Deck[] decks, boolean observed) {
        MyRandom.setRandom(new Random(49));
        long before = MyRandom.callCount();
        Match match = match(decks, GameType.Draft);
        ChaosMatchReport report = observed ? ChaosMatchReport.open(match, directory, true) : null;
        List<String> trace = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Game game = match.createGame();
            game.subscribeToEvents(new Object() {
                @Subscribe
                public void event(GameEvent event) {
                    trace.add(event.getClass().getSimpleName());
                }
            });
            if (report != null) {
                report.attach(game);
            }
            match.startGame(game, null);
            assertNull(game.getLocalObservation());
        }
        return new Run(trace, MyRandom.callCount() - before);
    }

    private static Match match(Deck[] decks, GameType type) {
        GameRules rules = new GameRules(type);
        rules.setGamesPerMatch(3);
        var human = new RegisteredPlayer(decks[0])
                .setPlayer(new ScriptedPlayer("Seat 0", false));
        var opponent = new RegisteredPlayer(decks[1])
                .setPlayer(new ScriptedPlayer("Seat 1", true));
        return new Match(rules, List.of(human, opponent), "Chaos report regression");
    }

    private static final class ScriptedPlayer extends LobbyPlayerAi {
        private final boolean opponent;

        ScriptedPlayer(String name, boolean opponent) {
            super(name, null);
            this.opponent = opponent;
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            player.setFirstController(new PlayerControllerAi(game, player, this) {
                private boolean alreadyAsked;

                @Override
                public boolean mulliganKeepHand(Player first, int cardsToReturn) {
                    boolean keep = alreadyAsked || opponent;
                    alreadyAsked = true;
                    return keep;
                }

                @Override
                public List<PaperCard> sideboard(Deck deck, GameType type, String message) {
                    if (opponent) {
                        return null;
                    }
                    var main = deck.getMain().toFlatList();
                    main.remove(card("Goblin Piker"));
                    main.add(card("Shock"));
                    return main;
                }

                @Override
                public List<SpellAbility> chooseSpellAbilityToPlay() {
                    if (opponent && game.getPhaseHandler().getTurn() >= 4) {
                        player.concede();
                        return null;
                    }
                    return super.chooseSpellAbilityToPlay();
                }
            });
            return player;
        }
    }

    private static Deck[] draft(Path directory) throws IOException {
        var draft = new ChaosDraftReport(directory);
        Deck[] decks = new Deck[8];
        for (int i = 0; i < decks.length; i++) {
            Deck deck = new Deck("Seat " + i);
            deck.getMain().add(card("Mountain"), 24);
            deck.getMain().add(card("Goblin Piker"), 16);
            deck.getOrCreate(DeckSection.Sideboard).add(card("Shock"));
            if (i == 7) {
                deck.getOrCreate(DeckSection.Attractions)
                        .add(ChaosDraftEvaluatorTest.script("b/balloon_stand.txt", "UNF"));
            }
            decks[i] = deck;
        }
        Deck[] opponents = java.util.Arrays.copyOfRange(decks, 1, 8);
        draft.identify(decks[0], opponents);
        draft.completed(decks[0], opponents);
        return decks;
    }

    private static Path outputDirectory() throws IOException {
        Path path = Files.createTempDirectory(Path.of("target"), "chaos-report-");
        System.out.println("Chaos regression JSONL: " + path.toAbsolutePath());
        return path;
    }

    private static List<JsonObject> rows(Path directory, String prefix) throws IOException {
        try (var paths = Files.list(directory)) {
            List<JsonObject> result = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()) {
                for (String line : Files.readAllLines(path)) {
                    result.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
            return result;
        }
    }

    private static List<JsonObject> events(List<JsonObject> rows, String event) {
        return rows.stream().filter(row -> field(row, "event").equals(event)).toList();
    }

    private static String field(JsonObject row, String name) {
        return row.get(name).getAsString();
    }
}
