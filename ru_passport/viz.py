"""Разбор визуальной зоны (текст на кириллице) и сведение с данными MRZ.

MRZ надёжнее: в ней есть контрольные цифры. Из визуальной зоны берём то,
чего в MRZ нет (кем выдан, место рождения), и сверяем остальное.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from difflib import SequenceMatcher
from typing import Optional

from .models import PassportData
from .mrz import check_digit, parse_mrz
from .translit import pretty

_DATE = r"(\d{2})[.,/ ]\s?(\d{2})[.,/ ]\s?(\d{4})"
# Подписи полей. Шаблоны терпимы к типичным ошибкам OCR («рожденя», «выдaн»).
LABELS = {
    "issued_by": r"паспорт\s+в\w*да\w*",
    "issue_date": r"дата\s+в\w*дач\w*",
    "division_code": r"код\s+подр\w*",
    "personal_code": r"личн\w*\s+код",
    "surname": r"фами\w*",
    "name": r"имя",
    "patronymic": r"отч\w*",
    "sex": r"пол",
    "birth_date": r"дата\s+рож\w*",
    "birth_place": r"мест\w*\s+рож\w*",
}
_LABEL_RE = {k: re.compile(rf"(?<![а-яё]){v}(?![а-яё])") for k, v in LABELS.items()}


@dataclass
class VizFields:
    surname: Optional[str] = None
    name: Optional[str] = None
    patronymic: Optional[str] = None
    birth_date: Optional[date] = None
    issue_date: Optional[date] = None
    birth_place: Optional[str] = None
    issued_by: Optional[str] = None
    division_code: Optional[str] = None
    series: Optional[str] = None
    number: Optional[str] = None
    sex: Optional[str] = None
    raw_lines: list[str] = field(default_factory=list)


def _to_date(m: re.Match) -> Optional[date]:
    try:
        return date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    except ValueError:
        return None


def _is_label(line: str) -> bool:
    low = line.lower()
    return any(r.match(low) for r in _LABEL_RE.values())


def _is_text(line: str) -> bool:
    """Строка похожа на осмысленный русский текст, а не на шум OCR (печати, фон)."""
    chars = line.replace(" ", "")
    if len(chars) < 2:
        return False
    good = sum(bool(re.match(r"[А-ЯЁа-яё0-9.,\-№()\"]", c)) for c in chars)
    return good / len(chars) >= 0.85 and bool(re.search(r"[А-ЯЁа-яё]{2,}", line))


def _value_after(lines: list[str], label: str, multiline: bool = False) -> Optional[str]:
    """Значение поля: остаток строки с подписью или следующая строка(и)."""
    pattern = _LABEL_RE[label]
    for i, line in enumerate(lines):
        m = pattern.search(line.lower())
        if not m:
            continue
        tail = line[m.end():].strip(" :.-—")
        collected = [tail] if tail else []
        for nxt in lines[i + 1:]:
            # Подпись следующего поля или строка из одних цифр (серия/номер) — конец значения.
            if (_is_label(nxt) or re.fullmatch(r"[\d\s]+", nxt) or not _is_text(nxt)
                    or (collected and not multiline)):
                break
            collected.append(nxt)
            if not multiline:
                break
        value = " ".join(collected).strip()
        return value or None
    return None


def _cyr_word(text: Optional[str]) -> Optional[str]:
    if not text:
        return None
    words = re.findall(r"[А-ЯЁа-яё][А-ЯЁа-яё\-]+", text)
    return pretty(" ".join(words).upper()) if words else None


def parse_viz(text: str) -> VizFields:
    lines = [re.sub(r"\s+", " ", l).strip() for l in text.splitlines()]
    # Строки MRZ сюда не относятся (и сбивают поиск серии/номера).
    lines = [l for l in lines if l and "<" not in l and not re.search(r"[A-Z0-9]{25,}", l)]
    f = VizFields(raw_lines=lines)
    flat = " ".join(lines)

    m = re.search(r"(?<!\d)(\d{2})\s?(\d{2})\s?(\d{6})(?!\d)", flat)
    if m:
        f.series, f.number = m.group(1) + m.group(2), m.group(3)
    m = re.search(r"(?<!\d)(\d{3})\s?[-–—]\s?(\d{3})(?!\d)", flat)
    if m:
        f.division_code = f"{m.group(1)}-{m.group(2)}"

    for attr in ("issue_date", "birth_date"):
        lm = _LABEL_RE[attr].search(flat.lower())
        if lm:
            dm = re.search(_DATE, flat[lm.end():lm.end() + 80])
            if dm:
                setattr(f, attr, _to_date(dm))
    if not (f.issue_date and f.birth_date):
        dates = [d for d in (_to_date(m) for m in re.finditer(_DATE, flat)) if d]
        # На развороте сначала (стр. 2) дата выдачи, затем (стр. 3) дата рождения.
        if len(dates) >= 2:
            f.issue_date = f.issue_date or dates[0]
            f.birth_date = f.birth_date or dates[1]

    f.surname = _cyr_word(_value_after(lines, "surname"))
    f.name = _cyr_word(_value_after(lines, "name"))
    f.patronymic = _cyr_word(_value_after(lines, "patronymic"))
    sex = _value_after(lines, "sex")
    if sex:
        s = sex.upper()
        f.sex = "M" if s.startswith("МУЖ") else "F" if s.startswith("ЖЕН") else None
    f.birth_place = _value_after(lines, "birth_place", multiline=True)
    issued = _value_after(lines, "issued_by", multiline=True)
    if issued:
        f.issued_by = re.split(rf"(?i){LABELS['issue_date']}|\d{{2}}\.\d{{2}}\.\d{{4}}", issued)[0].strip(" ,.") or None
    return f


def _similar(a: str, b: str) -> float:
    return SequenceMatcher(None, a.upper().replace("Ё", "Е"), b.upper().replace("Ё", "Е")).ratio()


def _resolve_with_viz(mrz: PassportData, viz: VizFields, today: Optional[date]) -> PassportData:
    """Неоднозначную ошибку в MRZ исправить по визуальной зоне.

    Значение из визуальной зоны принимается, только если с ним сходится
    контрольная цифра, напечатанная в самой MRZ.
    """
    l1, l2 = mrz.mrz_lines
    new = l2
    used = []
    if not mrz.checks.get("number") and viz.series and viz.number:
        doc = viz.series[:3] + viz.number
        if check_digit(doc) == l2[9]:
            new = doc + new[9:]
            used.append("серия/номер")
    if not mrz.checks.get("birth_date") and viz.birth_date:
        dob = viz.birth_date.strftime("%y%m%d")
        if check_digit(dob) == l2[19]:
            new = new[:13] + dob + new[19:]
            used.append("дата рождения")
    if not mrz.checks.get("optional") and viz.series and viz.issue_date and viz.division_code:
        opt = viz.series[3] + viz.issue_date.strftime("%y%m%d") + viz.division_code.replace("-", "") + "<"
        if check_digit(opt) == l2[42]:
            new = new[:28] + opt + new[42:]
            used.append("дата выдачи/код подразделения")
    if not used:
        return mrz
    fixed = parse_mrz(l1, new, today=today)
    fixed.corrections = mrz.corrections + [f"по визуальной зоне исправлено: {', '.join(used)}"]
    return fixed


def merge(mrz: Optional[PassportData], viz: VizFields, today: Optional[date] = None) -> PassportData:
    """Свести MRZ и визуальную зону в один результат."""
    if mrz is None:
        data = PassportData(source="viz")
        for k in ("surname", "name", "patronymic", "birth_date", "issue_date", "birth_place",
                  "issued_by", "division_code", "series", "number", "sex"):
            setattr(data, k, getattr(viz, k))
        data.citizenship = "RUS"
        data.add("warning", "no_mrz", "MRZ не найдена — данные только из визуальной зоны, без контрольных сумм")
        return data

    data = mrz
    if data.mrz_lines and not all(data.checks.values()):
        data = _resolve_with_viz(data, viz, today)
    data.source = "mrz+viz"
    data.birth_place = viz.birth_place
    data.issued_by = viz.issued_by

    for attr, title in (("series", "серия"), ("number", "номер"), ("division_code", "код подразделения"),
                        ("birth_date", "дата рождения"), ("issue_date", "дата выдачи"), ("sex", "пол")):
        a, b = getattr(data, attr), getattr(viz, attr)
        if a and b and a != b:
            data.add("warning", f"mismatch_{attr}", f"Расхождение MRZ и визуальной зоны ({title}): {a} / {b}")

    for attr, title in (("surname", "фамилия"), ("name", "имя"), ("patronymic", "отчество")):
        a, b = getattr(data, attr), getattr(viz, attr)
        if not (a and b):
            continue
        if a.replace(" ", "-") == b or (data.name_truncated and b.upper().startswith(a.upper())):
            # Дефис и полное (необрезанное) написание берём из визуальной зоны.
            setattr(data, attr, b)
        elif _similar(a, b) < 0.8:
            data.add("warning", f"mismatch_{attr}", f"Расхождение MRZ и визуальной зоны ({title}): {a} / {b}")
    return data
