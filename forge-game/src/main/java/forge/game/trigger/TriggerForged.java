package forge.game.trigger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.spellability.SpellAbility;
import forge.util.Localizer;

import java.util.Map;

public class TriggerForged extends Trigger {

    public TriggerForged(final Map<String, String> params,
                         final Card host,
                         final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public boolean performTest(final Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam(
                "ValidPlayer",
                runParams.get(AbilityKey.Player))) {
            return false;
        }

        if (!matchesValidParam(
                "ValidCause",
                runParams.get(AbilityKey.Cause))) {
            return false;
        }

        return matchesValidParam(
                "ValidCard",
                runParams.get(AbilityKey.Cards)
        );
    }

    @Override
    public void setTriggeringObjects(final SpellAbility sa,
                                     final Map<AbilityKey, Object> runParams) {
        @SuppressWarnings("unchecked")
        final Iterable<Card> created =
                (Iterable<Card>) runParams.get(AbilityKey.Cards);

        CardCollection cards = new CardCollection(created);

        if (hasParam("ValidCard")) {
            cards = CardLists.getValidCards(
                    cards,
                    getParam("ValidCard"),
                    getHostCard().getController(),
                    getHostCard(),
                    this
            );
        }

        sa.setTriggeringObject(AbilityKey.Cards, cards);
        sa.setTriggeringObject(AbilityKey.Amount, cards.size());

        sa.setTriggeringObjectsFrom(
                runParams,
                AbilityKey.Player,
                AbilityKey.Cause
        );
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return Localizer.getInstance().getMessage("lblPlayer")
                + ": "
                + sa.getTriggeringObject(AbilityKey.Player)
                + ", "
                + Localizer.getInstance().getMessage("lblAmount")
                + ": "
                + sa.getTriggeringObject(AbilityKey.Amount);
    }
}