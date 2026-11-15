package forge.game.card;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.perpetual.PerpetualAbilities;
import forge.game.card.perpetual.PerpetualInterface;
import forge.game.card.perpetual.PerpetualKeywords;
import forge.game.card.perpetual.PerpetualTypes;
import forge.game.combat.AttackingBand;
import forge.game.combat.CombatLki;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableProperty;
import forge.util.collect.FCollection;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static forge.game.card.CardFactory.getCard;

public class CardCopyService {
    // A service to copy all sorts of things from a card to a new card.
    // This pulled in functions from CardFactory and CardUtil

    private final Card copyFrom;
    private final Game toGame;
    public CardCopyService(Card copyFrom) {
        this(copyFrom, copyFrom == null ? null : copyFrom.getGame());
    }

    public CardCopyService(Card copyFrom, Game toGame) {
        this.copyFrom = copyFrom;
        this.toGame = toGame;
    }

    public final Card copyCard(boolean assignNewId) {
        return copyCard(assignNewId, copyFrom.getOwner());
    }

    // Straight copying for things like moving a card to a different zone or using GameCopier
    public final Card copyCard(boolean assignNewId, Player owner) {
        Card out;
        if (copyFrom.isRealToken() || copyFrom.getCopiedPermanent() != null || copyFrom.getPaperCard() == null) {
            out = copyStats(copyFrom, owner, assignNewId);
            out.setEffectSource(copyFrom.getEffectSource());
            out.setBoon(copyFrom.isBoon());
            out.dangerouslySetGame(toGame);

            // need to copy this values for the tokens
            out.setTokenSpawningAbility(copyFrom.getTokenSpawningAbility());
            out.setCopiedPermanent(copyFrom.getCopiedPermanent());
        } else {
            out = assignNewId ? getCard(copyFrom.getPaperCard(), owner, toGame)
                    : getCard(copyFrom.getPaperCard(), owner, copyFrom.getId(), toGame);
        }

        out.setZone(copyFrom.getZone());
        out.setState(copyFrom.getFaceupCardStateName(), true);
        out.setBackSide(copyFrom.isBackSide());
        out.setGamePieceType(copyFrom.getGamePieceType());
        out.setTokenCard(copyFrom.isTokenCard());

        if (toGame != copyFrom.getGame()) {
            out.setCraftedWithColors(copyFrom.getCraftedWithColors());
        }

        if (toGame == copyFrom.getGame()) {
            // Only copy these things if we're not copying them into a new game

            out.setCollectible(copyFrom.isCollectible());

            // this's necessary for forge.game.GameAction.unattachCardLeavingBattlefield(Card)
            if (copyFrom.hasCardAttachments()) {
                out.setAttachedCards(copyFrom.getAttachedCards());
            }
            if (copyFrom.isAttachedToEntity()) {
                out.setEntityAttachedTo(copyFrom.getEntityAttachedTo());
            }
            if (copyFrom.hasMergedCard()) {
                out.setMergedCards(copyFrom.getMergedCards());
            }

            out.setLeavesPlayCommands(copyFrom.getLeavesPlayCommands());
            out.setLeavesZoneCommands(copyFrom.getLeavesZoneCommands());

            out.setSpecialized(copyFrom.isSpecialized());
            out.addRemembered(copyFrom.getRemembered());
            out.addImprintedCards(copyFrom.getImprintedCards());
            out.setCommander(copyFrom.isRealCommander());
            //out.setFaceDown(copyFrom.isFaceDown());
        }

        int foil = copyFrom.getCurrentState().getFoil();
        if (foil > 0) {
            out.setFoil(foil);
        }

        return out;
    }

    private static void copyState(final Card from, final CardStateName fromState, final Card to,
                                 final CardStateName toState) {
        copyState(from, fromState, to, toState, true);
    }

