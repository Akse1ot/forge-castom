package forge.ai.ability;

import forge.ai.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class SacrificeAllAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        // AI needs to be expanded, since this function can be pretty complex
        // based on what the expected targets could be
        final String logic = sa.getParamOrDefault("AILogic", "");

        if (logic.equals("HellionEruption")) {
            if (ai.getCreaturesInPlay().size() < 5 || ai.getCreaturesInPlay().size() * 150 < ComputerUtilCard.evaluateCreatureList(ai.getCreaturesInPlay())) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        } else if (logic.equals("MadSarkhanDragon")) {
            return SpecialCardAi.SarkhanTheMad.considerMakeDragon(ai, sa);
        } else if (logic.equals("ShapeAnew")) {
            return considerShapeAnew(ai, sa);
        } else if (logic.equals("CursedSacrificeAll")) {
            return considerCursedSacrificeAll(ai, sa);
        }

        AiAbilityDecision decision = DestroyAllAi.doMassRemovalLogic(ai, sa);
        if (!decision.willingToPlay()) {
            return decision;
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        //TODO: Add checks for bad outcome
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);

    }

    private AiAbilityDecision considerCursedSacrificeAll(final Player ai, final SpellAbility sa) {
        if (!ai.getGame().getPhaseHandler().is(PhaseType.MAIN1, ai)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // Only evaluate abilities that sacrifice exclusively the AI's own permanents.
        if (!sa.hasParam("ValidCards") || sa.hasParam("Defined") || sa.hasParam("Controller")) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        final CardCollection cards = new CardCollection();
        for (final Card card : AbilityUtils.filterListByType(ai.getGame().getCardsIn(ZoneType.Battlefield), sa.getParam("ValidCards"), sa)) {
            if (!card.canBeSacrificedBy(sa, true)) {
                continue;
            }
            if (!card.getController().equals(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            cards.add(card);
        }

        final int souls = ComputerUtilCursed.getCursedAmount(cards);
        if (cards.isEmpty() || souls <= 0) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // SacMe marks permanents the AI already prefers to sacrifice.
        int sacrificeValue = 1; // The spell itself also costs a card.
        boolean allSacMe = true;
        for (final Card card : cards) {
            if (!card.hasSVar("SacMe")) {
                allSacMe = false;
                sacrificeValue += card.getCMC() + 1;
            }
        }
        if (allSacMe) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        int bestDamage = 0;
        for (final Player opponent : ai.getOpponents()) {
            if (!opponent.canLoseLife()) {
                continue;
            }

            // Conservative rough estimate: each opposing creature blocks one Soul.
            final int damage = Math.max(0, souls - opponent.getCreaturesInPlay().size());
            if (opponent.getCreaturesInPlay().isEmpty() && opponent.getLife() > 0
                    && souls >= opponent.getLife() && !opponent.cantLoseForZeroOrLessLife()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            bestDamage = Math.max(bestDamage, Math.min(damage, opponent.getLife()));
        }

        if (bestDamage >= Math.max(2, sacrificeValue)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    private AiAbilityDecision considerShapeAnew(Player ai, SpellAbility sa) {
        Card worstToSacrifice = ComputerUtilCard.getCheapestPermanentAI(CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.ARTIFACTS), sa, true);
        if (worstToSacrifice == null) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        Card worstToGain = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.ARTIFACTS));
        if (worstToGain != null && worstToGain.getCMC() > worstToSacrifice.getCMC() + sa.getHostCard().getCMC()) {
            sa.resetTargets();
            sa.getTargets().add(worstToSacrifice);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }
}
