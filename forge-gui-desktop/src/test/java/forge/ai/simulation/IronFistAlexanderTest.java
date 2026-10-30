package forge.ai.simulation;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactory;
import forge.game.card.CardZoneTable;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** Uses the real card script and stack legality checks, with deterministic mode/target choices. */
public class IronFistAlexanderTest extends AITest {
    @DataProvider
    public Object[][] attachmentModes() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "attachmentModes")
    public void oneOfTwoAttachmentsIsLockedOnTheStack(boolean aura) throws IOException {
        Fixture f = fixture(aura);
        SpellAbility charm = f.die();
        AbilitySub mode = choose(charm, aura ? 2 : 1, f.first);
        assertTrue(mode.usesTargeting());
        assertEquals(mode.getMinTargets(), 1);
        assertEquals(mode.getMaxTargets(), 1);
        assertFalse(mode.hasParam("ChooseFromDefined"));
        assertFalse(mode.isHidden());
        assertTrue(mode.canTarget(f.second));
        Card unrelated = addCardToZone(aura ? "Holy Strength" : "Bonesplitter", f.player,
                aura ? ZoneType.Graveyard : ZoneType.Battlefield);
        assertFalse(mode.canTarget(unrelated));
        assertSame(f.game.getStack().peekAbility().getSubAbility().getTargets().getFirstTargetedCard(), f.first);
        f.game.getStack().resolveStack();
        assertTrue(f.game.getCardState(f.first).isAttachedToEntity(f.alexander));
        assertFalse(f.game.getCardState(f.second).isAttachedToEntity(f.alexander));
    }

    @Test(dataProvider = "attachmentModes")
    public void exiledTargetIsNotReplaced(boolean aura) throws IOException {
        Fixture f = fixture(aura);
        SpellAbility charm = f.die();
        choose(charm, aura ? 2 : 1, f.first);
        f.game.getAction().exile(f.first, null, AbilityKey.newMap());
        f.game.getStack().resolveStack();
        assertTrue(f.game.getCardState(f.first).isInZone(ZoneType.Exile));
        assertFalse(f.game.getCardState(f.second).isAttachedToEntity(f.alexander));
        if (aura) {
            assertTrue(f.second.isInZone(ZoneType.Graveyard));
        }
    }

    @Test(dataProvider = "attachmentModes")
    public void leavingAndReturningDoesNotRestoreTheTarget(boolean aura) throws IOException {
        Fixture f = fixture(aura);
        choose(f.die(), aura ? 2 : 1, f.first);
        Card exiled = f.game.getAction().exile(f.first, null, AbilityKey.newMap());
        Card returned = aura ? f.game.getAction().moveToGraveyard(exiled, null)
                : f.game.getAction().moveToPlay(exiled, null, AbilityKey.newMap());
        f.game.getStack().resolveStack();
        assertFalse(returned.isAttachedToEntity(f.alexander));
        assertFalse(f.game.getCardState(f.second).isAttachedToEntity(f.alexander));
    }

    @Test
    public void targetGainingShroudDoesNotCauseReselection() throws IOException {
        Fixture f = fixture(false);
        AbilitySub mode = choose(f.die(), 1, f.first);
        f.first.addIntrinsicKeyword("Shroud");
        assertFalse(mode.canTarget(f.first, true));
        f.game.getStack().resolveStack();
        assertFalse(f.first.isAttachedToEntity(f.alexander));
        assertFalse(f.second.isAttachedToEntity(f.alexander));
    }

    @Test(dataProvider = "attachmentModes")
    public void protectionPreventsAttachmentWithoutSelectingAnotherObject(boolean aura) throws IOException {
        Fixture f = fixture(aura);
        choose(f.die(), aura ? 2 : 1, f.first);
        f.alexander.addIntrinsicKeyword(aura ? "Protection from white" : "Protection:Artifact");
        f.game.getStack().resolveStack();
        assertFalse(f.game.getCardState(f.first).isAttachedToEntity(f.alexander));
        assertFalse(f.game.getCardState(f.second).isAttachedToEntity(f.alexander));
        if (aura) {
            assertTrue(f.game.getCardState(f.first).isInZone(ZoneType.Graveyard));
        }
    }

    @Test
    public void auraMustBeInYourGraveyardAndAbleToEnchantAlexander() throws IOException {
        Fixture f = fixture(true);
        Card landAura = addCard("Wild Growth", f.player);
        // The dying creature was also a land, so this attachment was legal before death.
        f.creature.addType("Land");
        landAura.attachToEntity(f.creature, null);
        assertTrue(landAura.isAttachedToEntity(f.creature));
        Card foreign = addCard("Holy Strength", f.player.getOpponents().get(0));
        foreign.attachToEntity(f.creature, null);
        SpellAbility charm = f.die();
        landAura = f.game.getAction().moveToGraveyard(landAura, null);
        foreign = f.game.getAction().moveToGraveyard(foreign, null);
        AbilitySub mode = charm.getAdditionalAbilityList("Choices").get(2);
        assertFalse(mode.canTarget(foreign));
        assertTrue(mode.canTarget(landAura), "Enchant restrictions do not restrict targeting an Aura card");
        choose(charm, 2, landAura);
        f.game.getStack().resolveStack();
        assertTrue(f.game.getCardState(landAura).isInZone(ZoneType.Graveyard));
    }

    @Test
    public void simultaneousDeathsKeepSeparateAttachmentsAndCounterLki() throws IOException {
        Fixture f = fixture(false);
        Card other = addCard("Runeclaw Bear", f.player);
        Card equipment = addCard("Bonesplitter", f.player);
        equipment.attachToEntity(other, null);
        CounterType counters = CounterEnumType.P1P1;
        f.creature.setCounters(counters, 2);
        other.setCounters(counters, 5);
        // Both LKI snapshots precede either zone change, as in a simultaneous death batch.
        Card firstLki = CardCopyService.getLKICopy(f.creature);
        Card otherLki = CardCopyService.getLKICopy(other);
        f.game.getAction().moveToGraveyard(f.creature, null);
        f.game.getAction().moveToGraveyard(other, null);
        SpellAbility first = f.charm(firstLki);
        SpellAbility second = f.charm(otherLki);
        assertTrue(first.getAdditionalAbilityList("Choices").get(1).canTarget(f.first));
        assertFalse(first.getAdditionalAbilityList("Choices").get(1).canTarget(equipment));
        assertTrue(second.getAdditionalAbilityList("Choices").get(1).canTarget(equipment));
        assertFalse(second.getAdditionalAbilityList("Choices").get(1).canTarget(f.first));
        assertEquals(AbilityUtils.calculateAmount(f.alexander, "X", first.getAdditionalAbilityList("Choices").get(0)), 2);
        assertEquals(AbilityUtils.calculateAmount(f.alexander, "X", second.getAdditionalAbilityList("Choices").get(0)), 5);
        choose(first, 0, null);
        choose(second, 0, null);
        f.game.getStack().resolveStack();
        f.game.getStack().resolveStack();
        assertEquals(f.alexander.getCounters(counters), 7);
    }

    @Test(dataProvider = "attachmentModes")
    public void onlyModesWithLegalMandatoryTargetsAreAvailable(boolean aura) throws IOException {
        Fixture f = fixture(aura);
        SpellAbility charm = f.die();
        List<AbilitySub> options = CharmEffect.makePossibleOptions(charm);
        assertEquals(options.size(), 2);
        assertTrue(options.contains(charm.getAdditionalAbilityList("Choices").get(0)));
        assertTrue(options.contains(charm.getAdditionalAbilityList("Choices").get(aura ? 2 : 1)));
        f.game.getAction().exile(f.first, null, AbilityKey.newMap());
        f.game.getAction().exile(f.second, null, AbilityKey.newMap());
        assertEquals(CharmEffect.makePossibleOptions(charm).size(), 1);
    }

    @Test
    public void countersAloneUseLkiAndNeedNoTarget() throws IOException {
        Fixture f = fixture(false);
        f.first.unattachFromEntity(f.creature);
        f.second.unattachFromEntity(f.creature);
        CounterType counters = CounterEnumType.P1P1;
        f.creature.setCounters(counters, 3);
        SpellAbility charm = f.die();
        assertEquals(CharmEffect.makePossibleOptions(charm).size(), 1);
        assertFalse(charm.getAdditionalAbilityList("Choices").get(0).usesTargeting());
        choose(charm, 0, null);
        f.game.getStack().resolveStack();
        assertEquals(f.alexander.getCounters(counters), 3);
    }

    @Test
    public void auraAndCreatureDyingInOneBatchProduceATargetableGraveyardAura() throws IOException {
        Fixture f = fixture(true);
        List<SpellAbility> triggered = captureDeathBatch(f, List.of(f.creature, f.first, f.second));
        assertEquals(triggered.size(), 1);
        Card firstInGraveyard = f.game.getCardState(f.first);
        Card secondInGraveyard = f.game.getCardState(f.second);
        assertTrue(firstInGraveyard.isInZone(ZoneType.Graveyard));
        AbilitySub mode = choose(triggered.get(0), 2, firstInGraveyard);
        assertTrue(mode.canTarget(secondInGraveyard));
        f.game.getStack().resolveStack();
        assertTrue(f.game.getCardState(firstInGraveyard).isAttachedToEntity(f.alexander));
        assertTrue(secondInGraveyard.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void realSimultaneousDeathTriggersDoNotShareLki() throws IOException {
        Fixture f = fixture(false);
        Card other = addCard("Runeclaw Bear", f.player);
        Card otherEquipment = addCard("Bonesplitter", f.player);
        otherEquipment.attachToEntity(other, null);
        f.creature.setCounters(CounterEnumType.P1P1, 2);
        other.setCounters(CounterEnumType.P1P1, 5);
        List<SpellAbility> triggered = captureDeathBatch(f, List.of(f.creature, other));
        assertEquals(triggered.size(), 2);
        for (SpellAbility charm : triggered) {
            Card dead = (Card) charm.getTriggeringObject(AbilityKey.Card);
            AbilitySub equipmentMode = charm.getAdditionalAbilityList("Choices").get(1);
            boolean firstCreature = dead.equals(f.creature);
            assertTrue(equipmentMode.canTarget(firstCreature ? f.first : otherEquipment));
            assertFalse(equipmentMode.canTarget(firstCreature ? otherEquipment : f.first));
            assertEquals(AbilityUtils.calculateAmount(f.alexander, "X",
                    charm.getAdditionalAbilityList("Choices").get(0)), firstCreature ? 2 : 5);
            choose(charm, 0, null);
        }
        f.game.getStack().resolveStack();
        f.game.getStack().resolveStack();
        assertEquals(f.alexander.getCounters(CounterEnumType.P1P1), 7);
    }

    private List<SpellAbility> captureDeathBatch(Fixture f, List<Card> dying) {
        List<SpellAbility> triggered = new ArrayList<>();
        f.player.dangerouslySetController(new PlayerControllerAi(f.game, f.player, f.player.getLobbyPlayer()) {
            @Override
            public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
                // Keep the real wrapped triggers; the test supplies deterministic choices below.
                triggered.addAll(abilities);
            }
        });
        f.game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
        f.game.getTriggerHandler().resetActiveTriggers();
        f.game.copyLastState();
        CardZoneTable batch = new CardZoneTable(f.game.getLastStateBattlefield(), f.game.getLastStateGraveyard());
        Map<AbilityKey, Object> params = AbilityKey.newMap();
        AbilityKey.addCardZoneTableParams(params, batch);
        for (Card card : dying) {
            f.game.getAction().moveToGraveyard(card, null, params);
        }
        batch.triggerChangesZoneAll(f.game, null);
        f.game.getTriggerHandler().runWaitingTriggers();
        f.game.getStack().addAllTriggeredAbilitiesToStack();
        return triggered;
    }

    private AbilitySub choose(SpellAbility charm, int index, Card target) {
        AbilitySub option = charm.getAdditionalAbilityList("Choices").get(index);
        assertTrue(CharmEffect.makePossibleOptions(charm).contains(option));
        Player player = charm.getActivatingPlayer();
        player.runWithController(() -> assertTrue(CharmEffect.makeChoices(charm)),
                new PlayerControllerAi(player.getGame(), player, player.getLobbyPlayer()) {
                    @Override
                    public List<AbilitySub> chooseModeForAbility(SpellAbility ability, List<AbilitySub> possible,
                            int min, int max, boolean repeat) {
                        assertTrue(possible.contains(option));
                        assertEquals(min, 1);
                        assertEquals(max, 1);
                        return new ArrayList<>(List.of(option));
                    }
                });
        AbilitySub chosen = charm.getSubAbility();
        if (target != null) {
            assertTrue(chosen.canTarget(target));
            chosen.getTargets().add(target);
        }
        charm.getHostCard().getGame().getStack().add(charm);
        return chosen;
    }

    private Fixture fixture(boolean aura) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        Path path = Path.of("../forge-gui/res/cardsfolder/custom cards/ERLB/i/Iron_Fist_Alexander.txt");
        CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8));
        Card alexander = createAlexander(rules, player);
        Card creature = addCard("Runeclaw Bear", player);
        Card first = addCard(aura ? "Holy Strength" : "Bonesplitter", player);
        Card second = addCard(aura ? "Holy Strength" : "Bonesplitter", player);
        first.attachToEntity(creature, null);
        second.attachToEntity(creature, null);
        assertTrue(first.isAttachedToEntity(creature));
        assertTrue(second.isAttachedToEntity(creature));
        return new Fixture(game, player, alexander, creature, first, second, aura);
    }

    private Card createAlexander(CardRules rules, Player player) {
        Card card = CardFactory.getCard(new PaperCard(rules, "ERLB", CardRarity.Rare),
                player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static final class Fixture {
        final Game game;
        final Player player;
        final Card alexander;
        final Card creature;
        final boolean aura;
        Card first;
        Card second;

        Fixture(Game game, Player player, Card alexander, Card creature, Card first, Card second, boolean aura) {
            this.game = game;
            this.player = player;
            this.alexander = alexander;
            this.creature = creature;
            this.first = first;
            this.second = second;
            this.aura = aura;
        }

        SpellAbility die() {
            Card lki = CardCopyService.getLKICopy(creature);
            game.getAction().moveToGraveyard(creature, null);
            if (aura) {
                // Auras reach the graveyard before modes/targets are chosen; use pre-death attachments.
                first = game.getAction().moveToGraveyard(first, null);
                second = game.getAction().moveToGraveyard(second, null);
            }
            return charm(lki);
        }

        SpellAbility charm(Card lki) {
            Trigger trigger = alexander.getTriggers().get(0);
            // Match TriggerHandler: clone the ability built with the card, then supply event context.
            SpellAbility charm = trigger.getOverridingAbility().copy(alexander, player, false, true);
            charm.setTrigger(trigger);
            Map<AbilityKey, Object> params = AbilityKey.mapFromCard(game.getCardState(lki));
            params.put(AbilityKey.CardLKI, lki);
            trigger.setTriggeringObjects(charm, params);
            return charm;
        }
    }
}
