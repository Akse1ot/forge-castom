package forge.game.ability.effects;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TriggerSagaChaptersTest extends AITest {
    private Card scripted(final Player p, final List<String> lines) {
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(lines), "TEST", CardRarity.Rare), p);
        card.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private Card annaliese(final Player p) throws Exception {
        return scripted(p, Files.readAllLines(Path.of(
                "../forge-gui/res/cardsfolder/custom cards/a/annaliese_creator_of_the_trinity.txt")));
    }

    private Card saga(final Player p, final String name, final String chapters, final String... extra) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:1 W", "Types:Enchantment Saga",
                "K:Chapter:" + chapters,
                "SVar:One:DB$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.",
                "SVar:Two:DB$ GainLife | Defined$ You | LifeAmount$ 2 | SpellDescription$ Gain 2 life.",
                "SVar:Final:DB$ GainLife | Defined$ You | LifeAmount$ 10 | SpellDescription$ Gain 10 life.",
                "Oracle:Test Saga."));
        lines.addAll(List.of(extra));
        return scripted(p, lines);
    }

    private Trigger chapter(final Card card, final int number) {
        return card.getTriggers().stream().filter(t -> t.isChapter() && t.getChapter() == number)
                .findFirst().orElseThrow();
    }

    private void flush(final Game game) {
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private SpellAbility effect(final Card host, final Player p, final Card source) {
        SpellAbility sa = AbilityFactory.getAbility("DB$ TriggerSagaChapters", host);
        sa.setActivatingPlayer(p);
        if (source != null) {
            sa.setTriggeringObject(AbilityKey.Source, source);
        }
        return sa;
    }

    private void triggerFinal(final Game game, final Card source) {
        source.setCounters(CounterEnumType.LORE, source.getCurrentState().getFinalChapterNr() - 1);
        game.getTriggerHandler().resetActiveTriggers();
        SpellAbility counter = AbilityFactory.getAbility(
                "DB$ PutCounter | Defined$ Self | CounterType$ LORE | CounterNum$ 1", source);
        counter.setActivatingPlayer(source.getController());
        AbilityUtils.resolve(counter);
        game.getStack().addAllTriggeredAbilitiesToStack();
        Assert.assertTrue(game.getStack().peekAbility().isLastChapter());
        game.getStack().resolveStack();
        flush(game);
    }

    @Test
    public void finalResolutionQueuesAnnalieseThenTwoChaptersWithoutCountersOrSacrifice() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        annaliese(p);
        Card source = saga(p, "Source Saga", "3:One,Two,Final");
        Card chosen = saga(p, "Chosen Saga", "3:One,Two,Final", "K:Hexproof", "K:Shroud", "K:Ward:2");
        chosen.setCounters(CounterEnumType.LORE, 1);
        triggerFinal(game, source);
        Assert.assertTrue(source.isInZone(ZoneType.Graveyard));
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertEquals(game.getStack().peekAbility().getHostCard().getName(), "Annaliese, Creator of the Trinity");
        game.getStack().resolveStack();
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        int life = p.getLife();
        List<Integer> chapters = new ArrayList<>();
        while (!game.getStack().isEmpty()) {
            SpellAbility top = game.getStack().peekAbility();
            Assert.assertSame(top.getHostCard(), chosen);
            Assert.assertSame(top.getActivatingPlayer(), p);
            Assert.assertFalse(top.isLastChapter());
            chapters.add(top.getChapter());
            int gain = top.getChapter();
            game.getStack().resolveStack();
            life += gain;
            Assert.assertEquals(p.getLife(), life);
            flush(game);
        }
        Assert.assertTrue(chapters.containsAll(List.of(1, 2)));
        Assert.assertEquals(chosen.getCounters(CounterEnumType.LORE), 1);
        Assert.assertTrue(chosen.isInPlay());
    }

    @Test
    public void excludesSourceAndOpponentSagas() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card source = saga(p, "Only Own Saga", "3:One,Two,Final");
        saga(game.getPlayers().get(1), "Opponent Saga", "3:One,Two,Final");
        AbilityUtils.resolve(effect(host, p, source));
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void finalOnlySagaHasNothingToTrigger() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        saga(p, "Final Only", "1:Final");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void chosenSagaRemovedInResponseIsNotAvailableOnResolution() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        annaliese(p);
        Card source = saga(p, "Source Saga", "3:One,Two,Final");
        Card other = saga(p, "Other Saga", "3:One,Two,Final");
        triggerFinal(game, source);
        game.getAction().moveToGraveyard(other, null);
        game.getStack().resolveStack();
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void combinedChaptersEachTriggerOnceIncludingWhenFinalSharesText() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card combined = saga(p, "Combined Saga", "3:One,One,One");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        int life = p.getLife();
        for (int i = 0; i < 2; i++) {
            Assert.assertFalse(game.getStack().peekAbility().isLastChapter());
            game.getStack().resolveStack();
        }
        Assert.assertEquals(p.getLife(), life + 2);
        Assert.assertEquals(combined.getCounters(CounterEnumType.LORE), 0);
    }

    @Test
    public void chapterTargetsChosenByNormalAiStackProcessing() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        Card host = annaliese(p);
        Card target = addCard("Grizzly Bears", opponent);
        saga(p, "Targeted Saga", "3:Kill,Two,Final",
                "SVar:Kill:DB$ Destroy | ValidTgts$ Creature.OppCtrl | TgtPrompt$ Choose a creature | SpellDescription$ Destroy target creature an opponent controls.");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        boolean targeted = false;
        while (!game.getStack().isEmpty()) {
            SpellAbility top = game.getStack().peekAbility();
            if (top.usesTargeting()) {
                Assert.assertTrue(top.getTargets().getTargetCards().contains(target));
                targeted = true;
            }
            game.getStack().resolveStack();
            flush(game);
        }
        Assert.assertTrue(targeted);
        Assert.assertTrue(target.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void chapterWithoutLegalTargetDoesNotBlockOtherChapter() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        saga(p, "No Targets", "3:Kill,Two,Final",
                "SVar:Kill:DB$ Destroy | ValidTgts$ Creature.OppCtrl | TgtPrompt$ Choose a creature | SpellDescription$ Destroy target creature an opponent controls.");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertEquals(game.getStack().peekAbility().getChapter(), 2);
    }

    @Test
    public void ordinaryLoreCounterTriggersOnlyItsChapterWithoutAnnaliese() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card saga = saga(p, "Ordinary Saga", "3:One,Two,Final");
        game.getTriggerHandler().resetActiveTriggers();
        SpellAbility counter = AbilityFactory.getAbility("DB$ PutCounter | Defined$ Self | CounterType$ LORE | CounterNum$ 1", saga);
        counter.setActivatingPlayer(p);
        AbilityUtils.resolve(counter);
        flush(game);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertEquals(game.getStack().peekAbility().getChapter(), 1);
        game.getStack().resolveStack();
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertEquals(saga.getCounters(CounterEnumType.LORE), 1);
    }

    @Test
    public void multipleFinalResolutionsProduceSeparateAnnalieseTriggers() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        annaliese(p);
        Card source = saga(p, "Repeat Source", "3:One,Two,Final");
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().triggerChapter(chapter(source, 3));
        game.getTriggerHandler().triggerChapter(chapter(source, 3));
        flush(game);
        game.getStack().resolveStack();
        game.getStack().resolveStack();
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        for (var entry : game.getStack()) {
            Assert.assertEquals(entry.getSpellAbility().getHostCard().getName(), "Annaliese, Creator of the Trinity");
        }
    }

    @Test
    public void readAheadStillPreventsSkippedChapterOnEntryTurn() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card readAhead = saga(p, "Read Ahead Saga", "3:One,Two,Final", "K:Read ahead");
        readAhead.setCounters(CounterEnumType.LORE, 2);
        readAhead.setTurnInZone(game.getPhaseHandler().getTurn());
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertEquals(game.getStack().peekAbility().getChapter(), 2);
    }

    @Test
    public void extraTriggerEffectsApplyToForcedChapters() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        saga(p, "Doubled Saga", "3:One,Two,Final");
        Card doubler = scripted(p, List.of("Name:Chapter Doubler", "ManaCost:1", "Types:Artifact",
                "S:Mode$ Panharmonicon | ValidCard$ Saga.YouCtrl | Description$ Saga abilities trigger an additional time.",
                "Oracle:Test doubler."));
        Assert.assertTrue(doubler.isInPlay());
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 4);
    }

    @Test
    public void copiedSagaUsesItsOwnSourceAndController() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card original = saga(p, "Copy Source", "3:One,Two,Final");
        SpellAbility copy = AbilityFactory.getAbility("DB$ CopyPermanent | Defined$ Remembered", host);
        host.addRemembered(original);
        copy.setActivatingPlayer(p);
        AbilityUtils.resolve(copy);
        game.getStack().clear();
        game.getStack().clearSimultaneousStack();
        Card token = p.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).findFirst().orElseThrow();
        AbilityUtils.resolve(effect(host, p, original));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        Assert.assertSame(game.getStack().peekAbility().getHostCard(), token);
    }

    @Test
    public void originalHistoryOfBenaliaCreatesTwoKnights() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card history = addCard("History of Benalia", p);
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        game.getStack().resolveStack();
        game.getStack().resolveStack();
        Assert.assertEquals(p.getCreaturesInPlay().stream().filter(c -> c.getType().hasSubtype("Knight")).count(), 2L);
        Assert.assertEquals(history.getCounters(CounterEnumType.LORE), 0);
        Assert.assertTrue(history.isInPlay());
    }

    @Test
    public void nonFinalChapterDoesNotTriggerAnnaliese() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        annaliese(p);
        Card source = saga(p, "Nonfinal Source", "3:One,Two,Final");
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().triggerChapter(chapter(source, 1));
        flush(game);
        game.getStack().resolveStack();
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void chaptersRemainOnStackAfterChosenSagaLeaves() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card chosen = saga(p, "Departing Saga", "3:One,Two,Final");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        game.getAction().moveToGraveyard(chosen, null);
        int life = p.getLife();
        game.getStack().resolveStack();
        game.getStack().resolveStack();
        Assert.assertEquals(p.getLife(), life + 3);
    }

    @Test
    public void readAheadDoesNotRestrictChaptersOnLaterTurns() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card chosen = saga(p, "Older Read Ahead Saga", "3:One,Two,Final", "K:Read ahead");
        chosen.setTurnInZone(game.getPhaseHandler().getTurn() - 1);
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
    }

    @Test
    public void aiChoosesSagaWithNonFinalChaptersOverFinalOnlySaga() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        saga(p, "Final Only Choice", "1:Final");
        Card useful = saga(p, "Useful Choice", "3:One,Two,Final");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 2);
        Assert.assertSame(game.getStack().peekAbility().getHostCard(), useful);
    }

    @Test
    public void chapterWhoseTargetLeavesInResponseFizzlesNormally() throws Exception {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card host = annaliese(p);
        Card target = addCard("Grizzly Bears", game.getPlayers().get(1));
        saga(p, "Response Saga", "2:Kill,Final",
                "SVar:Kill:DB$ Destroy | ValidTgts$ Creature.OppCtrl | TgtPrompt$ Choose a creature | SpellDescription$ Destroy target creature an opponent controls.");
        AbilityUtils.resolve(effect(host, p, null));
        flush(game);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertTrue(game.getStack().peekAbility().getTargets().getTargetCards().contains(target));
        game.getAction().moveToHand(target, null);
        game.getStack().resolveStack();
        flush(game);
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertTrue(target.isInZone(ZoneType.Hand));
    }
}
