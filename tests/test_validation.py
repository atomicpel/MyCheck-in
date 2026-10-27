from datetime import date

from ru_passport import build_mrz, parse
from ru_passport.validation import validity

TODAY = date(2026, 10, 27)


def mk(born, issued, series="4601", today=TODAY):
    return parse(*build_mrz("Петров", "Пётр", "Петрович", series, "654321", "M", born, issued, "500-001"), today=today)


def test_validity_periods():
    assert validity(date(2000, 1, 10), date(2014, 2, 1)) == date(2020, 1, 10)
    assert validity(date(2000, 1, 10), date(2020, 2, 1)) == date(2045, 1, 10)
    assert validity(date(1970, 1, 10), date(2015, 2, 1)) is None
    assert validity(date(2004, 2, 29), date(2018, 3, 1)) == date(2024, 2, 29)


def test_valid_until_45():
    d = mk(date(1990, 3, 15), date(2010, 6, 20))
    assert d.status == "valid" and d.valid_until == date(2035, 3, 15) and d.is_valid


def test_indefinite_after_45():
    d = mk(date(1970, 3, 15), date(2016, 6, 20), series="4616")
    assert d.status == "valid" and d.valid_until is None and d.is_valid


def test_replacement_period_is_warning():
    d = mk(date(2006, 9, 1), date(2020, 9, 15), series="4520")
    assert d.status == "replacement_period"
    assert d.is_valid and any(i.code == "replacement" for i in d.issues)


def test_expired():
    d = mk(date(2005, 1, 1), date(2019, 2, 1), series="4519")
    assert d.status == "expired" and not d.is_valid


def test_issued_before_14_is_error():
    d = mk(date(2010, 1, 1), date(2022, 1, 1), series="4521")
    assert any(i.code == "issue_age" for i in d.issues) and not d.is_valid


def test_unknown_region_warning():
    d = mk(date(1990, 3, 15), date(2010, 6, 20), series="0201")
    assert d.region is None and any(i.code == "series_region" for i in d.issues)


def test_series_year_after_issue_warning():
    d = mk(date(1990, 3, 15), date(2010, 6, 20), series="4515")
    assert any(i.code == "series_year" for i in d.issues)
