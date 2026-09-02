package forge.game.trigger;

import forge.game.CustomReplayEtbTriggerSupport;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class CustomReplayEtbWrappedAbility extends WrappedAbility {
    public CustomReplayEtbWrappedAbility(final Trigger trigger,
                                         final SpellAbility sa, final Player decider) {
        super(trigger, sa, decider);
    }

    @Override
    protected boolean checkTriggerRequirements(final Game game,
                                               final Trigger trigger) {
        return CustomReplayEtbTriggerSupport.meetsReplayRequirements(game, trigger);
    }
}