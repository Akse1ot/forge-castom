package forge.game.keyword;

import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.spellability.SpellAbility;

public class Cursed extends KeywordWithAmount {
    private static final String X_SVAR = "CursedXValue";

    public boolean hasX() {
        return withX;
    }

    public String getTokenAmount() {
        return withX ? X_SVAR : getAmountString();
    }

    public void initializeX(final CardState cardState) {
        cardState.setSVar(X_SVAR, "Number$0");
    }

    public static void storeEnteringX(final Card entering, final SpellAbility cause) {
        boolean hasCursedX = false;
        for (final KeywordInterface inst : entering.getKeywords(Keyword.CURSED)) {
            if (inst instanceof Cursed cursed && cursed.hasX()) {
                hasCursedX = true;
                break;
            }
        }

        if (!hasCursedX) {
            return;
        }

        int x = 0;
        if (cause != null
                && cause.isSpell()
                && entering.equals(cause.getHostCard())
                && cause.getXManaCostPaid() != null) {
            x = cause.getXManaCostPaid();
        }

        entering.setSVar(X_SVAR, "Number$" + x);
    }
}