# Реализация replacement-эффекта на поиск в библиотеке

## Цель

Реализовать replacement-эффект:

```text
If a player would search a library, that player ... instead.
```

На уровне движка Forge, чтобы он:

```text
- перехватывал поиск библиотеки в ChangeZoneEffect;
- отменял сам поиск;
- не запускал reveal / выбор карт / shuffle / SearchedLibrary trigger;
- выполнял ReplaceWith SubAbility;
- был доступен из card-script через Mode$ Replace.
```

---



# Применение

Основной параметр

```text
SearchLibrary
```

## Пример карты

```text
R:Event$ SearchLibrary | ActiveZones$ Battlefield | ValidPlayer$ Any | ReplaceWith$ ReplaceDraw | Description$ If a player would search a library, that player draws a card instead.
SVar:ReplaceDraw:DB$ Draw | Defined$ Player | NumCards$ 1
```

---

# Реализация

## 1. Добавить новый ReplacementType

Файл

```text
forge/game/replacement/ReplacementType.java
```

## Найти enum `ReplacementType`

В списке replacement-типов добавить:

```java
SearchLibrary(ReplaceSearchLibrary.class),
```

---

# 2. Создать новый replacement-класс

## Файл новый

```text
forge/game/replacement/ReplaceSearchLibrary.java
```

## Полный код файла

```java
package forge.game.replacement;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class ReplaceSearchLibrary extends ReplacementEffect {

    public ReplaceSearchLibrary(final Map<String, String> mapParams, final Card host, final boolean intrinsic) {
        super(mapParams, host, intrinsic);
    }

    @Override
    public boolean canReplace(final Map<AbilityKey, Object> runParams) {
        final Object affected = runParams.get(AbilityKey.Affected);
        if (!(affected instanceof Player)) {
            return false;
        }

        return matchesValidParam("ValidPlayer", affected);
    }

    @Override
    public void setReplacingObjects(final Map<AbilityKey, Object> runParams, final SpellAbility sa) {
        final Object affected = runParams.get(AbilityKey.Affected);

        sa.setReplacingObject(AbilityKey.Player, affected);
        sa.setReplacingObject(AbilityKey.Affected, affected);
    }
}
```
---

# 3. Добавить перехват в ChangeZoneEffect

Файл

```text
forge/game/ability/effects/ChangeZoneEffect.java
```

---

## 3.1. Добавить imports

В блок импортов добавить:

```java
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
```

---

## 3.2. Найти метод

```java
private void changeHiddenOriginResolve(final SpellAbility sa)
```

Внутри метода найти блок, где начинается обработка поиска библиотеки.

Искомый фрагмент:

```java
fetchList = new CardCollection(player.getCardsIn(origin));
if (origin.contains(ZoneType.Library) && !sa.hasParam("NoLooking") && !sa.hasParam("OriginLibraryPosition")) {
```

Вставить replacement-проверку перед `searchedLibrary = true;`

## Было

```java
fetchList = new CardCollection(player.getCardsIn(origin));
if (origin.contains(ZoneType.Library) && !sa.hasParam("NoLooking") && !sa.hasParam("OriginLibraryPosition")) {
    searchedLibrary = true;

    if (decider.hasKeyword("LimitSearchLibrary")) { // Aven Mindcensor
        fetchList.removeAll(player.getCardsIn(ZoneType.Library));
        final int fetchNum = Math.min(player.getCardsIn(ZoneType.Library).size(), 4);
        if (fetchNum == 0) {
            searchedLibrary = false;
        } else {
            fetchList.addAll(player.getCardsIn(ZoneType.Library, fetchNum));
        }
    }
```

## Стало

```java
fetchList = new CardCollection(player.getCardsIn(origin));
if (origin.contains(ZoneType.Library) && !sa.hasParam("NoLooking") && !sa.hasParam("OriginLibraryPosition")) {
    Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(decider);
    repParams.put(AbilityKey.Player, decider);
    repParams.put(AbilityKey.Cause, sa);

    if (game.getReplacementHandler()
            .run(ReplacementType.SearchLibrary, repParams)
            != ReplacementResult.NotReplaced) {
        continue; // search is fully replaced
    }

    searchedLibrary = true;

    if (decider.hasKeyword("LimitSearchLibrary")) { // Aven Mindcensor
        fetchList.removeAll(player.getCardsIn(ZoneType.Library));
        final int fetchNum = Math.min(player.getCardsIn(ZoneType.Library).size(), 4);
        if (fetchNum == 0) {
            searchedLibrary = false;
        } else {
            fetchList.addAll(player.getCardsIn(ZoneType.Library, fetchNum));
        }
    }
```

---
