package forge.game.card;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;

/** A bounded, opt-in reveal group owned by a resolving spell, not general card knowledge. */
public final class LibraryRevealHelper {
    public static final String STATE = "TrackedLibraryReveal";
    private static final String PREFIX = "LibraryReveal$";

    private LibraryRevealHelper() {
    }

    private record Key(int id, long timestamp) {
        static Key of(Card card) {
            return new Key(card.getId(), card.getGameTimestamp());
        }
    }

    private record Group(int playerId, Set<Key> keys) {
    }

    public static void clear(final Card source) {
        source.setSVar(STATE, "Number$0");
    }

    public static void track(final Card source, final Player player, final Iterable<Card> cards) {
        if (!source.isInZone(ZoneType.Stack)) {
            throw new IllegalArgumentException("TrackLibraryReveal requires a spell on the stack");
        }
        Set<Key> keys = new LinkedHashSet<>();
        for (Card card : cards) {
            keys.add(Key.of(card));
        }
        store(source, new Group(player.getId(), keys));
    }

    public static CardCollection getTracked(final Card source) {
        CardCollection result = new CardCollection();
        Group group = read(source);
        if (group == null) {
            return result;
        }
        Player player = source.getGame().getPlayer(group.playerId());
        if (player != null) {
            for (Card card : player.getCardsIn(ZoneType.Library)) {
                if (group.keys().contains(Key.of(card))) {
                    result.add(card);
                }
            }
        }
        return result;
    }

    /** affected identifies participants in the operation, not cards whose indexes merely shifted. */
    public static void reordered(final Game game, final Player player, final Iterable<Card> affected) {
        if (player == null) {
            return;
        }
        Set<Key> removed = null;
        for (SpellAbilityStackInstance entry : game.getStack()) {
            Card source = entry.getSourceCard();
            Group group = read(source);
            if (group == null || group.playerId() != player.getId()) {
                continue;
            }
            if (removed == null) {
                removed = new HashSet<>();
                for (Card card : affected) {
                    removed.add(Key.of(card));
                }
            }
            if (group.keys().removeAll(removed)) {
                store(source, group);
            }
        }
    }

    /** Copy only opt-in values and the live library identities they describe. */
    public static void copyForSimulation(final Game from, final Game to) {
        for (SpellAbilityStackInstance entry : from.getStack()) {
            Card source = entry.getSourceCard();
            if (!source.hasSVar(STATE) && !entry.getSpellAbility().hasParam("TrackLibraryReveal")) {
                continue;
            }
            Card copied = to.findById(source.getId());
            if (copied == null) {
                continue;
            }
            Group group = read(source);
            if (group == null) {
                clear(copied);
                continue;
            }
            to.dangerouslySetTimestamp(Math.max(to.getTimestamp(), from.getTimestamp()));
            Set<Key> surviving = new LinkedHashSet<>();
            for (Card card : getTracked(source)) {
                Card target = to.findById(card.getId());
                if (target != null && target.isInZone(ZoneType.Library)
                        && target.getOwner().getId() == card.getOwner().getId()) {
                    target.setGameTimestamp(card.getGameTimestamp());
                    surviving.add(Key.of(card));
                }
            }
            // Do not resurrect a stale zero-timestamp key when a library card is rebuilt.
            store(copied, new Group(group.playerId(), surviving));
        }
    }

    private static Group read(final Card source) {
        if (!source.hasSVar(STATE)) {
            return null;
        }
        String value = source.getSVar(STATE);
        if (!value.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = value.substring(PREFIX.length()).split("\\|", -1);
        Set<Key> keys = new LinkedHashSet<>();
        if (!parts[1].isEmpty()) {
            for (String encoded : parts[1].split(",")) {
                String[] key = encoded.split(":");
                keys.add(new Key(Integer.parseInt(key[0]), Long.parseLong(key[1])));
            }
        }
        return new Group(Integer.parseInt(parts[0]), keys);
    }

    private static void store(final Card source, final Group group) {
        if (group.keys().isEmpty()) {
            clear(source);
            return;
        }
        StringJoiner keys = new StringJoiner(",");
        for (Key key : group.keys()) {
            keys.add(key.id() + ":" + key.timestamp());
        }
        source.setSVar(STATE, PREFIX + group.playerId() + "|" + keys);
    }
}
