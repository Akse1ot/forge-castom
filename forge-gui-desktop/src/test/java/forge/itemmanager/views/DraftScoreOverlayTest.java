package forge.itemmanager.views;

import org.testng.annotations.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.testng.Assert.*;

public class DraftScoreOverlayTest {
    @Test
    public void changesOnlyLeftFooterAndDoesNotAlterNativeGraphics() {
        BufferedImage image = new BufferedImage(140, 196, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Rectangle bounds = new Rectangle(0, 0, 140, 196);
            Rectangle clip = new Rectangle(2, 3, 135, 190);
            Font nativeFont = new Font(Font.DIALOG, Font.PLAIN, 14);
            g.setColor(Color.BLUE);
            g.fill(bounds);
            // Simulate the native rank already drawn in the centre of the thumbnail.
            g.setColor(Color.WHITE);
            g.setFont(nativeFont);
            g.setClip(clip);
            g.drawString("72", 65, 100);
            int[] original = image.getRGB(0, 0, 140, 196, null, 0, 140);
            DraftScoreOverlay.draw(g, bounds, new DraftScoreOverlay.Label("72.5", true));
            assertEquals(g.getClipBounds(), clip);
            assertEquals(g.getFont(), nativeFont);
            assertEquals(g.getColor(), Color.WHITE);
            Rectangle safe = DraftScoreOverlay.safeArea(bounds);
            assertTrue(safe.y >= 180);
            assertTrue(safe.getMaxX() < bounds.getCenterX());
            int changed = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (original[y * 140 + x] != image.getRGB(x, y)) {
                        assertTrue(safe.contains(x, y));
                        changed++;
                    }
                }
            }
            assertTrue(changed > 0);
            int[] normal = image.getRGB(0, 0, 140, 196, null, 0, 140);
            DraftScoreOverlay.draw(g, new Rectangle(0, 0, 40, 56), new DraftScoreOverlay.Label("72.5", false));
            assertEquals(image.getRGB(0, 0, 140, 196, null, 0, 140), normal);
        } finally {
            g.dispose();
        }
    }
}
