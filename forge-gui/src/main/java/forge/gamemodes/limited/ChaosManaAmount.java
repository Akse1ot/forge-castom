package forge.gamemodes.limited;

import forge.card.ICardFace;
import forge.game.ability.AbilityFactory;
import forge.item.PaperCard;

import java.util.Map;
import java.util.function.Function;

/** A small subset of Forge mana amounts with a provable floor and a readable condition. */
record ChaosManaAmount(int minimum, int enhanced, String condition, ChaosRequirement counted) {
    private static final String URZA_LANDS = "UrzaLands";

    static ChaosManaAmount read(Map<String, String> params, ICardFace face) {
        return read(params, name -> {
            for (var variable : face.getVariables()) {
                if (variable.getKey().equals(name)) {
                    return variable.getValue();
                }
            }
            return null;
        });
    }

    static ChaosManaAmount read(Map<String, String> params, Function<String, String> variable) {
        String amount = params.getOrDefault("Amount", "1");
        if (amount.matches("[0-9]")) {
            int value = Integer.parseInt(amount);
            return new ChaosManaAmount(value, value, "", null);
        }
        String expression = variable.apply(amount);
        if (expression != null && expression.matches("Count\\$UrzaLands\\.[1-9]\\.[0-9]")) {
            String[] parts = expression.split("\\.");
            return new ChaosManaAmount(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), URZA_LANDS, null);
        }
        if (expression != null && expression.startsWith("Count$Valid ")) {
            var requirement = new ChaosRequirement(expression.substring("Count$Valid ".length()),
                    "Battlefield", ChaosRequirement.Kind.PERMANENT, 0);
            if (requirement.isRecognized()) {
                return new ChaosManaAmount(0, 0, expression, requirement);
            }
        }
        return new ChaosManaAmount(0, 0, "UNKNOWN:" + amount, null);
    }

    boolean variable() {
        return !condition.isEmpty();
    }

    boolean recognized() {
        return !condition.startsWith("UNKNOWN:");
    }

    boolean needsUrzaLands() {
        return URZA_LANDS.equals(condition);
    }

    int guaranteed(PaperCard card) {
        return counted != null && !counted.other() && counted.matches(card.getMainFace().getType(), false)
                ? 1 : minimum;
    }

    int potential(PaperCard card, Iterable<PaperCard> pool) {
        if (needsUrzaLands()) {
            int types = urzaType(card);
            for (PaperCard item : pool) {
                types |= urzaType(item);
            }
            return types == 7 ? enhanced : minimum;
        }
        if (counted != null) {
            int matches = guaranteed(card);
            for (PaperCard item : pool) {
                if (ChaosRequirement.fitsZones(item, counted.zone())
                        && counted.matches(item.getMainFace().getType(), false)) {
                    matches++;
                }
            }
            return matches;
        }
        return minimum;
    }

    static boolean assembledRequirementMet(PaperCard card, Iterable<PaperCard> pool) {
        var face = card.getMainFace();
        for (String ability : face.getAbilities()) {
            var params = AbilityFactory.getMapParams(ability);
            if ("Mana".equals(params.get("AB"))) {
                var amount = read(params, face);
                if (amount.needsUrzaLands()) {
                    return amount.potential(card, pool) == amount.enhanced();
                }
            }
        }
        return false;
    }

    static boolean hasAssemblyRequirement(PaperCard card) {
        var face = card.getMainFace();
        for (String ability : face.getAbilities()) {
            var params = AbilityFactory.getMapParams(ability);
            if ("Mana".equals(params.get("AB")) && read(params, face).needsUrzaLands()) {
                return true;
            }
        }
        return false;
    }

    private static int urzaType(PaperCard card) {
        var type = card.getMainFace().getType();
        if (!type.isLand() || !type.hasSubtype("Urza's")) {
            return 0;
        }
        return (type.hasSubtype("Mine") ? 1 : 0) | (type.hasSubtype("Power-Plant") ? 2 : 0)
                | (type.hasSubtype("Tower") ? 4 : 0);
    }
}
