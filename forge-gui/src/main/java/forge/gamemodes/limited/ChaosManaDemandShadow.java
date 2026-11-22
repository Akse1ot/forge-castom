package forge.gamemodes.limited;

import forge.deck.Deck;
import forge.item.PaperCard;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static forge.gamemodes.limited.ChaosReportFile.fields;

/** Optional, fail-closed diagnostics. No result from this class is consumed by the tuner. */
final class ChaosManaDemandShadow implements AutoCloseable {
    private final Consumer<Map<String, Object>> output;
    private final Set<String> logged = new HashSet<>();
    private final List<Long> timings = new ArrayList<>();
    private ChaosManaDemand model;
    private boolean failed;
    private boolean closed;
    private int trials;
    private int accepted;
    private int anchoredRound = -1;
    private String failure;

    static ChaosManaDemandShadow open(Deck seed, List<PaperCard> stock) {
        try {
            var directory = FullChaosDraftPicker.reportDirectory();
            var settings = directory.getParent();
            if (!Files.isRegularFile(settings.resolve("enabled.flag"))
                    || !Files.isRegularFile(settings.resolve("mana-demand-shadow.flag"))) {
                return null;
            }
            Files.createDirectories(directory);
            var file = Files.createTempFile(directory, "mana-demand-shadow-", ".jsonl");
            var sink = new ChaosReportFile(file);
            return new ChaosManaDemandShadow(seed.getMain().toFlatList(), stock,
                    ChaosManaDemand.Config.defaults(), row -> sink.append(row,
                    "mana_demand_summary".equals(row.get("event"))));
        } catch (IOException | RuntimeException ex) {
            // An optional diagnostic cannot change the build or trigger its native fallback.
            return null;
        }
    }

    ChaosManaDemandShadow(List<PaperCard> seed, List<PaperCard> stock, ChaosManaDemand.Config config,
                         Consumer<Map<String, Object>> output) {
        this.output = output;
        try {
            model = new ChaosManaDemand(seed, stock, config);
            emit(fields("event", "mana_demand_begin", "version", 1, "config", config,
                    "workload", model.workload(), "queries", model.queries(), "deltaScope", "fixed_seed_workload",
                    "interpretation", "virtual_cost_availability_not_card_utility"));
        } catch (RuntimeException ex) {
            fail(ex);
        }
    }

    void trial(List<PaperCard> current, int index, PaperCard replacement, double oldGain,
               String rejection, int round) {
        if (failed || closed) { return; }
        try {
            trials++;
            if (anchoredRound != round) {
                model.anchor(current);
                anchoredRound = round;
            }
            PaperCard removed = current.get(index);
            List<PaperCard> trial = new ArrayList<>(current);
            trial.set(index, replacement);
            var beforeSlots = model.slots(current);
            var afterSlots = model.slots(trial);
            var before = evaluation(beforeSlots, true);
            var after = evaluation(afterSlots, true);
            var genericSlots = model.genericSwap(beforeSlots, afterSlots);
            var genericBefore = evaluation(genericSlots.before(), false);
            var genericAfter = evaluation(genericSlots.after(), false);
            emit(fields("event", "mana_demand_trial", "trial", trials, "round", round,
                    "oldGain", oldGain, "oldRejection", rejection,
                    "before", before.id(), "after", after.id(),
                    "genericBefore", genericBefore.id(), "genericAfter", genericAfter.id(),
                    "manaDelta", model.difference(before, after),
                    "genericRampDelta", model.difference(genericBefore, genericAfter),
                    "fixingDelta", model.fixingDifference(before, after, genericBefore, genericAfter),
                    "coverageBefore", model.coverage(current), "coverageAfter", model.coverage(trial),
                    "lostNonmana", model.facts(removed), "gainedNonmana", model.facts(replacement)));
        } catch (RuntimeException ex) {
            fail(ex);
        }
    }

    private ChaosManaDemand.Evaluation evaluation(List<ChaosManaDemand.Card> slots, boolean full) {
        long start = System.nanoTime();
        var result = model.evaluate(slots, full);
        if (timings.size() < 4096) { timings.add(System.nanoTime() - start); }
        if (logged.size() < 4096 && logged.add(result.id())) {
            emit(fields("event", "mana_demand_configuration", "id", result.id(),
                    "probabilities", result.probabilities(), "scenarioHitsHex", result.hitWords()));
        }
        return result;
    }

    void accepted(PaperCard removed, PaperCard replacement, int round) {
        if (failed || closed) { return; }
        try {
            accepted++;
            emit(fields("event", "mana_demand_accepted", "round", round,
                    "out", ChaosDraftReport.printing(removed), "in", ChaosDraftReport.printing(replacement)));
        } catch (RuntimeException ex) { fail(ex); }
    }

    private void fail(RuntimeException ex) {
        failed = true;
        failure = ex.getClass().getSimpleName() + ":" + ex.getMessage();
        emit(fields("event", "mana_demand_unavailable", "reason", failure,
                "interpretation", "no_diagnostic_result_not_zero_card_utility"));
    }

    private void emit(Map<String, Object> row) {
        try { output.accept(row); }
        catch (RuntimeException ex) { failed = true; failure = "diagnostic_sink_failed"; }
    }

    @Override public void close() {
        if (closed) { return; }
        try {
            List<Long> sorted = timings.stream().sorted().toList();
            emit(fields("event", "mana_demand_summary", "trials", trials, "accepted", accepted,
                    "failed", failed, "failure", failure, "stats", model == null ? null : model.stats(),
                    "evaluationCount", timings.size(), "timingsLimited", timings.size() >= 4096,
                    "evaluationNanos", List.copyOf(timings),
                    "p50Nanos", sorted.isEmpty() ? null : sorted.get((sorted.size() - 1) / 2),
                    "p95Nanos", sorted.isEmpty() ? null : sorted.get((int) Math.ceil(sorted.size() * .95) - 1)));
        } finally {
            if (model != null) { model.close(); }
            logged.clear();
            timings.clear();
            closed = true;
        }
    }
}
