package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardZoneTable;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Lang;

import java.util.List;

public final class SacrificeInAPNAPEffect extends SpellAbilityEffect {
    public static PlayerCollection players(final SpellAbility sa) {
        return AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParamOrDefault("Defined", "Player"), sa);
    }

    public static CardCollectionView candidates(final SpellAbility sa, final Player player) {
        return CardLists.filter(
                AbilityUtils.filterListByType(player.getCardsIn(ZoneType.Battlefield), sa.getParamOrDefault("SacValid", "Permanent"), sa),
                CardPredicates.canBeSacrificedBy(sa, true));
    }

    public static int amount(final SpellAbility sa) {
        return AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("Amount", "1"), sa);
    }

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return Lang.joinHomogenous(players(sa)) + " sacrifice "
                + Lang.nounWithNumeralExceptOne(amount(sa), description(sa)) + ".";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Game game = sa.getHostCard().getGame();
        final PlayerCollection affected = players(sa);
        final int amount = amount(sa);
        final CardCollection selected = new CardCollection();
        final CardZoneTable table = new CardZoneTable(game.copyLastStateBattlefield(), game.copyLastStateGraveyard());
        for (Player player : game.getPlayersInTurnOrder(game.getPhaseHandler().getPlayerTurn())) {
            if (!affected.contains(player)) {
                continue;
            }
            final CardCollectionView legal = candidates(sa, player);
            final int count = Math.min(amount, legal.size());
            if (count <= 0) {
                continue;
            }
            final String message = selected.isEmpty() ? description(sa)
                    : description(sa) + " (previous choices: " + Lang.joinHomogenous(selected) + ")";
            selected.addAll(player.getController().choosePermanentsToSacrifice(sa, count, count, legal, message));
        }

        final CardCollection sacrificed = game.getAction().sacrificeBatch(selected, sa, table);
        if (sa.hasParam("RememberSacrificed")) {
            sa.getHostCard().clearRemembered();
            sa.getHostCard().addRemembered(sacrificed);
        }
        table.triggerChangesZoneAll(game, sa);
    }

    private static String description(final SpellAbility sa) {
        return sa.getParamOrDefault("SacMessage", Lang.getInstance().buildValidDesc(
                List.of(sa.getParamOrDefault("SacValid", "Permanent").split(",")), false));
    }
}
