"""Работа с изображением: поиск MRZ, предобработка, OCR (OpenCV + Tesseract).

Зависимости ставятся отдельно: pip install "ru-passport[ocr]" и системный
tesseract с языками eng и rus.
"""

from __future__ import annotations

from collections import Counter
from datetime import date
from pathlib import Path
from typing import Optional, Union

try:
    import cv2
    import numpy as np
    import pytesseract
except ImportError as e:  # pragma: no cover
    raise ImportError('Для OCR нужны зависимости: pip install "ru-passport[ocr]"') from e

from .models import PassportData
from .mrz import parse_mrz, find_mrz_lines

ImageInput = Union[str, Path, bytes, "np.ndarray"]

MRZ_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
_MRZ_CONFIG = f"--oem 1 --psm 6 -c tessedit_char_whitelist={MRZ_WHITELIST}"
_ROTATIONS = (None, cv2.ROTATE_180, cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE)


def load_image(src: ImageInput) -> "np.ndarray":
    if isinstance(src, np.ndarray):
        return src
    if isinstance(src, (str, Path)):
        buf = np.fromfile(str(src), dtype=np.uint8)
    else:
        buf = np.frombuffer(src, dtype=np.uint8)
    img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Не удалось прочитать изображение")
    return img


def _gray(img: "np.ndarray") -> "np.ndarray":
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img


def _resize_width(img: "np.ndarray", width: int) -> tuple["np.ndarray", float]:
    k = width / img.shape[1]
    return cv2.resize(img, None, fx=k, fy=k, interpolation=cv2.INTER_AREA if k < 1 else cv2.INTER_CUBIC), k


def locate_mrz(img: "np.ndarray") -> list[tuple[int, int, int, int]]:
    """Прямоугольники-кандидаты (x, y, w, h) с MRZ, сверху вниз по уверенности.

    Классический приём: blackhat выделяет тёмный текст на светлом фоне,
    горизонтальный градиент + морфологическое закрытие склеивают строки MRZ
    в одну широкую плашку.
    """
    gray = _gray(img)
    small, k = _resize_width(gray, 1000)
    small = cv2.GaussianBlur(small, (3, 3), 0)
    rect = cv2.getStructuringElement(cv2.MORPH_RECT, (25, 7))
    sq = cv2.getStructuringElement(cv2.MORPH_RECT, (31, 31))

    blackhat = cv2.morphologyEx(small, cv2.MORPH_BLACKHAT, rect)
    grad = np.absolute(cv2.Sobel(blackhat, cv2.CV_32F, 1, 0, ksize=-1))
    grad = cv2.normalize(grad, None, 0, 255, cv2.NORM_MINMAX).astype("uint8")
    grad = cv2.morphologyEx(grad, cv2.MORPH_CLOSE, rect)
    thresh = cv2.threshold(grad, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)[1]
    thresh = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, sq)
    thresh = cv2.erode(thresh, None, iterations=3)

    H, W = small.shape
    found = []
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for c in contours:
        x, y, w, h = cv2.boundingRect(c)
        if w / max(h, 1) > 4 and w / W > 0.55:
            px, py = int(w * 0.04), int(h * 0.25)
            x, y = max(0, x - px), max(0, y - py)
            w, h = min(W - x, w + 2 * px), min(H - y, h + 2 * py)
            # Ниже на странице — вероятнее MRZ.
            found.append(((y + h / 2) / H, (int(x / k), int(y / k), int(w / k), int(h / k))))
    found.sort(key=lambda t: -t[0])
    boxes = [b for _, b in found]
    # Запасные варианты: нижняя треть и весь кадр (если прислали уже вырезанную MRZ).
    h0, w0 = gray.shape
    boxes += [(0, int(h0 * 0.65), w0, h0 - int(h0 * 0.65)), (0, 0, w0, h0)]
    return boxes


def estimate_skew(gray: "np.ndarray", limit: float = 8.0) -> float:
    """Угол наклона текста (градусы) методом проекционного профиля:
    при правильном угле строки текста дают самые «резкие» горизонтальные суммы."""
    small, _ = _resize_width(gray, 800)
    inv = cv2.threshold(small, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)[1]
    h, w = inv.shape
    center = (w / 2, h / 2)

    def sharpness(angle: float) -> float:
        m = cv2.getRotationMatrix2D(center, angle, 1.0)
        rows = cv2.warpAffine(inv, m, (w, h)).sum(axis=1, dtype=np.float64)
        return float(np.square(np.diff(rows)).sum())

    best = max(np.arange(-limit, limit + 0.01, 0.5), key=sharpness)
    return float(max(np.arange(best - 0.5, best + 0.51, 0.1), key=sharpness))


