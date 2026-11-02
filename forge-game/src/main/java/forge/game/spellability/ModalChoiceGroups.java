package forge.game.spellability;

import forge.game.IEntityMap;
import forge.game.ability.AbilityKey;
import forge.game.ability.effects.CharmEffect;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;

import java.util.ArrayList;
import java.util.List;

/** Opt-in two-group charm: its caster chooses one mode, then one opponent chooses one. */
public final class ModalChoiceGroups {
    public static final String PARAM = "ModalChoiceGroups";
    private ModalChoiceGroups() { }

    private static int firstSize(final SpellAbility sa) {
        String[] sizes = sa.getParam(PARAM).split(",");
        if (sizes.length != 2) {
            throw new IllegalArgumentException("ModalChoiceGroups requires exactly two groups");
        }
        int first = Integer.parseInt(sizes[0].trim()), second = Integer.parseInt(sizes[1].trim());
        if (first < 1 || second < 1 || first + second != sa.getAdditionalAbilityList("Choices").size()) {
            throw new IllegalArgumentException("ModalChoiceGroups sizes do not match Choices");
        }
        return first;
    }

    public static List<AbilitySub> firstGroupOptions(final SpellAbility sa, final List<AbilitySub> legal) {
        int first = firstSize(sa);
        List<AbilitySub> all = sa.getAdditionalAbilityList("Choices");
        return legal.stream().filter(mode -> all.indexOf(mode) >= 0 && all.indexOf(mode) < first).toList();
    }

    private static AbilitySub choose(final SpellAbility sa, final Player chooser, final List<AbilitySub> offered) {
        if (offered.isEmpty()) {
            return null;
        }
        // Existing controllers expect a complete one-group Charm, including their AI's cached choice.
        SpellAbility view = sa.copy(sa.getHostCard(), sa.getActivatingPlayer(), false, true);
        view.removeParam(PARAM);
        view.getOriginalMapParams().remove(PARAM);
        view.setSubAbility(null);
        view.setChosenList(null);
        List<AbilitySub> options = new ArrayList<>();
        for (AbilitySub mode : offered) {
            AbilitySub option = (AbilitySub) mode.copy(sa.getActivatingPlayer());
            option.setParent(view);
            options.add(option);
        }
        view.setAdditionalAbilityList("Choices", options);
        List<AbilitySub> selected = chooser.getController().chooseModeForAbility(view, options, 1, 1, false);
        if (selected == null || selected.size() != 1 || !options.contains(selected.get(0))) {
            return null;
        }
        return offered.get(options.indexOf(selected.get(0)));
    }

    public static boolean makeChoices(final SpellAbility sa) {
        sa.setSubAbility(null);
        sa.setChosenList(null);
        sa.setChoosingPlayer(null);
        List<AbilitySub> legal = CharmEffect.makePossibleOptions(sa);
        List<AbilitySub> first = firstGroupOptions(sa, legal);
        AbilitySub own = choose(sa, sa.getActivatingPlayer(), first);
        if (own == null || sa.getActivatingPlayer().getOpponents().isEmpty()) {
            return false;
        }
        Player opponent = sa.getActivatingPlayer().getController().chooseSingleEntityForEffect(
                sa.getActivatingPlayer().getOpponents(), sa, "Choose an opponent for the second modal group", null);
        if (opponent == null || !sa.getActivatingPlayer().getOpponents().contains(opponent)) {
            return false;
        }
        sa.setChoosingPlayer(opponent);
        List<AbilitySub> second = new ArrayList<>(legal);
        second.removeAll(first);
        AbilitySub other = choose(sa, opponent, second);
        if (other == null) {
            sa.setChoosingPlayer(null);
            return false;
        }
        List<AbilitySub> chosen = new ArrayList<>(List.of(own, other));
        List<AbilitySub> all = sa.getAdditionalAbilityList("Choices");
        chosen.forEach(mode -> mode.setSVar("CharmOrder", Integer.toString(all.indexOf(mode) + 1)));
        sa.setChosenList(new ArrayList<>(chosen));
        CharmEffect.chainAbilities(sa, chosen);
        for (Player chooser : List.of(sa.getActivatingPlayer(), opponent)) {
            chooser.getGame().getTriggerHandler().runTrigger(TriggerType.FacesDilemma, AbilityKey.mapFromPlayer(chooser), false);
        }
        return true;
    }

    public static String description(final SpellAbility sa, final boolean includeModes) {
        int split = firstSize(sa);
        StringBuilder text = new StringBuilder(sa.getCostDescription()).append("Choose one");
        if (!includeModes) {
            return text.append(" from the first group. An opponent chooses one from the second group.").toString();
        }
        List<AbilitySub> all = sa.getAdditionalAbilityList("Choices");
        text.append(" —\r\n");
        for (int i = 0; i < all.size(); i++) {
            if (i == split) {
                text.append("An opponent chooses one —\r\n");
            }
            text.append("• ").append(all.get(i).getParam("SpellDescription")).append("\r\n");
        }
        return text.toString();
    }

    /** Rebuild only opted-in modal choices; ordinary charms retain their existing copy paths. */
    public static void copyChoices(final SpellAbility source, final SpellAbility destination, final IEntityMap map) {
        if (!source.hasParam(PARAM) || source.getChosenList() == null) {
            return;
        }
        destination.setChoosingPlayer(source.getChoosingPlayer() == null ? null : map.map(source.getChoosingPlayer()));
        List<AbilitySub> chosen = new ArrayList<>();
        for (AbilitySub original : source.getChosenList()) {
            AbilitySub mode = destination.getAdditionalAbilityList("Choices").stream()
                    .filter(candidate -> candidate.getOriginalMapParams().equals(original.getOriginalMapParams()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Cannot map grouped modal choice"));
            mode.setSVar("CharmOrder", original.getSVar("CharmOrder"));
            chosen.add(mode);
        }
        destination.setChosenList(new ArrayList<>(chosen));
        destination.setSubAbility(null);
        CharmEffect.chainAbilities(destination, chosen);
        for (SpellAbility a = source, b = destination; a != null && b != null; a = a.getSubAbility(), b = b.getSubAbility()) {
            if (a.usesTargeting()) {
                b.setTargets(a.getTargets().copyMapped(map::map));
            }
        }
    }
}
