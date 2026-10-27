from datetime import date

import pytest

from ru_passport import MRZAccumulator, build_mrz, check_digit, parse
from ru_passport.mrz import find_mrz_lines, parse_mrz
from ru_passport.translit import decode_name, encode_name

TODAY = date(2026, 10, 27)
SAMPLE = dict(surname="Иванов", name="Иван", patronymic="Иванович", series="4601", number="123456",
              sex="M", birth_date=date(1990, 3, 15), issue_date=date(2010, 6, 20), division_code="770-120")


def sample(**kw):
    return build_mrz(**{**SAMPLE, **kw})


def test_check_digit_icao_examples():
    # Примеры из ICAO Doc 9303
    assert check_digit("L898902C3") == "6"
    assert check_digit("740812") == "2"
    assert check_digit("120415") == "9"
    assert check_digit("<<<<<<") == "0"


def test_consultant_example_optional_field():
    # Серия 4601, выдан 20.06.2010, код 770-120 -> "1100620770120"
    _, l2 = sample()
    assert l2[28:41] == "1100620770120"
    assert len(l2) == 44


def test_roundtrip():
    l1, l2 = sample()
    assert l1 == "PNRUSIVANOV<<IVAN<IVANOVI3<<<<<<<<<<<<<<<<<<"
    d = parse(l1, l2, today=TODAY)
    assert (d.surname, d.name, d.patronymic) == ("Иванов", "Иван", "Иванович")
    assert (d.series, d.number, d.series_number) == ("4601", "123456", "46 01 123456")
    assert d.birth_date == date(1990, 3, 15)
    assert d.issue_date == date(2010, 6, 20)
    assert d.division_code == "770-120"
    assert d.sex == "M" and d.citizenship == "RUS"
    assert all(d.checks.values()) and not d.corrections
    assert d.is_valid and d.confidence == 1.0
    assert d.region == "Московская область"


def test_translit_is_reversible_for_whole_alphabet():
    alphabet = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    enc = encode_name(alphabet)
    assert len(enc) == len(alphabet) == len(set(enc))
    assert decode_name(enc) == alphabet


def test_special_letters():
    l1, l2 = sample(surname="Щёголева", name="Юлия", patronymic="Эдуардовна", sex="F")
    assert l1.startswith("PNRUSW2GOLEVA<<7LI8<6DUARDOVNA<")
    d = parse(l1, l2, today=TODAY)
    assert (d.surname, d.name, d.patronymic, d.sex) == ("Щёголева", "Юлия", "Эдуардовна", "F")


def test_no_patronymic_and_compound_patronymic():
    d = parse(*sample(patronymic=""), today=TODAY)
    assert d.patronymic is None
    d = parse(*sample(surname="Алиев", name="Эльчин", patronymic="Ахмед оглы"), today=TODAY)
    assert d.patronymic == "Ахмед Оглы"


def test_truncated_name_flagged():
    d = parse(*sample(surname="Константинопольский", name="Александр", patronymic="Константинович"), today=TODAY)
    assert d.name_truncated
    assert any(i.code == "name_truncated" for i in d.issues)


def test_ocr_noise_is_normalized():
    l1, l2 = sample()
    noisy1 = "РNRUS" + l1[5:].replace("<<<<", "<K<<", 1).replace("<", "«", 3)   # кириллическая Р, K и « в заполнителе
    noisy2 = l2.replace("0", "O", 1).replace("<", "«", 2)                        # O вместо нуля
    d = parse(noisy1, noisy2, today=TODAY)
    assert d.surname == "Иванов" and d.patronymic == "Иванович"
    assert d.number == "123456" and all(d.checks.values())
    assert d.corrections


@pytest.mark.parametrize("pos,wrong", [(15, "6"), (31, "8"), (9, "7")])
def test_single_digit_misread_repaired_by_check_digits(pos, wrong):
    l1, l2 = sample()
    assert l2[pos] != wrong
    bad = l2[:pos] + wrong + l2[pos + 1:]
    d = parse(l1, bad, today=TODAY)
    assert d.mrz_lines[1] == l2, d.corrections
    assert all(d.checks.values())
    assert any("контрольной цифре" in c for c in d.corrections)


@pytest.mark.parametrize("pos,wrong", [(4, "7"), (37, "8")])
def test_ambiguous_misread_is_not_guessed(pos, wrong):
    # Несколько похожих цифр дают ту же контрольную сумму — молча угадывать нельзя.
    l1, l2 = sample()
    bad = l2[:pos] + wrong + l2[pos + 1:]
    d = parse(l1, bad, today=TODAY)
    assert d.mrz_lines[1] == bad
    assert not all(d.checks.values()) and not d.is_valid


def test_unrepairable_error_reported():
    l1, l2 = sample()
    bad = l2[:3] + "999" + l2[6:]
    d = parse(l1, bad, today=TODAY)
    assert not d.checks["number"]
    assert not d.is_valid


def test_find_lines_in_garbage_and_glued():
    l1, l2 = sample()
    text = f"РОССИЙСКАЯ ФЕДЕРАЦИЯ\nкакой-то мусор 123\n {l1[:20]} {l1[20:]} \n{l2}\n\n"
    assert find_mrz_lines(text) == (l1, l2)
    assert find_mrz_lines("xx" + l1 + l2 + "yy") == (l1, l2)
    assert find_mrz_lines("ничего похожего") is None


def test_short_line_is_padded():
    l1, l2 = sample()
    d = parse_mrz(l1.rstrip("<"), l2, today=TODAY)
    assert d.mrz_lines[0] == l1


def test_accumulator_votes_out_per_frame_errors():
    l1, l2 = sample()
    acc = MRZAccumulator()
    for pos in (2, 15, 33):
        acc.add(l1, l2[:pos] + "X" + l2[pos + 1:])
    acc.add(l1, l2)
    d = acc.result(today=TODAY)
    assert d.mrz_lines == (l1, l2) and not d.corrections


def test_birth_century_resolution():
    d = parse(*sample(birth_date=date(2008, 5, 1), issue_date=date(2022, 6, 1)), today=TODAY)
    assert d.birth_date == date(2008, 5, 1)
    d = parse(*sample(birth_date=date(1930, 5, 1), issue_date=date(2001, 6, 1)), today=TODAY)
    assert d.birth_date == date(1930, 5, 1)


def test_misread_composite_digit_fixed():
    l1, l2 = sample()
    wrong = next(c for c in "0123456789" if c != l2[43] and l2[43] in __import__(
        "ru_passport.mrz", fromlist=["x"])._DIGIT_CONFUSIONS[c])
    d = parse(l1, l2[:43] + wrong, today=TODAY)
    assert d.mrz_lines[1] == l2 and all(d.checks.values())
