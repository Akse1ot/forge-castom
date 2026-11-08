package forge.gamemodes.limited;

import forge.ai.SpellApiToAi;
import forge.card.CardRulesPredicates;
import forge.card.DeckHints;
import forge.game.ability.ApiType;
import forge.item.PaperCard;

import java.util.Map;

/** Evidence about the AI's ability to use an effect, separate from draft resources. */
final class ChaosAiEvidence {
    enum Status { SUPPORTED, PARTIAL, UNSUPPORTED, UNKNOWN }

    record Assessment(Status status, String reason) {}

    private ChaosAiEvidence() {}

    static boolean deckAllowed(PaperCard card, Iterable<PaperCard> cards) {
        var rules = card.getRules();
        if (!CardRulesPredicates.IS_KEPT_IN_AI_LIMITED_DECKS.test(rules)) {
            return false;
        }
        var hints = rules.getAiHints();
        if (!hints.getRemRandomDecks()) {
            return true;
        }
        if (ChaosManaAmount.hasAssemblyRequirement(card)) {
            return ChaosManaAmount.assembledRequirementMet(card, cards);
        }
        return matches(hints.getDeckNeeds(), cards);
    }

    private static boolean matches(DeckHints hints, Iterable<PaperCard> cards) {
        return hints != null && hints.isValid() && hints.filter(cards).iterator().hasNext();
    }

    static Assessment assess(ApiType api, Map<String, String> params, boolean voluntary) {
        if (!voluntary) {
            if (params.containsKey("ValidTgts") || params.containsKey("Choices") || params.containsKey("AILogic")) {
                return new Assessment(Status.PARTIAL, "mandatory_effect_requires_selection:" + api);
            }
            return new Assessment(Status.SUPPORTED, "engine_resolves_mandatory_effect");
        }
        if (SpellApiToAi.Converter.hasExplicitPlayVeto(api)) {
            return new Assessment(Status.UNSUPPORTED, "CannotPlayAi:" + api);
        }
        if (api == ApiType.ChoosePlayer && "MostCardsInHand".equals(params.get("AILogic"))
                && "Player.Opponent".equals(params.get("Choices"))) {
            return new Assessment(Status.SUPPORTED, "ChoosePlayerAi:MostCardsInHand");
        }
        if (api == ApiType.Mana && params.getOrDefault("Cost", "").matches("(?:[0-9] )?T")
                && !params.containsKey("RestrictValid") && !params.containsKey("AILogic")
                && params.getOrDefault("Produced", "").matches("(?:Combo )?[WUBRGC](?: [WUBRGC])*")) {
            return new Assessment(Status.SUPPORTED, "simple_mana_ability");
        }
        if ((api == ApiType.OpenAttraction || api == ApiType.AssembleContraption || api == ApiType.PutSticker)
                && !params.containsKey("ValidTgts") && !params.containsKey("DefinedContraption")) {
            return new Assessment(Status.PARTIAL, "handler_checks_auxiliary_resources:" + api);
        }
        return new Assessment(Status.UNKNOWN, "specific_parameters_not_verified:" + api);
    }
}
