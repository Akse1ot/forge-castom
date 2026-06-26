/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.spellability;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ForwardingList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import forge.card.CardStateName;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.util.IterableUtil;
import forge.util.collect.FCollection;

/**
 * <p>
 * Target_Choices class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class TargetChoices extends ForwardingList<GameObject> implements Cloneable {

    private final FCollection<GameObject> targets = new FCollection<>();

    private final Map<Card, Player> cardControllers = Maps.newHashMap();

    private final Map<GameObject, Integer> dividedMap = Maps.newHashMap();

    private final Map<Card, CardStateName> targetedCardStates = Maps.newHashMap();

    private Card findTargetedCardStateKey(final Card c) {
        if (c == null) {
            return null;
        }

        if (targetedCardStates.containsKey(c)) {
            return c;
        }

        for (final Card key : targetedCardStates.keySet()) {
            if (key != null && key.equalsWithGameTimestamp(c)) {
                return key;
            }
        }

        return null;
    }

    public final void setTargetedCardState(final Card c, final CardStateName state) {
        if (c == null) {
            return;
        }

        final Card key = findTargetedCardStateKey(c);
        if (state == null) {
            if (key != null) {
                targetedCardStates.remove(key);
            }
            return;
        }

        targetedCardStates.put(key == null ? c : key, state);
    }

    public final boolean hasTargetedCardState(final Card c) {
        return findTargetedCardStateKey(c) != null;
    }

    public final CardStateName getTargetedCardState(final Card c) {
        final Card key = findTargetedCardStateKey(c);
        return key == null ? null : targetedCardStates.get(key);
    }

    public final void clearTargetedCardState(final Card c) {
        final Card key = findTargetedCardStateKey(c);
        if (key != null) {
            targetedCardStates.remove(key);
        }
    }

    public final int getTargetedCMC(final Card c) {
        return TargetEitherFaceUtil.getCMCForState(c, getTargetedCardState(c));
    }

    public final int getTotalTargetedCMC() {
        int totalCMC = 0;
        for (Card c : IterableUtil.filter(targets, Card.class)) {
            totalCMC += getTargetedCMC(c);
        }
        return totalCMC;
    }

    public final int getTotalTargetedPower() {
        int totalPower = 0;
        for (Card c : IterableUtil.filter(targets, Card.class)) {
            totalPower += c.getNetPower();
        }
        return totalPower;
    }

    public final boolean forEachControllerChanged(Card c) {
        return !c.getController().equals(cardControllers.get(c));
    }

    public final boolean add(final GameObject o) {
        if (o instanceof Player || o instanceof Card || o instanceof SpellAbility) {
            if (o instanceof Card c) {
                cardControllers.put(c, c.getController());
            }
            return super.add(o);
        }
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        boolean result = super.removeAll(collection);
        for (Object e : collection) {
            this.dividedMap.remove(e);
        }
        for (Object e : collection) {
            this.cardControllers.remove(e);
        }
        for (Object e : collection) {
            if (e instanceof Card c) {
                clearTargetedCardState(c);
            } else {
                this.targetedCardStates.remove(e);
            }
        }
        return result;
    }

    @Override
    public boolean remove(Object object) {
        boolean result = super.remove(object);
        dividedMap.remove(object);
        cardControllers.remove(object);
        if (object instanceof Card c) {
            clearTargetedCardState(c);
        } else {
            targetedCardStates.remove(object);
        }
        return result;
    }

    public final CardCollectionView getTargetCards() {
        return new CardCollection(IterableUtil.filter(targets, Card.class));
    }

    public final Iterable<Player> getTargetPlayers() {
        return IterableUtil.filter(targets, Player.class);
    }

    public final Iterable<SpellAbility> getTargetSpells() {
        return IterableUtil.filter(targets, SpellAbility.class);
    }

    public final Iterable<GameEntity> getTargetEntities() {
        return IterableUtil.filter(targets, GameEntity.class);
    }

    public final boolean isTargetingAnyCard() {
        return targets.stream().anyMatch(Card.class::isInstance);
    }

    public final boolean isTargetingAnyPlayer() {
        return targets.stream().anyMatch(Player.class::isInstance);
    }

    public final boolean isTargetingAnySpell() {
        return targets.stream().anyMatch(SpellAbility.class::isInstance);
    }

    public final Card getFirstTargetedCard() {
        return Iterables.getFirst(getTargetCards(), null);
    }

    public final Player getFirstTargetedPlayer() {
        return Iterables.getFirst(getTargetPlayers(), null);
    }

    public final SpellAbility getFirstTargetedSpell() {
        return Iterables.getFirst(getTargetSpells(), null);
    }

    public final void replaceTargetCard(final Card old, final CardCollectionView replace) {
        final boolean hadOldState = hasTargetedCardState(old);
        final CardStateName oldState = getTargetedCardState(old);
        clearTargetedCardState(old);

        targets.remove(old);
        targets.addAll(replace);

        for (Card c : replace) {
            cardControllers.put(c, c.getController());
        }

        if (hadOldState && replace.size() == 1) {
            setTargetedCardState(Iterables.getOnlyElement(replace), oldState);
        }
    }

    @Override
    public TargetChoices clone() {
        TargetChoices tc = new TargetChoices();
        tc.targets.addAll(targets);
        tc.dividedMap.putAll(dividedMap);
        tc.cardControllers.putAll(cardControllers);
        tc.targetedCardStates.putAll(targetedCardStates);
        return tc;
    }
    @Override
    protected List<GameObject> delegate() {
        return targets;
    }

    @Override
    public boolean contains(Object o) {
        if (o instanceof Card) {
            return IterableUtil.any(IterableUtil.filter(targets, Card.class), c -> c.equalsWithGameTimestamp((Card) o));
        }
        return super.contains(o);
    }

    public final void addDividedAllocation(final GameObject tgt, final Integer portionAllocated) {
        this.dividedMap.put(tgt, portionAllocated);
    }
    public Integer getDividedValue(GameObject c) {
        return dividedMap.get(c);
    }

    public Collection<Integer> getDividedValues() {
        return dividedMap.values();
    }

    public int getTotalDividedValue() {
        int result = 0;
        for (Integer i : getDividedValues()) {
            if (i != null)
                result += i;
        }
        return result;
    }
}