    private static void copyState(final Card from, final CardStateName fromState, final Card to,
                                 final CardStateName toState, boolean updateView) {
        // copy characteristics not associated with a state
        to.setText(from.getSpellText());

        // get CardCharacteristics for desired state
        if (!to.getStates().contains(toState)) {
            to.addAlternateState(toState, updateView);
        }
        final CardState toCharacteristics = to.getState(toState), fromCharacteristics = from.getState(fromState);
        toCharacteristics.copyFrom(fromCharacteristics, false);
    }

    public static Card copyStats(final Card in, final Player newOwner, boolean assignNewId) {
        int id = in.getId();
        if (assignNewId) {
            id = newOwner == null ? 0 : newOwner.getGame().nextCardId();
        }
        final Card c;
        if(in instanceof DetachedCardEffect)
            c = new DetachedCardEffect((DetachedCardEffect) in, assignNewId);
        else
            c = new Card(id, in.getPaperCard(), in.getGame());

        c.setOwner(newOwner);
        c.setSetCode(in.getSetCode());

        for (final CardStateName state : in.getStates()) {
            copyState(in, state, c, state, false);
        }

        c.setState(in.getCurrentStateName(), false);
        c.setBackSide(in.isBackSide());

        return c;
    }

    @Deprecated
    public void copyCopiableCharacteristics(final Card to, SpellAbility sourceSA, SpellAbility targetSA) {
        final boolean toIsFaceDown = to.isFaceDown();
        if (toIsFaceDown) {
            // If to is face down, copy to its front side
            to.setState(CardStateName.Original, false);
            copyCopiableCharacteristics(to, sourceSA, targetSA);
            to.setState(CardStateName.FaceDown, false);
            return;
        }

        final boolean fromIsFlipCard = copyFrom.isFlipCard();
        final boolean fromIsTransformedCard = copyFrom.getCurrentStateName() == CardStateName.Backside || copyFrom.getCurrentStateName() == CardStateName.Meld;

        if (fromIsFlipCard) {
            if (to.getCurrentStateName().equals(CardStateName.Flipped)) {
                copyState(copyFrom, CardStateName.Original, to, CardStateName.Original);
            } else {
                copyState(copyFrom, CardStateName.Original, to, to.getCurrentStateName());
            }
            copyState(copyFrom, CardStateName.Flipped, to, CardStateName.Flipped);
        } else if (copyFrom.isTransformable()
                && sourceSA != null && ApiType.CopySpellAbility.equals(sourceSA.getApi())
                && targetSA != null && targetSA.isSpell() && targetSA.getHostCard().isPermanent()) {
            copyState(copyFrom, CardStateName.Original, to, CardStateName.Original);
            copyState(copyFrom, CardStateName.Backside, to, CardStateName.Backside);
            // 707.10g If an effect creates a copy of a transforming permanent spell, the copy is also a transforming permanent spell that has both a front face and a back face.
            // The characteristics of its front and back face are determined by the copiable values of the same face of the spell it is a copy of, as modified by any other copy effects.
            // If the spell it is a copy of has its back face up, the copy is created with its back face up. The token that’s put onto the battlefield as that spell resolves is a transforming token.
            to.setBackSide(copyFrom.isBackSide());
        } else if (fromIsTransformedCard) {
            copyState(copyFrom, copyFrom.getCurrentStateName(), to, CardStateName.Original);
        } else {
            copyState(copyFrom, copyFrom.getCurrentStateName(), to, to.getCurrentStateName());
        }
    }

    // ========================================================
    // LKI functions

    public static List<Card> getLKICopyList(final Iterable<Card> in, Map<Integer, Card> cachedMap) {
        if (in == null) {
            return null;
        }
        List<Card> result = Lists.newArrayList();
        for (final Card c : in) {
            result.add(new CardCopyService(c).getLKICopy(cachedMap));
        }
        return result;
    }

