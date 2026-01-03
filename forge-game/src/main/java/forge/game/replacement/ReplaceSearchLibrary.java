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
        // игрок, который ищет
        Object player = runParams.get(AbilityKey.Player);
        if (!(player instanceof Player)) {
            return false;
        }

        // ValidPlayer$ check (как у ReplaceScry)
        if (!matchesValidParam("ValidPlayer", player)) {
            return false;
        }

        return true;
    }

    @Override
    public void setReplacingObjects(final Map<AbilityKey, Object> runParams, final SpellAbility sa) {
        // передаём игрока в саб-абилку
        sa.setReplacingObject(AbilityKey.Player, runParams.get(AbilityKey.Player));
    }
}
