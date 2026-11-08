package forge.gamemodes.limited;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.IPaperCard;
import forge.item.PaperCard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static forge.gamemodes.limited.ChaosReportFile.fields;

/** Draft identity and deck snapshots shared by the draft and local match reports. */
public final class ChaosDraftReport {
    private static final String ID_TAG = "chaosDraftId:";
    private static final String SEAT_TAG = "chaosDraftSeat:";

    record Seat(UUID draftId, int number) {
        String deckId() {
            return draftId + "/" + number;
        }
    }

    private final UUID id;
    private final ChaosReportFile output;

    ChaosDraftReport(Path directory) {
        this(directory, UUID.randomUUID());
    }

    private ChaosDraftReport(Path directory, UUID id) {
        this.id = id;
        output = new ChaosReportFile(directory.resolve("draft_" + id + ".jsonl"));
    }

    void legacy(String json) {
        output.appendLegacy(json, id.toString());
    }

    void humanPick(ChaosDraftEvaluator.PackEvaluation snapshot, PaperCard selected, int pack, int pick) {
        if (selected == PaperCard.FAKE_CARD || snapshot.visible().stream().noneMatch(selected::equals)) {
            return;
        }
        try {
            Map<PaperCard, ChaosDraftEvaluator.Candidate> scores = new IdentityHashMap<>();
            snapshot.candidates().forEach(candidate -> scores.putIfAbsent(candidate.card(), candidate));
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (PaperCard card : snapshot.visible()) {
                var candidate = scores.get(card);
                var row = printing(card);
                if (candidate == null) {
                    row.put("excluded", "unsupported");
                } else {
                    var strength = candidate.strength();
                    row.putAll(fields("nativeRank", candidate.nativeRank(), "rating", candidate.rating(),
                            "ratingSource", candidate.ratingSource(), "contextualBase", strength.value(),
                            "poolBase", strength.value() == null ? null : candidate.poolBase(),
                            "score", candidate.selectable() ? candidate.score() : null,
                            "excluded", candidate.exclusion(), "standalone", strength.standalone(),
                            "support", candidate.support(), "dependencyReason", strength.dependencyReason(),
                            "availableContribution", strength.availableContribution(),
                            "unavailableContribution", strength.unavailableContribution(),
                            "fitReason", candidate.fitReason(), "colorPenalty", candidate.colorPenalty(),
                            "curveBonus", candidate.curveBonus(), "landBonus", candidate.landBonus(), "manaBonus", candidate.manaBonus(),
                            "nativeBonus", candidate.nativeBonus()));
                }
                candidates.add(row);
            }
            PaperCard recommended = snapshot.recommended();
            var row = fields("event", "human_pick", "draftId", id.toString(), "seat", 0, "pack", pack, "pick", pick,
                    "chosenColorMask", snapshot.colorMask(), "commitment", snapshot.commitment(),
                    "recommended", recommended == null ? null : printing(recommended),
                    "selected", printing(selected), "candidates", candidates);
            output.append(row, false);
            output.flush();
        } catch (RuntimeException ex) {
            // A failed optional report cannot interfere with the accepted pick.
        }
    }

    void identify(Deck human, Deck[] opponents) {
        tag(human, 0);
        for (int i = 0; i < opponents.length; i++) {
            tag(opponents[i], i + 1);
        }
    }

    void completed(Deck human, Deck[] opponents) {
        snapshot(human, "draft_pool");
        for (Deck opponent : opponents) {
            snapshot(opponent, "draft_ai_built");
        }
    }

    private void tag(Deck deck, int seat) {
        deck.getTags().removeIf(tag -> tag.startsWith(ID_TAG) || tag.startsWith(SEAT_TAG));
        deck.getTags().add(ID_TAG + id);
        deck.getTags().add(SEAT_TAG + seat);
    }

