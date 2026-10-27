import shutil
from datetime import date

import pytest

pytest.importorskip("cv2")
pytest.importorskip("pytesseract")
if not shutil.which("tesseract"):
    pytest.skip("tesseract не установлен", allow_module_level=True)

from ru_passport import scan  # noqa: E402

from .synthetic import PERSON, render  # noqa: E402

TODAY = date(2026, 10, 27)


@pytest.mark.parametrize("angle,blur", [(0, 0), (2.5, 0.8), (-6, 1.2), (180, 0), (90, 0)])
def test_scan_synthetic_page(tmp_path, angle, blur):
    path = tmp_path / "page.png"
    l1, l2 = render(path, angle=angle, blur=blur)
    d = scan(str(path), today=TODAY)
    assert d.mrz_lines == (l1, l2), d.corrections
    assert (d.surname, d.name, d.patronymic) == ("Смирнова", "Ольга", "Сергеевна")
    assert (d.series, d.number) == (PERSON["series"], PERSON["number"])
    assert d.birth_date == PERSON["birth_date"] and d.issue_date == PERSON["issue_date"]
    assert d.division_code == "770-095"
    assert d.birth_place == "ГОР. МОСКВА"
    assert d.is_valid, d.issues
