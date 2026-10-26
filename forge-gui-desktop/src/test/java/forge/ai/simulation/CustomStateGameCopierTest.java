package forge.ai.simulation;

import forge.ai.AITest;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.keyword.MelodyUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.NextSpellColorHelper;
import forge.game.spellability.ResonanceHelper;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class CustomStateGameCopierTest extends AITest {
    @DataProvider
    public Object[][] copyBackends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "copyBackends")
    public void lifePaymentHistorySurvivesCopyAndCleanup(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card host = addCard("Forest", p);
        SpellAbility payment = AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 0", host);
        payment.setActivatingPlayer(p);
        p.setLifePaidLastTurn(3);
        assertTrue(p.payLife(2, payment, false));

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);

        assertEquals(copied.getLifePaidThisTurn(), 2);
        assertEquals(copied.getLifePaidLastTurn(), 3);
        assertEquals(copied.getGreatestLifeLostAtOnceThisTurn(), 2);
        copied.onCleanupPhase();
        assertEquals(copied.getLifePaidThisTurn(), 0);
        assertEquals(copied.getLifePaidLastTurn(), 2);
        assertEquals(p.getLifePaidThisTurn(), 2);
        assertEquals(p.getLifePaidLastTurn(), 3);
        assertEquals(p.getLife(), 18);
    }

    @Test(dataProvider = "copyBackends")
    public void wormfaceCreatesTokenAtEndStepOnlyInCopiedGame(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        original.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        Card wormface = addCard("Wormface", p);
        SpellAbility payment = AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 0", wormface);
        payment.setActivatingPlayer(p);
        assertTrue(p.payLife(2, payment, false));

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Game simulation = copied.getGame();
        assertTrue(simulation.getPhaseHandler().devAdvanceToPhase(PhaseType.END_OF_TURN));
        GameSimulator.resolveStack(simulation, copied.getOpponents().get(0));

        assertEquals(copied.getCreaturesInPlay().size(), 2);
        assertTrue(copied.getCreaturesInPlay().anyMatch(c -> c.isToken() && c.getType().hasSubtype("Insect")));
        assertEquals(p.getCreaturesInPlay().size(), 1);
        assertEquals(original.getPhaseHandler().getPhase(), PhaseType.MAIN2);
        assertEquals(p.getLifePaidThisTurn(), 2);
    }

    @Test(dataProvider = "copyBackends")
    public void pendingResonanceMapsEveryCardAndHasIndependentList(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card first = addCardToZone("Nourish", p, ZoneType.Exile);
        Card second = addCardToZone("Nourish", p, ZoneType.Exile);
        p.addPendingResonance(first);
        p.addPendingResonance(second);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);

        assertNotSame(copied.getPendingResonance(), p.getPendingResonance());
        assertEquals(copied.getPendingResonance().size(), 2);
        assertSame(copied.getPendingResonance().get(0), copier.find(first));
        assertSame(copied.getPendingResonance().get(1), copier.find(second));
        assertNotSame(copied.getPendingResonance().get(0), first);
        assertSame(copied.getPendingResonance().get(0).getGame(), copied.getGame());
        copied.getPendingResonance().clear();
        assertEquals(p.getPendingResonance().size(), 2);
        p.getPendingResonance().remove(first);
        assertTrue(copied.getPendingResonance().isEmpty());
    }

    @Test(dataProvider = "copyBackends")
    public void pendingResonanceMergesResolvesAndIsConsumedOnlyInCopy(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card pending = addCardToZone("Nourish", p, ZoneType.Exile);
        Card next = addCardToZone("Nourish", p, ZoneType.Hand);
        p.addPendingResonance(pending);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Game simulation = copied.getGame();
        Card copiedPending = copier.find(pending);
        Card copiedNext = copier.find(next);
        SpellAbility spell = copiedNext.getFirstSpellAbility();
        spell.setActivatingPlayer(copied);
        int life = copied.getLife();

        ResonanceHelper.mergeOnCast(simulation, copied, spell);
        assertNotNull(spell.getSubAbility(), "The pending spell's effect must be merged");
        assertSame(spell.getSubAbility().getHostCard(), copiedNext);
        assertSame(spell.getSubAbility().getActivatingPlayer(), copied);
        ResonanceHelper.commitOnCastSuccess(simulation, copied, spell);
        AbilityUtils.resolve(spell);

        assertEquals(copied.getLife(), life + 12);
        assertTrue(copied.getPendingResonance().isEmpty());
        assertTrue(copiedPending.isInZone(ZoneType.Graveyard));
        assertEquals(p.getLife(), life);
        assertSame(p.getPendingResonance().get(0), pending);
        assertTrue(pending.isInZone(ZoneType.Exile));
        assertTrue(next.isInZone(ZoneType.Hand));
        assertNull(next.getFirstSpellAbility().getSubAbility());
    }

    @Test(dataProvider = "copyBackends")
    public void nextSpellColorsApplyCommitAndExpireIndependently(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card spellCard = addCardToZone("Nourish", p, ZoneType.Hand);
        p.setNextSpellAddColors(ColorSet.UB);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Card copiedCard = copier.find(spellCard);
        SpellAbility spell = copiedCard.getFirstSpellAbility();
        spell.setActivatingPlayer(copied);
        assertEquals(copied.getNextSpellAddColors(), ColorSet.UB);

        NextSpellColorHelper.prepareSpellColor(copied, spell);
        assertTrue(copiedCard.getColor().hasAllColors(ColorSet.BGU.getColor()));
        NextSpellColorHelper.commitPreparedSpellColor(copied, spell);
        assertFalse(copied.hasNextSpellAddColors());
        assertEquals(p.getNextSpellAddColors(), ColorSet.UB);
        assertEquals(spellCard.getColor(), ColorSet.G);
        NextSpellColorHelper.clearDirectColorOverrideForSpellCast(spell);
        assertEquals(copiedCard.getColor(), ColorSet.G);
        copied.setNextSpellAddColors(ColorSet.R);
        copied.onCleanupPhase();
        assertFalse(copied.hasNextSpellAddColors());
        assertEquals(p.getNextSpellAddColors(), ColorSet.UB);
    }

    @Test(dataProvider = "copyBackends")
    public void subjugateDoesNotBecomeFirstActionAgain(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        addCard("Forest", p);
        p.addSubjugatedThisTurn(1, new CardCollection(), null);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Card observer = addCard("Forest", copied);
        Trigger firstTime = TriggerHandler.parseTrigger(
                "Mode$ Subjugated | ValidPlayer$ You | FirstTime$ True | Static$ True | TriggerZones$ Battlefield",
                observer, true);
        firstTime.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 1", observer));
        observer.addTrigger(firstTime);
        copied.getGame().getTriggerHandler().resetActiveTriggers();
        int life = copied.getLife();

        assertEquals(copied.getSubjugatedNumThisTurn(), 1);
        copied.addSubjugatedThisTurn(1, new CardCollection(), null);
        assertEquals(copied.getSubjugatedNumThisTurn(), 2);
        assertEquals(copied.getLife(), life, "The second Subjugate must not trigger FirstTime");
        copied.onCleanupPhase();
        copied.addSubjugatedThisTurn(1, new CardCollection(), null);
        assertEquals(copied.getLife(), life + 1, "Positive control after cleanup");
        assertEquals(p.getSubjugatedNumThisTurn(), 1);
    }

    @Test(dataProvider = "copyBackends")
    public void craftedColorsRemainAvailableToCardProperties(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card card = addCard("Runeclaw Bear", p);
        card.setCraftedWithColors(ColorSet.UB);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Card copiedCard = copier.find(card);

        assertEquals(copiedCard.getCraftedWithColors(), ColorSet.UB);
        assertTrue(copiedCard.isValid("Card.CraftedWithColorBlue", copied, copiedCard, null));
        copiedCard.setCraftedWithColors(ColorSet.R);
        assertEquals(card.getCraftedWithColors(), ColorSet.UB);
    }

    @Test(dataProvider = "copyBackends")
    public void melodyStillMapsSourcesAndPreventsUntapOnlyInCopy(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card source = addCard("Forest", p);
        source.setTapped(true);
        Card creature = addCard("Runeclaw Bear", p);
        SpellAbility cast = creature.getFirstSpellAbility().copy(creature, p, false);
        cast.addPaidByMelody(source);
        creature.setCastSA(cast);

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Card copiedSource = copier.find(source);
        Card copiedCreature = copier.find(creature);

        assertNotSame(copiedCreature.getCastSA(), cast);
        assertSame(copiedCreature.getCastSA().getActivatingPlayer(), copied);
        assertSame(copiedCreature.getCastSA().getPaidByMelody().get(0), copiedSource);
        assertTrue(MelodyUtil.isBeguiledBy(copiedCreature, copiedSource));
        assertFalse(copiedSource.canUntap(copied, false));
        assertTrue(copiedSource.isTapped());
        copiedCreature.getCastSA().clearPaidByMelody();
        assertTrue(copiedSource.canUntap(copied, false));
        assertTrue(copiedSource.untap(copied));
        assertTrue(source.isTapped());
        assertTrue(MelodyUtil.preventsUntap(source, p));
    }

    @Test(dataProvider = "copyBackends")
    public void enlightenedEffectIsMappedWithoutDuplication(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card crucible = addCard("Steinar's Crucible", p);
        p.setEnlightened(true, crucible.getSetCode());

        GameCopier copier = copy(original, snapshot);
        Player copied = copier.find(p);
        Card copiedCrucible = copier.find(crucible);

        assertTrue(copied.isEnlightened());
        long effects = copied.getCardsIn(ZoneType.Command).stream().filter(c -> c.getName().equals("Enlightened")).count();
        assertEquals(effects, 1L);
        SpellAbility makeTokens = copiedCrucible.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.Token).findFirst().orElseThrow();
        makeTokens.setActivatingPlayer(copied);
        AbilityUtils.resolve(makeTokens);
        assertEquals(copied.getCreaturesInPlay().size(), 2);
        copied.setEnlightened(false, null);
        AbilityUtils.resolve(makeTokens);
        assertEquals(copied.getCreaturesInPlay().size(), 3);
        assertTrue(p.isEnlightened());
        assertEquals(p.getCreaturesInPlay().size(), 0);
    }

    private GameCopier copy(Game original, boolean snapshot) {
        original.EXPERIMENTAL_RESTORE_SNAPSHOT = snapshot;
        original.getAction().checkStateEffects(true);
        original.copyLastState();
        GameCopier copier = new GameCopier(original);
        copier.makeCopy(null, original.getPlayers().get(0));
        return copier;
    }

    @Test(dataProvider = "copyBackends")
    public void resolvedGroupsKeepTheirCountAndAdvanceIndependently(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card host = addCard("Runeclaw Bear", p);
        SpellAbility ability = host.getFirstSpellAbility();
        ability.setActivatingPlayer(p);
        host.addAbilityResolvedGroup("Cycle", ability);
        host.addAbilityResolvedGroup("Cycle", ability);

        GameCopier copier = copy(original, snapshot);
        Card copied = copier.find(host);
        Player copiedPlayer = copier.find(p);
        assertEquals(copied.getAbilityResolvedGroupThisTurn("Cycle"), 2);
        SpellAbility count = AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ Count$ResolvedGroupThisTurn.Cycle", copied);
        count.setActivatingPlayer(copiedPlayer);
        int life = copiedPlayer.getLife();
        AbilityUtils.resolve(count);
        assertEquals(copiedPlayer.getLife(), life + 2);
        copied.addAbilityResolvedGroup("Cycle", count);
        assertEquals(copied.getAbilityResolvedGroupThisTurn("Cycle"), 3);
        assertEquals(host.getAbilityResolvedGroupThisTurn("Cycle"), 2);
        copied.resetAbilityResolvedThisTurn();
        assertEquals(copied.getAbilityResolvedGroupThisTurn("Cycle"), 0);
        assertEquals(host.getAbilityResolvedGroupThisTurn("Cycle"), 2);
    }

    @Test
    public void resolvedGroupsKeepOriginalTriggerAccounting() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = addCard("Forest", p);
        SpellAbility ability = AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 1", host);
        ability.setActivatingPlayer(p);
        Trigger trigger = TriggerHandler.parseTrigger("Mode$ Always", host, true);
        ability.setTrigger(trigger);
        host.addAbilityResolvedGroup("Cycle", ability);
        assertEquals(host.getAbilityResolvedGroupThisTurn("Cycle"), 0);
        trigger.setOverridingAbility(ability);
        host.addAbilityResolvedGroup("Cycle", ability);
        assertEquals(host.getAbilityResolvedGroupThisTurn("Cycle"), 1);
    }

    @Test(dataProvider = "copyBackends")
    public void loyaltyAbilityUsageIsMappedToCopiedAbility(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        original.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card walker = addCard("Jace Beleren", p);
        walker.setCounters(CounterEnumType.LOYALTY, 3);
        SpellAbility used = walker.getSpellAbilities().stream().filter(SpellAbility::isPwAbility).findFirst().orElseThrow();
        used.setActivatingPlayer(p);
        walker.addActivatedThisLoyaltyAbilityThisTurn(used);

        GameCopier copier = copy(original, snapshot);
        Card copied = copier.find(walker);
        SpellAbility copiedAbility = copied.getSpellAbilities().stream()
                .filter(sa -> sa.getDescription().equals(used.getDescription())).findFirst().orElseThrow();
        copiedAbility.setActivatingPlayer(copier.find(p));
        assertNotSame(copiedAbility, used);
        assertTrue(copied.hasActivatedThisLoyaltyAbilityThisTurn(copiedAbility));
        copied.addStaticAbility("Mode$ NumLoyaltyAct | ValidCard$ Card.Self | EachAbilityOnce$ True");
        assertFalse(copiedAbility.getRestrictions().canPlay(copied, copiedAbility));
        copied.resetActivationsPerTurn();
        assertTrue(copiedAbility.getRestrictions().canPlay(copied, copiedAbility));
        assertTrue(walker.hasActivatedThisLoyaltyAbilityThisTurn(used));
    }

    @Test(dataProvider = "copyBackends")
    public void focusedExileFlagIsIndependentAndAvailableToConditions(boolean snapshot) {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card card = addCardToZone("Nourish", p, ZoneType.Exile);
        card.setFocused(true);

        GameCopier copier = copy(original, snapshot);
        Card copied = copier.find(card);
        Player copiedPlayer = copier.find(p);
        assertTrue(copied.isFocused());
        SpellAbility condition = AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 1 | Condition$ Focused", copied);
        condition.setActivatingPlayer(copiedPlayer);
        int life = copiedPlayer.getLife();
        AbilityUtils.resolve(condition);
        assertEquals(copiedPlayer.getLife(), life + 1);
        copied.setFocused(false);
        AbilityUtils.resolve(condition);
        assertEquals(copiedPlayer.getLife(), life + 1);
        assertTrue(card.isFocused());
    }

    @Test
    public void monteCarloSimulatorUsesTheSameCustomStateCopy() {
        Game original = initAndCreateGame();
        Player p = original.getPlayers().get(0);
        Card pending = addCardToZone("Nourish", p, ZoneType.Exile);
        p.addPendingResonance(pending);
        p.setLifePaidThisTurn(2);
        p.setLifePaidLastTurn(3);
        p.setNextSpellAddColors(ColorSet.U);
        long timestamp = original.getTimestamp();

        GameSimulator simulator = new GameSimulator(original, p);
        Player copied = simulator.getSimulatedAiPlayer();

        assertEquals(copied.getLifePaidThisTurn(), 2);
        assertEquals(copied.getLifePaidLastTurn(), 3);
        assertEquals(copied.getNextSpellAddColors(), ColorSet.U);
        assertNotSame(copied.getPendingResonance().get(0), pending);
        assertSame(copied.getPendingResonance().get(0).getGame(), simulator.getSimulatedGameState());
        assertEquals(original.getTimestamp(), timestamp);
        assertSame(p.getPendingResonance().get(0), pending);
    }
}
