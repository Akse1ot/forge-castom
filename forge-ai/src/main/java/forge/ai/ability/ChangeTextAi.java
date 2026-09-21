package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

public class ChangeTextAi extends SpellAbilityAi {
    private static final int MIN_REPLACE_SCORE = 6;

    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        if (!sa.hasParam("ReplaceSpellAbility") || !sa.usesTargeting()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        sa.resetTargets();

        final SpellAbility target = chooseTargetSpellAbility(ai.getGame(), sa, ai, false);
        if (target == null) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        sa.getTargets().add(target);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(final Player ai, final SpellAbility sa) {
        return doTriggerNoCost(ai, sa, true);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        if (!sa.hasParam("ReplaceSpellAbility") || !sa.usesTargeting()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        sa.resetTargets();

        final SpellAbility target = chooseTargetSpellAbility(ai.getGame(), sa, ai, mandatory);
        if (target == null) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        sa.getTargets().add(target);
        return new AiAbilityDecision(100, mandatory ? AiPlayDecision.MandatoryPlay : AiPlayDecision.WillPlay);
    }

    private SpellAbility chooseTargetSpellAbility(final Game game, final SpellAbility sa,
                                                  final Player ai, final boolean mandatory) {
        SpellAbility best = null;
        SpellAbility opponentFallback = null;
        SpellAbility fallback = null;
        int bestScore = Integer.MIN_VALUE;

        for (final SpellAbilityStackInstance si : game.getStack()) {
            final SpellAbility target = si.getSpellAbility();

            if (!sa.canTargetSpellAbility(target)) {
                continue;
            }

            if (fallback == null) {
                fallback = target;
            }

            final Player controller = target.getActivatingPlayer();
            if (controller == null || !controller.isOpponentOf(ai)) {
                continue;
            }

            if (opponentFallback == null) {
                opponentFallback = target;
            }

            if (target.hasResolvingAbilityReplacement()) {
                continue;
            }

            final int score = evaluateSpell(target);
            if (score >= MIN_REPLACE_SCORE && score > bestScore) {
                best = target;
                bestScore = score;
            }
        }

        if (best != null) {
            return best;
        }

        if (mandatory) {
            return opponentFallback != null ? opponentFallback : fallback;
        }

        return null;
    }

    private int evaluateSpell(final SpellAbility sa) {
        int score = 0;

        if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null) {
            score += sa.getPayCosts().getTotalMana().getCMC();
            if (sa.getPayCosts().getTotalMana().countX() > 0) {
                score += 3;
            }
        }

        SpellAbility part = sa;
        while (part != null) {
            score += evaluateApi(part.getApi());
            part = part.getSubAbility();
        }

        return score;
    }

    private int evaluateApi(final ApiType api) {
        if (api == null) {
            return 0;
        }

        return switch (api) {
            case DestroyAll, SacrificeAll -> 9;
            case Counter, GainControl, ControlSpell, DamageAll -> 8;
            case Destroy, Sacrifice, ChangeZone, ChangeZoneAll, DealDamage, LoseLife -> 6;
            case Draw, Dig, DigUntil, Clone, CopySpellAbility, CopyPermanent,
                 PumpAll, PutCounterAll, Token -> 4;
            default -> 0;
        };
    }
}