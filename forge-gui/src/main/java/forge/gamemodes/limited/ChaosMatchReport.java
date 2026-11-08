package forge.gamemodes.limited;

import com.google.common.eventbus.Subscribe;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameObservation;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static forge.gamemodes.limited.ChaosReportFile.fields;

/** One report per local Full Chaos match, with a separate observer on each real game. */
public final class ChaosMatchReport {
    private final ChaosReportFile output;
    private final boolean standard;
    private final UUID draftId;
    private final UUID matchId = UUID.randomUUID();
    private final Map<RegisteredPlayer, ChaosDraftReport.Seat> seats;
    private final Map<Integer, Revision> revisions = new HashMap<>();
    private int sequence;
    private int gamesStarted;
    private int gamesCompleted;
    private boolean closed;
    private Observation active;

    private record Revision(String fingerprint, int number) {}

    public static ChaosMatchReport open(Match match, boolean local) {
        if (!local || !Boolean.getBoolean("forge.chaosDraft.matchReport") || !FullChaosDraftPicker.diagnosticsEnabled()) {
            return null;
        }
        return open(match, FullChaosDraftPicker.reportDirectory(),
                !"summary".equalsIgnoreCase(System.getProperty("forge.chaosDraft.matchReportLevel", "standard")));
    }