    public static Card getLKICopy(Card c) {
        // Ideally, we'd just convert all calls to getLKICopy to use the Map version
        return new CardCopyService(c).getLKICopy();
    }

    public static Card getLKICopy(final Card c, Map<Integer, Card> cachedMap) {
        return new CardCopyService(c).getLKICopy(cachedMap);
    }

    public static GameEntity getLKICopy(final GameEntity c, Map<Integer, Card> cachedMap) {
        // Ideally, we'd just convert all calls to getLKICopy to use the Map version
        if (c instanceof Card) {
            return new CardCopyService((Card) c).getLKICopy(cachedMap);
        }
        return c;
    }

    public Card getLKICopy() {
        return getLKICopy(Maps.newHashMap());
    }

    // The copy is built with a view that does not compute ability text, since walking every card in the
    // game to produce it is the single largest cost of making one. It takes its source's text instead.
    private static void copyAbilityText(final CardView from, final CardView to) {
        to.getCurrentState().set(TrackableProperty.AbilityText, from.getCurrentState().getAbilityText());
        if (from.hasAlternateState() && to.hasAlternateState()) {
            to.getAlternateState().set(TrackableProperty.AbilityText, from.getAlternateState().getAbilityText());
        }
    }

    public Card getLKICopy(Map<Integer, Card> cachedMap) {
        return getLKICopy(cachedMap, null);
    }

    /** Copies historical LKI by reference, since a card ID can occur in multiple zone-change records. */
    public static Card copyLkiForGame(final Card card, final Game game, final Map<Card, Card> copies) {
        return new CardCopyService(card, game).getLKICopy(null, copies);
    }

    private Card copyLkiCard(final Card card, final Map<Integer, Card> cachedMap, final Map<Card, Card> history) {
        return history == null ? getLKICopy(card, cachedMap) : copyLkiForGame(card, toGame, history);
    }

    private GameEntity copyLkiEntity(final GameEntity entity, final Map<Card, Card> history) {
        if (entity instanceof Card card) {
            return copyLkiForGame(card, toGame, history);
        }
        return entity instanceof Player player ? toGame.getPlayer(player.getId()) : null;
    }

    private Zone mapZone(final Zone zone) {
        if (zone == null || zone.is(ZoneType.None)) {
            return null;
        }
        if (zone.is(ZoneType.Stack)) {
            return toGame.getStackZone();
        }
        final Player player = zone.getPlayer();
        return player == null ? new Zone(zone.getZoneType(), toGame) : toGame.getPlayer(player.getId()).getZone(zone.getZoneType());
    }

