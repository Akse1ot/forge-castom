package forge.game.replacement;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class ReplaceSearchLibrary extends ReplacementEffect {

    public ReplaceSearchLibrary(final Map<String, String> mapParams, final Card host, final boolean intrinsic) {
        super(mapParams, host, intrinsic);
    }

    @Override
    public boolean canReplace(final Map<AbilityKey, Object> runParams) {
        final Object affected = runParams.get(AbilityKey.Affected);
        if (!(affected instanceof Player)) {
            return false;
        }

        return matchesValidParam("ValidPlayer", affected);
    }

    @Override
    public void setReplacingObjects(final Map<AbilityKey, Object> runParams, final SpellAbility sa) {
        final Object affected = runParams.get(AbilityKey.Affected);

        sa.setReplacingObject(AbilityKey.Player, affected);
        sa.setReplacingObject(AbilityKey.Affected, affected);
    }
}