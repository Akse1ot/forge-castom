package forge.game;

import forge.deck.Deck;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Optional local observation points for actions without a corresponding precise game event.
 * Install on a single game before it starts. Observers must only read state.
 */
public interface GameObservation {
    void deckPrepared(Player player, Deck deck);
    void openingHand(Player player, boolean kept);
    void cardDrawn(Player player, Card card);
    void manaProduced(Card source, SpellAbility ability, Player recipient, String produced);
}
