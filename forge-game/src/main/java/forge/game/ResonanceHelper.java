package forge.game;

import com.google.common.collect.Maps;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.*;
import forge.game.zone.ZoneType;
import forge.game.ability.*;
import forge.game.ability.effects.*;
import forge.util.*;

import java.util.*;

public final class ResonanceHelper {

    private ResonanceHelper() {}

    /**
     * MAIN MERGE LOGIC — вызывается ТОЛЬКО из MagicStack.add(),
     * строго перед hasLegalTargeting(sp).
     */
    public static void maybeMerge(Game game, Player activator, SpellAbility root) {

        // --- FILTERING -------------------------------------------------------
        if (root == null || !root.isSpell())
            return;

        Card host = root.getHostCard();
        if (host == null)
            return;

        if (!host.isInstant() && !host.isSorcery())
            return;

        if (activator == null)
            return;

        List<Card> pending = new ArrayList<>(activator.getPendingResonance());
        if (pending.isEmpty())
            return;

        System.out.println("[Resonance] Checking pending merge for " + host.getName());

        // --- MERGE LOOP ------------------------------------------------------
        for (Card resCard : pending) {

            // A перестала быть в изгнании?
            if (!resCard.isInZone(ZoneType.Exile)) {
                activator.removePendingResonance(resCard);
                continue;
            }

            // SpellAbility A
            SpellAbility saA = resCard.getFirstSpellAbility();
            if (saA == null)
                continue;

            // --- ASK PLAYER: apply? -----------------------------------------
            boolean apply = activator.getController().confirmAction(
                    root,
                    PlayerActionConfirmMode.OptionalChoose,
                    "Apply Resonance from " + resCard.getName() + "?",
                    Collections.emptyList(),
                    resCard,
                    null
            );

            if (!apply) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            // --- STEP 1: COPY TARGETING FROM A TO ROOT -----------------------
            TargetRestrictions trA = saA.getTargetRestrictions();
            if (trA != null) {

                // 1. Устанавливаем форму таргетинга
                root.setTargetRestrictions(trA);

                // 2. Переносим ValidTgts (обязательно)
                Map<String, String> mapA = saA.getMapParams();
                if (mapA != null && mapA.containsKey("ValidTgts")) {
                    root.getMapParams().put("ValidTgts", mapA.get("ValidTgts"));
                }

                // 3. Обнуляем выбор целей, чтобы движок запросил их заново
                //    Это КЛЮЧЕВОЙ шаг, который делает B таргетным даже если он был нетаргетным.
                root.clearTargets();
            }

            // --- STEP 2: BUILD SUBABILITY LIKE SPLICE ------------------------
            Map<String, String> params = Maps.newHashMap();
            if (saA.getMapParams() != null) {
                params.putAll(saA.getMapParams());
            }

            // SubAbility не должна иметь своих ValidTgts
            params.remove("ValidTgts");

            // Как Splice → указываем, что цель подспособности = Targeted root'а
            params.put("Defined", "Targeted");

            ApiType api = saA.getApi();

            // SubAbility без targetRestrictions
            AbilitySub sub = new AbilitySub(api, host, null, params);
            sub.setActivatingPlayer(activator);
            sub.setHostCard(host);

            root.appendSubAbility(sub);

            // --- STEP 3: UPDATE STACK DESCRIPTION ----------------------------
            root.setStackDescription(root.toUnsuppressedString());

            // --- STEP 4: MOVE A → GY -----------------------------------------
            activator.removePendingResonance(resCard);
            game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);

            System.out.println("[Resonance] Applied " + resCard.getName() + " to " + host.getName());
        }
    }
}
