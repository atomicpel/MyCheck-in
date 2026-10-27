"""Разбор, проверка и исправление машиночитаемой записи (MRZ) паспорта РФ.

Формат (2 строки по 44 символа, похож на ICAO TD3):

    Строка 1:  PNRUS<ФАМИЛИЯ<<ИМЯ<ОТЧЕСТВО<<<<<<<<<<<<<<<<<<<<
               [0:2] тип "PN", [2:5] "RUS", [5:44] ФИО в транслитерации МВД

    Строка 2:  4601234565RUS8001017M<<<<<<<11006207701203<1
               [0:9]   три первые цифры серии + номер (6 цифр)
               [9]     контрольная цифра [0:9]
               [10:13] гражданство RUS
               [13:19] дата рождения ГГММДД, [19] контрольная цифра
               [20]    пол M/F
               [21:28] срок действия — не заполняется ("<<<<<<<")
               [28]    последняя цифра серии
               [29:35] дата выдачи ГГММДД
               [35:41] код подразделения без дефиса
               [41]    "<"
               [42]    контрольная цифра [28:42]
               [43]    итоговая контрольная цифра [0:10]+[13:20]+[21:43]
"""

from __future__ import annotations

import itertools
import re
from datetime import date
from typing import Callable, Iterable, Optional

from .models import PassportData
from .translit import decode_name, encode_name, pretty

LINE_LEN = 44
_WEIGHTS = (7, 3, 1)

# Кириллица, которую OCR часто подставляет вместо похожих латинских букв.
_HOMOGLYPHS = str.maketrans({
    "А": "A", "В": "B", "Е": "E", "К": "K", "М": "M", "Н": "H", "О": "O",
    "Р": "P", "С": "C", "Т": "T", "Х": "X", "У": "Y", "З": "3", "Ч": "4",
    "«": "<", "‹": "<", "≪": "<", "＜": "<", "(": "<", "[": "<", "{": "<",
})
# Буквы, которые в цифровых полях почти наверняка являются цифрами.
_TO_DIGIT = str.maketrans({
    "O": "0", "Q": "0", "D": "0", "U": "0", "I": "1", "L": "1", "J": "1",
    "Z": "2", "S": "5", "B": "8", "G": "6", "T": "7", "A": "4", "<": "0",
})
# В поле ФИО цифры 0, 1, 5 невозможны (2-4, 6-9 — это Ё, Ч, Ш, Э, Ю, Я, Ь).
_NAME_FIX = str.maketrans({"0": "O", "1": "I", "5": "S"})
# Визуально похожие цифры — кандидаты для исправления по контрольной сумме.
_DIGIT_CONFUSIONS = {
    "0": "869", "1": "74", "2": "73", "3": "8592", "4": "19", "5": "638",
    "6": "580", "7": "12", "8": "30695", "9": "8034",
}
# Путаница симметрична: если 3 читается как 8, то и 8 — как 3.
for _d, _alts in list(_DIGIT_CONFUSIONS.items()):
    for _a in _alts:
        if _d not in _DIGIT_CONFUSIONS[_a]:
            _DIGIT_CONFUSIONS[_a] += _d


def check_digit(data: str) -> str:
    """Контрольная цифра ICAO 9303: веса 7-3-1, A=10..Z=35, '<'=0."""
    total = 0
    for i, ch in enumerate(data):
        if ch.isdigit():
            v = int(ch)
        elif "A" <= ch <= "Z":
            v = ord(ch) - 55
        elif ch == "<":
            v = 0
        else:
            raise ValueError(f"Недопустимый символ MRZ: {ch!r}")
        total += v * _WEIGHTS[i % 3]
    return str(total % 10)


# --------------------------------------------------------------------------
# Поиск и нормализация строк
# --------------------------------------------------------------------------

def _clean(line: str) -> str:
    line = line.upper().translate(_HOMOGLYPHS)
    return re.sub(r"[^A-Z0-9<]", "", line)


