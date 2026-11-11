package forge.game;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Tracks the two most recent turn windows for each controller of a Tesla law. */
public final class TeslaJustice {
    private final Map<Integer, TurnHistory> history = new HashMap<>();
    private final Set<Integer> copiedCasters = new HashSet<>();

    private static final class TurnHistory {
        private Set<Integer> previous;
        private Set<Integer> current = new HashSet<>();
    }

    private void startTurn(Player player) {
        TurnHistory turns = history.get(player.getId());
        if (turns == null) {
            history.put(player.getId(), new TurnHistory());
            return;
        }
        turns.previous = turns.current;
        turns.current = new HashSet<>();
    }

    public void startTurns(Iterable<Player> players) {
        players.forEach(this::startTurn);
    }

    public void finishTurns(Game game, Iterable<Player> activePlayers) {
        visitLaws(game, law -> {
            if (!"NoSpells".equals(law.getParam("Law"))) {
                return;
            }
            for (Player active : activePlayers) {
                if (!copiedCasters.contains(active.getId()) && active.getSpellsCastThisTurn() == 0) {
                    record(law.getHostCard().getController(), active);
                }
            }
        });
        copiedCasters.clear();
    }

    public void observeCombat(Game game, TriggerType event, Map<AbilityKey, Object> params) {
        if (event == TriggerType.Blocks && params.get(AbilityKey.Blocker) instanceof Card blocker) {
            visitLaws(game, law -> {
                if ("EnchantedCombat".equals(law.getParam("Law"))
                        && blocker.equals(law.getHostCard().getEnchantingCard())) {
                    record(law.getHostCard().getController(), blocker.getController());
                }
            });
        } else if (event == TriggerType.AttackersDeclaredOneTarget) {
            recordAttack(game, (List<?>) params.get(AbilityKey.AttackedTarget),
                    (Iterable<?>) params.get(AbilityKey.Attackers));
        }
    }

    private void recordAttack(Game game, List<?> attacked, Iterable<?> attackers) {
        visitLaws(game, law -> {
            Player controller = law.getHostCard().getController();
            boolean attacksController = "AttackYou".equals(law.getParam("Law")) && attacked.contains(controller);
            Card enchanted = "EnchantedCombat".equals(law.getParam("Law"))
                    ? law.getHostCard().getEnchantingCard() : null;
            if (!attacksController && enchanted == null) {
                return;
            }
            for (Object value : attackers) {
                if (value instanceof Card attacker && (attacksController || attacker.equals(enchanted))) {
                    record(controller, attacker.getController());
                }
            }
        });
    }

    void checkState(Game game) {
        visitLaws(game, law -> {
            Player controller = law.getHostCard().getController();
            switch (law.getParamOrDefault("Law", "")) {
                case "ThreeCreatures":
                    for (Player opponent : controller.getOpponents()) {
                        if (opponent.getCreaturesInPlay().size() >= 3) {
                            record(controller, opponent);
                        }
                    }
                    break;
                case "MoreLife":
                    for (Player opponent : controller.getOpponents()) {
                        if (opponent.getLife() > controller.getLife()) {
                            record(controller, opponent);
                        }
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void visitLaws(Game game, Consumer<StaticAbility> action) {
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            for (StaticAbility law : card.getStaticAbilities()) {
                if (law.checkConditions(StaticAbilityMode.TeslaLaw)) {
                    action.accept(law);
                }
            }
        }
    }

    private void record(Player controller, Player offender) {
        if (!offender.isOpponentOf(controller)) {
            return;
        }
        TurnHistory turns = history.get(controller.getId());
        if (turns == null && controller.getTurn() > 0) {
            // A game can acquire its first law after the controller's turn began.
            turns = new TurnHistory();
            history.put(controller.getId(), turns);
        }
        if (turns != null) {
            turns.current.add(offender.getId());
        }
    }

    public PlayerCollection getOffenders(Player controller) {
        PlayerCollection result = new PlayerCollection();
        TurnHistory turns = history.get(controller.getId());
        if (turns != null && turns.previous != null) {
            for (Player opponent : controller.getOpponents()) {
                if (turns.previous.contains(opponent.getId()) || turns.current.contains(opponent.getId())) {
                    result.add(opponent);
                }
            }
        }
        return result;
    }

    TeslaJustice copy(Game source) {
        TeslaJustice copy = new TeslaJustice();
        history.forEach((player, turns) -> {
            TurnHistory copied = new TurnHistory();
            if (turns.previous != null) {
                copied.previous = new HashSet<>(turns.previous);
            }
            copied.current.addAll(turns.current);
            copy.history.put(player, copied);
        });
        // Both game copiers preserve player IDs, but do not rebuild all cast history.
        copy.copiedCasters.addAll(copiedCasters);
        for (Player player : source.getPlayers()) {
            if (player.getSpellsCastThisTurn() > 0) {
                copy.copiedCasters.add(player.getId());
            }
        }
        return copy;
    }
}
