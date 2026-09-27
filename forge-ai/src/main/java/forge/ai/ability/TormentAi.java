package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.ability.AbilityUtils;
import forge.game.ability.TormentUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;

import java.util.Collection;

public class TormentAi extends SpellAbilityAi {
    public static String chooseOption(final Player ai,
                                      final SpellAbility sa,
                                      final Collection<String> validTypes,
                                      final boolean effect) {
        if (validTypes == null || validTypes.isEmpty()) {
            return null;
        }

        if (validTypes.size() == 1) {
            return validTypes.iterator().next();
        }

        String bestChoice = null;
        int bestScore = Integer.MAX_VALUE;

        for (final String choice : validTypes) {
            final int score = scoreTormentChoice(ai, sa, choice, effect);

            if (score < bestScore) {
                bestScore = score;
                bestChoice = choice;
            }
        }

        return bestChoice != null ? bestChoice : validTypes.iterator().next();
    }

    private static Card getDiscardChoice(final Player player,
                                         final SpellAbility sa,
                                         final boolean effect) {
        final CardCollection discardable =
                TormentUtil.getDiscardableCards(player, sa, effect);

        if (discardable.isEmpty()) {
            return null;
        }

        Card discard = ComputerUtil.getCardPreference(
                player,
                sa.getHostCard(),
                "DiscardCost",
                discardable,
                sa
        );

        if (discard == null) {
            discard = ComputerUtilCard.getWorstAI(discardable);
        }

        return discard;
    }

    private static Card getSacrificeChoice(final Player player,
                                           final SpellAbility sa,
                                           final boolean effect) {
        final CardCollection sacrificable =
                TormentUtil.getSacrificablePermanents(player, sa, effect);

        if (sacrificable.isEmpty()) {
            return null;
        }

        if (player.getController().isAI()) {
            final CardCollection chosen =
                    ComputerUtil.choosePermanentsToSacrifice(
                            player,
                            sacrificable,
                            1,
                            sa,
                            false,
                            false
                    );

            if (!chosen.isEmpty()) {
                return chosen.getFirst();
            }
        }

        return ComputerUtilCard.getWorstAI(sacrificable);
    }

    private static int scoreTormentChoice(final Player player,
                                          final SpellAbility sa,
                                          final String choice,
                                          final boolean effect) {
        if (TormentUtil.CHOICE_LOSE_LIFE.equals(choice)) {
            if (!player.canLoseLife()) {
                return 0;
            }

            if (player.getLife() <= 3
                    && !player.cantLoseForZeroOrLessLife()) {
                return 1000;
            }

            if (ComputerUtil.aiLifeInDanger(player, false, 3)) {
                return 70;
            }

            return 30;
        }

        if (TormentUtil.CHOICE_DISCARD.equals(choice)) {
            final Card discard = getDiscardChoice(player, sa, effect);

            if (discard == null) {
                return Integer.MAX_VALUE;
            }

            if (discard.hasSVar("DiscardMe")) {
                return 0;
            }

            if (ComputerUtil.isWorseThanDraw(player, discard)) {
                return 10;
            }

            return 20 + Math.min(30, discard.getCMC() * 4);
        }

        if (TormentUtil.CHOICE_SACRIFICE.equals(choice)) {
            final Card sacrifice = getSacrificeChoice(player, sa, effect);

            if (sacrifice == null) {
                return Integer.MAX_VALUE;
            }

            if (sacrifice.hasSVar("SacMe")) {
                return 0;
            }

            if (ComputerUtil.shouldSacrificeThreatenedCard(
                    player,
                    sacrifice,
                    sa)) {
                return 5;
            }

            if (sacrifice.isToken()) {
                return 8;
            }

            return 25 + Math.min(40, sacrifice.getCMC() * 5);
        }

        return Integer.MAX_VALUE;
    }

    private static int evaluateTorment(final Player player,
                                       final SpellAbility sa,
                                       final boolean effect) {
        final Collection<String> choices =
                TormentUtil.getAvailableChoices(player, sa, effect);

        int bestScore = Integer.MAX_VALUE;

        for (final String choice : choices) {
            bestScore = Math.min(
                    bestScore,
                    scoreTormentChoice(player, sa, choice, effect)
            );
        }

        return bestScore == Integer.MAX_VALUE ? 0 : bestScore;
    }

    private static PlayerCollection getAffectedPlayers(
            final SpellAbility sa) {
        if (sa.usesTargeting() && !sa.hasParam("Defined")) {
            return new PlayerCollection(
                    sa.getTargets().getTargetPlayers()
            );
        }

        return AbilityUtils.getDefinedPlayers(
                sa.getHostCard(),
                sa.getParamOrDefault("Defined", "You"),
                sa
        );
    }

