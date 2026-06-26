package forge.game.ability.effects;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

public class AetheriseEffect extends ChangeZoneEffect {
    private static final String DESTINATION = "Destination";
    private static final String LIBRARY_POSITION = "LibraryPosition";

    @Override
    public void buildSpellAbility(final SpellAbility sa) {
        sa.getMapParams().put(DESTINATION, "Library");
        sa.getMapParams().put(LIBRARY_POSITION, "0");
        super.buildSpellAbility(sa);
    }

    @Override
    protected void onCardMoved(final SpellAbility sa, final Card movedCard,
                               final Zone originZone, final ZoneType destination) {
        AetheriseHelper.fireAetherisedTrigger(sa, movedCard, originZone, destination);
    }
}