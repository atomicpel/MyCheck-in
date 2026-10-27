from datetime import date

from ru_passport import build_mrz, parse_text
from ru_passport.viz import parse_viz

TODAY = date(2026, 10, 27)
VIZ = """РОССИЙСКАЯ ФЕДЕРАЦИЯ
Паспорт выдан ОТДЕЛОМ УФМС РОССИИ ПО ГОР. МОСКВЕ
ПО РАЙОНУ ТВЕРСКОЙ
Дата выдачи 20.06.2010 Код подразделения 770-120
Фамилия ИВАНОВ
Имя ИВАН
Отчество ИВАНОВИЧ
Пол МУЖ. Дата рождения 15.03.1990
Место рождения ГОР. МОСКВА
46 01 123456
PNRUSIVANOV<<IVAN<IVANOVI3<<<<<<<<<<<<<<<<<<
"""


def test_parse_viz_fields():
    f = parse_viz(VIZ)
    assert f.issued_by == "ОТДЕЛОМ УФМС РОССИИ ПО ГОР. МОСКВЕ ПО РАЙОНУ ТВЕРСКОЙ"
    assert f.issue_date == date(2010, 6, 20) and f.birth_date == date(1990, 3, 15)
    assert f.division_code == "770-120"
    assert (f.surname, f.name, f.patronymic, f.sex) == ("Иванов", "Иван", "Иванович", "M")
    assert f.birth_place == "ГОР. МОСКВА"
    assert (f.series, f.number) == ("4601", "123456")


def test_merge_fills_fields_and_detects_mismatch():
    l1, l2 = build_mrz("Иванов", "Иван", "Иванович", "4601", "123456", "M",
                       date(1990, 3, 15), date(2010, 6, 20), "770-120")
    d = parse_text(f"{l1}\n{l2}", viz_text=VIZ, today=TODAY)
    assert d.source == "mrz+viz" and d.issued_by.startswith("ОТДЕЛОМ УФМС")
    assert d.birth_place == "ГОР. МОСКВА"
    assert not [i for i in d.issues if i.code.startswith("mismatch")]

    d = parse_text(f"{l1}\n{l2}", viz_text=VIZ.replace("770-120", "770-121"), today=TODAY)
    assert any(i.code == "mismatch_division_code" for i in d.issues)


def test_hyphenated_surname_restored_from_viz():
    l1, l2 = build_mrz("Римский-Корсаков", "Николай", "Андреевич", "4601", "123456", "M",
                       date(1990, 3, 15), date(2010, 6, 20), "770-120")
    viz = VIZ.replace("ИВАНОВ\n", "РИМСКИЙ-КОРСАКОВ\n", 1).replace("ИВАН\n", "НИКОЛАЙ\n").replace("ИВАНОВИЧ", "АНДРЕЕВИЧ")
    d = parse_text(f"{l1}\n{l2}", viz_text=viz, today=TODAY)
    assert d.surname == "Римский-Корсаков"


def test_viz_only_fallback():
    d = parse_text("", viz_text=VIZ, today=TODAY)
    assert d.source == "viz" and d.series == "4601" and d.number == "123456"
    assert any(i.code == "no_mrz" for i in d.issues)


def test_ambiguous_mrz_error_resolved_by_viz():
    l1, l2 = build_mrz("Иванов", "Иван", "Иванович", "4601", "123456", "M",
                       date(1990, 3, 15), date(2010, 6, 20), "770-120")
    bad = l2[:4] + "7" + l2[5:37] + "8" + l2[38:]
    d = parse_text(f"{l1}\n{bad}", viz_text=VIZ, today=TODAY)
    assert d.mrz_lines[1] == l2 and all(d.checks.values()) and d.is_valid
    assert any("по визуальной зоне" in c for c in d.corrections)


def test_viz_value_rejected_if_check_digit_disagrees():
    l1, l2 = build_mrz("Иванов", "Иван", "Иванович", "4601", "123456", "M",
                       date(1990, 3, 15), date(2010, 6, 20), "770-120")
    bad = l2[:4] + "7" + l2[5:]
    d = parse_text(f"{l1}\n{bad}", viz_text=VIZ.replace("123456", "123450"), today=TODAY)
    assert not d.checks["number"] and not d.is_valid


def test_line2_with_covered_start_is_aligned_and_restored_from_viz():
    # Начало строки 2 закрыто пальцем: OCR видит только "...3456" + контрольную.
    l1, l2 = build_mrz("Иванов", "Иван", "Иванович", "4601", "123456", "M",
                       date(1990, 3, 15), date(2010, 6, 20), "770-120")
    d = parse_text(f"{l1}\n{l2[5:]}", today=TODAY)
    assert d.birth_date == date(1990, 3, 15) and d.division_code == "770-120"
    assert not d.checks["number"] and any(i.code == "line2_cut" for i in d.issues)

    d = parse_text(f"{l1}\n{l2[5:]}", viz_text=VIZ, today=TODAY)
    assert d.mrz_lines[1] == l2 and all(d.checks.values())
    assert d.series_number == "46 01 123456"