    private Card getLKICopy(final Map<Integer, Card> cachedMap, final Map<Card, Card> history) {
        if (copyFrom == null) {
            return null;
        }
        final boolean historical = history != null;
        Card cachedCard = historical ? history.get(copyFrom) : cachedMap.get(copyFrom.getId());
        if (cachedCard != null) {
            return cachedCard;
        }

        if (!historical) {
            final Breadcrumb bread = new Breadcrumb("CardUtil:getLKICopy copy object");
            bread.setData("Card", copyFrom.getName());
            bread.setData("CardState", copyFrom.getCurrentStateName().toString());
            bread.setData("Player", copyFrom.getController().getName());
            Sentry.addBreadcrumb(bread);
        }

        final Card newCopy;
        if (historical) {
            newCopy = new Card(copyFrom.getId(), copyFrom.getPaperCard(), toGame, null, true);
            history.put(copyFrom, newCopy);
            newCopy.setOwner((Player) copyLkiEntity(copyFrom.getOwner(), history));
            newCopy.setController((Player) copyLkiEntity(copyFrom.getController(), history), 0);
        } else {
            if (copyFrom instanceof DetachedCardEffect)
                newCopy = new DetachedCardEffect((DetachedCardEffect) copyFrom, false);
            else
                newCopy = new Card(copyFrom.getId(), copyFrom.getPaperCard(), copyFrom.getGame(), null, true);
            cachedMap.put(copyFrom.getId(), newCopy);
            newCopy.setOwner(copyFrom.getOwner());
            newCopy.setController(copyFrom.getController(), 0);
        }
        newCopy.setSetCode(copyFrom.getSetCode());
        newCopy.setCommander(copyFrom.isCommander());
        newCopy.setCollectible(copyFrom.isCollectible());

        // needed to ensure that the LKI object has correct CMC info no matter what state the original card was in
        // (e.g. Scrap Trawler + transformed Harvest Hand)
        newCopy.setLKICMC(copyFrom.getCMC());
        // used for the purpose of cards that care about the zone the card was known to be in last
        newCopy.setLastKnownZone(historical ? mapZone(copyFrom.getLastKnownZone()) : copyFrom.getLastKnownZone());
        // copy EffectSource for description
        newCopy.setEffectSource(copyLkiCard(copyFrom.getEffectSource(), cachedMap, history));

        if (copyFrom.isFlipCard()) {
            newCopy.getState(CardStateName.Original).copyFrom(copyFrom.getState(CardStateName.Original), true);
            newCopy.addAlternateState(CardStateName.Flipped, false);
            newCopy.getState(CardStateName.Flipped).copyFrom(copyFrom.getState(CardStateName.Flipped), true);
        } else if (copyFrom.isTransformable()) {
            newCopy.getState(CardStateName.Original).copyFrom(copyFrom.getState(CardStateName.Original), true);
            newCopy.addAlternateState(CardStateName.Backside, false);
            newCopy.getState(CardStateName.Backside).copyFrom(copyFrom.getState(CardStateName.Backside), true);
        } else if (copyFrom.hasState(CardStateName.Secondary)) {
            newCopy.getState(CardStateName.Original).copyFrom(copyFrom.getState(CardStateName.Original), true);
            newCopy.addAlternateState(CardStateName.Secondary, false);
            newCopy.getState(CardStateName.Secondary).copyFrom(copyFrom.getState(CardStateName.Secondary), true);
        } else if (copyFrom.hasState(CardStateName.PreparedSpell)) {
            newCopy.getState(CardStateName.Original).copyFrom(copyFrom.getState(CardStateName.Original), true);
            newCopy.addAlternateState(CardStateName.PreparedSpell, false);
            newCopy.getState(CardStateName.PreparedSpell).copyFrom(copyFrom.getState(CardStateName.PreparedSpell), true);
        } else if (copyFrom.isSplitCard()) {
            newCopy.getState(CardStateName.Original).copyFrom(copyFrom.getState(CardStateName.Original), true);
            newCopy.addAlternateState(CardStateName.LeftSplit, false);
            newCopy.getState(CardStateName.LeftSplit).copyFrom(copyFrom.getState(CardStateName.LeftSplit), true);
            newCopy.addAlternateState(CardStateName.RightSplit, false);
            newCopy.getState(CardStateName.RightSplit).copyFrom(copyFrom.getState(CardStateName.RightSplit), true);
        } else {
            newCopy.getCurrentState().copyFrom(copyFrom.getState(copyFrom.getFaceupCardStateName()), true);
        }
        newCopy.setFlipped(copyFrom.isFlipped());
        newCopy.setBackSide(copyFrom.isBackSide());
        if (newCopy.hasAlternateState()) {
            newCopy.setState(copyFrom.getCurrentStateName(), false, true);
        }
        if (copyFrom.isFaceDown()) {
            newCopy.turnFaceDownNoUpdate();
            newCopy.setType(new CardType(copyFrom.getFaceDownState().getType()));
        }
        // prevent StackDescription from revealing face
        newCopy.updateStateForView();
        copyAbilityText(copyFrom.getView(), newCopy.getView());

        /*
        if (in.isCloned()) {
            newCopy.addAlternateState(CardStateName.Cloner, false);
            newCopy.getState(CardStateName.Cloner).copyFrom(in.getState(CardStateName.Cloner), true);
        }
        */

        newCopy.setGamePieceType(copyFrom.getGamePieceType());
        newCopy.setTokenCard(copyFrom.isTokenCard());
        newCopy.setEmblem(copyFrom.isEmblem());

        // lock in the current P/T
        newCopy.setBasePower(copyFrom.getCurrentPower());
        newCopy.setBaseToughness(copyFrom.getCurrentToughness());

        // extra copy PT boost
        newCopy.setPTBoost(copyFrom.getPTBoostTable());

        newCopy.copyFrom(copyFrom, historical);
        if (historical) {
            for (final CardStateName state : newCopy.getStates()) {
                final CardState face = newCopy.getState(state);
                face.setType(new CardType(face.getType()));
            }
        }
        newCopy.setCounters(HashMultiset.create(copyFrom.getCounters()));

        newCopy.setColor(copyFrom.getColor());
        newCopy.setCraftedWithColors(copyFrom.getCraftedWithColors());
        newCopy.setPhasedOut(historical ? (Player) copyLkiEntity(copyFrom.getPhasedOut(), history) : copyFrom.getPhasedOut());
        newCopy.setTapped(copyFrom.isTapped());
        newCopy.setTributed(copyFrom.isTributed());
        newCopy.setUnearthed(copyFrom.isUnearthed());
        newCopy.setMonstrous(copyFrom.isMonstrous());
        newCopy.setRenowned(copyFrom.isRenowned());
        newCopy.setDevout(copyFrom.isDevout());
        newCopy.setSolved(copyFrom.isSolved());
        newCopy.setPromisedGift(copyFrom.getPromisedGift());
        newCopy.setSaddled(copyFrom.isSaddled());
        if (newCopy.isSaddled()) {
            final CardCollection saddlers = copyFrom.getSaddledByThisTurn();
            newCopy.setSaddledByThisTurn(historical && saddlers != null ? copyHistoryCards(saddlers, history) : saddlers);
        }
        if (copyFrom.isSuspected()) {
            newCopy.setSuspectedStatic(copyFrom.getSuspectedStatic().copy(newCopy, true));
        }

        if (historical) {
            newCopy.setDamageHistory(copyFrom.getDamageHistory().copyForGame(entity -> copyLkiEntity(entity, history)));
            newCopy.setDamageReceivedThisTurn(Lists.newArrayList(copyFrom.getDamageReceivedThisTurn()));
            newCopy.getBlockedThisTurn().addAll(copyHistoryCards(copyFrom.getBlockedThisTurn(), history));
            newCopy.getBlockedByThisTurn().addAll(copyHistoryCards(copyFrom.getBlockedByThisTurn(), history));
            newCopy.setAttachedCards(copyHistoryCards(copyFrom.getAttachedCards(), history));
            newCopy.setEntityAttachedTo(copyLkiEntity(copyFrom.getEntityAttachedTo(), history));
        } else {
            newCopy.setDamageHistory(copyFrom.getDamageHistory());
            newCopy.setDamageReceivedThisTurn(copyFrom.getDamageReceivedThisTurn());

            // these are LKI already
            newCopy.getBlockedThisTurn().addAll(copyFrom.getBlockedThisTurn());
            newCopy.getBlockedByThisTurn().addAll(copyFrom.getBlockedByThisTurn());
            newCopy.setAttachedCards(getLKICopyList(copyFrom.getAttachedCards(), cachedMap));
            newCopy.setEntityAttachedTo(getLKICopy(copyFrom.getEntityAttachedTo(), cachedMap));
        }
        newCopy.setExcessDamageReceivedThisTurn(copyFrom.getExcessDamageThisTurn());
        newCopy.setCopiedPermanent(historical ? copyLkiCard(copyFrom.getCopiedPermanent(), cachedMap, history) : copyFrom.getCopiedPermanent());
        newCopy.setHaunting(historical ? copyLkiCard(copyFrom.getHaunting(), cachedMap, history) : copyFrom.getHaunting());
        for (final Card haunter : copyFrom.getHauntedBy()) {
            newCopy.addHauntedBy(historical ? copyLkiCard(haunter, cachedMap, history) : haunter, false);
        }

        newCopy.setIntensity(copyFrom.getIntensity(false));
        // Don't re-apply perpetual effects - they're already copied via copyFrom().
        // Re-applying would create duplicate ReplacementEffect objects that cause
        // infinite recursion in getReplacementList for "enters tapped" effects.
        if (historical) {
            for (final PerpetualInterface perpetual : copyFrom.getPerpetual()) {
                newCopy.addPerpetual(copyHistoricalPerpetual(perpetual, newCopy));
            }
            for (final Object remembered : copyFrom.getRemembered()) {
                if (remembered instanceof GameEntity entity) {
                    newCopy.addRemembered(copyLkiEntity(entity, history));
                } else if (remembered instanceof String || remembered instanceof Number || remembered instanceof Enum<?>) {
                    newCopy.addRemembered(remembered);
                }
            }
            newCopy.addImprintedCards(copyHistoryCards(copyFrom.getImprintedCards(), history));
            newCopy.setChosenCards(copyHistoryCards(copyFrom.getChosenCards(), history));
            newCopy.setChosenPlayer((Player) copyLkiEntity(copyFrom.getChosenPlayer(), history));
        } else {
            newCopy.setPerpetual(copyFrom, false);
            newCopy.addRemembered(copyFrom.getRemembered());
            newCopy.addImprintedCards(copyFrom.getImprintedCards());
            newCopy.setChosenCards(copyFrom.getChosenCards());
        }

        newCopy.setChosenType(copyFrom.getChosenType());
        newCopy.setChosenType2(copyFrom.getChosenType2());
        newCopy.setNamedCards(Lists.newArrayList(copyFrom.getNamedCards()));
        newCopy.setChosenColors(Lists.newArrayList(copyFrom.getChosenColors()));
        if (copyFrom.hasChosenNumber()) {
            newCopy.setChosenNumber(copyFrom.getChosenNumber());
        }
        newCopy.setChosenEvenOdd(copyFrom.getChosenEvenOdd());

        // for getReplacementList (run after setChangedCardKeywords for caching)
        newCopy.setStoredKeywords(copyFrom.getStoredKeywords(), true);
        if (!historical) {
            newCopy.setStoredReplacements(copyFrom.getStoredReplacements());
        }

        newCopy.copyChangedTextFrom(copyFrom);
        newCopy.changedCardKeywordsByWord = copyFrom.changedCardKeywordsByWord.copy(newCopy, true);

        newCopy.setGameTimestamp(copyFrom.getGameTimestamp());
        newCopy.setLayerTimestamp(copyFrom.getLayerTimestamp());

        newCopy.setBestowTimestamp(copyFrom.getBestowTimestamp());

        newCopy.setTurnInZone(copyFrom.getTurnInZone());

        newCopy.setForetold(copyFrom.isForetold());
        newCopy.setForetoldCostByEffect(copyFrom.isForetoldCostByEffect());

        newCopy.setPlotted(copyFrom.isPlotted());

        newCopy.setPrepared(copyFrom.getPrepared());

        newCopy.setMeldedWith(copyLkiCard(copyFrom.getMeldedWith(), cachedMap, history));

        // update keyword cache on all states
        for (CardStateName s : newCopy.getStates()) {
            newCopy.updateKeywordsCache(newCopy.getState(s));
        }

        if (copyFrom.getCastSA() != null) {
            SpellAbility castSA = historical
                    ? copyFrom.getCastSA().copy(newCopy, (Player) copyLkiEntity(copyFrom.getCastSA().getActivatingPlayer(), history), true)
                    : copyFrom.getCastSA().copy(newCopy, true);
            castSA.setLastStateBattlefield(CardCollection.EMPTY);
            castSA.setLastStateGraveyard(CardCollection.EMPTY);
            newCopy.setCastSA(castSA);
        }
        newCopy.setCastFrom(historical ? mapZone(copyFrom.getCastFrom()) : copyFrom.getCastFrom());

        newCopy.setExiledBy(historical ? (Player) copyLkiEntity(copyFrom.getExiledBy(), history) : copyFrom.getExiledBy());
        newCopy.setExiledWith(copyLkiCard(copyFrom.getExiledWith(), cachedMap, history));
        if (historical) {
            newCopy.addExiledCards(copyHistoryCards(copyFrom.getExiledCards(), history));
        } else {
            newCopy.setExiledSA(copyFrom.getExiledSA());
            newCopy.addExiledCards(copyFrom.getExiledCards());
        }

        newCopy.setDiscarded(copyFrom.wasDiscarded());
        newCopy.setMilled(copyFrom.wasMilled());
        newCopy.setSurveilled(copyFrom.wasSurveilled());

        newCopy.getAbilityActivatedThisTurn().putAll(copyFrom.getAbilityActivatedThisTurn());
        newCopy.getAbilityActivatedThisGame().putAll(copyFrom.getAbilityActivatedThisGame());
        newCopy.getAbilityResolvedThisTurn().putAll(copyFrom.getAbilityResolvedThisTurn());

        if (historical) {
            final CombatLki combat = copyFrom.getCombatLKI();
            if (combat != null) {
                final FCollection<AttackingBand> bands = new FCollection<>();
                for (final AttackingBand band : combat.relatedBands) {
                    final AttackingBand mapped = new AttackingBand(copyHistoryCards(band.getAttackers(), history));
                    if (band.isBlocked() != null) {
                        mapped.setBlocked(band.isBlocked());
                    }
                    bands.add(mapped);
                }
                newCopy.setCombatLKI(new CombatLki(combat.isAttacker, bands));
            }
        } else if (copyFrom.getGame().getCombat() != null && copyFrom.isPermanent()) {
            newCopy.setCombatLKI(copyFrom.getGame().getCombat().saveLKI(newCopy));
        }

        if (historical) {
            copyFrom.getGoadMap().forEach((timestamp, player) -> newCopy.getGoadMap().put(timestamp, (Player) copyLkiEntity(player, history)));
        } else {
            newCopy.getGoadMap().putAll(copyFrom.getGoadMap());
        }

        return newCopy;
    }

    private static PerpetualInterface copyHistoricalPerpetual(final PerpetualInterface effect, final Card destination) {
        if (effect instanceof PerpetualAbilities abilities) {
            return new PerpetualAbilities(abilities.timestamp(), abilities.changes().copy(destination, true));
        }
        if (effect instanceof PerpetualKeywords keywords) {
            return new PerpetualKeywords(keywords.timestamp(),
                    keywords.addKeywords() == null ? null : List.copyOf(keywords.addKeywords()),
                    keywords.removeKeywords() == null ? null : List.copyOf(keywords.removeKeywords()), keywords.removeAll());
        }
        if (effect instanceof PerpetualTypes types) {
            return new PerpetualTypes(types.timestamp(), types.addTypes() == null ? null : new CardType(types.addTypes()),
                    types.removeTypes() == null ? null : new CardType(types.removeTypes()),
                    types.removeXTypes() == null ? null : Set.copyOf(types.removeXTypes()));
        }
        return effect;
    }

    private CardCollection copyHistoryCards(final Iterable<Card> cards, final Map<Card, Card> history) {
        final CardCollection result = new CardCollection();
        for (final Card card : cards) {
            result.add(copyLkiForGame(card, toGame, history));
        }
        return result;
    }

}
