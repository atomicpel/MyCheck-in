"""Логические проверки данных паспорта поверх контрольных цифр MRZ."""

from __future__ import annotations

import re
from datetime import date, timedelta
from typing import Optional

from .models import PassportData
from .regions import SERIES_REGIONS

# Паспорт нового образца выдаётся с 1 октября 1997 г.
FIRST_ISSUE = date(1997, 10, 1)
MIN_AGE = 14
REPLACE_AGES = (20, 45)
# Срок подачи документов на замену после 20/45 лет.
REPLACE_GRACE = timedelta(days=90)


def _birthday(born: date, age: int) -> date:
    try:
        return born.replace(year=born.year + age)
    except ValueError:  # 29 февраля
        return date(born.year + age, 3, 1)


def age_on(born: date, day: date) -> int:
    return day.year - born.year - ((day.month, day.day) < (born.month, born.day))


def validity(born: date, issued: date) -> Optional[date]:
    """Дата, до которой действует паспорт (день 20/45-летия), или None — бессрочно."""
    for age in REPLACE_AGES:
        b = _birthday(born, age)
        if issued < b:
            return b
    return None


def validate(data: PassportData, today: Optional[date] = None) -> PassportData:
    today = today or date.today()

    if data.citizenship and data.citizenship != "RUS":
        data.add("error", "citizenship", f"Гражданство {data.citizenship}, ожидалось RUS")

    if data.series:
        if not re.fullmatch(r"\d{4}", data.series):
            data.add("error", "series_format", f"Серия должна состоять из 4 цифр: {data.series}")
        else:
            data.region = SERIES_REGIONS.get(data.series[:2])
            if data.region is None:
                data.add("warning", "series_region", f"Неизвестный код региона в серии: {data.series[:2]}")
    if data.number and not re.fullmatch(r"\d{6}", data.number):
        data.add("error", "number_format", f"Номер должен состоять из 6 цифр: {data.number}")
    if data.division_code and not re.fullmatch(r"\d{3}-\d{3}", data.division_code):
        data.add("error", "division_format", f"Код подразделения должен быть вида 000-000: {data.division_code}")

    born, issued = data.birth_date, data.issue_date
    if born and born > today:
        data.add("error", "birth_future", "Дата рождения в будущем")
    if issued:
        if issued > today:
            data.add("error", "issue_future", "Дата выдачи в будущем")
        if issued < FIRST_ISSUE:
            data.add("error", "issue_too_early", "Паспорта нового образца выдаются с 01.10.1997")
        if data.series and data.series[2:].isdigit():
            yy = int(data.series[2:])
            blank_year = 1900 + yy if yy >= 97 else 2000 + yy
            if issued.year < blank_year - 1:
                data.add("warning", "series_year",
                         f"Бланк {blank_year} г. выдан раньше ({issued.year} г.) — проверьте серию")

    if born and issued:
        age = age_on(born, issued)
        if age < MIN_AGE:
            data.add("error", "issue_age", f"Паспорт выдан в {age} лет (с {MIN_AGE} лет)")
        data.valid_until = validity(born, issued)
        if data.valid_until is None:
            data.status = "valid"
        else:
            data.replace_deadline = data.valid_until + REPLACE_GRACE
            if today < data.valid_until:
                data.status = "valid"
            elif today <= data.replace_deadline:
                data.status = "replacement_period"
                data.add("warning", "replacement",
                         f"Паспорт подлежит замене (до {data.replace_deadline:%d.%m.%Y})")
            else:
                data.status = "expired"
                data.add("error", "expired", f"Паспорт недействителен с {data.valid_until:%d.%m.%Y}")
    return data
