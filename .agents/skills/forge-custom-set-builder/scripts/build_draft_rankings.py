"""Deterministic Limited ranking: conservative features + mandatory Oracle review.

No rarity or collector-number inputs are used by score_card or tie breaking.
This estimates card strength; it does not learn win rates or simulate games.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata

VERSION = 'limited-heuristic-1'
ROLE_POINTS = {'interaction': .50, 'advantage': .40, 'engine': .20, 'ramp': .25,
               'fixing': .25, 'tokens': .20, 'selection': .10,
               'protection': .10, 'finisher': .10, 'evasion': 0, 'creature': 0, 'land': 0}
KEYWORD_POINTS = {'Flying': .50, 'Double Strike': .45, 'Deathtouch': .35,
                  'Indestructible': .35, 'First Strike': .20, 'Hexproof': .20,
                  'Lifelink': .18, 'Menace': .20, 'Haste': .18, 'Vigilance': .12,
                  'Trample': .15, 'Reach': .10, 'Flash': .12, 'Prowess': .10, 'Unearth': .18,
                  'Defender': -.35}


def rank_key(name):
    # ReadDraftRankings strips accents both at load and lookup, but collapses
    # the split separator ONLY at lookup. Emit that collapsed spelling.
    return ''.join(c for c in unicodedata.normalize('NFD', name.replace(' // ', ' '))
                   if unicodedata.category(c) != 'Mn')


def semantic_hash(card):
    fields = {k: card.get(k) for k in ('name', 'mana_cost', 'types', 'power', 'toughness',
                                      'colors', 'oracle', 'keywords', 'faces', 'mana_value')}
    return hashlib.sha256(json.dumps(fields, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def read_pool(path):
    pool = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    assert re.fullmatch('[A-Z]{3,6}', pool['set_code']), 'Invalid set code'
    assert pool['cards'], 'Empty draft pool'
    seen = set()
    for c in pool['cards']:
        assert c['name'] and not any(x in c['name'] for x in ('|', '\n', '\r')), c['name']
        assert not ('Basic' in c['types'].split() and 'Land' in c['types'].split()), 'Supply draft pool without basics'
        key = rank_key(c['name'])
        assert key not in seen, 'Duplicate/colliding Forge rank key: ' + c['name']
        seen.add(key)
        assert c['rarity'] in ('C', 'U', 'R', 'M', 'S', 'L'), c['rarity']
    return pool


def read_review(path):
    path = Path(path)
    if path.suffix == '.json':
        data = json.loads(path.read_text(encoding='utf-8-sig'))
        result = {}
        for r in data['cards']:
            assert r['name'] not in result, 'Duplicate Oracle review: ' + r['name']
            result[r['name']] = r
        return result
    result = {}
    for r in csv.DictReader(path.read_text(encoding='utf-8-sig').splitlines(), delimiter='|'):
        assert r['name'] not in result, 'Duplicate Oracle review: ' + r['name']
        r['oracle_grade'] = float(r['oracle_grade'])
        r['synergy_bonus'] = float(r['synergy_bonus'])
        r['roles'] = r['roles'].split(',') if r['roles'] else []
        result[r['name']] = r
    return result


def clamp(x, lo, hi):
    return max(lo, min(hi, x))


def score_card(card, review):
    """Small visible model. Deliberately blind to rarity, edition and collector number."""
    grade = review['oracle_grade']
    synergy = review['synergy_bonus']
    assert isinstance(grade, (int, float)) and math.isfinite(grade) and 0 <= grade <= 5
    assert isinstance(synergy, (int, float)) and math.isfinite(synergy) and 0 <= synergy <= .25
    assert review['reason'].strip(), card['name']
    roles = set(review['roles'])
    assert roles <= ROLE_POINTS.keys(), roles - ROLE_POINTS.keys()
    parts = {}
    cost = card['mana_cost']
    symbols = cost.replace('{', '').replace('}', ' ').split()
    mv = card.get('mana_value')
    if mv is None:
        mv = sum(max((int(t) if t.isdecimal() else 0 if t in ('X','Y','Z','no','cost') else 1)
                     for t in s.split('/')) for s in symbols)
    colors = set(card['colors'])
    types = card['types'].split()
    creature, land = 'Creature' in types, 'Land' in types
    parts['base'] = 1.65 if creature else 1.4 if land else 1.8
    if creature and 'X' not in symbols:
        try:
            power, toughness = float(card['power']), float(card['toughness'])
            expected = 2.5 if mv == 1 else 4 if mv == 2 else 1.5 * mv + 1.5
            parts['body_efficiency'] = clamp(.22 * (power + toughness - expected), -.8, 1.0)
        except (TypeError, ValueError):
            parts['body_efficiency'] = 0  # CDA/variable bodies require actual Oracle judgment.
    if creature:
        printed = {k.split(':', 1)[0] for k in card.get('keywords', [])}
        parts['printed_combat'] = clamp(sum(KEYWORD_POINTS.get(k, 0) for k in printed), -.35, 1.1)
    # Do not count keywords quoted inside tokens/reminder/conditional abilities.
    parts['functional_roles'] = min(1.0, sum(ROLE_POINTS[k] for k in roles))
    if not land:
        parts['expensive'] = -.08 * max(0, mv - 5) if 'X' not in symbols else 0
        parts['commitment'] = -.10 * max(0, len(colors) - 1) - .04 * max(0, sum(s in 'WUBRG' for s in symbols) - 2)
    elif 'enters the battlefield tapped' in card['oracle'].lower():
        parts['tapped_land'] = -.15
    heuristic = clamp(sum(parts.values()), 0, 5)
    # Oracle review supplies limitations, repeatability, replacement/choice drawbacks,
    # efficient removal versus narrow hate, and realistic multi-turn mana costs.
    score = round(clamp(.25 * heuristic + .75 * grade + synergy, 0, 5), 4)
    return score, round(heuristic, 4), parts


def generate(pool, reviews, out_dir):
    names = {c['name'] for c in pool['cards']}
    assert names == reviews.keys(), {'missing_review': sorted(names - reviews.keys()),
                                     'obsolete_review': sorted(reviews.keys() - names)}
    scored = []
    for card in pool['cards']:
        review = reviews[card['name']]
        assert review.get('semantic_sha256') == semantic_hash(card), 'Stale Oracle review: ' + card['name']
        score, heuristic, parts = score_card(card, review)
        scored.append(dict(card, oracle_grade=review['oracle_grade'], synergy_bonus=review['synergy_bonus'],
                           roles=review['roles'], reason=review['reason'], score=score,
                           heuristic=heuristic, heuristic_components=parts))
    scored.sort(key=lambda c: (-c['score'], rank_key(c['name']).casefold(), c['name']))
    # Only comparison/output consumes rarity. Neither baseline tie-break uses a card number.
    rarity_order = {'M': 0, 'R': 1, 'U': 2, 'C': 3, 'S': 4, 'L': 5}
    rarity_baseline = sorted(scored, key=lambda c: (rarity_order[c['rarity']], rank_key(c['name']).casefold()))
    baseline_rank = {c['name']: i for i,c in enumerate(rarity_baseline, 1)}
    for i,c in enumerate(scored, 1):
        c['rank'] = i
        c['rarity_baseline_rank'] = baseline_rank[c['name']]
        c['forge_raw_score'] = 100 - 100 * i / len(scored)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    edition = pool['set_code']
    lines = ['//Rank|Name|Rarity|Set', '//Provisional Limited estimates; ordinal, not empirical win rates.']
    lines += [f"#{c['rank']}|{c['name'].replace(' // ', ' ')}|{c['rarity']}|{edition}" for c in scored]
    (out_dir / f'{edition.lower()}.rnk').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    report = dict(version=VERSION, set_code=edition, pool_size=len(scored), model='.25 heuristic + .75 Oracle grade + synergy (0..0.25)',
                  status='provisional; not calibrated by games', cards=scored)
    (out_dir/'ratings.json').write_text(json.dumps(report,ensure_ascii=False,indent=2), encoding='utf-8')
    with (out_dir/'ratings.csv').open('w',encoding='utf-8-sig',newline='') as f:
        columns=['rank','name','rarity','score','heuristic','oracle_grade','synergy_bonus','rarity_baseline_rank','reason']
        w=csv.DictWriter(f,fieldnames=columns,extrasaction='ignore');w.writeheader();w.writerows(scored)
    md = [f'# {edition}: предварительная оценка Limited', '',
          f'Полный пул: {len(scored)} уникальных карт. Это исходный pick order без выбранных цветов и конкретной колоды, а не предсказанные win rates.', '',
          'Оценка = 25% прозрачной эвристики + 75% содержательной оценки Oracle (0–5) + ограниченный бонус синергии (0–0.25). Редкость и коллекционный номер не участвуют. При равенстве — алфавитный порядок канонического имени, не утверждение превосходства.', '',
          'Рубрика Oracle: 0 — практически неиграбельно; 1 — нишевый filler; 2 — обычная playable; 3 — сильная playable; 4 — премиальная карта; 5 — исключительная бомба. Десятые доли — экспертные оценки, не измеренная точность. Условия, цена всех активаций, темп, самостоятельность и повторяемость включены в эту оценку.', '',
          'Эвристика учитывает тело относительно стоимости, напечатанные боевые keywords, функциональную роль и цветовую/позднюю стоимость. Она не пытается понимать весь текст. Карточный Oracle-review обязателен, включая drawbacks и сложные механики; без него генерация запрещена.', '',
          'Синергии оцениваются для вероятной колоды, а не для коллекции из всех карт выпуска. Пять разных имён земель, WUBRG, одинаковые spells и многокарточные комбинации не считаются выполненными автоматически. Бонус внутри выпуска небольшой; динамические DeckHints/DeckNeeds Forge применяются отдельно.', '',
          '| Позиция | Карта | Редкость | Итог | Эвристика | Oracle | Синергия | Обоснование |',
          '|---|---|---|---|---|---|---|---|']
    for c in scored:
        md.append(f"| {c['rank']} | {c['name']} | {c['rarity']} | {c['score']:.2f} | {c['heuristic']:.2f} | {c['oracle_grade']:.1f} | +{c['synergy_bonus']:.2f} | {c['reason']} |")
    commons=[c for c in scored if c['rarity']=='C'][:8]
    weak_rares=[c for c in reversed(scored) if c['rarity'] in ('R','M')][:8]
    md += ['', '## Отличие от сортировки по редкости', '',
           'Базовое сравнение: M → R → U → C, алфавит внутри редкости. Это только контрольная модель; редкость не передаётся оценщику.', '',
           '| Карта | Редкость | Новый rank | Rank по редкости |', '|---|---|---|---|']
    for c in commons+weak_rares:
        md.append(f"| {c['name']} | {c['rarity']} | {c['rank']} | {c['rarity_baseline_rank']} |")
    md += ['', '## Обновление', '',
           'При изменении состава сначала заново выгрузить фактический draft pool из итогового editions/колляции. Для новых/изменённых карт обновить Oracle-review. Semantic hash запрещает молча применять старый разбор к изменённому тексту. После этого повторить генерацию и проверку Reader/имён; файл автоматически не устанавливать.', '',
           'Небольшая разница соседних оценок неопределённа. Не проводились массовые партии, обучение или оценка практического качества Draft AI. В .rnk сохраняется только порядок: Forge равномерно распределяет позиции по своей шкале и не знает расстояний между нашими оценками.']
    (out_dir/'ratings.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
    return scored


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pool',required=True)
    p.add_argument('--review',required=True)
    p.add_argument('--out-dir',required=True)
    p.add_argument('--prepare-review',action='store_true',help='Create/refresh JSON review template; never claims missing semantic reviews are complete')
    args=p.parse_args()
    pool=read_pool(args.pool)
    if args.prepare_review:
        assert Path(args.review).suffix == '.json', '--prepare-review writes JSON; use a .json review path'
        existing=read_review(args.review) if Path(args.review).exists() else {}
        records=[]
        for c in pool['cards']:
            old=existing.get(c['name'],{})
            changed=bool(old.get('semantic_sha256')) and old['semantic_sha256']!=semantic_hash(c)
            r=dict(old,name=c['name'],semantic_sha256=semantic_hash(c))
            if changed or not old:
                r.update(oracle_grade=None,synergy_bonus=0,roles=[],reason='REVIEW REQUIRED: '+c['oracle'])
            records.append(r)
        Path(args.review).write_text(json.dumps(dict(version=VERSION,cards=records),ensure_ascii=False,indent=2),encoding='utf-8')
        print('Review template updated; inspect all REVIEW REQUIRED entries before generating.')
        return
    scored=generate(pool,read_review(args.review),args.out_dir)
    print(f"Wrote {len(scored)} consecutive {pool['set_code']} rankings to {args.out_dir}")


if __name__=='__main__':
    main()
