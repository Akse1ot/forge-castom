# Движок — проверенные технические заметки

## Контекст проверки

**Статус: проверено чтением исходников, 30.10.2026.** Локальная ветка `forge-custom-mechanics`, HEAD `0c45ce937684a0b83e9e279275050ffb130f3fc8`, рабочее дерево с незакоммиченными изменениями. HEAD сам по себе не воспроизводит проверенное состояние. Java 17 — требование проекта; компиляция и тесты в этой процедуре не запускались. Пути ниже относительно корня Forge. Это адресные сведения, не аудит всего движка.

## Deck-фильтры: дополнительные границы

**Контекст Java:** `forge-core/src/main/java/forge/card/DeckHints.java` (конструктор, `contains`, `is`, `filterByType`, `getCardsForFilter`, `rulesWithTokens`), `CardRulesPredicates.deckHas/deckHasExactly`; `forge-gui/src/main/java/forge/gamemodes/limited/CardRanker.java`, `getScoreForDeckHints`. Базовое различие полей и операторов уже описано в `ai-hints.md`.

- `contains(type, hint)` использует регистрозависимый `String.contains`: это поиск подстроки, не равенство тега. `is(type, hints[])`, вызываемый `deckHasExactly`, ищет равные строки записей, но **не запрещает дополнительные** DeckHas. Не использовать название метода как доказательство точного равенства всего набора; не дублировать записи одного тега (счётчик совпадений не является множеством).
- `getCardsForFilter` возвращает список с повторами: отдельные совпадения альтернатив `|`, бонус `deckHasExactly` и встроенная проверка типа/цвета/keyword могут добавить ту же карту снова. `filterByType` пересекает повторные категории через `retainAll`, разные категории остаются отдельными. Это не булева алгебра над уникальными картами.
- `CardRanker` считает `Iterables.size` этих списков. DeckHints других карт повышают оценку кандидата; DeckNeeds самого кандидата дают штраф за недобор до порога. Поэтому число совпадений не равно числу уникальных источников поддержки. Это объясняет ранжирование, но не подтверждает качество Draft AI.
- По умолчанию совпадения включают рекурсивно доступные `TokenScript` через `StaticData.getAllTokens`; `Modifier$NoToken` отключает этот путь. В `rulesWithTokens` явно отмечено отсутствие keyword-generated tokens. В минимальной среде без `StaticData` токены не учитываются: такой тест не доказывает результат полной базы.

**Пример:** `deckHasExactly(ABILITY, {"Token", "LifeGain"})` может пройти для `DeckHas:Ability$Token & Ability$LifeGain & Ability$Mana`. Использовать штатные названия/регистр тегов, а не опираться на частичные совпадения. Постоянный пример: `DWAPCardScriptTest.deckHintsDistinguishAlternativesIntersectionAndProviders`.

## Чтение лиц и разрешение имён

**Контекст Java:** `CardRules.Reader` (`readCard`, `parseLine`, `getCard`), `CardRules.fromScript/getName`, `CardSplitType`; `forge-core/src/main/java/forge/CardStorageReader.java` (`loadCard`, `primaryNamesOf`, `attemptToLoadCard`); `forge-core/src/main/java/forge/card/CardDb.java` (`addFaceToDbNames`, `addCard`, `getFaceByName`).

- `ALTERNATE` переключает `curFace` на 1, следующий `Name:` создаёт второе лицо; `AlternateMode` задаёт `CardSplitType`. Deck-поля хранятся в Reader на всю карту и перезаписываются последней строкой соответствующего поля независимо от лица (историческое правило подтверждено).
- `CardRules.getName()` соединяет имена через ` // ` только при агрегации `COMBINE` (текущий `Split`); остальные режимы возвращают основное имя. Наличие двух лиц само по себе не означает комбинированного имени.
- `CardDb` хранит имена без учёта регистра, индексирует имена лиц; loose lookup альтернативного имени неоднозначен и использует `putIfAbsent`, а `facesByName` заполняется через `put`. Lazy-индекс `CardStorageReader` строится по разобранным именам карты/лиц, а не только имени файла; для общего лица `Prepare` есть исключение. Коллизии проверять для **всех лиц**; общий поиск имени не доказывает, какой скрипт загружен. Римские суффиксы движок автоматически не назначает.
- `Reader.readCard` сбрасывает Reader, пропускает пустые строки и строки с начальным `#`. `CardRules.fromScript` непосредственно вызывает `parseLine` для каждой строки: он не повторяет этот пропуск, пустая строка опасна для `key.charAt(0)`. В DWAP `load(Path, ...)` заранее удаляет blank lines. Для внешнего staging можно читать точный файл и создавать `PaperCard`/`CardFactory.getCard` без установки в базу; это не проверяет editions, глобальные коллизии, lazy loader или изображения.

**Границы:** `CopyFaceFrom` требует разрешения placeholder-лиц через базу; прямая изолированная загрузка не является универсальной заменой `CardDb`. См. `names-and-identity.md`, адресные тестовые приёмы в `validation.md`.
