# Проверенные правила скриптов Forge

Статус: **проверено 02.11.2026**. Локальная ветка `forge-custom-mechanics`, HEAD `6e72a0c5993`, с существующими незакоммиченными расширениями. Подтверждение: чтение указанных исходников и адресные игровые/граничные сценарии. Это справочник, а не неизменяемая спецификация всех версий Forge. Пути ниже относительно корня репозитория; постоянных зависимостей от временных аудитов нет.

## 1. Counter: собственный словарь Destination

- `Counter` использует `Graveyard`, `Exile`, `Hand`, `Battlefield`, `TopOfLibrary`, `BottomOfLibrary` (проверять текущие ветви в resolve). `Destination$ Library` здесь недопустим; `LibraryPosition$ -1` его не исправляет.
- Для `ChangeZone` направление в библиотеку задаётся иначе: `Destination$ Library | LibraryPosition$ -1`.
- При Counter проверять реальное разрешение и случай заклинания, которое нельзя отменить. Только успешно отменённое заклинание должно перемещаться этим эффектом.

```text
# Неправильно для Counter:
A:SP$ Counter | TargetType$ Spell | ValidTgts$ Card | Destination$ Library | LibraryPosition$ -1
# Правильно:
A:SP$ Counter | TargetType$ Spell | ValidTgts$ Card | Destination$ BottomOfLibrary
```

Источник: `forge-game/src/main/java/forge/game/ability/effects/CounterEffect.java`, ветвление destination после проверки counterability. Пример: `forge-gui/res/cardsfolder/s/spell_crumple.txt`; у него Counter направляет цель через BottomOfLibrary, а отдельный ChangeZone перемещает сам Spell Crumple через Library/LibraryPosition.

## 2. Зоны: TriggerZones, ActiveZones и ActivationZone

- `TriggerZones` ограничивает существование обычного trigger; `ActiveZones` относится к replacement; `ActivationZone` — к активируемой способности. Они не взаимозаменяемы.
- Отсутствие TriggerZones не означает Battlefield по умолчанию: `Trigger` задаёт зоны, только когда параметр присутствует; `TriggerHandler.isTriggerActive` вызывает zonesCheck. Для upkeep постоянного артефакта/чар по Oracle нужна Battlefield.
- Не добавлять Battlefield всем T автоматически. Собственный ETB, LTB с lookback, graveyard/cast/delayed triggers требуют разного анализа. У собственного события Card.Self часто уже есть естественное ограничение; у внешнего Phase такого ограничения нет.
- Сопоставлять не только положительный случай, но и неактивную зону.

Пример: `forge-gui/res/cardsfolder/p/phyrexian_arena.txt`: `Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield`.
Источники: `forge-game/src/main/java/forge/game/trigger/Trigger.java` (конструктор), `TriggerHandler.java:isTriggerActive`, `forge-game/src/main/java/forge/game/TriggerReplacementBase.java:zonesCheck`. Пример корректного lookback без механического добавления зоны: `forge-gui/res/cardsfolder/c/casting_of_bones.txt`.

## 3. Условие: активация, intervening if или разрешение

- `CheckSVar`/`SVarCompare` в AB — ограничения активации в `SpellAbilityRestriction`; не заменять их Condition-параметрами, если Oracle запрещает саму активацию.
- У T `CheckSVar`/`IsPresent` участвуют в requirementsCheck. `WrappedAbility.resolve` обычно повторяет эти требования: это подходит intervening if, проверяемому при trigger и при разрешении. Исключения вроде NoResolvingCheck требуют отдельного обоснования, а не копирования из соседней карты.
- «When …, [effect] unless …» не равно «When …, if …, [effect]». Для первого trigger возникает независимо от условия; условие эффекта проверять в DB через `ConditionCheckSVar`/`ConditionSVarCompare` (или подходящий стандартный API), сохраняя Oracle.
- Не предполагать, что ложное условие DB отменяет всю последующую цепочку: `AbilityUtils.resolveApiAbility` пропускает resolve этой способности, затем вызывает resolveSubAbilities. Cleanup может и должен выполняться после пропущенного условного действия.

