package forge.ai.simulation;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

public class LinkedExileGameCopierTest extends AITest {
    @Test
    public void linkedImprintUsesLocalObjectsAndPreservesBothTimestamps() {
        Game original = initAndCreateGame();
        Player owner = original.getPhaseHandler().getPlayerTurn();
        Card source = addCard("Isochron Scepter", owner);
        Card duplicate = addCard("Isochron Scepter", owner);
        Card exiled = imprint(source);
        long layerTimestamp = original.getNextTimestamp();
        source.setLayerTimestamp(layerTimestamp);
        Card unrelatedExile = addCardToZone("Shock", owner, ZoneType.Exile);

        GameCopier copier = new GameCopier(original);
        Game copy = copier.makeCopy();
        Card copiedSource = copier.find(source);
        Card copiedExile = copier.find(exiled);
        assertEquals(copiedSource.getGameTimestamp(), source.getGameTimestamp());
        assertEquals(copiedSource.getLayerTimestamp(), layerTimestamp);
        assertEquals(copiedExile.getGameTimestamp(), exiled.getGameTimestamp());
        assertEquals(copier.find(unrelatedExile).getGameTimestamp(), unrelatedExile.getGameTimestamp());
        assertSame(copier.reverseFind(copiedExile), exiled);
        assertSame(copiedSource.getExiledCards().get(0), copiedExile);
        assertSame(copiedSource.getImprintedCards().get(0), copiedExile);
        assertSame(copiedExile.getExiledWith(), copiedSource);
        assertSame(copiedExile.getExiledBy(), copier.find(owner));
        assertSame(copiedExile.getGame(), copy);
        assertNotSame(copiedExile, exiled);
        assertNotSame(copiedSource, source);
        assertTrue(copier.find(duplicate).getExiledCards().isEmpty());
        assertTrue(copier.find(duplicate).getImprintedCards().isEmpty());

        Player copiedOwner = copier.find(owner);
        useFirstChoice(copiedOwner);
        SpellAbility play = copiedSource.getSpellAbilities().stream()
                .filter(ability -> ability.getApi() == ApiType.Play).findFirst().orElseThrow();
        play.setActivatingPlayer(copiedOwner);
        assertTrue(copiedExile.isValid("Card.IsImprinted+ExiledWithSource", copiedOwner, copiedSource, play));
        AbilityUtils.resolve(play);
        copy.getStack().unfreezeStack();
        assertEquals(copy.getStack().size(), 1, "Scepter must cast the linked card");
        Card castCopy = copy.getStack().peekAbility().getHostCard();
        assertEquals(castCopy.getName(), copiedExile.getName());
        assertNotEquals(castCopy.getId(), copiedExile.getId());
        assertSame(castCopy.getGame(), copy);
        assertTrue(copiedExile.isInZone(ZoneType.Exile));
        assertTrue(original.getStack().isEmpty());
        assertSame(exiled.getExiledWith(), source);
    }

    @Test
    public void blinkKeepsTheLinkedExileWithTheOldIncarnation() {
        Game original = initAndCreateGame();
        Player owner = original.getPhaseHandler().getPlayerTurn();
        Card source = addCard("Forest", owner);
        Card oldExile = exileWith(source);
        original.getTriggerHandler().resetActiveTriggers();
        original.copyLastState();
        Card inExile = original.getAction().exile(source, null, null);
        Card returned = original.getAction().moveToPlay(inExile, null, null);
        Card newExile = exileWith(returned);

        GameCopier copier = new GameCopier(original);
        Game copy = copier.makeCopy();
        Card copiedCurrent = copier.find(returned);
        Card copiedOldExile = copier.find(oldExile);
        Card copiedNewExile = copier.find(newExile);
        Card historicalSource = copiedOldExile.getExiledWith();
        assertTrue(historicalSource.isLKI());
        assertSame(historicalSource.getGame(), copy);
        assertSame(historicalSource.getOwner(), copier.find(owner));
        assertTrue(historicalSource.equalsWithGameTimestamp(source));
        assertFalse(historicalSource.equalsWithGameTimestamp(copiedCurrent));
        assertNotSame(historicalSource, source);
        assertSame(copiedNewExile.getExiledWith(), copiedCurrent);
        assertSame(copiedCurrent.getExiledCards().get(0), copiedNewExile);
        assertFalse(copiedCurrent.hasExiledCard(copiedOldExile));
    }

    private Card exileWith(Card source) {
        Player owner = source.getOwner();
        Card card = addCardToZone("Opt", owner, ZoneType.Hand);
        SpellAbility cause = AbilityFactory.getAbility("DB$ ChangeZone | Origin$ Hand | Destination$ Exile", source);
        cause.setActivatingPlayer(owner);
        Card exiled = owner.getGame().getAction().exile(card, cause, null);
        SpellAbilityEffect.handleExiledWith(exiled, cause);
        return exiled;
    }

    private Card imprint(Card source) {
        Player owner = source.getOwner();
        Card spell = addCardToZone("Opt", owner, ZoneType.Hand);
        useFirstChoice(owner);
        owner.getGame().copyLastState();
        SpellAbility exile = source.getTriggers().get(0).ensureAbility();
        exile.setActivatingPlayer(owner);
        AbilityUtils.resolve(exile);
        Card exiled = owner.getGame().findById(spell.getId());
        assertTrue(exiled.isInZone(ZoneType.Exile));
        assertSame(exiled.getExiledWith(), source);
        return exiled;
    }

    private static void useFirstChoice(Player player) {
        player.dangerouslySetController(new PlayerControllerAi(player.getGame(), player, player.getLobbyPlayer()) {
            @Override
            public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options,
                    DelayedReveal reveal, SpellAbility ability, String title, boolean optional,
                    Player targeted, Map<String, Object> params) {
                return options.stream().findFirst().orElse(null);
            }

            @Override
            public boolean confirmAction(SpellAbility ability, PlayerActionConfirmMode mode, String message,
                    List<String> options, Card card, Map<String, Object> params) {
                return true;
            }

            @Override
            public boolean playSaFromPlayEffect(SpellAbility spell) {
                assertTrue(ComputerUtil.playStack(spell, player, player.getGame()), "The spell must be playable");
                return true;
            }
        });
    }
}
