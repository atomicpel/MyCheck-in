"""Высокоуровневый API: картинка или текст -> проверенные данные паспорта."""

from __future__ import annotations

from datetime import date
from typing import Optional

from .models import PassportData
from .mrz import parse_mrz, parse_mrz_text
from .validation import validate
from .viz import merge, parse_viz


def scan(image, today: Optional[date] = None, with_viz: bool = True) -> PassportData:
    """Распознать паспорт по фото/скану разворота (путь, bytes или numpy-массив)."""
    from . import ocr  # тяжёлые зависимости грузим только при работе с картинками

    img = ocr.load_image(image)
    mrz, frame = ocr.read_mrz_oriented(img, today=today)
    if with_viz:
        data = merge(mrz, parse_viz(ocr.read_viz(frame)), today=today)
    elif mrz is None:
        data = PassportData(source="none")
        data.add("error", "not_found", "MRZ на изображении не найдена")
        return data
    else:
        data = mrz
    return validate(data, today=today)


def parse(line1: str, line2: str, today: Optional[date] = None) -> PassportData:
    """Разобрать и проверить две строки MRZ (например, из сканера MRZ или камеры)."""
    return validate(parse_mrz(line1, line2, today=today), today=today)


def parse_text(text: str, viz_text: Optional[str] = None, today: Optional[date] = None) -> PassportData:
    """Разобрать произвольный текст OCR: найти MRZ, опционально свести с визуальной зоной."""
    mrz = parse_mrz_text(text, today=today)
    if viz_text is not None:
        data = merge(mrz, parse_viz(viz_text), today=today)
    elif mrz is None:
        data = PassportData(source="none")
        data.add("error", "not_found", "MRZ в тексте не найдена")
        return data
    else:
        data = mrz
    return validate(data, today=today)
