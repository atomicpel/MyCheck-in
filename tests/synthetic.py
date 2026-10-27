"""Генератор синтетического скана страницы паспорта для сквозных тестов OCR."""

from datetime import date

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from ru_passport import build_mrz

MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

PERSON = dict(surname="Смирнова", name="Ольга", patronymic="Сергеевна", series="4512", number="345678",
              sex="F", birth_date=date(1985, 7, 9), issue_date=date(2012, 8, 14), division_code="770-095")


def render(path, angle=0.0, blur=0.0, person=PERSON):
    W, H = 1700, 1150
    img = Image.new("RGB", (W, H), (246, 238, 236))
    d = ImageDraw.Draw(img)
    sans, small, mono = ImageFont.truetype(SANS, 34), ImageFont.truetype(SANS, 22), ImageFont.truetype(MONO, 43)
    p = person
    rows = [
        ("Фамилия", p["surname"].upper()), ("Имя", p["name"].upper()), ("Отчество", p["patronymic"].upper()),
        ("Пол", "ЖЕН." if p["sex"] == "F" else "МУЖ."),
        ("Дата рождения", p["birth_date"].strftime("%d.%m.%Y")),
        ("Место рождения", "ГОР. МОСКВА"),
    ]
    d.rectangle((60, 120, 420, 600), outline=(160, 160, 160), width=3)  # «фото»
    y = 140
    for label, value in rows:
        d.text((480, y), label, font=small, fill=(90, 90, 90))
        d.text((480 + 260, y - 6), value, font=sans, fill=(20, 20, 20))
        y += 75
    s = p["series"]
    vert = Image.new("RGB", (560, 60), (246, 238, 236))
    ImageDraw.Draw(vert).text((0, 5), f"{s[:2]} {s[2:]} {p['number']}", font=ImageFont.truetype(SANS, 44),
                              fill=(170, 30, 30))
    img.paste(vert.rotate(90, expand=True), (W - 110, 120))
    l1, l2 = build_mrz(**p)
    d.text((70, 900), l1, font=mono, fill=(0, 0, 0))
    d.text((70, 980), l2, font=mono, fill=(0, 0, 0))
    if angle:
        img = img.rotate(angle, expand=True, fillcolor=(120, 120, 120), resample=Image.BICUBIC)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    img.save(path)
    return l1, l2
