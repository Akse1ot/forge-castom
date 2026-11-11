package forge.gamemodes.limited;

import forge.game.ability.AbilityFactory;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recognized resource requirements of mana paths in non-Commander Full Chaos Limited. */
record ChaosManaTraits(Kind kind, int threshold, boolean independentEffect) {
    enum Kind { ORDINARY, UNKNOWN, FORMAT_UNAVAILABLE, LAND_THRESHOLD, CHOSEN, REFLECTED, STORAGE, FODDER, ONE_SHOT, AURA }

    private static final Set<String> CHOSEN_PARAMS = Set.of("AB", "Cost", "Produced", "SpellDescription", "Description");
    private static final Set<String> THRESHOLD_PARAMS = Set.of("AB", "Cost", "Produced", "Amount", "IsPresent",
            "PresentCompare", "SpellDescription", "Description");

    static ChaosManaTraits read(PaperCard card) {
        var face = card.getMainFace();
        Kind kind = Kind.ORDINARY;
        int threshold = 0;
        boolean other = card.hasBackFace() || !face.getType().isLand() && !face.getType().isArtifact()
                || face.getStaticAbilities().iterator().hasNext() || face.getTriggers().iterator().hasNext();
        boolean alternativeMana = false;
        boolean choseColor = false;
        boolean storesMana = false;
        Map<String, String> variables = new java.util.HashMap<>();
        face.getVariables().forEach(entry -> variables.put(entry.getKey(), entry.getValue()));
        for (String keyword : face.getKeywords()) {
            if (keyword.startsWith("ETBReplacement:")) {
                String[] parts = keyword.split(":", 4);
                if (parts.length >= 3 && variables.containsKey(parts[2])) {
                    var action = AbilityFactory.getMapParams(variables.get(parts[2]));
                    boolean chooses = "ChooseColor".equals(action.get("DB")) && "You".equals(action.get("Defined"))
                            && action.keySet().stream().allMatch(Set.of("DB", "Defined", "SpellDescription")::contains);
                    choseColor |= chooses;
                    other |= !chooses && !("Tap".equals(action.get("DB")) && "Self".equals(action.get("Defined")));
                } else {
                    other = true;
                }
            } else if (!"CARDNAME enters tapped.".equals(keyword)) {
                other = true;
            }
        }
        for (String replacement : face.getReplacements()) {
            var params = AbilityFactory.getMapParams(replacement);
            String script = variables.get(params.get("ReplaceWith"));
            var effect = script == null ? Map.<String, String>of() : AbilityFactory.getMapParams(script);
            other |= !"Tap".equals(effect.get("DB")) || !"Self".equals(effect.get("Defined")) || !"True".equals(effect.get("ETB"));
        }
        List<Map<String, String>> abilities = new ArrayList<>();
        for (String ability : face.getAbilities()) {
            var params = AbilityFactory.getMapParams(ability);
            abilities.add(params);
            storesMana |= "PutCounter".equals(params.get("AB")) && "True".equals(params.get("RememberCostMana"))
                    && "CHARGE".equals(params.get("CounterType")) && "1".equals(params.get("CounterNum"))
                    && "1 T".equals(params.get("Cost"));
        }
        for (var p : abilities) {
            String api = p.get("AB");
            String cost = p.getOrDefault("Cost", "");
            String produced = p.getOrDefault("Produced", "");
            Kind path = Kind.ORDINARY;
            if ("ManaReflected".equals(api)) {
                path = "T".equals(cost) && "Land.YouCtrl".equals(p.get("Valid")) && "Color".equals(p.get("ColorOrType"))
                        && "Produce".equals(p.get("ReflectProperty")) && p.keySet().stream().allMatch(
                                Set.of("AB", "Cost", "Valid", "ColorOrType", "ReflectProperty", "SpellDescription", "Description")::contains)
                        ? Kind.REFLECTED : Kind.UNKNOWN;
            } else if ("Mana".equals(api)) {
                if ("Combo ColorIdentity".equals(produced)) {
                    path = Kind.FORMAT_UNAVAILABLE;
                } else if ("Chosen".equals(produced)) {
                    path = choseColor && chosenMana(p) ? Kind.CHOSEN : Kind.UNKNOWN;
                } else if (produced.contains("LastNotedType")) {
                    path = storesMana && "T SubCounter<1/CHARGE>".equals(cost) ? Kind.STORAGE : Kind.UNKNOWN;
                } else if (cost.matches("Sac<1/Artifact(?:\\.Other)?>") && fixedOutput(p) > 0) {
                    path = Kind.FODDER;
                    threshold = fixedOutput(p);
                } else if (cost.matches("(?:T )?Sac<1/CARDNAME>") && fixedOutput(p) > 0) {
                    path = Kind.ONE_SHOT;
                    threshold = fixedOutput(p);
                } else if (landThreshold(p) > 0) {
                    path = Kind.LAND_THRESHOLD;
                    threshold = landThreshold(p);
                } else if (ChaosManaPlan.simpleMana(p) && cost.matches("(?:[0-9] )?T")) {
                    alternativeMana = true;
                } else {
                    path = Kind.UNKNOWN;
                }
            } else if (!("PutCounter".equals(api) && storesMana && "True".equals(p.get("RememberCostMana")))) {
                other = true;
            }
            if (path != Kind.ORDINARY) {
                other |= kind != Kind.ORDINARY && path != kind;
                kind = kind == Kind.ORDINARY || kind == path ? path : Kind.UNKNOWN;
            }
        }
        if (face.getType().isLand()) {
            for (String subtype : forge.card.MagicColor.Constant.BASIC_LANDS) {
                alternativeMana |= face.getType().hasSubtype(subtype);
            }
        }
        if (kind == Kind.ORDINARY && face.getType().hasSubtype("Aura")) {
            for (String trigger : face.getTriggers()) {
                var p = AbilityFactory.getMapParams(trigger);
                String script = variables.get(p.get("Execute"));
                if ("TapsForMana".equals(p.get("Mode")) && "Card.AttachedBy".equals(p.get("ValidCard")) && script != null) {
                    var action = AbilityFactory.getMapParams(script);
                    if ("Mana".equals(action.get("DB")) && "Any".equals(action.get("Produced"))
                            && "TriggeredCardController".equals(action.get("Defined")) && fixedOutput(action) == 1
                            && action.keySet().stream().allMatch(java.util.Set.of("DB", "Produced", "Amount", "Defined")::contains)
                            && java.util.stream.StreamSupport.stream(face.getKeywords().spliterator(), false)
                            .anyMatch("Enchant:Land"::equals)) {
                        return new ChaosManaTraits(Kind.AURA, 0, false);
                    }
                }
            }
        }
        return new ChaosManaTraits(kind, threshold, other || alternativeMana && kind != Kind.ORDINARY);
    }