    static Seat seat(Deck deck) {
        if (deck == null) {
            return null;
        }
        UUID draftId = null;
        Integer number = null;
        try {
            for (String tag : deck.getTags()) {
                if (tag.startsWith(ID_TAG)) {
                    if (draftId != null) {
                        return null;
                    }
                    String id = tag.substring(ID_TAG.length());
                    draftId = UUID.fromString(id);
                    if (!draftId.toString().equals(id)) {
                        return null;
                    }
                } else if (tag.startsWith(SEAT_TAG)) {
                    if (number != null) {
                        return null;
                    }
                    number = Integer.valueOf(tag.substring(SEAT_TAG.length()));
                }
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return draftId == null || number == null || number < 0 || number > 7 ? null : new Seat(draftId, number);
    }

    public static void humanDeckSaved(Deck deck) {
        if (FullChaosDraftPicker.diagnosticsEnabled()) {
            reportHumanDeck(deck, "human_saved");
        }
    }

    public static void humanDeckConfirmed(Deck deck) {
        if (FullChaosDraftPicker.draftReportEnabled()) {
            reportHumanDeck(deck, "human_confirmed_for_match");
        }
    }

    private static void reportHumanDeck(Deck deck, String stage) {
        Seat seat = seat(deck);
        if (seat != null && seat.number() == 0) {
            new ChaosDraftReport(FullChaosDraftPicker.reportDirectory(), seat.draftId()).snapshot(deck, stage);
        }
    }

    private void snapshot(Deck deck, String stage) {
        try {
            Seat seat = seat(deck);
            if (seat == null || !id.equals(seat.draftId())) {
                return;
            }
            Map<String, Object> row = deckSnapshot(deck, deck, seat, stage);
            row.put("event", "deck_snapshot");
            row.put("draftId", id.toString());
            output.append(row, false);
            output.flush();
        } catch (RuntimeException ex) {
            // Reporting is optional, including when a card cannot be inspected.
        }
    }

    static Map<String, Object> deckSnapshot(Deck registered, Deck prepared, Seat seat, String stage) {
        List<PaperCard> main = prepared.getMain().toFlatList();
        Map<DeckSection, List<Map<String, Object>>> sections = new EnumMap<>(DeckSection.class);
        for (var entry : registered) {
            DeckSection section = entry.getKey();
            if (section != DeckSection.Main && section != DeckSection.Sideboard) {
                sections.put(section, cardCounts(entry.getValue()));
            }
        }
        for (DeckSection section : List.of(DeckSection.Main, DeckSection.Sideboard)) {
            var pool = prepared.get(section);
            sections.put(section, pool == null ? List.of() : cardCounts(pool));
        }

        ChaosManaPlan mana = new ChaosManaPlan(main, new IdentityHashMap<>());
        Map<Integer, Integer> curve = new TreeMap<>();
        int spells = 0;
        int uncastable = 0;
        double casting = 0;
        for (PaperCard card : main) {
            if (!card.getMainFace().getType().isLand()) {
                curve.merge(card.getMainFace().getManaCost().getCMC(), 1, Integer::sum);
                spells++;
                double quality = mana.castability(card);
                uncastable += quality == 0 ? 1 : 0;
                casting += quality;
            }
        }
        return fields("deckId", seat.deckId(), "seat", seat.number(), "stage", stage,
                "deckName", registered.getName(), "sections", sections,
                "metrics", fields("mainCount", main.size(), "lands", mana.lands(), "manaCurve", curve,
                        "landSourceOrder", "W,U,B,R,G,C,snow", "landSources", mana.landSources(),
                        "spells", spells, "zeroCastabilityEstimate", uncastable,
                        "meanCastabilityEstimate", spells == 0 ? null : casting / spells));
    }

    private static List<Map<String, Object>> cardCounts(Iterable<Map.Entry<PaperCard, Integer>> cards) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : cards) {
            Map<String, Object> row = printing(entry.getKey());
            row.put("count", entry.getValue());
            result.add(row);
        }
        result.sort(Comparator.comparing(ChaosReportFile::json));
        return result;
    }

    static Map<String, Object> printing(IPaperCard card) {
        var marked = card.getMarkedColors();
        return fields("name", card.getName(), "edition", card.getEdition(), "collector", card.getCollectorNumber(),
                "art", card.getArtIndex(), "foil", card.isFoil(), "variant", card.getFunctionalVariant(),
                "markedColors", marked == null ? null : marked.getColor(), "imageKey", card.getCardImageKey());
    }
}
