"""Транслитерация ФИО для машиночитаемой записи внутреннего паспорта РФ.

В MRZ внутреннего паспорта используется собственная таблица МВД, а не ICAO:
каждой букве кириллицы соответствует ровно один символ, поэтому запись
обратима — из MRZ можно точно восстановить ФИО на кириллице.
"""

CYR_TO_MRZ = {
    "А": "A", "Б": "B", "В": "V", "Г": "G", "Д": "D", "Е": "E", "Ё": "2",
    "Ж": "J", "З": "Z", "И": "I", "Й": "Q", "К": "K", "Л": "L", "М": "M",
    "Н": "N", "О": "O", "П": "P", "Р": "R", "С": "S", "Т": "T", "У": "U",
    "Ф": "F", "Х": "H", "Ц": "C", "Ч": "3", "Ш": "4", "Щ": "W", "Ъ": "X",
    "Ы": "Y", "Ь": "9", "Э": "6", "Ю": "7", "Я": "8",
}
MRZ_TO_CYR = {v: k for k, v in CYR_TO_MRZ.items()}

# Пробел и дефис в составных именах (Анна-Мария, Мамин Сибиряк) пишутся как "<".
_SEPARATORS = {" ": "<", "-": "<"}


def encode_name(text: str) -> str:
    """Кириллица -> символы MRZ. Неизвестные символы вызывают ValueError."""
    out = []
    for ch in text.upper():
        if ch in CYR_TO_MRZ:
            out.append(CYR_TO_MRZ[ch])
        elif ch in _SEPARATORS:
            out.append(_SEPARATORS[ch])
        else:
            raise ValueError(f"Символ {ch!r} не может быть записан в MRZ")
    return "".join(out)


def decode_name(mrz: str) -> str:
    """Символы MRZ -> кириллица (заглавными). '<' внутри части имени -> пробел."""
    out = []
    for ch in mrz:
        if ch == "<":
            out.append(" ")
        elif ch in MRZ_TO_CYR:
            out.append(MRZ_TO_CYR[ch])
        else:
            out.append("?")
    return "".join(out).strip()


def pretty(name: str) -> str:
    """ИВАНОВ-ПЕТРОВ -> Иванов-Петров."""
    return "-".join(
        " ".join(w.capitalize() for w in part.split(" ")) for part in name.split("-")
    )
