# Custom Engine Changes

This file documents custom modifications added to this fork on top of upstream **Forge: The Magic: The Gathering Rules Engine**.

It is intended as a maintenance index for custom mechanics, custom script support, and engine patches. Keep this file updated when a custom mechanic is added, reworked, removed, or merged with upstream changes.

## Status Legend

- **Stable** — implemented and tested in this fork.
- **Experimental** — implemented or partially implemented, but may still need broader testing.
- **Work in progress** — design or implementation is not final.
- **Needs review** — the idea exists, but the current code should be checked before relying on it.

## Кастомные выпуски карт и механики в них используемые.

### Aenyr

**Status:** Experimental / custom fork feature

Aenyr is a new plane of the Multiverse where Art is the source of all Magic. Aenyr is a whole custom Magic: The Gathering set that’s playable in both limited and constructed environments.

Это очень качественный, продуманный и интересный набор, который был высоко оценен игроками. Более подробно о наборе, его архетипах и механиках можно прочитать на [странице автора.](https://doortonothingness.wordpress.com/2016/04/19/aenyr/?utm_source=chatgpt.com)

##### Механики, добавленные в оригинальный движок Forge из этого набора:

`Resonance`   {cost}  (If you cast this spell for  {cost}, exile it as it resolves. As you cast your next instant or sorcery spell, put this card into your graveyard and you may add its effects and colors to that spell.) [подробнее](Implementation_of_custom_mechanics/Aenyr/Resonance.txt)





