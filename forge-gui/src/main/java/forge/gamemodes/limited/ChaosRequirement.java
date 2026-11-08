package forge.gamemodes.limited;

import forge.card.CardType;
import forge.game.ability.AbilityFactory;
import forge.item.PaperCard;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recognized positive effects gated by simple Forge selectors. Complex selectors are kept unknown. */
record ChaosRequirement(String selector, String zone, Kind kind, int value) {
    enum Kind { PERMANENT, ASSEMBLY }

    static ChaosRequirement fromStatic(Map<String, String> params) {
        if (!"Continuous".equals(params.get("Mode")) || !(params.containsKey("AddKeyword")
                || params.getOrDefault("AddPower", "").matches("[1-9][0-9]?")
                || params.getOrDefault("AddToughness", "").matches("[1-9][0-9]?"))) {
            return null;
        }
        String present = params.get("IsPresent");
        if (present != null && ((params.containsKey("PresentCompare") && !"GE1".equals(params.get("PresentCompare")))
                || params.containsKey("CheckSVar") || params.containsKey("CheckSecondSVar"))) {
            return new ChaosRequirement("", "", Kind.PERMANENT, 5);
        }
        if (present == null) {
            String affected = params.getOrDefault("Affected", "");
            if (affected.contains("+Other") || affected.contains(".Other")) {
                present = affected;
            }
        }
        return present == null ? null : new ChaosRequirement(present,
                params.getOrDefault("PresentZone", "Battlefield"), Kind.PERMANENT, 5);
    }

    static ChaosRequirement fromSpell(Map<String, String> params) {
        String target = params.get("ValidTgts");
        if ("ChangeZone".equals(params.get("SP")) && "Graveyard".equals(params.get("Origin"))
                && target != null && (target.endsWith(".YouCtrl") || target.endsWith(".YouOwn"))
                && Set.of("Hand", "Battlefield").contains(params.getOrDefault("Destination", ""))) {
            return new ChaosRequirement(target, "Graveyard", Kind.PERMANENT, 10);
        }
        return null;
    }

    static ChaosRequirement fromReplacement(Map<String, String> params, Map<String, String> variables) {
        String selector = params.get("ValidCause");
        String replacement = variables.get(params.getOrDefault("ReplaceWith", ""));
        if ("AssembleContraption".equals(params.get("Event")) && selector != null && replacement != null) {
            Map<String, String> effect = AbilityFactory.getMapParams(replacement);
            if ("AssembleContraption".equals(effect.get("DB")) && "ReplacedCause".equals(effect.get("DefinedAssembler"))
                    && "2".equals(effect.get("Amount"))) {
                return new ChaosRequirement(selector, "Battlefield", Kind.ASSEMBLY, 7);
            }
        }
        return null;
    }

    boolean isRecognized() {
        if (!Set.of("Battlefield", "Graveyard", "Library").contains(zone)) {
            return false;
        }
        List<String> parts = parts();
        return !parts.isEmpty() && parts.stream().allMatch(part -> Set.of("Card", "Permanent", "Other", "YouCtrl",
                "YouOwn", "stickered", "Token", "nonToken").contains(part)
                || CardType.isACardType(part) || CardType.isASubType(part));
    }

    boolean needsSticker() {
        return parts().contains("stickered");
    }

    boolean other() {
        return parts().contains("Other");
    }

    boolean matches(CardType type, boolean token) {
        for (String part : parts()) {
            boolean matches = switch (part) {
                case "Card", "Other", "YouCtrl", "YouOwn", "stickered" -> true;
                case "Permanent" -> type.isPermanent();
                case "Token" -> token;
                case "nonToken" -> !token;
                default -> type.hasSubtype(part) || type.getCoreTypes().stream()
                        .anyMatch(core -> core.toString().equalsIgnoreCase(part));
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private List<String> parts() {
        return Arrays.asList(selector.split("[.+]"));
    }

    static boolean fitsZones(PaperCard card, String zone) {
        var type = card.getMainFace().getType();
        return !"Battlefield".equals(zone) || type.isPermanent();
    }
}
