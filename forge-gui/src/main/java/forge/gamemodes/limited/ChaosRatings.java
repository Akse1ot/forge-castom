package forge.gamemodes.limited;

import forge.StaticData;
import forge.card.CardDb;
import forge.item.PaperCard;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only external evidence. The built-in CardRanker keeps its own ratings. */
final class ChaosRatings {
    record Rating(double value, String source) {}

    private final Map<String, Double> standard;
    private final Map<String, Double> custom;

    ChaosRatings(Path directory) {
        standard = loadRatings(directory.resolve("ratings.tsv"));
        custom = loadCustomRatings(directory.resolve("custom_ratings.tsv"));
    }

    boolean isAvailable() {
        return !standard.isEmpty() || !custom.isEmpty();
    }

    int standardSize() {
        return standard.size();
    }

    static boolean isCustom(PaperCard card) {
        String path = card.getRules().getPath();
        return card.getRules().isCustom() || (path != null
                && ("/" + path.replace('\\', '/').toLowerCase(Locale.ROOT)).contains("/custom cards/"));
    }

    Rating lookup(PaperCard card) {
        final String name = key(card.getName());
        final String print = name + "\t" + card.getEdition().toUpperCase(Locale.ROOT);
        final boolean customCard = isCustom(card);
        final boolean manual = custom.containsKey(print) || custom.containsKey(name);
        // A legacy override has no script fingerprint. Use it only for the
        // active, unambiguous rules and functional variant of the named card.
        final boolean identified = manual && (!customCard || isCanonicalCustom(card));
        if (identified && custom.containsKey(print)) {
            return new Rating(custom.get(print), "custom_rating");
        }
        if (customCard) {
            return identified && custom.containsKey(name) ? new Rating(custom.get(name), "custom_rating") : null;
        }
        Double value = standard.get(name);
        final int separator = name.indexOf(" // ");
        if (value == null && separator > 0) {
            value = standard.get(name.substring(0, separator));
        }
        if (value != null) {
            return new Rating(value, "draftmancer_rating");
        }
        return identified && custom.containsKey(name) ? new Rating(custom.get(name), "supplemental_rating") : null;
    }

    private static boolean isCanonicalCustom(PaperCard card) {
        StaticData data = StaticData.instance();
        if (data == null) {
            return false;
        }
        CardDb db = card.getRules().isVariant() ? data.getVariantCards() : data.getCommonCards();
        CardDb other = card.getRules().isVariant() ? data.getCommonCards() : data.getVariantCards();
        return db.getRules(card.getName(), false) == card.getRules()
                && other.getRules(card.getName(), true) == null
                && db.getAllCards(card.getName()).stream().allMatch(print -> print.getRules() == card.getRules()
                        && print.getFunctionalVariant().equals(card.getFunctionalVariant()));
    }

    private static String key(final String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<String, Double> loadRatings(Path file) {
        final Map<String, Double> result = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] columns = line.split("\\t", -1);
                if (columns.length != 2 || columns[0].trim().isEmpty()) {
                    continue;
                }
                try {
                    final double value = Double.parseDouble(columns[1]);
                    if (Double.isFinite(value) && value >= 0 && value <= 100) {
                        result.put(key(columns[0]), value);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid individual rows must not affect the game.
                }
            }
        } catch (IOException | SecurityException ignored) {
            result.clear();
        }
        return result;
    }

    private static Map<String, Double> loadCustomRatings(Path file) {
        final Map<String, Double> result = new HashMap<>();
        final Set<String> ambiguous = new HashSet<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] fields = line.split("\t", -1);
                if (fields.length < 2 || fields[0].isBlank()) {
                    continue;
                }
                try {
                    // Name<TAB>score[<TAB>source], or Name<TAB>edition<TAB>score.
                    // A source/confidence column does not change the numeric score.
                    boolean editionSpecific = false;
                    double value;
                    try {
                        value = Double.parseDouble(fields[1].trim());
                    } catch (NumberFormatException noGenericScore) {
                        if (fields.length < 3) {
                            continue;
                        }
                        editionSpecific = true;
                        value = Double.parseDouble(fields[2].trim());
                    }
                    if (!Double.isFinite(value) || value < 0 || value > 100) {
                        continue;
                    }
                    if (editionSpecific && fields[1].isBlank()) {
                        continue;
                    }
                    final String key = editionSpecific
                            ? key(fields[0]) + "\t" + fields[1].trim().toUpperCase(Locale.ROOT) : key(fields[0]);
                    final Double previous = result.putIfAbsent(key, value);
                    if (previous != null && previous.doubleValue() != value) {
                        ambiguous.add(key);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid custom ratings must not affect the game.
                }
            }
        } catch (IOException | SecurityException ignored) {
            result.clear();
        }
        ambiguous.forEach(result::remove);
        return result;
    }

}
