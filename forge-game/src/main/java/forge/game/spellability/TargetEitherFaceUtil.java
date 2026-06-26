package forge.game.spellability;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardState;

import java.util.EnumSet;

public final class TargetEitherFaceUtil {
    private TargetEitherFaceUtil() {
    }

    public static boolean isEnabled(final SpellAbility sa) {
        return sa != null && sa.hasParam("TargetEitherFace");
    }

    public static boolean isSupportedMDFC(final Card card) {
        return card != null && card.isModal() && card.hasState(CardStateName.Backside);
    }

    public static CardStateName getTargetedStateOrNull(final SpellAbility sa, final Card card) {
        return getTargetedStateOrNull(sa, card, null);
    }

    public static CardStateName getTargetedStateOrNull(final SpellAbility sa, final Card first, final Card second) {
        if (sa == null) {
            return null;
        }

        CardStateName state = getTargetedStateFromChoices(sa.getTargets(), first, second);
        if (state != null) {
            return state;
        }

        for (final TargetChoices choices : sa.getAllTargetChoices()) {
            if (choices == null || choices == sa.getTargets()) {
                continue;
            }

            state = getTargetedStateFromChoices(choices, first, second);
            if (state != null) {
                return state;
            }
        }

        return null;
    }

    private static CardStateName getTargetedStateFromChoices(final TargetChoices choices, final Card first, final Card second) {
        if (choices == null) {
            return null;
        }

        if (first != null) {
            final CardStateName state = choices.getTargetedCardState(first);
            if (state != null) {
                return state;
            }
        }

        if (second != null) {
            final CardStateName state = choices.getTargetedCardState(second);
            if (state != null) {
                return state;
            }
        }

        return null;
    }

    public static CardStateName getTargetedStateOrOriginal(final SpellAbility sa, final Card card) {
        final CardStateName state = getTargetedStateOrNull(sa, card);
        return state == null ? CardStateName.Original : state;
    }

    public static CardStateName getTargetedStateOrOriginal(final SpellAbility sa, final Card first, final Card second) {
        final CardStateName state = getTargetedStateOrNull(sa, first, second);
        return state == null ? CardStateName.Original : state;
    }

    public static boolean hasExplicitTargetedState(final SpellAbility sa, final Card card) {
        return getTargetedStateOrNull(sa, card) != null;
    }

    public static boolean hasExplicitTargetedState(final SpellAbility sa, final Card first, final Card second) {
        return getTargetedStateOrNull(sa, first, second) != null;
    }

    public static boolean canTargetInState(final SpellAbility sa, final Card card, final CardStateName state) {
        if (sa == null || card == null || state == null) {
            return false;
        }

        final TargetChoices targets = sa.getTargets();
        final boolean hadOldState = targets.hasTargetedCardState(card);
        final CardStateName oldState = targets.getTargetedCardState(card);

        targets.setTargetedCardState(card, state);
        try {
            return sa.canTarget(card);
        } finally {
            if (hadOldState) {
                targets.setTargetedCardState(card, oldState);
            } else {
                targets.clearTargetedCardState(card);
            }
        }
    }

    public static EnumSet<CardStateName> getLegalTargetStates(final SpellAbility sa, final Card card) {
        final EnumSet<CardStateName> result = EnumSet.noneOf(CardStateName.class);

        if (sa == null || card == null) {
            return result;
        }

        if (!isEnabled(sa) || !isSupportedMDFC(card)) {
            if (sa.canTarget(card)) {
                result.add(CardStateName.Original);
            }
            return result;
        }

        if (canTargetInState(sa, card, CardStateName.Original)) {
            result.add(CardStateName.Original);
        }
        if (canTargetInState(sa, card, CardStateName.Backside)) {
            result.add(CardStateName.Backside);
        }

        return result;
    }

    public static CardStateName getAutoTargetState(final SpellAbility sa, final Card card) {
        if (sa == null || card == null) {
            return null;
        }

        if (!isEnabled(sa) || !isSupportedMDFC(card)) {
            return sa.canTarget(card) ? CardStateName.Original : null;
        }

        final EnumSet<CardStateName> legalStates = getLegalTargetStates(sa, card);

        final boolean frontOk = legalStates.contains(CardStateName.Original);
        final boolean backOk = legalStates.contains(CardStateName.Backside);

        if (frontOk && !backOk) {
            return CardStateName.Original;
        }
        if (backOk && !frontOk) {
            return CardStateName.Backside;
        }

        return null;
    }

    public static Card createStateCheckCard(final Card original, final CardStateName state) {
        if (original == null || state == null) {
            return null;
        }

        final Card check = new CardCopyService(original).copyCard(false);
        if (check == null || !check.hasState(state)) {
            return null;
        }

        check.setBackSide(state == CardStateName.Backside);
        if (!check.setState(state, false, true)) {
            return null;
        }

        return check;
    }

    public static int getCMCForState(final Card card, final CardStateName state) {
        if (card == null) {
            return 0;
        }

        final CardState cardState = getStateOrCurrent(card, state);
        if (cardState != null && cardState.getManaCost() != null) {
            return cardState.getManaCost().getCMC();
        }

        return card.getCMC();
    }

    public static String getNameForState(final Card card, final CardStateName state) {
        if (card == null) {
            return "";
        }

        final CardState cardState = getStateOrCurrent(card, state);
        return cardState == null ? card.getName() : card.getName(cardState);
    }

    public static CardState getStateOrCurrent(final Card card, final CardStateName state) {
        if (card == null) {
            return null;
        }

        if (state != null && card.hasState(state)) {
            return card.getState(state);
        }

        return card.getCurrentState();
    }

    public static StateSnapshot captureState(final Card card) {
        return new StateSnapshot(card);
    }

    public static final class StateSnapshot {
        private final Card card;
        private CardStateName oldStateName;
        private boolean oldBackSide;
        private boolean applied;
        private boolean committed;

        private StateSnapshot(final Card card0) {
            card = card0;
        }

        public boolean apply(final CardStateName state) {
            if (card == null || state == null || !isSupportedMDFC(card)) {
                return true;
            }

            oldStateName = card.getCurrentStateName();
            oldBackSide = card.isBackSide();

            if (!card.hasState(state)) {
                return false;
            }

            card.setBackSide(state == CardStateName.Backside);
            if (!card.setState(state, true, true)) {
                restore();
                return false;
            }

            applied = true;
            return true;
        }

        public void commit() {
            committed = true;
        }

        public void restore() {
            if (!applied || committed || card == null) {
                return;
            }

            card.setBackSide(oldBackSide);
            card.setState(oldStateName, true, true);
            applied = false;
        }
    }
}