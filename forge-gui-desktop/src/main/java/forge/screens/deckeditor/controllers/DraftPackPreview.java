package forge.screens.deckeditor.controllers;

import forge.gamemodes.limited.ChaosDraftEvaluator.PackEvaluation;
import forge.gamemodes.limited.ChaosDraftEvaluator.PreviewScore;
import forge.item.PaperCard;
import forge.itemmanager.views.DraftScoreOverlay;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The table and thumbnail lookups share the visible pack owned by one draft controller. */
final class DraftPackPreview {
    private final Map<PaperCard, PreviewScore> scores = new IdentityHashMap<>();
    private final Map<PaperCard, DraftScoreOverlay.Label> overlays = new IdentityHashMap<>();
    private PackEvaluation snapshot;

    void show(List<PaperCard> visible, Supplier<PackEvaluation> evaluate, boolean showOverlay) {
        show(visible, evaluate, true, showOverlay);
    }

    void show(List<PaperCard> visible, Supplier<PackEvaluation> evaluate, boolean showHints, boolean showOverlay) {
        clear();
        snapshot = evaluate.get();
        if (!showHints) {
            return;
        }
        for (PaperCard card : visible) {
            PreviewScore score = snapshot.get(card);
            if (card == PaperCard.FAKE_CARD || score == null) {
                continue;
            }
            scores.put(card, score);
            if (showOverlay) {
                overlays.put(card, new DraftScoreOverlay.Label(score.text(), score.recommended()));
            }
        }
    }

    void clear() {
        snapshot = null;
        scores.clear();
        overlays.clear();
    }

    PackEvaluation takeSnapshot() {
        PackEvaluation result = snapshot;
        snapshot = null;
        return result;
    }

    boolean confirmClose(BooleanSupplier confirm) {
        if (!confirm.getAsBoolean()) {
            return false;
        }
        clear();
        return true;
    }

    Object display(PaperCard card) {
        PreviewScore score = scores.get(card);
        return score == null ? "" : score.text();
    }

    Double sortValue(PaperCard card) {
        PreviewScore score = scores.get(card);
        return score == null ? Double.NEGATIVE_INFINITY : score.value();
    }

    DraftScoreOverlay.Label overlay(PaperCard card) {
        return overlays.get(card);
    }
}