def _fit(line: str, anchor: Optional[str] = None) -> str:
    """Привести строку к 44 символам. Лишнее отрезается вокруг якоря."""
    if anchor and anchor in line:
        line = line[line.index(anchor):]
    if len(line) > LINE_LEN:
        line = line[:LINE_LEN]
    return line.ljust(LINE_LEN, "<")


def align_line2(line: str) -> tuple[str, int]:
    """Выровнять строку 2 по якорю: "RUS" стоит в позициях 11-13, пол и "<<<<<<<" — с 21-й.

    Помогает, когда начало строки закрыто пальцем/бликом или к ней прилип мусор.
    Возвращает (строка из 44 символов, сдвиг). Недостающие символы заполняются "<".
    """
    i = line.find("RUS")
    if 0 <= i <= 20:
        shift = 10 - i
    else:
        m = re.search(r"[MF]<{4,}", line)
        shift = 20 - m.start() if m else 0
    if shift > 0:
        line = "<" * shift + line
    elif shift < 0:
        line = line[-shift:]
    return _fit(line), shift


def find_mrz_lines(text: str) -> Optional[tuple[str, str]]:
    """Найти две строки MRZ в произвольном тексте OCR.

    Работает и с одной склеенной строкой из 88 символов, и с мусором вокруг.
    """
    rows = [_clean(r) for r in text.splitlines()]
    rows = [r for r in rows if len(r) >= 20]
    joined = "".join(rows)

    # Якорь строки 1 — "PNRUS" (с типичными ошибками OCR).
    m = re.search(r"P[NM]R[UV][S5]", joined)
    if not m:
        # Строки целиком без якоря: берём две самые длинные подряд.
        if len(rows) >= 2:
            rows44 = [r for r in rows if 40 <= len(r) <= 48]
            if len(rows44) >= 2:
                return _fit(rows44[-2]), _fit(rows44[-1])
        return None

    start = m.start()
    # Если строки разделены, строка 1 — та, в которой найден якорь.
    pos = 0
    for idx, r in enumerate(rows):
        if pos <= start < pos + len(r):
            l1 = r[start - pos:]
            rest = rows[idx + 1:]
            if len(l1) > LINE_LEN + 10:          # склеены в одну строку
                return _fit(l1[:LINE_LEN]), _fit(l1[LINE_LEN:])
            if not rest:
                return None
            # Строка 2 — первая следующая, похожая на цифровую.
            l2 = max(rest[:2], key=lambda s: sum(c.isdigit() for c in s))
            return _fit(l1), _fit(l2)
        pos += len(r)
    return None


def _strip_filler(field: str) -> tuple[str, bool]:
    """Отделить ФИО от заполнителя.

    Фамилия отделяется от имени "<<", имя от отчества — одиночным "<".
    Значит, следующее "<<" после разделителя фамилии — начало заполнителя,
    и всё после него (одиночные K/C/S — ошибки OCR на "<") отбрасывается.
    Возвращает (ФИО, был_ли_заполнитель). Нет заполнителя = ФИО могло обрезаться.
    """
    sep = field.find("<<")
    if sep < 0:
        content = field.rstrip("<")
        return content, len(content) < len(field)
    end = field.find("<<", sep + 2)
    if end < 0:
        content = field.rstrip("<")
        return content, len(content) < len(field)
    # Однобуквенных частей имени не бывает: "...<K" перед заполнителем — тоже шум.
    content = field[:end]
    while re.search(r"[^<]<[^<]$", content) and content.rfind("<") > sep + 1:
        content = content[:-2]
    return content, True


