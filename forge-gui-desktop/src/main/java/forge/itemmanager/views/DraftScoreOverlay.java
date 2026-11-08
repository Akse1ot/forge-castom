package forge.itemmanager.views;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;

/** Places a small number in the left footer of a card thumbnail. */
public final class DraftScoreOverlay {
    public record Label(String text, boolean recommended) {}

    private static final Color RECOMMENDED = new Color(255, 232, 72);
    private static final Color NEUTRAL = new Color(232, 232, 232);
    private static final BasicStroke OUTLINE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private DraftScoreOverlay() {}

    static Rectangle safeArea(Rectangle image) {
        int footer = (int) Math.floor(image.height * .075);
        return new Rectangle(image.x + Math.max(4, image.width / 24),
                image.y + image.height - footer - 2, (int) (image.width * .40), footer);
    }

    static void draw(Graphics2D g, Rectangle image, Label label) {
        Rectangle safe = safeArea(image);
        if (safe.height < 10 || safe.width < 22) {
            return;
        }
        Graphics2D text = (Graphics2D) g.create();
        try {
            text.clip(image);
            text.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = g.getFont().deriveFont(Font.BOLD, Math.min(13f, Math.max(10f, image.width * .10f)));
            GlyphVector glyphs = font.createGlyphVector(text.getFontRenderContext(), label.text());
            Rectangle2D bounds = glyphs.getVisualBounds();
            Shape number = glyphs.getOutline((float) (safe.x + 2 - bounds.getX()),
                    (float) (safe.getMaxY() - 1 - bounds.getMaxY()));
            if (!safe.contains(OUTLINE.createStrokedShape(number).getBounds2D())) {
                return;
            }
            text.setColor(Color.BLACK);
            text.setStroke(OUTLINE);
            text.draw(number);
            text.setColor(label.recommended() ? RECOMMENDED : NEUTRAL);
            text.fill(number);
        } finally {
            text.dispose();
        }
    }
}