Пример разрешаемого условия: `forge-gui/res/cardsfolder/l/lord_of_the_pit.txt`, DBDamage использует `ConditionCheckSVar$ X | ConditionSVarCompare$ LT1` после попытки жертвы.
Источники: `forge-game/src/main/java/forge/game/CardTraitBase.java:meetsCommonRequirements`; `spellability/SpellAbilityRestriction.java:setRestrictions`; `spellability/SpellAbilityCondition.java:setConditions`; `trigger/WrappedAbility.java:resolve`; `ability/AbilityUtils.java:resolveApiAbility`.
Правила: [официальные CR, 603.4 и 608.2](https://media.wizards.com/2026/downloads/MagicCompRules%2020260925.txt). Нумерацию сверять при обновлении правил.

## 4. ChangeZone: неявный shuffle и его исполнитель

- Обычный hidden-origin поиск в библиотеке через ChangeZone обычно сам вызывает shuffle. Перед добавлением отдельного Shuffle или второго ChangeZone с Shuffle проверить всю цепочку, а не только явные строки.
- Если Oracle велит сначала найти/переместить карту и лишь позднее перемешать, использовать поддерживаемый `NoShuffle$ True` на поиске и единственное явное перемешивание в нужном месте.
- `ChangeZone | Destination$ Library | Shuffle$ True` в known-origin пути выбирает владельцев перемещаемых карт (либо activator, если список пуст). Это не всегда тот, кто должен перемешать библиотеку по Oracle. Для явного «your library» предпочтительно отделить перемещение (`Shuffle$ False`) и `DB$ Shuffle | Defined$ You`, когда это соответствует тексту.
- Нельзя считать лишнее перемешивание безвредным: есть отдельные shuffle-события/триггеры. Проверять также пустую библиотеку, отсутствующий источник и различие owner/controller, если они существенны.

Пример: `forge-gui/res/cardsfolder/g/gamble.txt`: ChangeZone с NoShuffle, затем случайный Discard, затем DB Shuffle Defined You. Сложный owner-пример: `forge-gui/res/cardsfolder/e/elixir_of_immortality.txt` явно разделяет перемещения и shuffle владельцев.
Источники: `forge-game/src/main/java/forge/game/ability/effects/ChangeZoneEffect.java:changeHiddenOriginResolve,changeKnownOriginResolve`; `forge-game/src/main/java/forge/game/player/Player.java:shuffle`; `ability/effects/ShuffleEffect.java`. Правила: официальные CR 701.24, а не старый номер 701.20.

## 5. ChangeZone: Origin и зоны целей

- `ChangeZoneEffect.getStackDescription` вызывает `AbilityFactory.adjustChangeZoneTarget`: при Origin и карточных целях зона целей выводится из Origin. Отсутствие явного TgtZone само по себе не доказывает ошибку graveyard-target.
- Для смешанных зон, игроков, карточного поиска и Defined проверить выбранный путь API. Hidden переводит ChangeZone в hidden-origin resolve; это не универсальная подсказка для остальных API.
- AtRandom должен реально выбирать из допустимого множества; проверять непустой/пустой пул и не превращать random в выбор игрока.

Пример целевого возврата: `forge-gui/res/cardsfolder/r/revolutionist.txt`. Источники: `forge-game/src/main/java/forge/game/ability/AbilityFactory.java:adjustChangeZoneTarget`; `ability/effects/ChangeZoneEffect.java:getStackDescription,resolve`; `spellability/SpellAbility.java:isHidden`.

## 6. LTB, attachments и LKI

- Для ChangesZone с Origin Battlefield текущий `TriggerChangesZone.performTest` проверяет ValidCard по `AbilityKey.CardLKI`. Искусственное событие с одним Card не воспроизводит настоящий producer и может дать ложный отрицательный результат.
- Для смерти зачарованного существа `Card.AttachedBy` — поддерживаемый селектор. Не заменять его только потому, что другой пример использует Creature.EnchantedBy; сначала проверить связи и LKI.
- `Targeted$CardToughness` использует xCount, который берёт ChangeZone LKI для характеристик. Перед добавлением отдельного сохранения числа проверить, не обеспечивает ли нужный LKI сам движок.
- «It deals damage» требует источником именно соответствующий объект/LKI, а не автоматически host способности.

Пример: `forge-gui/res/cardsfolder/c/casting_of_bones.txt`; источники: `forge-game/src/main/java/forge/game/trigger/TriggerChangesZone.java:performTest,setTriggeringObjects`; `card/CardProperty.java` (AttachedBy); `ability/AbilityUtils.java:xCount` (608.2h, CardToughness); `card/CardCopyService.java:getLKICopy`.

## 7. Continuous: AffectedDefined не отменяет Affected

- `StaticAbilityContinuous.getAffectedCards` сначала получает AffectedDefined, затем применяет Affected. Их сочетание допустимо: например, ограничить зачарованное существо белым цветом.
- Для величины, зависящей от каждого затронутого объекта, проверить вычисление относительно affectedCard, а не host чар. Не все SVars автоматически вычисляются для затронутого объекта: источник обрабатывает Affected-маркеры при повторном расчёте бонуса.
- Проверять разные количества/характеристики у двух объектов; один объект с одним бонусом может скрыть неправильный источник вычисления.

Примеры: `forge-gui/res/cardsfolder/a/ancestral_mask.txt` (AffectedDefined и Affected); `g/graceblade_artisan.txt` (Aura.Attached). Источники: `forge-game/src/main/java/forge/game/staticability/StaticAbilityContinuous.java:getAffectedCards,applyContinuousAbility`; `ability/AbilityUtils.java:calculateAmount`.

## 8. Discard: Optional в TgtChoose — диапазон 0..N

- В текущем `DiscardEffect`, Mode TgtChoose с Optional задаёт min=0, max=min(NumCards, подходящая рука). Это поддерживает «discard up to N» и не является только выбором «всё или ничего».
- AnyNumber снимает фиксированный максимум; не заменять Optional на AnyNumber, если Oracle задаёт верхнюю границу.
- RememberDiscarded и последующий счётчик должны учитывать фактическое число сброшенных карт, затем очищаться. Проверять 0, промежуточное число и максимум.

Пример: `forge-gui/res/cardsfolder/c/cathartic_pyre.txt`, DBDisc использует `Mode$ TgtChoose | NumCards$ 2 | Optional$ True | RememberDiscarded$ True`; DBDraw берёт Remembered$Amount, затем Cleanup. Источники: `forge-game/src/main/java/forge/game/ability/effects/DiscardEffect.java:resolve`; `ability/AbilityUtils.java:handlePaid` (Remembered/Amount).

## 9. Ссылки на токены после ручных изменений

До готовности Token-способности проверить, что ID реально разрешается в текущий tokenscript и его игровые характеристики совпадают. После ручного переименования определений staging может содержать старые ссылки, хотя загружается без ошибки до вызова Token. Синхронизировать относящиеся к исправляемой карте TokenScript и [tokens]; изменения других этапов отдельно обозначать, не проводить скрытую массовую замену.
Именование, повторное использование и обязательная регистрация — `editions.md`; источник выполнения — `forge-game/src/main/java/forge/game/ability/effects/TokenEffect.java` и `TokenEffectBase.java` (определение tokenscript сверять по ApiType текущей версии). Не считать успешную загрузку host-карты проверкой создания токена.