    private static int evaluateAbility(final Player ai,
                                       final SpellAbility sa,
                                       final boolean effect) {
        int amount = 1;

        if (sa.hasParam("Amount")) {
            amount = AbilityUtils.calculateAmount(
                    sa.getHostCard(),
                    sa.getParam("Amount"),
                    sa
            );
        }

        if (amount <= 0) {
            return 0;
        }

        int score = 0;

        for (final Player player : getAffectedPlayers(sa)) {
            if (!player.isInGame()) {
                continue;
            }

            final int tormentScore =
                    evaluateTorment(player, sa, effect) * amount;

            if (player.isOpponentOf(ai)) {
                score += tormentScore;
            } else {
                score -= tormentScore;
            }
        }

        return score;
    }

    @Override
    protected AiAbilityDecision checkApiLogic(
            final Player ai,
            final SpellAbility sa) {
        if (sa.usesTargeting() && !doTgt(ai, sa, false)) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.TargetingFailed
            );
        }

        if (evaluateAbility(ai, sa, true) <= 0) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.CantPlayAi
            );
        }

        if (ComputerUtil.playImmediately(ai, sa)) {
            return new AiAbilityDecision(
                    100,
                    AiPlayDecision.WillPlay
            );
        }

        if (ai.getGame().getPhaseHandler().getPhase()
                .isBefore(PhaseType.MAIN2)
                && !sa.hasParam("ActivationPhases")
                && !ComputerUtil.castSpellInMain1(ai, sa)) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.WaitForMain2
            );
        }

        if (ComputerUtil.waitForBlocking(sa)) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.WaitForCombat
            );
        }

        if (isSorcerySpeed(sa, ai)
                || sa.hasParam("ActivationPhases")
                || playReusable(ai, sa)
                || ComputerUtil.activateForCost(sa, ai)) {
            return new AiAbilityDecision(
                    100,
                    AiPlayDecision.WillPlay
            );
        }

        return new AiAbilityDecision(
                0,
                AiPlayDecision.CantPlayAi
        );
    }

    @Override
    public AiAbilityDecision chkDrawback(
            final Player ai,
            final SpellAbility sa) {
        if (sa.usesTargeting() && !doTgt(ai, sa, true)) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.CantPlayAi
            );
        }

        return new AiAbilityDecision(
                100,
                AiPlayDecision.WillPlay
        );
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(
            final Player ai,
            final SpellAbility sa,
            final boolean mandatory) {
        if (sa.usesTargeting()
                && !doTgt(ai, sa, mandatory)) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.CantPlayAi
            );
        }

        if (mandatory) {
            return new AiAbilityDecision(
                    50,
                    AiPlayDecision.MandatoryPlay
            );
        }

        if (evaluateAbility(ai, sa, true) <= 0) {
            return new AiAbilityDecision(
                    0,
                    AiPlayDecision.CantPlayAi
            );
        }

        return new AiAbilityDecision(
                100,
                AiPlayDecision.WillPlay
        );
    }

    private boolean doTgt(final Player ai,
                          final SpellAbility sa,
                          final boolean mandatory) {
        sa.resetTargets();

        final PlayerCollection opponents =
                ai.getOpponents().filter(
                        PlayerPredicates.isTargetableBy(sa)
                );

        Player best = null;
        int bestScore = Integer.MIN_VALUE;

        for (final Player opponent : opponents) {
            final int score =
                    evaluateTorment(opponent, sa, true);

            if (score > bestScore) {
                bestScore = score;
                best = opponent;
            }
        }

        if (best != null
                && (mandatory || bestScore > 0)) {
            sa.getTargets().add(best);
            return true;
        }

        if (!mandatory) {
            return false;
        }

        Player fallback = null;
        int fallbackScore = Integer.MAX_VALUE;

        for (final Player ally : ai.getAllies()) {
            if (!sa.canTarget(ally)) {
                continue;
            }

            final int score =
                    evaluateTorment(ally, sa, true);

            if (score < fallbackScore) {
                fallbackScore = score;
                fallback = ally;
            }
        }

        if (sa.canTarget(ai)) {
            final int score =
                    evaluateTorment(ai, sa, true);

            if (score < fallbackScore) {
                fallback = ai;
            }
        }

        if (fallback != null) {
            sa.getTargets().add(fallback);
            return true;
        }

        return false;
    }
}