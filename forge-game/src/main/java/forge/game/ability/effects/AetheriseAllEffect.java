package forge.game.ability.effects;

import forge.game.spellability.SpellAbility;

public class AetheriseAllEffect extends ChangeZoneAllEffect {
    private static final String DESTINATION = "Destination";
    private static final String LIBRARY_POSITION = "LibraryPosition";

    @Override
    public void buildSpellAbility(final SpellAbility sa) {
        sa.getMapParams().put(DESTINATION, "Library");
        sa.getMapParams().put(LIBRARY_POSITION, "0");
        super.buildSpellAbility(sa);
    }
}