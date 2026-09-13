package forge.game.ability.effects;

import com.google.common.collect.Table;
import forge.StaticData;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.card.CardZoneTable;
import forge.game.card.TokenCreateTable;
import forge.game.event.GameEventTokenCreated;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class SubjugateEffect extends SpellAbilityEffect {

    private static final String NIGHTMARE_NAME = "Vicious Nightmare";

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        final int amount = AbilityUtils.calculateAmount(
                sa.getHostCard(),
                sa.getParamOrDefault("Amount", "1"),
                sa
        );

        return "Subjugate " + amount + ".";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Game game = host.getGame();

        Player subjugator = sa.getActivatingPlayer();
        if (subjugator == null) {
            subjugator = host.getController();
        }

        final int amount = AbilityUtils.calculateAmount(
                host,
                sa.getParamOrDefault("Amount", "1"),
                sa
        );

        if (amount <= 0) {
            return;
        }

        final PaperCard nightmarePaper =
                StaticData.instance()
                        .getCommonCards()
                        .getUniqueByName(NIGHTMARE_NAME);

        if (nightmarePaper == null) {
            throw new RuntimeException(
                    "SubjugateEffect didn't find card: " + NIGHTMARE_NAME
            );
        }

        final PlayerCollection opponents =
                AbilityUtils.getDefinedPlayers(host, "Opponents", sa);

        /*
         * TokenCreateTable row = creator.
         *
         * The prototype owner/controller initially identifies
         * the opponent who will receive the persistent token card.
         *
         * creator   = player performing Subjugate
         * recipient = opponent
         * owner     = recipient
         */
        TokenCreateTable tokenTable = new TokenCreateTable();

        for (final Player opponent : opponents) {
            if (!opponent.isInGame()) {
                continue;
            }

            final Card prototype =
                    Card.fromPaperCard(nightmarePaper, opponent);

            /*
             * The prototype must match token predicates and participate
             * in CreateToken replacements, but it must not be a real
             * GamePieceType.TOKEN because the resulting card has to
             * survive in Library, Hand and Graveyard.
             */
            prototype.setTokenCard(true);
            prototype.setTokenSpawningAbility(sa);

            tokenTable.put(subjugator, prototype, amount);
        }

        /*
         * Run the normal CreateToken replacement pipeline before
         * materializing the persistent token cards.
         *
         * There is one original creator: the player performing Subjugate.
         */
        if (!tokenTable.isEmpty()) {
            final Map<AbilityKey, Object> repParams =
                    AbilityKey.mapFromAffected(subjugator);

            repParams.put(AbilityKey.Token, tokenTable);
            repParams.put(AbilityKey.Cause, sa);
            repParams.put(AbilityKey.EffectOnly, true);

            final ReplacementResult result =
                    game.getReplacementHandler()
                            .run(ReplacementType.CreateToken, repParams);

            if (result == ReplacementResult.Updated) {
                tokenTable =
                        (TokenCreateTable) repParams.get(AbilityKey.Token);
            } else if (result != ReplacementResult.NotReplaced) {
                tokenTable.clear();
            }
        }

        final CardZoneTable triggerList =
                CardZoneTable.getSimultaneousInstance(sa);

        final Map<AbilityKey, Object> moveParams =
                AbilityKey.newMap();

        moveParams.put(
                AbilityKey.LastStateBattlefield,
                triggerList.getLastStateBattlefield()
        );

        moveParams.put(
                AbilityKey.LastStateGraveyard,
                triggerList.getLastStateGraveyard()
        );

        final CardCollection createdCards = new CardCollection();

        /*
         * IMPORTANT:
         * A library is added here only after a created card actually
         * finishes in that Library. This prevents false shuffle events
         * if CreateToken is prevented or a Moved replacement redirects
         * the card somewhere else.
         */
        final Set<Player> librariesToShuffle =
                new LinkedHashSet<>();

        final long timestamp = game.getNextTimestamp();

        /*
         * Materialize the final prototypes AFTER CreateToken
         * replacement effects have modified the TokenCreateTable.
         */
        for (final Table.Cell<Player, Card, Integer> cell
                : tokenTable.cellSet()) {

            final Player creator = cell.getRowKey();
            final Card prototype = cell.getColumnKey();
            final int count = cell.getValue();

            if (creator == null
                    || !creator.isInGame()
                    || prototype == null
                    || count <= 0) {
                continue;
            }

            /*
             * Token replacements express the resulting controller
             * through prototype.getController().
             *
             * For Subjugate that player becomes the owner/recipient
             * of the persistent token card.
             */
            final Player recipient = prototype.getController();

            if (recipient == null || !recipient.isInGame()) {
                continue;
            }

            for (int i = 0; i < count; i++) {
                final Card created =
                        new CardCopyService(prototype).copyCard(true);

                /*
                 * Disconnect copied states from the prototype in the
                 * same way TokenEffectBase does for ordinary tokens.
                 */
                created.getStates().forEach(
                        cs -> created.getState(cs)
                                .resetOriginalHost(prototype)
                );

                created.setOwner(recipient);
                created.setController(recipient, timestamp);
                created.setGameTimestamp(timestamp);

                /*
                 * Critical Subjugate distinction:
                 *
                 * CARD      -> may exist in normal zones.
                 * TokenCard -> still counts as a token-card for
                 *              token predicates and mechanics.
                 */
                created.setGamePieceType(GamePieceType.CARD);
                created.setTokenCard(true);
                created.setTokenSpawningAbility(sa);

                /*
                 * First introduce the generated card into ZoneType.None,
                 * matching Forge's MakeCard pipeline for generated cards.
                 */
                game.getAction().moveTo(
                        ZoneType.None,
                        created,
                        sa,
                        moveParams
                );

                /*
                 * Then attempt to put it into its owner's Library.
                 *
                 * Normal Moved replacement effects are intentionally
                 * allowed to operate on this transition.
                 */
                final Card made = game.getAction().moveTo(
                        ZoneType.Library,
                        created,
                        0,
                        sa,
                        moveParams
                );

                if (made == null || made.getZone() == null) {
                    continue;
                }

                /*
                 * CardCopyService recreates an ordinary PaperCard during
                 * a zone change. tokenCard survives that copy, but
                 * tokenSpawningAbility does not, so restore it here.
                 */
                made.setTokenSpawningAbility(sa);

                /*
                 * Register the actual final destination. A Moved
                 * replacement may have redirected Library elsewhere.
                 */
                triggerList.put(
                        ZoneType.None,
                        made.getZone().getZoneType(),
                        made
                );

                /*
                 * TokenCreated uses an LKI object in the normal Forge
                 * token pipeline as well.
                 */
                final Card lki =
                        CardCopyService.getLKICopy(made);

                final boolean firstToken =
                        creator.getNumTokenCreatedThisTurn() == 0;

                /*
                 * Explicit creator is mandatory here because owner is
                 * the recipient, not the player performing Subjugate.
                 */
                triggerList.addToken(
                        lki,
                        creator,
                        firstToken
                );

                creator.addTokensCreatedThisTurn(lki);
                createdCards.add(made);

                /*
                 * Shuffle only a library which actually received one
                 * of the resulting token cards.
                 *
                 * Do NOT register recipients before replacements.
                 */
                if (made.isInZone(ZoneType.Library)) {
                    librariesToShuffle.add(made.getOwner());
                }
            }
        }

        /*
         * CardZoneTable fires TokenCreatedOnce and then ChangesZoneAll.
         */
        triggerList.triggerChangesZoneAll(game, sa);

        /*
         * Mirror the normal TokenEffect notification. This is a game
         * event rather than TriggerType.TokenCreated itself.
         */
        game.fireEvent(new GameEventTokenCreated());

        /*
         * Each affected library is shuffled exactly once, regardless
         * of how many Vicious Nightmares were inserted into it.
         */
        for (final Player player : librariesToShuffle) {
            if (player.isInGame()) {
                player.shuffle(sa);
            }
        }

        /*
         * One "Subjugate N" action = one Subjugated event.
         *
         * Amount remains the printed/calculated N; token-doubling
         * replacement effects do not change the value of Subjugate.
         */
        subjugator.addSubjugatedThisTurn(
                amount,
                createdCards,
                sa
        );
    }
}