    static boolean chosenMana(Map<String, String> p) {
        return "Mana".equals(p.get("AB")) && "Chosen".equals(p.get("Produced")) && "T".equals(p.get("Cost"))
                && p.keySet().stream().allMatch(CHOSEN_PARAMS::contains);
    }

    static int landThreshold(Map<String, String> p) {
        String present = p.getOrDefault("PresentCompare", "");
        return "Mana".equals(p.get("AB")) && "Land.YouCtrl".equals(p.get("IsPresent")) && present.matches("GE[2-9]")
                && "T".equals(p.get("Cost")) && "C".equals(p.get("Produced")) && fixedOutput(p) > 0
                && p.keySet().stream().allMatch(THRESHOLD_PARAMS::contains) ? present.charAt(2) - '0' : 0;
    }

    private static int fixedOutput(Map<String, String> p) {
        String amount = p.getOrDefault("Amount", "1");
        String produced = p.getOrDefault("Produced", "");
        if (!amount.matches("[1-9]") || !produced.matches("(?:Any|[WUBRGC](?: [WUBRGC])*)")
                || p.keySet().stream().anyMatch(key -> key.startsWith("Condition") || key.startsWith("Restrict"))) {
            return 0;
        }
        return (amount.charAt(0) - '0') * produced.split(" ").length;
    }
}
