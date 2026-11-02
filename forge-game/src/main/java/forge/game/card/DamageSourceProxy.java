package forge.game.card;

import forge.game.CardTraitBase;
import forge.game.player.Player;

/** An opted-in command Effect can stand in for a non-card damage source. */
public final class DamageSourceProxy {
    public static final String MARKER = "DamageSourceProxy";

    private DamageSourceProxy() { }

    public static boolean isProxy(Card card) {
        return card.isImmutable() && "True".equals(card.getSVar(MARKER));
    }

    /** Only damage-source consumers may use this instead of ordinary card validity. */
    public static boolean isValid(Card card, String[] restrictions, Player controller,
                                  Card context, CardTraitBase trait) {
        if (!isProxy(card)) {
            return card.isValid(restrictions, controller, context, trait);
        }
        return card.isValid(sourceRestrictions(restrictions), controller, context, trait);
    }

    public static String[] sourceRestrictions(String[] restrictions) {
        String[] result = restrictions.clone();
        for (int i = 0; i < result.length; i++) {
            String r = result[i];
            String prefix = r.startsWith("!") ? "!" : "";
            String body = r.substring(prefix.length());
            int dot = body.indexOf('.');
            String type = dot < 0 ? body : body.substring(0, dot);
            if (type.equals("Card") || type.equals("card")) {
                result[i] = prefix + "Effect" + (dot < 0 ? "" : body.substring(dot));
            }
        }
        return result;
    }
}
