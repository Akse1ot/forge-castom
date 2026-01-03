package forge.game.spellability;

import forge.game.player.Player;
import java.util.List;

public final class ModalChoiceContext {
    public final SpellAbility sa;
    public final Player chooser;

    public List<AbilitySub> possible;
    public int min;
    public int max;
    public boolean allowRepeat;

    public ModalChoiceContext(
            SpellAbility sa,
            Player chooser,
            List<AbilitySub> possible,
            int min,
            int max,
            boolean allowRepeat
    ) {
        this.sa = sa;
        this.chooser = chooser;
        this.possible = possible;
        this.min = min;
        this.max = max;
        this.allowRepeat = allowRepeat;
    }
}