def _normalize_line2(l2: str) -> tuple[str, list[str]]:
    fixes = []
    s = list(l2)

    def digits(a: int, b: int) -> None:
        for i in range(a, b):
            d = s[i].translate(_TO_DIGIT)
            if d != s[i]:
                fixes.append(f"строка 2, поз. {i + 1}: {s[i]!r} -> {d!r}")
                s[i] = d

    digits(0, 10)
    if "".join(s[10:13]) != "RUS":
        fixes.append(f"строка 2: гражданство {''.join(s[10:13])!r} -> 'RUS'")
        s[10:13] = list("RUS")
    digits(13, 20)
    sex = {"H": "M", "N": "M", "W": "M", "E": "F", "P": "F"}.get(s[20], s[20])
    if sex != s[20]:
        fixes.append(f"строка 2: пол {s[20]!r} -> {sex!r}")
        s[20] = sex
    for i in range(21, 28):
        if s[i] != "<":
            fixes.append(f"строка 2, поз. {i + 1}: {s[i]!r} -> '<'")
            s[i] = "<"
    digits(28, 41)
    if s[41] != "<":
        fixes.append(f"строка 2, поз. 42: {s[41]!r} -> '<'")
        s[41] = "<"
    digits(42, 44)
    return "".join(s), fixes


# --------------------------------------------------------------------------
# Исправление по контрольным цифрам
# --------------------------------------------------------------------------

def _parse_yymmdd(s: str) -> Optional[tuple[int, int, int]]:
    if not re.fullmatch(r"\d{6}", s):
        return None
    yy, mm, dd = int(s[:2]), int(s[2:4]), int(s[4:6])
    try:
        date(2000, mm, dd)  # 2000 — високосный, 29 февраля допустимо
    except ValueError:
        return None
    return yy, mm, dd


def _candidates(value: str, check: str, valid: Callable[[str], bool]) -> list[tuple[str, str]]:
    """Варианты (значение, контрольная), проходящие проверку.

    Если исходный вариант корректен — только он. Иначе пробуем заменить одну
    цифру на визуально похожую (в значении или в самой контрольной цифре).
    """
    if check_digit(value) == check and valid(value):
        return [(value, check)]
    found = []
    for i, ch in enumerate(value):
        for alt in _DIGIT_CONFUSIONS.get(ch, ""):
            v = value[:i] + alt + value[i + 1:]
            if check_digit(v) == check and valid(v):
                found.append((v, check))
    if valid(value):
        cd = check_digit(value)
        if cd in _DIGIT_CONFUSIONS.get(check, ""):
            found.append((value, cd))
    return found


def _optional_valid(v: str) -> bool:
    # v = посл. цифра серии (1) + дата выдачи (6) + код подразделения (6) + "<"
    return v[0].isdigit() and _parse_yymmdd(v[1:7]) is not None and v[7:13].isdigit()


def repair_line2(l2: str) -> tuple[str, dict[str, bool], list[str]]:
    """Подобрать исправление строки 2, при котором сходятся все контрольные суммы.

    Возвращает (строка, {поле: ок}, список исправлений).
    """
    l2, fixes = _normalize_line2(l2)
    fields = {
        "number": (l2[0:9], l2[9], lambda v: v.isdigit()),
        "birth_date": (l2[13:19], l2[19], lambda v: _parse_yymmdd(v) is not None),
        "optional": (l2[28:42], l2[42], _optional_valid),
    }
    options = {}
    for key, (val, chk, valid) in fields.items():
        try:
            options[key] = _candidates(val, chk, valid) or [(val, chk)]
        except ValueError:
            options[key] = [(val, chk)]

    def assemble(combo) -> str:
        (num, nc), (dob, dc), (opt, oc) = combo
        return num + nc + l2[10:13] + dob + dc + l2[20:28] + opt + oc + l2[43]

    def composite_ok(s: str) -> bool:
        return check_digit(s[0:10] + s[13:20] + s[21:43]) == s[43]

    combos = list(itertools.product(options["number"], options["birth_date"], options["optional"]))
    good = [c for c in combos if composite_ok(assemble(c))]
    if len(good) == 1:
        chosen = good[0]
    elif len(combos) == 1:
        chosen = combos[0]
    else:
        # Неоднозначно — оставляем как есть, пусть решает человек/следующий кадр.
        chosen = ((l2[0:9], l2[9]), (l2[13:19], l2[19]), (l2[28:42], l2[42]))
    fixed = assemble(chosen)
    expected = check_digit(fixed[0:10] + fixed[13:20] + fixed[21:43])
    if (not composite_ok(fixed) and all(check_digit(v) == c for v, c in chosen)
            and expected in _DIGIT_CONFUSIONS.get(fixed[43], "")):
        fixes.append(f"итоговая контрольная цифра: {fixed[43]} -> {expected}")
        fixed = fixed[:43] + expected

    for (key, (val, chk, _)), (nval, nchk) in zip(fields.items(), chosen):
        if (val, chk) != (nval, nchk):
            fixes.append(f"поле {key}: {val}{chk} -> {nval}{nchk} (по контрольной цифре)")

    checks = {
        "number": check_digit(fixed[0:9]) == fixed[9],
        "birth_date": check_digit(fixed[13:19]) == fixed[19],
        "optional": check_digit(fixed[28:42]) == fixed[42],
        "composite": composite_ok(fixed),
    }
    return fixed, checks, fixes


