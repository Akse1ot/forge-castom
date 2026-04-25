package forge.game.ability.effects;

import java.util.List;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

public class TaxEffect extends SpellAbilityEffect {

    @Override
    public void resolve(SpellAbility sa) {
        List<Card> list = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
        for (Card c : list) {
            if (c != null && c.getCastSA() != null) {
                c.getCastSA().setTax(true);
            }
        }
    }
}