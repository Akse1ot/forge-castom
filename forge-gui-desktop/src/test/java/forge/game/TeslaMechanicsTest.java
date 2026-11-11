package forge.game;

import forge.ai.AITest;
import forge.ai.AiCostDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostPayment;
import forge.game.cost.CostTapType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

public class TeslaMechanicsTest extends AITest {
    private static final String MARKED = "Card.YouCtrl+hasAbilityLabelRevolution";
    private static final String TURN_BACK = "DB$ SetState | Mode$ Transform | Defined$ ReplacedCard | ETB$ True";
    private static final String ENTER_BACK = "R:Event$ Moved | ActiveZones$ Battlefield | Destination$ Battlefield"
            + " | ValidCard$ Creature.YouCtrl+hasAbilityLabelRevolution | Layer$ Transform"
            + " | ReplacementResult$ Updated | ReplaceWith$ Back";

    @Test
    public void noTeslaCardKeepsTrackingDisabled() {
        Game game = initAndCreateGame();
        addCard("Forest", game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        assertNull(game.getTeslaJustice());
    }

    @Test
    public void existingStateAndRepeatedOffensesAreSharedAndSurviveSourceLeaving() {
        Game game = initAndCreateThreePlayerGame();
        Player judge = game.getPlayers().get(1);
        Player first = game.getPlayers().get(0);
        Player second = game.getPlayers().get(2);
        addCards("Grizzly Bears", 3, first);
        first.setLife(25, null);
        second.setLife(25, null);
        Card law = law(judge, "ThreeCreatures");
        Card lifeLaw = law(judge, "MoreLife");
        Card payoff = justice(judge, "Trig", "DB$ LoseLife | Defined$ JusticeOffenders | LifeAmount$ 2");

        begin(game, judge);
        game.getAction().checkStateEffects(true);
        game.getAction().checkStateEffects(true);
        assertTrue(game.getTeslaJustice().getOffenders(judge).isEmpty(), "First turn has no previous window");
        game.getAction().moveToGraveyard(law, null);
        game.getAction().moveToGraveyard(lifeLaw, null);
        begin(game, judge);
        fireUpkeep(game, judge);
        assertEquals(game.getStack().size(), 1);
        resolveStack(game);
        assertEquals(first.getLife(), 23);
        assertEquals(second.getLife(), 23);
        assertEquals(judge.getLife(), 20);
        assertTrue(payoff.isInPlay());

        begin(game, judge);
        fireUpkeep(game, judge);
        assertTrue(game.getStack().isEmpty(), "Another own turn expires the old window");
    }

    @Test
    public void stateLawChangesControllerAndDoesNotShareHistoryAcrossPlayers() {
        Game game = initAndCreateThreePlayerGame();
        Player judge = game.getPlayers().get(1);
        Player rival = game.getPlayers().get(0);
        Player offender = game.getPlayers().get(2);
        Card law = law(judge, "MoreLife");
        Card otherLaw = law(rival, "AttackYou");
        begin(game, judge);
        begin(game, rival);
        offender.setLife(22, null);
        game.getAction().checkStateEffects(true);
        begin(game, judge);
        begin(game, rival);
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(offender));
        assertTrue(game.getTeslaJustice().getOffenders(rival).isEmpty());

        law.setController(rival, game.getNextTimestamp());
        game.getAction().checkStateEffects(true);
        assertEquals(game.getTeslaJustice().getOffenders(rival), List.of(offender));
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(offender));
        assertTrue(otherLaw.isInPlay());
    }

    @Test
    public void anotherUpkeepDoesNotClearHistoryAndInterveningIfIsRechecked() {
        Game game = initAndCreateThreePlayerGame();
        Player judge = game.getPlayers().get(1);
        Player offender = game.getPlayers().get(0);
        Card law = law(judge, "MoreLife");
        justice(judge, "Trig", "DB$ GainLife | Defined$ You | LifeAmount$ 1");
        begin(game, judge);
        offender.setLife(25, null);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToGraveyard(law, null);
        begin(game, judge);
        addCardToZone("Forest", judge, ZoneType.Library);
        fireUpkeep(game, judge);
        game.getPhaseHandler().addExtraPhase(PhaseType.UPKEEP, List.of(PhaseType.UPKEEP), PhaseType.DRAW);
        assertTrue(game.getPhaseHandler().devAdvanceToPhase(PhaseType.DRAW, () -> resolveStack(game)));
        assertEquals(judge.getLife(), 22);

        fireUpkeep(game, judge);
        assertEquals(game.getStack().size(), 1);
        offender.concede();
        game.getAction().checkStateEffects(true);
        assertTrue(game.getTeslaJustice().getOffenders(judge).isEmpty());
        resolveStack(game);
        assertEquals(judge.getLife(), 22);
    }

    @Test
    public void combatLawsRecordDeclaredAttackersAndTheEnchantedBlocker() {
        Game game = initAndCreateThreePlayerGame();
        Player judge = game.getPlayers().get(1);
        Player attacker = game.getPlayers().get(0);
        Player blocker = game.getPlayers().get(2);
        Card bears = addCard("Grizzly Bears", attacker);
        Card wolf = addCard("Grizzly Bears", blocker);
        law(judge, "AttackYou");
        Card aura = script(judge, ZoneType.Battlefield, "Name:Tesla Test House Arrest", "ManaCost:W",
                "Types:Enchantment Aura", "K:Enchant:Creature", "S:Mode$ TeslaLaw | Law$ EnchantedCombat");
        aura.attachToEntity(wolf, null, true);
        assertSame(aura.getEnchantingCard(), wolf);
        begin(game, judge);
        game.getTriggerHandler().runTrigger(TriggerType.AttackersDeclaredOneTarget,
                Map.of(AbilityKey.AttackedTarget, List.of(judge), AbilityKey.Attackers, List.of(bears),
                        AbilityKey.AttackingPlayer, attacker), false);
        game.getTriggerHandler().runTrigger(TriggerType.Blocks,
                Map.of(AbilityKey.Blocker, wolf, AbilityKey.Attackers, List.of(bears)), false);
        begin(game, judge);
        assertTrue(game.getTeslaJustice().getOffenders(judge).containsAll(List.of(attacker, blocker)));
    }

    @Test
    public void noSpellsLawUsesTheCompletedTurnAndTheRealPhaseTransition() {
        Game game = initAndCreateGame();
        Player judge = game.getPlayers().get(1);
        Player offender = game.getPlayers().get(0);
        law(judge, "NoSpells");
        justice(judge, "Trig", "DB$ LoseLife | Defined$ JusticeOffenders | LifeAmount$ 1");
        begin(game, judge);
        game.getPhaseHandler().devModeSet(PhaseType.CLEANUP, offender, 2);
        assertTrue(game.getTeslaJustice().getOffenders(judge).isEmpty());
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, () -> resolveStack(game)));
        assertEquals(game.getPhaseHandler().getPlayerTurn(), judge);
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(offender));
        resolveStack(game);
        assertEquals(offender.getLife(), 19);
    }

    @Test
    public void extraTurnUsesNewWindowAndUncommittedStaticProbeDoesNotRecord() {
        Game game = initAndCreateGame();
        Player judge = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        Card law = law(judge, "MoreLife");
        begin(game, judge);
        other.setLife(24, null);
        game.getAction().checkStaticAbilities();
        other.setLife(20, null);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToGraveyard(law, null);

        game.getPhaseHandler().devModeSet(PhaseType.CLEANUP, judge, 1);
        game.getPhaseHandler().addExtraTurn(judge);
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, () -> resolveStack(game)));
        assertEquals(game.getPhaseHandler().getPlayerTurn(), judge);
        assertTrue(game.getTeslaJustice().getOffenders(judge).isEmpty());

        law(judge, "MoreLife");
        other.setLife(24, null);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().devModeSet(PhaseType.CLEANUP, judge, 2);
        game.getPhaseHandler().addExtraTurn(judge);
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, () -> resolveStack(game)));
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(other));
    }

    @DataProvider
    public Object[][] stateLaws() {
        return new Object[][] {
                {"MoreLife", "GainLife | Defined$ You | LifeAmount$ 1",
                        "LoseLife | Defined$ You | LifeAmount$ 1"},
                {"ThreeCreatures", "Token | TokenScript$ c_1_1_a_thopter_flying | TokenOwner$ You",
                        "SacrificeAll | ValidCards$ Creature.token+YouCtrl"}
        };
    }

    @Test(dataProvider = "stateLaws")
    public void stateLawCountsOnlyViolationsThatReachTheStateBasedActionsCheck(
            String lawName, String enterViolation, String undoViolation) {
        Game game = initAndCreateGame();
        Player judge = game.getPlayers().get(0);
        Player other = game.getPlayers().get(1);
        law(judge, lawName);
        addCards("Grizzly Bears", 2, other);
        Card host = addCard("Forest", other);
        host.setSVar("Undo", "DB$ " + undoViolation);
        begin(game, judge);
        begin(game, judge);

        resolve(host, "DB$ " + enterViolation + " | SubAbility$ Undo");
        game.getAction().checkStateEffects(true);
        assertEquals(other.getLife(), 20);
        assertEquals(other.getCreaturesInPlay().size(), 2);
        assertTrue(game.getTeslaJustice().getOffenders(judge).isEmpty(),
                "The violation ended within the same resolution");

        resolve(host, "DB$ " + enterViolation);
        game.getAction().checkStateEffects(true);
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(other),
                "The violation was still present at the state-based actions check");
    }

    @DataProvider
    public Object[][] copyBackends() {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "copyBackends")
    public void historySurvivesGameCopyWithNoSharedMutableSets(boolean snapshot) {
        Game game = initAndCreateGame();
        Player judge = game.getPlayers().get(1);
        Player offender = game.getPlayers().get(0);
        Card law = law(judge, "MoreLife");
        begin(game, judge);
        offender.setLife(25, null);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToGraveyard(law, null);
        begin(game, judge);
        Game copy = snapshot ? new GameSnapshot(game).makeCopy(null, false)
                : new GameCopier(game).withStack(false).makeCopy();
        Player copiedJudge = copy.getPlayer(judge.getId());
        Player copiedOffender = copy.getPlayer(offender.getId());
        assertSame(copy.getTeslaJustice().getOffenders(copiedJudge).getFirst(), copiedOffender);
        begin(copy, copiedJudge);
        assertTrue(copy.getTeslaJustice().getOffenders(copiedJudge).isEmpty());
        assertEquals(game.getTeslaJustice().getOffenders(judge), List.of(offender));
    }

    @Test(dataProvider = "copyBackends")
    public void spellCastBeforeCopyPreventsNoSpellsViolation(boolean snapshot) {
        Game game = initAndCreateGame();
        Player judge = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        law(judge, "NoSpells");
        begin(game, judge);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, other, 2);
        Card ornithopter = addCardToZone("Ornithopter", other, ZoneType.Hand);
        SpellAbility cast = ornithopter.getFirstSpellAbility();
        cast.setActivatingPlayer(other);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(other, cast, null));
        resolveStack(game);
        assertEquals(other.getSpellsCastThisTurn(), 1);

        Game copy = snapshot ? new GameSnapshot(game).makeCopy(null, false)
                : new GameCopier(game).withStack(false).makeCopy();
        Player copiedOther = copy.getPlayer(other.getId());
        Player copiedJudge = copy.getPlayer(judge.getId());
        copy.getTeslaJustice().finishTurns(copy, List.of(copiedOther));
        begin(copy, copiedJudge);
        assertTrue(copy.getTeslaJustice().getOffenders(copiedJudge).isEmpty());
    }

    @Test
    public void targetFilterAcceptsOnlyCreaturesOfOffenders() {
        Game game = initAndCreateThreePlayerGame();
        Player judge = game.getPlayers().get(1);
        Player offender = game.getPlayers().get(0);
        Player other = game.getPlayers().get(2);
        law(judge, "MoreLife");
        String destroy = "DB$ Destroy | ValidTgts$ Creature | TargetsWithDefinedController$ JusticeOffenders";
        Card payoff = justice(judge, "Trig", destroy);
        Card valid = addCard("Grizzly Bears", offender);
        Card invalid = addCard("Grizzly Bears", other);
        begin(game, judge);
        offender.setLife(25, null);
        game.getAction().checkStateEffects(true);
        begin(game, judge);
        SpellAbility action = AbilityFactory.getAbility(destroy, payoff);
        action.setActivatingPlayer(judge);
        assertTrue(action.canTarget(valid));
        assertFalse(action.canTarget(invalid));
    }

    @Test
    public void revolutionPaysOnceAndTransformsCurrentGroupEvenWithoutSource() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = revolutionary(player, "Alpha", "3/3", ZoneType.Battlefield);
        Card fellow = revolutionary(player, "Beta", "4/4", ZoneType.Battlefield);
        SpellAbility revolt = revolt(source);
        assertFalse(revolt.getPayCosts().canPay(revolt, player, false), "Source is not a second creature");
        Card other = addCard("Grizzly Bears", player);
        assertTrue(revolt.getPayCosts().canPay(revolt, player, false));
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, player);
        assertFalse(revolt.canPlay(), "Revolution requires sorcery timing");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        assertTrue(revolt.canPlay());
        payAndStack(game, revolt);
        assertTrue(source.isTapped() && fellow.isTapped() && other.isTapped());
        game.getAction().moveToGraveyard(source, null);
        Card late = revolutionary(player, "Late", "5/5", ZoneType.Battlefield);
        resolveStack(game);
        assertTrue(fellow.isBackSide());
        assertTrue(late.isBackSide());
        assertEquals(game.getCardState(source).getZone().getZoneType(), ZoneType.Graveyard);
        assertFalse(other.isBackSide());
    }

    @Test
    public void currentControllerAndAbilitiesDetermineTheGroupForEachActivation() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card source = revolutionary(player, "Alpha", "4/4", ZoneType.Battlefield);
        Card stolen = revolutionary(player, "Stolen", "4/4", ZoneType.Battlefield);
        Card silenced = revolutionary(player, "Silenced", "4/4", ZoneType.Battlefield);
        addCards("Grizzly Bears", 2, player);
        payAndStack(game, revolt(source));
        stolen.setController(opponent, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(stolen);
        resolve(silenced, "DB$ Animate | Defined$ Self | RemoveAllAbilities$ True | Duration$ EndOfTurn");
        game.getAction().checkStaticAbilities();
        resolveStack(game);
        assertTrue(source.isBackSide());
        assertFalse(stolen.isBackSide());
        assertFalse(silenced.isBackSide());

        Card next = revolutionary(player, "Next", "5/5", ZoneType.Battlefield);
        addCards("Grizzly Bears", 2, player);
        payAndStack(game, revolt(next));
        resolveStack(game);
        assertTrue(next.isBackSide());
        assertTrue(source.isBackSide(), "A prior revolt does not make the back face a member of the next group");
    }

    @Test
    public void lieutenantTriggersOnActivationEvenIfCounteredAndNotOnCopyOrLegacy() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = revolutionary(player, "Alpha", "4/4", ZoneType.Battlefield);
        Card one = addCard("Grizzly Bears", player);
        Card two = addCard("Grizzly Bears", player);
        Card lieutenant = script(player, ZoneType.Battlefield, "Name:Tesla Test Lieutenant", "ManaCost:1 R",
                "Types:Creature Human Soldier", "PT:2/2",
                "T:Mode$ AbilityCast | ValidSA$ Activated.TeslaRevolution | ValidActivatingPlayer$ You"
                        + " | TriggerZones$ Battlefield | Execute$ Untap",
                "SVar:Untap:DB$ UntapAll | ValidCards$ Creature.YouCtrl");
        SpellAbility otherAbility = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | LifeAmount$ 1", source);
        otherAbility.setActivatingPlayer(player);
        payAndStack(game, otherAbility);
        assertEquals(game.getStack().size(), 1, "A different activation on a revolutionary is not revolt");
        resolveStack(game);

        SpellAbility revolt = revolt(source);
        payAndStack(game, revolt);
        assertEquals(game.getStack().size(), 2);
        game.getStack().resolveStack();
        assertFalse(source.isTapped() || one.isTapped() || two.isTapped());
        assertFalse(source.isBackSide());
        SpellAbility copy = revolt.copy(player);
        copy.setCopied(true);
        game.getStack().add(copy);
        assertEquals(game.getStack().size(), 2, "A copy adds only the copied ability");
        game.getStack().remove(game.getStack().peek());
        game.getStack().remove(game.getStack().peek()); // Counter the original.
        assertFalse(source.isBackSide());
        resolve(lieutenant, "DB$ SetState | Mode$ Transform | Defined$ Valid " + MARKED);
        assertTrue(source.isBackSide());
        assertTrue(game.getStack().isEmpty(), "Transforming through Legacy does not cause revolt");
    }

    @Test
    public void transformedEntryIsAppliedToTheGroupAndEndsWithLastSource() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = revolutionary(player, "Alpha", "4/4", ZoneType.Battlefield);
        resolve(source, "DB$ SetState | Mode$ Transform | Defined$ Self");
        game.getStack().addAllTriggeredAbilitiesToStack();
        resolveStack(game);
        assertEquals(source.getCounters(CounterEnumType.P1P1), 1, "Actual transform fires the back trigger once");
        Card first = revolutionary(player, "Beta", "4/4", ZoneType.Graveyard);
        Card second = revolutionary(player, "Gamma", "4/4", ZoneType.Graveyard);
        Card host = addCard("Forest", player);
        game.getAction().checkStateEffects(true);
        resolve(host, "DB$ ChangeZoneAll | ChangeType$ Creature.YouOwn | Origin$ Graveyard | Destination$ Battlefield");
        assertTrue(game.getCardState(first).isBackSide());
        assertTrue(game.getCardState(second).isBackSide());
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
        resolveStack(game);
        assertEquals(game.getCardState(first).getCounters(CounterEnumType.P1P1), 1);
        assertEquals(game.getCardState(second).getCounters(CounterEnumType.P1P1), 1);

        for (Card back : List.of(source, first, second)) {
            game.getAction().exile(game.getCardState(back), null, null);
        }
        Card last = revolutionary(player, "Last", "4/4", ZoneType.Graveyard);
        resolve(host, "DB$ ChangeZoneAll | ChangeType$ Creature.YouOwn | Origin$ Graveyard | Destination$ Battlefield");
        assertFalse(game.getCardState(last).isBackSide());
    }

    @Test
    public void anotherCardEnteringInTheSameBatchDoesNotSupplyItsBackFaceReplacement() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = revolutionary(player, "First", "4/4", ZoneType.Graveyard);
        source.setSVar("SelfBack", TURN_BACK);
        source.addReplacementEffect(ReplacementHandler.parseReplacement(
                "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield"
                        + " | Layer$ Transform | ReplacementResult$ Updated | ReplaceWith$ SelfBack", source, true));
        Card second = revolutionary(player, "Second", "4/4", ZoneType.Graveyard);
        Card host = addCard("Forest", player);
        game.getAction().checkStateEffects(true);
        resolve(host, "DB$ ChangeZoneAll | ChangeType$ Creature.YouOwn | Origin$ Graveyard | Destination$ Battlefield");
        assertTrue(game.getCardState(source).isBackSide());
        assertFalse(game.getCardState(second).isBackSide());
    }

    @Test
    public void singleFacedCopyCanActivateCopiedRevolutionButCannotTransformItself() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card original = revolutionary(player, "Original", "8/8", ZoneType.Battlefield);
        Card clone = addCard("Grizzly Bears", player);
        clone.addRemembered(original);
        resolve(clone, "DB$ Clone | Defined$ Remembered | CloneTarget$ Self");
        clone.setSickness(false);
        Card helper = addCard("Grizzly Bears", player);
        SpellAbility copiedRevolt = revolt(clone);
        assertFalse(clone.isTransformable());
        assertEquals(((PlayerControllerAi) player.getController()).getAi().canPlaySa(copiedRevolt),
                AiPlayDecision.WillPlay);
        payAndStack(game, copiedRevolt);
        resolveStack(game);
        assertTrue(original.isBackSide());
        assertFalse(clone.isBackSide());
        assertTrue(helper.isTapped());
    }

    @Test
    public void alternativeCostAndConservativeAiUseTheSameActivation() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = revolutionary(player, "Alpha", "8/8", ZoneType.Battlefield);
        List<Card> helpers = addCards("Grizzly Bears", 2, player);
        assertEquals(((PlayerControllerAi) player.getController()).getAi().canPlaySa(revolt(source)),
                AiPlayDecision.WillPlay);
        for (Card helper : helpers) {
            game.getAction().exile(helper, null, null);
        }
        Card district = addCard("Forest", player);
        district.setSVar("Alternative", "Mode$ AlternativeCost | EffectZone$ Command | ValidPlayer$ You"
                + " | ValidSA$ Activated.TeslaRevolution | Cost$ T");
        resolve(district, "DB$ Effect | Duration$ EndOfTurn | StaticAbilities$ Alternative");
        SpellAbility normal = revolt(source);
        SpellAbility alternate = GameActionUtil.getAlternativeCosts(normal, player, false).stream()
                .filter(sa -> sa != normal && sa.getPayCosts().getCostPartByType(CostTapType.class) == null)
                .findFirst().orElseThrow();
        assertEquals(alternate.getParam("PrecostDesc"), "Revolution");
        assertTrue(alternate.getRestrictions().isSorcerySpeed());
        assertTrue(alternate.canPlay());
        assertEquals(((PlayerControllerAi) player.getController()).getAi().canPlaySa(alternate), AiPlayDecision.WillPlay);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, alternate, null));
        resolveStack(game);
        assertTrue(source.isBackSide());

        game.getAction().exile(source, null, null);
        Card poor = revolutionary(player, "Poor", "1/1", ZoneType.Battlefield);
        addCards("Grizzly Bears", 2, player);
        assertNotEquals(((PlayerControllerAi) player.getController()).getAi().canPlaySa(revolt(poor)),
                AiPlayDecision.WillPlay);
    }

    private Card law(Player owner, String name) {
        return script(owner, ZoneType.Battlefield, "Name:Tesla Test " + name, "ManaCost:W",
                "Types:Enchantment", "S:Mode$ TeslaLaw | Law$ " + name);
    }

    private Card justice(Player owner, String effectName, String effect) {
        return script(owner, ZoneType.Battlefield, "Name:Tesla Test Justice", "ManaCost:W", "Types:Enchantment",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield"
                        + " | CheckDefinedPlayer$ JusticeOffendersOfTriggeredPlayer | Execute$ " + effectName,
                "SVar:" + effectName + ":" + effect);
    }

    private Card revolutionary(Player owner, String name, String backPt, ZoneType zone) {
        return script(owner, zone, "Name:Tesla Test " + name, "ManaCost:W", "Types:Creature Human",
                "PT:2/2", "AlternateMode:DoubleFaced",
                "A:AB$ SetState | Mode$ Transform | Cost$ T tapXType<2/Creature> | PrecostDesc$ Revolution"
                        + " | SorcerySpeed$ True | Defined$ Valid " + MARKED + " | SpellDescription$ Transform your revolutionaries.",
                "ALTERNATE", "Name:Tesla Test " + name + " Back", "ManaCost:no cost", "Colors:white",
                "Types:Creature Human", "PT:" + backPt, ENTER_BACK, "SVar:Back:" + TURN_BACK,
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self"
                        + " | Execute$ Counter | TriggerDescription$ When CARDNAME enters, put a +1/+1 counter on it.",
                "T:Mode$ Transformed | ValidCard$ Card.Self | Execute$ Counter"
                        + " | TriggerDescription$ When CARDNAME transforms into this face, put a +1/+1 counter on it.",
                "SVar:Counter:DB$ PutCounter | Defined$ Self | CounterType$ P1P1");
    }

    private Card script(Player owner, ZoneType zone, String... lines) {
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(Arrays.asList(lines)),
                "TEST", CardRarity.Common), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }

    private static SpellAbility revolt(Card card) {
        SpellAbility result = card.getSpellAbilities().stream().filter(sa -> "Revolution".equals(sa.getParam("PrecostDesc")))
                .findFirst().orElseThrow();
        result.setActivatingPlayer(card.getController());
        return result;
    }

    private static void begin(Game game, Player player) {
        game.getTeslaJustice().startTurns(List.of(player));
    }

    private static void fireUpkeep(Game game, Player player) {
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, player);
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(player), false);
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private static void payAndStack(Game game, SpellAbility ability) {
        Player player = ability.getActivatingPlayer();
        game.getAction().checkStaticAbilities();
        game.getTriggerHandler().resetActiveTriggers();
        assertTrue(new CostPayment(ability.getPayCosts(), ability)
                .payComputerCosts(new AiCostDecision(player, ability, false)));
        game.getStack().add(ability);
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private static void resolve(Card host, String script) {
        SpellAbility ability = AbilityFactory.getAbility(script, host);
        ability.setActivatingPlayer(host.getController());
        AbilityUtils.resolve(ability);
    }

    private static void resolveStack(Game game) {
        while (!game.getStack().isEmpty()) {
            game.getStack().resolveStack();
        }
    }
}