    static ChaosMatchReport open(Match match, Path directory, boolean standard) {
        if (match.getRules().getGameType() != GameType.Draft) {
            return null;
        }
        Map<RegisteredPlayer, ChaosDraftReport.Seat> seats = new IdentityHashMap<>();
        Set<Integer> used = new TreeSet<>();
        UUID draftId = null;
        try {
            for (RegisteredPlayer player : match.getPlayers()) {
                var seat = ChaosDraftReport.seat(player.getDeck());
                if (seat == null || !used.add(seat.number()) || draftId != null && !draftId.equals(seat.draftId())) {
                    return null;
                }
                draftId = seat.draftId();
                seats.put(player, seat);
            }
            return used.size() < 2 || !used.contains(0) ? null
                    : new ChaosMatchReport(match, directory, standard, draftId, seats);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ChaosMatchReport(Match match, Path directory, boolean standard, UUID draftId,
                             Map<RegisteredPlayer, ChaosDraftReport.Seat> seats) {
        this.standard = standard;
        this.draftId = draftId;
        this.seats = seats;
        output = new ChaosReportFile(directory.resolve("match_" + matchId + ".jsonl"));
        List<Map<String, Object>> players = match.getPlayers().stream().map(player -> {
            var seat = seats.get(player);
            return fields("seat", seat.number(), "deckId", seat.deckId(), "name", player.getPlayer().getName());
        }).toList();
        write("match_start", null, fields("level", standard ? "standard" : "summary", "players", players), false);
        output.flush();
    }

    public synchronized void attach(Game game) {
        if (closed) {
            return;
        }
        try {
            var observer = new Observation(game, gamesStarted + 1);
            game.subscribeToEvents(observer);
            game.setLocalObservation(observer);
            active = observer;
            gamesStarted++;
            observer.record("game_start", fields("gameNumber", gamesStarted));
            output.flush();
        } catch (RuntimeException ex) {
            // A failure to install the optional observer leaves gameplay untouched.
        }
    }

    public synchronized void finish(Match match, String interrupted) {
        if (closed) {
            return;
        }
        if (active != null && active.game.getOutcome() != null) {
            active.observe(active::result);
        }
        if (!closed && interrupted != null) {
            if (active != null) {
                write("game_abort", active, fields("reason", interrupted), true);
            }
            end(match, "match_abort", interrupted);
        }
    }

    private void end(Match match, String event, String reason) {
        write(event, null, fields("gamesStarted", gamesStarted, "gamesCompleted", gamesCompleted,
                "winnerSeat", seat(match.isMatchOver() ? match.getWinner() : null), "reason", reason), true);
        active = null;
        closed = true;
    }

    private Integer seat(RegisteredPlayer player) {
        var seat = seats.get(player);
        return seat == null ? null : seat.number();
    }

    private void write(String event, Observation observer, Map<String, Object> data, boolean terminal) {
        if (closed) {
            return;
        }
        data.put("event", event);
        data.put("draftId", draftId.toString());
        data.put("matchId", matchId.toString());
        data.put("seq", ++sequence);
        if (terminal || "game_start".equals(event) || "match_start".equals(event)) {
            data.put("time", Instant.now().toString());
        }
        if (observer != null) {
            data.put("gameId", observer.id);
            var phases = observer.game.getPhaseHandler();
            data.put("turn", phases.getTurn());
            data.put("phase", phases.getPhase());
        }
        output.append(data, terminal);
    }

    public final class Observation implements GameObservation {
        private final Game game;
        private final String id;
        private final Map<Integer, Integer> playerSeats = new HashMap<>();
        private int startSnapshotTurn = -1;
        private int endSnapshotTurn = -1;

        private Observation(Game game, int number) {
            this.game = game;
            id = matchId + "/" + number;
            for (Player player : game.getRegisteredPlayers()) {
                playerSeats.put(player.getId(), ChaosMatchReport.this.seat(player.getRegisteredPlayer()));
            }
        }

        private void observe(Runnable action) {
            synchronized (ChaosMatchReport.this) {
                if (active != this || closed) {
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException ex) {
                    // An unsupported or partially populated game event cannot affect play.
                }
            }
        }

        @Override
        public void deckPrepared(Player player, Deck prepared) {
            observe(() -> {
                RegisteredPlayer registered = player.getRegisteredPlayer();
                var seat = seats.get(registered);
                String stage = seat.number() == 0 ? "human_confirmed_for_game" : "ai_prepared_for_game";
                var snapshot = ChaosDraftReport.deckSnapshot(registered.getDeck(), prepared, seat, stage);
                Object sections = snapshot.get("sections");
                String fingerprint = UUID.nameUUIDFromBytes(ChaosReportFile.json(sections)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                Revision previous = revisions.get(seat.number());
                boolean changed = previous != null && !previous.fingerprint().equals(fingerprint);
                int revision = previous == null ? 1 : previous.number() + (changed ? 1 : 0);
                revisions.put(seat.number(), new Revision(fingerprint, revision));
                snapshot.put("revision", revision);
                snapshot.put("fingerprint", fingerprint);
                snapshot.put("changed", changed);
                if (!standard || previous != null && !changed) {
                    snapshot.remove("sections");
                }
                record(previous == null || changed ? "deck_snapshot" : "deck_reference", snapshot);
            });
        }

        @Override
        public void openingHand(Player player, boolean kept) {
            if (standard) {
                observe(() -> record(kept ? "kept_hand" : "opening_hand",
                        fields("seat", playerSeats.get(player.getId()), "cards", hand(player, false))));
            }
        }

        @Override
        public void cardDrawn(Player player, Card card) {
            if (standard) {
                observe(() -> record("draw", fields("seat", playerSeats.get(player.getId()), "card", card(card),
                        "gameStage", game.getAge())));
            }
        }

        @Override
        public void manaProduced(Card source, SpellAbility ability, Player recipient, String produced) {
            if (standard) {
                observe(() -> record("mana_produced", fields("recipientSeat", playerSeats.get(recipient.getId()),
                        "source", card(source), "produced", produced,
                        "abilityId", ability == null ? null : ability.getId(),
                        "activatorSeat", ability == null || ability.getActivatingPlayer() == null ? null
                                : playerSeats.get(ability.getActivatingPlayer().getId()),
                        "activatedManaAbility", ability != null && ability.isActivatedAbility() && ability.isManaAbility())));
            }
        }

        @Subscribe
        public void onEvent(GameEvent event) {
            if (event instanceof GameEventGameOutcome) {
                observe(this::result);
            } else if (standard) {
                observe(() -> standardEvent(event));
            }
        }

        private void result() {
            var outcome = game.getOutcome();
            if (active != this || outcome == null) {
                return;
            }
            if (standard) {
                for (Player player : game.getRegisteredPlayers()) {
                    if (player.getController().isAI()) {
                        observe(() -> record("ai_final_hand", fields("seat", playerSeats.get(player.getId()),
                                "cards", hand(player, true))));
                    }
                }
            }
            var results = game.getRegisteredPlayers().stream().map(player -> {
                var playerOutcome = player.getOutcome();
                return fields("seat", playerSeats.get(player.getId()), "lossReason",
                        playerOutcome == null ? null : playerOutcome.lossState);
            }).toList();
            write("game_end", this, fields("winnerSeat", ChaosMatchReport.this.seat(outcome.getWinningPlayer()),
                    "reason", outcome.getWinCondition(), "turns", outcome.getLastTurnNumber(), "playerResults", results), true);
            gamesCompleted++;
            active = null;
            game.setLocalObservation(null);
            if (game.getMatch().isMatchOver()) {
                end(game.getMatch(), "match_end", "completed");
            }
        }

        private void standardEvent(GameEvent event) {
            if (event instanceof GameEventPlayerPriority e) {
                int turn = game.getPhaseHandler().getTurn();
                if (turn != startSnapshotTurn && e.phase().isAfter(PhaseType.DRAW)
                        && e.phase() != PhaseType.CLEANUP && snapshot("start", game.getPhaseHandler().getPlayerTurn())) {
                    startSnapshotTurn = turn;
                }
            } else if (event instanceof GameEventTurnEnded) {
                int turn = game.getPhaseHandler().getTurn();
                if (turn != endSnapshotTurn && snapshot("end", game.getPhaseHandler().getPreviousPlayerTurn())) {
                    endSnapshotTurn = turn;
                }
                output.flush();
            } else if (event instanceof GameEventGameStarted e) {
                record("game_setup", fields("firstSeat", seat(e.firstTurn())));
                output.flush();
            } else if (event instanceof GameEventMulligan e) {
                Player player = game.getRegisteredPlayers().stream().filter(p -> p.getId() == e.player().getId())
                        .findFirst().orElse(null);
                if (player != null) {
                    record("mulligan", fields("seat", seat(e.player()), "cardsAfterRedraw", hand(player, false)));
                }
            } else if (event instanceof GameEventCardChangeZone e) {
                record("zone_change", fields("card", card(e.card()), "from", e.from() == null ? null : e.from().zoneType(),
                        "fromSeat", e.from() == null ? null : seat(e.from().player()),
                        "to", e.to() == null ? null : e.to().zoneType(), "toSeat", e.to() == null ? null : seat(e.to().player())));
            } else if (event instanceof GameEventLandPlayed e) {
                record("land_played", fields("seat", seat(e.player()), "card", card(e.land())));
            } else if (event instanceof GameEventSpellAbilityCast e) {
                var item = e.si();
                var top = game.getStack().peek();
                SpellAbility actual = top != null && top.getId() == item.getId() ? top.getSpellAbility() : null;
                String kind;
                if (item.isTrigger()) {
                    kind = "trigger_stacked";
                } else if (actual == null) {
                    kind = "stack_added";
                } else if (actual.isCopied()) {
                    kind = "copy_stacked";
                } else if (actual.isSpell()) {
                    kind = "spell_cast";
                } else {
                    kind = actual.isActivatedAbility() ? "ability_activated" : "stack_added";
                }
                record(kind, fields("seat", seat(item.getActivatingPlayer()), "abilityId", e.sa().getId(),
                        "card", card(e.sa().getHostCard()), "stackId", item.getId(), "stackIndex", e.stackIndex(),
                        "targetDescription", e.targetDescription(), "targetCards", map(item.getTargetCards(), ChaosMatchReport::card),
                        "targetSeats", map(item.getTargetPlayers(), this::seat),
                        "targetStackIds", map(item.getTargetStackItems(), target -> target.getId())));
            } else if (event instanceof GameEventSpellResolved e) {
                record("stack_resolved", fields("abilityId", e.spell().getId(), "card", card(e.spell().getHostCard()),
                        "fizzled", e.hasFizzled(), "description", e.stackDescription()));
            } else if (event instanceof GameEventCardModeChosen e) {
                record("mode_chosen", fields("seat", seat(e.player()), "cardName", e.cardName(), "mode", e.mode(), "random", e.random()));
            } else if (event instanceof GameEventAttackersDeclared e) {
                record("attackers", fields("seat", seat(e.player()), "assignments", e.attackersMap().entries().stream()
                        .map(pair -> fields("attacker", card(pair.getValue()), "defender",
                                pair.getKey() instanceof PlayerView p ? fields("seat", seat(p)) :
                                        pair.getKey() instanceof CardView c ? card(c) : null)).toList()));
            } else if (event instanceof GameEventBlockersDeclared e) {
                var assignments = new ArrayList<Map<String, Object>>();
                for (var group : e.blockers().values()) {
                    for (var pair : group.entries()) {
                        assignments.add(fields("attacker", card(pair.getKey()), "blocker", card(pair.getValue())));
                    }
                }
                record("blockers", fields("seat", seat(e.defendingPlayer()), "assignments", assignments));
            } else if (event instanceof GameEventPlayerDamaged e) {
                record("player_damage", fields("seat", seat(e.target()), "source", card(e.source()),
                        "amount", e.amount(), "combat", e.combat(), "infect", e.infect()));
            } else if (event instanceof GameEventCardDamaged e) {
                record("card_damage", fields("card", card(e.card()), "source", card(e.source()), "amount", e.amount(), "kind", e.type()));
            } else if (event instanceof GameEventPlayerLivesChanged e) {
                record("life_changed", fields("seat", seat(e.player()), "before", e.oldLives(), "after", e.newLives()));
            }
        }

        private Integer seat(PlayerView view) {
            return view == null ? null : playerSeats.get(view.getId());
        }

        private void record(String event, Map<String, Object> data) {
            write(event, this, data, false);
        }

        private boolean snapshot(String boundary, Player activePlayer) {
            if (activePlayer == null || !activePlayer.getController().isAI()) {
                return false;
            }
            List<Map<String, Object>> players = new ArrayList<>();
            for (Player player : game.getRegisteredPlayers()) {
                players.add(playerState(player, playerSeats.get(player.getId())));
            }
            record("game_state_snapshot", fields("boundary", boundary, "activeSeat", playerSeats.get(activePlayer.getId()),
                    "players", players, "aiHand", hand(activePlayer, true), "alternatives", "UNAVAILABLE"));
            return true;
        }
    }

    private static <T, R> List<R> map(Iterable<T> values, Function<T, R> mapper) {
        if (values == null) {
            return List.of();
        }
        List<R> result = new ArrayList<>();
        for (T value : values) {
            result.add(mapper.apply(value));
        }
        return result;
    }

    private static Map<String, Object> card(CardView card) {
        return card == null ? null : fields("cardId", card.getId(), "name", card.getName());
    }

    private static Map<String, Object> card(Card card) {
        return fields("cardId", card.getId(), "name", card.getName(),
                "printing", card.getPaperCard() == null ? null : ChaosDraftReport.printing(card.getPaperCard()));
    }

    private static List<Map<String, Object>> hand(Player player, boolean markCastability) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card held : player.getCardsIn(ZoneType.Hand)) {
            var row = card(held);
            if (markCastability && !held.isLand()) {
                row.put("castability", "UNKNOWN");
            }
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> playerState(Player player, Integer seat) {
        int lands = 0;
        int creatures = 0;
        int tappedCreatures = 0;
        int sources = 0;
        int unknown = 0;
        Set<String> colors = new TreeSet<>();
        var battlefield = player.getCardsIn(ZoneType.Battlefield);
        for (Card permanent : battlefield) {
            lands += permanent.isLand() ? 1 : 0;
            creatures += permanent.isCreature() ? 1 : 0;
            tappedCreatures += permanent.isCreature() && permanent.isTapped() ? 1 : 0;
            if (permanent.isTapped() || permanent.isCreature() && permanent.isSick()) {
                continue;
            }
            // Reading getManaAbilities() can create cached spell abilities and consume engine IDs.
            // Use effective land types and simple printed abilities only while no trait overrides are present.
            if (permanent.getCurrentStateName() != CardStateName.Original || permanent.hasRemoveIntrinsic()
                    || !permanent.getChangedCardTraits().isEmpty() || !permanent.getChangedCardTraitsByText().isEmpty()) {
                unknown++;
                continue;
            }
            Set<String> known = new TreeSet<>();
            if (permanent.isLand()) {
                for (MagicColor.Color color : MagicColor.Color.values()) {
                    if (color.getBasicLandType() != null && permanent.getType().hasSubtype(color.getBasicLandType())) {
                        known.add(color.getShortName());
                    }
                }
            }
            boolean hasUnknownAbility = false;
            var printing = permanent.getPaperCard();
            if (printing != null) {
                for (String script : printing.getMainFace().getAbilities()) {
                    var params = AbilityFactory.getMapParams(script);
                    if (!"Mana".equals(params.get("AB")) && !"ManaReflected".equals(params.get("AB"))) {
                        continue;
                    }
                    String produced = params.getOrDefault("Produced", "");
                    if ("Mana".equals(params.get("AB")) && "T".equals(params.get("Cost"))
                            && params.keySet().stream().allMatch(SIMPLE_MANA_PARAMS::contains)
                            && produced.matches("(?:[WUBRGC](?: [WUBRGC])*|[1-9])")) {
                        for (String color : produced.split(" ")) {
                            known.add(Character.isDigit(color.charAt(0)) ? "C" : color);
                        }
                    } else {
                        hasUnknownAbility = true;
                    }
                }
            }
            if (!known.isEmpty()) {
                sources++;
                colors.addAll(known);
            } else if (hasUnknownAbility || permanent.isLand()) {
                unknown++;
            }
        }
        Map<String, Integer> pool = new java.util.LinkedHashMap<>();
        for (String color : List.of("W", "U", "B", "R", "G", "C")) {
            pool.put(color, player.getManaPool().getAmountOfColor(ManaAtom.fromName(color)));
        }
        return fields("seat", seat, "life", player.getLife(), "hand", player.getCardsIn(ZoneType.Hand).size(),
                "library", player.getCardsIn(ZoneType.Library).size(), "lands", lands,
                "creatures", creatures, "otherPermanents", battlefield.size() - creatures,
                "tappedCreatures", tappedCreatures, "untappedCreatures", creatures - tappedCreatures,
                "untappedSimpleManaSources", sources, "unknownManaSources", unknown, "possibleColors", colors, "manaPool", pool);
    }

    private static final Set<String> SIMPLE_MANA_PARAMS = Set.of("AB", "Cost", "Produced", "SpellDescription",
            "Description", "Secondary");
}
