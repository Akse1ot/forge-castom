package forge.screens.deckeditor.controllers;

import forge.ai.AITest;
import forge.gamemodes.limited.ChaosDraftEvaluatorTest;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.testng.Assert.*;

public class DraftPackPreviewTest extends AITest {
    @Test
    public void exposesTheSameScoreForOldPrintingsUnfinityAndMultifaceCards() throws IOException {
        List<PaperCard> visible = List.of(ChaosDraftEvaluatorTest.script("s/serra_angel.txt", "LEA"),
                ChaosDraftEvaluatorTest.script("b/big_winner.txt", "UNF"),
                ChaosDraftEvaluatorTest.script("s/soulcipher_board_cipherbound_spirit.txt", "VOW"));
        var scores = ChaosDraftEvaluatorTest.preview(visible);
        var preview = new DraftPackPreview();
        preview.show(visible, () -> scores, true);
        for (PaperCard card : visible) {
            assertNotNull(preview.overlay(card), card.getName());
            assertEquals(preview.display(card), preview.overlay(card).text());
        }
        assertNull(preview.overlay(PaperCard.FAKE_CARD));
        preview.clear();
        assertNull(preview.overlay(visible.get(0)));
    }

    @Test
    public void switchingPacksClosingAndRestartingDoNotReuseScores() throws IOException {
        PaperCard bears = ChaosDraftEvaluatorTest.script("g/grizzly_bears.txt", "M10");
        PaperCard cutter = ChaosDraftEvaluatorTest.script("l/line_cutter.txt", "UNF");
        var firstScores = ChaosDraftEvaluatorTest.preview(List.of(bears, cutter));
        var secondScores = ChaosDraftEvaluatorTest.preview(List.of(cutter));
        var preview = new DraftPackPreview();
        preview.show(List.of(bears, cutter), () -> firstScores, false);
        assertEquals(preview.display(bears), firstScores.get(bears).text());
        assertEquals(preview.sortValue(bears), firstScores.get(bears).value());
        assertFalse(preview.confirmClose(() -> false));
        assertEquals(preview.display(bears), firstScores.get(bears).text());

        preview.show(List.of(cutter), () -> secondScores, false);
        assertSame(preview.takeSnapshot(), secondScores);
        assertNull(preview.takeSnapshot());
        assertEquals(preview.display(bears), "");
        assertEquals(preview.display(cutter), secondScores.get(cutter).text());
        assertNotEquals(preview.display(cutter), firstScores.get(cutter).text());
        assertEquals(preview.display(PaperCard.FAKE_CARD), "");
        assertTrue(preview.confirmClose(() -> true));
        assertEquals(preview.display(cutter), "");
        assertNull(preview.overlay(cutter));

        var restarted = new DraftPackPreview();
        restarted.show(List.of(bears), () -> firstScores, false);
        assertEquals(restarted.display(cutter), "");
        assertEquals(preview.display(bears), "");
        restarted.clear();
        assertEquals(restarted.display(bears), "");
    }
}