# --------------------------------------------------------------------------
# Разбор
# --------------------------------------------------------------------------

def _resolve_date(yymmdd: str, today: date, not_before: Optional[date] = None) -> Optional[date]:
    p = _parse_yymmdd(yymmdd)
    if not p:
        return None
    yy, mm, dd = p
    for century in (2000, 1900):
        try:
            d = date(century + yy, mm, dd)
        except ValueError:
            continue
        if d <= today and (not_before is None or d >= not_before):
            return d
    return None


def parse_mrz(line1: str, line2: str, today: Optional[date] = None) -> PassportData:
    """Разобрать две строки MRZ. Ошибки OCR исправляются там, где это однозначно."""
    today = today or date.today()
    l1 = _fit(_clean(line1), "PN")
    l2, shift = align_line2(_clean(line2))
    data = PassportData(source="mrz")
    if shift > 0:
        data.add("warning", "line2_cut", f"Начало строки 2 не прочитано ({shift} симв.) — серия/номер под вопросом")
    elif shift < 0:
        data.corrections.append(f"строка 2: отброшено {-shift} лишних символов в начале")

    if not re.match(r"P[NM<]R[UV][S5]", l1[:5]) and l1[2:5] != "RUS":
        data.add("error", "mrz_header", f"Строка 1 не похожа на паспорт РФ: {l1[:5]!r}")
    if l1[:5] != "PNRUS":
        data.corrections.append(f"строка 1: заголовок {l1[:5]!r} -> 'PNRUS'")
        l1 = "PNRUS" + l1[5:]

    names_raw = l1[5:].translate(_NAME_FIX)
    if names_raw != l1[5:]:
        data.corrections.append(f"строка 1: {l1[5:].rstrip('<')} -> {names_raw.rstrip('<')}")
        l1 = l1[:5] + names_raw

    l2, checks, fixes = repair_line2(l2)
    data.corrections.extend(fixes)
    data.checks = checks
    data.mrz_lines = (l1, l2)

    # --- ФИО ---
    names, had_filler = _strip_filler(l1[5:])
    clean1 = ("PNRUS" + names).ljust(LINE_LEN, "<")
    if clean1 != l1:
        data.corrections.append(f"строка 1: мусор в заполнителе {l1[5 + len(names):]!r} удалён")
        l1 = clean1
        data.mrz_lines = (l1, l2)
    data.name_truncated = not had_filler
    if data.name_truncated:
        data.add("warning", "name_truncated", "ФИО заполняет строку целиком — возможно, обрезано")
    surname, _, given = names.partition("<<")
    parts = [p for p in given.split("<") if p]
    data.surname = pretty(decode_name(surname)) or None
    data.name = pretty(decode_name(parts[0])) if parts else None
    data.patronymic = pretty(decode_name("<".join(parts[1:]))) if len(parts) > 1 else None
    if "?" in (data.surname or "") + (data.name or "") + (data.patronymic or ""):
        data.add("warning", "name_chars", "В ФИО есть символы вне таблицы транслитерации МВД")

    # --- Документ ---
    data.citizenship = l2[10:13]
    data.series = l2[0:3] + l2[28]
    data.number = l2[3:9]
    data.sex = l2[20] if l2[20] in "MF" else None
    if data.sex is None:
        data.add("error", "sex", f"Не распознан пол: {l2[20]!r}")
    data.birth_date = _resolve_date(l2[13:19], today)
    if data.birth_date is None:
        data.add("error", "birth_date", f"Некорректная дата рождения: {l2[13:19]}")
    data.issue_date = _resolve_date(l2[29:35], today, not_before=data.birth_date)
    if data.issue_date is None:
        data.add("error", "issue_date", f"Некорректная дата выдачи: {l2[29:35]}")
    code = l2[35:41]
    data.division_code = f"{code[:3]}-{code[3:]}" if code.isdigit() else None

    labels = {
        "number": "серии/номера", "birth_date": "даты рождения",
        "optional": "серии/даты выдачи/кода подразделения", "composite": "итоговая",
    }
    for key, ok in checks.items():
        if not ok:
            # Итоговая при прочих верных — скорее всего, ошибка OCR в одной цифре.
            level = "warning" if key == "composite" and all(
                v for k, v in checks.items() if k != "composite") else "error"
            data.add(level, f"check_{key}", f"Не сходится контрольная цифра {labels[key]}")
    return data