def deskew(img: "np.ndarray") -> "np.ndarray":
    angle = estimate_skew(_gray(img))
    if abs(angle) < 0.2:
        return img
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(img, m, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def _variants(roi: "np.ndarray"):
    """Несколько вариантов предобработки: какой-нибудь да прочитается чисто."""
    gray = _gray(roi)
    # Высота символа ~30-40 px — оптимум для Tesseract.
    target_w = 1400
    if gray.shape[1] < target_w:
        gray, _ = _resize_width(gray, target_w)
    yield gray
    yield cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)[1]
    yield cv2.adaptiveThreshold(cv2.GaussianBlur(gray, (3, 3), 0), 255,
                                cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 15)


def _score(d: PassportData) -> float:
    return sum(d.checks.values()) * 10 - len(d.corrections) - 5 * sum(i.level == "error" for i in d.issues)


def read_mrz(img: ImageInput, today: Optional[date] = None,
             lang: str = "eng") -> Optional[PassportData]:
    """Найти и прочитать MRZ (см. read_mrz_oriented)."""
    return read_mrz_oriented(img, today, lang)[0]


def read_mrz_oriented(img: ImageInput, today: Optional[date] = None,
                      lang: str = "eng") -> tuple[Optional[PassportData], "np.ndarray"]:
    """Найти и прочитать MRZ. Возвращает также выровненный кадр в той
    ориентации, в которой нашлась MRZ, — по нему удобно читать остальное.

    Перебирает повороты, области и варианты предобработки. Строка 2 защищена
    контрольными цифрами — берём лучшее чтение. У строки 1 (ФИО) контрольной
    цифры нет, поэтому её выбираем голосованием среди всех верных чтений.
    """
    img = load_image(img)
    readings: list[tuple[PassportData, "np.ndarray"]] = []
    perfect: list[PassportData] = []
    for rot in _ROTATIONS:
        frame = deskew(img if rot is None else cv2.rotate(img, rot))
        for x, y, w, h in locate_mrz(frame)[:4]:
            roi = frame[y:y + h, x:x + w]
            for variant in _variants(roi):
                text = pytesseract.image_to_string(variant, lang=lang, config=_MRZ_CONFIG)
                lines = find_mrz_lines(text)
                if not lines:
                    continue
                data = parse_mrz(*lines, today=today)
                readings.append((data, frame))
                if all(data.checks.values()):
                    perfect.append(data)
            if len(perfect) >= 3:
                break
        if perfect:
            break
    if not readings:
        return None, img

    best, frame = max(readings, key=lambda r: _score(r[0]))
    pool = [d for d, _ in readings if d.mrz_lines[1] == best.mrz_lines[1]]
    line1 = Counter(d.mrz_lines[0] for d in pool).most_common(1)[0][0]
    if line1 == best.mrz_lines[0]:
        return best, frame
    result = parse_mrz(line1, best.mrz_lines[1], today=today)
    result.corrections = sorted(set(result.corrections) | set(best.corrections))
    return result, frame


def read_viz(img: ImageInput) -> str:
    """OCR визуальной зоны на русском. Серия и номер напечатаны вертикально
    у правого края — поэтому дополнительно читаем повёрнутую правую полосу."""
    img = load_image(img)
    gray = _gray(deskew(img))
    if gray.shape[1] < 1600:
        gray, _ = _resize_width(gray, 1600)
    text = pytesseract.image_to_string(gray, lang="rus", config="--oem 1 --psm 4")
    h, w = gray.shape
    strip = gray[:, int(w * 0.85):]
    for rot in (cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE):
        side = pytesseract.image_to_string(cv2.rotate(strip, rot), lang="eng",
                                           config="--oem 1 --psm 7 -c tessedit_char_whitelist=0123456789 ")
        if sum(ch.isdigit() for ch in side) >= 10:
            text += "\n" + side
            break
    return text
