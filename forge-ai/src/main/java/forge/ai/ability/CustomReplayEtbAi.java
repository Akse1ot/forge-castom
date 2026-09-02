package forge.ai.ability;

import java.util.List;
import java.util.Map;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.ai.SpellApiToAi;
import forge.game.CustomReplayEtbTriggerSupport;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerChangesZone;

public class CustomReplayEtbAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai,
                                              final SpellAbility sa) {
        return chooseTargets(ai, sa, false);
    }

    @Override
    public AiAbilityDecision chkDrawback(final Player ai,
                                         final SpellAbility sa) {
        return chooseTargets(ai, sa, false);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai,
                                                final SpellAbility sa, final boolean mandatory) {
        return chooseTargets(ai, sa, mandatory);
    }

    private AiAbilityDecision chooseTargets(final Player ai,
                                            final SpellAbility sa, final boolean mandatory) {
        if (!sa.usesTargeting()) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        sa.resetTargets();

        while (sa.canAddMoreTarget()) {
            // Before the minimum is reached a mandatory ability may have to
            // take a bad target. Once target count is legal, only add targets
            // whose replay is actually useful.
            final boolean requireUseful =
                    !mandatory || sa.isTargetNumberValid();

            final Card target = chooseTarget(ai, sa, requireUseful);
            if (target == null) {
                break;
            }

            sa.getTargets().add(target);
        }

        if (!sa.isTargetNumberValid()) {
            sa.resetTargets();
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        if (!mandatory && sa.getTargets().isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    private Card chooseTarget(final Player ai, final SpellAbility sa,
                              final boolean requireUseful) {
        CardCollection candidates = CardUtil.getValidCardsToTarget(sa);
        candidates = CardLists.canSubsequentlyTarget(candidates, sa);

        if (candidates.isEmpty()) {
            return null;
        }

        final CardCollection best = new CardCollection();
        int bestScore = Integer.MIN_VALUE;

        for (final Card card : candidates) {
            final int score = evaluateTarget(ai, card);

            if (score > bestScore) {
                best.clear();
                best.add(card);
                bestScore = score;
            } else if (score == bestScore) {
                best.add(card);
            }
        }

        if (requireUseful && bestScore <= 0) {
            return null;
        }

        return ComputerUtilCard.getBestAI(best);
    }

    private int evaluateTarget(final Player ai, final Card card) {
        final Map<AbilityKey, Object> runParams =
                CustomReplayEtbTriggerSupport.getReplayRunParams(card);

        if (runParams == null) {
            return Integer.MIN_VALUE;
        }

        final List<TriggerChangesZone> triggers =
                CustomReplayEtbTriggerSupport.getReplayableEtbTriggers(
                        card, runParams);

        if (triggers.isEmpty()) {
            return -1000;
        }

        int bestRating = 0;
        boolean mandatoryBadEffect = false;

        for (final TriggerChangesZone trigger : triggers) {
            final SpellAbility preview =
                    CustomReplayEtbTriggerSupport.buildPreviewAbility(
                            trigger, ai, runParams);

            if (preview == null || preview.getApi() == null) {
                continue;
            }

            // Do not recursively evaluate another Worship-like effect.
            if (preview.getApi() == ApiType.CustomReplayEtb) {
                continue;
            }

            final AiAbilityDecision decision =
                    SpellApiToAi.Converter.get(preview)
                            .doTriggerNoCostWithSubs(ai, preview, false);

            if (decision.willingToPlay(preview)) {
                bestRating = Math.max(bestRating, decision.rating());
            } else if (!trigger.hasParam("OptionalDecider")) {
                mandatoryBadEffect = true;
            }
        }

        if (mandatoryBadEffect) {
            bestRating -= 100;
        }

        return bestRating;
    }
}