def parse_mrz_text(text: str, today: Optional[date] = None) -> Optional[PassportData]:
    lines = find_mrz_lines(text)
    return parse_mrz(*lines, today=today) if lines else None


# --------------------------------------------------------------------------
# Генерация (для тестов, печати образцов и сверки)
# --------------------------------------------------------------------------

def build_mrz(surname: str, name: str, patronymic: str, series: str, number: str,
              sex: str, birth_date: date, issue_date: date, division_code: str) -> tuple[str, str]:
    names = encode_name(surname) + "<<" + encode_name(name)
    if patronymic:
        names += "<" + encode_name(patronymic)
    l1 = ("PNRUS" + names)[:LINE_LEN].ljust(LINE_LEN, "<")

    doc = series[:3] + number
    dob = birth_date.strftime("%y%m%d")
    opt = series[3] + issue_date.strftime("%y%m%d") + division_code.replace("-", "") + "<"
    l2 = doc + check_digit(doc) + "RUS" + dob + check_digit(dob) + sex + "<" * 7 + opt + check_digit(opt)
    l2 += check_digit(l2[0:10] + l2[13:20] + l2[21:43])
    return l1, l2


class MRZAccumulator:
    """Посимвольное голосование по нескольким кадрам видеопотока.

    Каждый кадр по отдельности может содержать ошибки OCR, но большинство
    кадров редко ошибается в одной и той же позиции.
    """

    def __init__(self) -> None:
        self._frames: list[tuple[str, str]] = []

    def add(self, line1: str, line2: str) -> None:
        self._frames.append((_fit(_clean(line1), "PN"), _fit(_clean(line2))))

    def add_text(self, text: str) -> bool:
        lines = find_mrz_lines(text)
        if lines:
            self.add(*lines)
        return bool(lines)

    def __len__(self) -> int:
        return len(self._frames)

    @staticmethod
    def _vote(rows: Iterable[str]) -> str:
        cols = zip(*rows)
        return "".join(max(set(c), key=c.count) for c in cols)

    def result(self, today: Optional[date] = None) -> Optional[PassportData]:
        if not self._frames:
            return None
        l1 = self._vote(f[0] for f in self._frames)
        l2 = self._vote(f[1] for f in self._frames)
        return parse_mrz(l1, l2, today=today)
