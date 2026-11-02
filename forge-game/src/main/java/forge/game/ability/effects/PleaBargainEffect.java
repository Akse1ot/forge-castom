package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The unrevealed card exists only in this resolution's private local state. */
public final class PleaBargainEffect extends SpellAbilityEffect {
    public static final String REVEALED_CHOICES = "RevealedChoices";

    @Override
    public void resolve(SpellAbility sa) {
        Game game = sa.getHostCard().getGame();
        Player chooser = sa.getActivatingPlayer();
        for (Player opponent : getTargetPlayers(sa)) {
            CardCollection hand = new CardCollection(opponent.getCardsIn(ZoneType.Hand));
            if (hand.isEmpty()) {
                continue;
            }
            Map<Card, Long> originalObjects = new IdentityHashMap<>();
            for (Card c : hand) {
                originalObjects.put(c, c.getGameTimestamp());
            }
            Card hidden = opponent.getController().chooseSingleEntityForEffect(hand, sa,
                    "Choose one card to keep unrevealed", Collections.emptyMap());
            if (!sameHandObject(hidden, opponent, originalObjects)) {
                continue;
            }
            CardCollection revealed = new CardCollection(opponent.getCardsIn(ZoneType.Hand));
            revealed.remove(hidden);
            Map<Card, Long> eligibleObjects = new IdentityHashMap<>();
            eligibleObjects.put(hidden, hidden.getGameTimestamp());
            for (Card c : revealed) {
                eligibleObjects.put(c, c.getGameTimestamp());
            }
            game.getAction().reveal(revealed, opponent);

            // Every option is hosted on the public spell, never on the unrevealed card.
            List<SpellAbility> options = new ArrayList<>();
            SpellAbility blind = option(sa, "Discard the unrevealed card");
            options.add(blind);
            Map<SpellAbility, Card> publicChoices = new IdentityHashMap<>();
            for (Card c : revealed) {
                if (!c.isLand()) {
                    SpellAbility option = option(sa, "Discard " + c.getName());
                    options.add(option);
                    publicChoices.put(option, c);
                }
            }
            SpellAbility selected = chooser.getController().chooseSingleSpellForEffect(options, sa,
                    "Choose a revealed nonland card or the unrevealed card to discard",
                    Map.of(REVEALED_CHOICES, Collections.unmodifiableMap(publicChoices)));
            if (!options.contains(selected)) {
                continue;
            }
            Card discarded = selected == blind ? hidden : publicChoices.get(selected);
            if (sameHandObject(discarded, opponent, eligibleObjects)) {
                var params = AbilityKey.newMap();
                var table = AbilityKey.addCardZoneTableParams(params, sa);
                opponent.discard(discarded, sa, true, params);
                table.triggerChangesZoneAll(game, sa);
            }
        }
    }

    private static boolean sameHandObject(Card card, Player owner, Map<Card, Long> originals) {
        Long timestamp = originals.get(card);
        if (timestamp == null || timestamp != card.getGameTimestamp()) {
            return false;
        }
        for (Card current : owner.getCardsIn(ZoneType.Hand)) {
            if (current == card) {
                return true;
            }
        }
        return false;
    }

    private static SpellAbility option(SpellAbility cause, String label) {
        SpellAbility option = AbilityFactory.getAbility("DB$ Cleanup", cause.getHostCard());
        option.setActivatingPlayer(cause.getActivatingPlayer());
        option.setDescription(label);
        return option;
    }
}
