package forge.game.ability.effects;

import forge.game.ability.AbilityKey;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

public class TriggerSagaChaptersEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return "Trigger each non-final chapter ability of another Saga you control.";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Player player = sa.getActivatingPlayer();
        final Object source = sa.getRootAbility().getTriggeringObject(AbilityKey.Source);
        final CardCollection choices = new CardCollection();
        for (final Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isSaga() && !card.isPhasedOut()
                    && (!(source instanceof Card) || !card.equalsWithGameTimestamp((Card) source))) {
                choices.add(card);
            }
        }
        if (choices.isEmpty()) {
            return;
        }
        final Card chosen = player.getController().chooseSingleEntityForEffect(
                choices, sa, "Choose another Saga you control", false, null);
        if (chosen == null) {
            return;
        }
        // CardFactoryUtil creates a separate trigger for each chapter number, including I, II.
        for (final Trigger chapter : chosen.getTriggers()) {
            if (chapter.isChapter() && !chapter.isLastChapter()) {
                player.getGame().getTriggerHandler().triggerChapter(chapter);
            }
        }
    }
}
