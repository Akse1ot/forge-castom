package forge.ai;

import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.keyword.Cursed;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;

public final class ComputerUtilCursed {

    private ComputerUtilCursed() {
    }

    public static int getCursedAmount(final Card card) {
        int amount = 0;
        for (final KeywordInterface inst : card.getKeywords(Keyword.CURSED)) {
            if (inst instanceof Cursed cursed) {
                amount += cursed.hasX()
                        ? AbilityUtils.calculateAmount(card, cursed.getTokenAmount(), null)
                        : cursed.getAmount();
            }
        }
        return amount;
    }

    public static int getCursedAmount(final Iterable<Card> cards) {
        int amount = 0;
        for (final Card card : cards) {
            amount += getCursedAmount(card);
        }
        return amount;
    }
}
