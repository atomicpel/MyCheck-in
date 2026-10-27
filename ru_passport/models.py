from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date
from typing import Literal, Optional

Level = Literal["error", "warning", "info"]


@dataclass
class Issue:
    level: Level
    code: str
    message: str


@dataclass
class PassportData:
    """Результат распознавания паспорта гражданина РФ."""

    surname: Optional[str] = None
    name: Optional[str] = None
    patronymic: Optional[str] = None
    sex: Optional[Literal["M", "F"]] = None
    birth_date: Optional[date] = None
    birth_place: Optional[str] = None
    series: Optional[str] = None          # "4601"
    number: Optional[str] = None          # "123456"
    issue_date: Optional[date] = None
    division_code: Optional[str] = None   # "770-120"
    issued_by: Optional[str] = None
    citizenship: Optional[str] = None     # "RUS"

    # Производные поля
    region: Optional[str] = None
    valid_until: Optional[date] = None    # None + status "valid" = бессрочно (после 45 лет)
    replace_deadline: Optional[date] = None
    status: Optional[str] = None          # valid / replacement_period / expired

    # Диагностика
    source: str = "mrz"
    mrz_lines: Optional[tuple[str, str]] = None
    checks: dict[str, bool] = field(default_factory=dict)
    corrections: list[str] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    name_truncated: bool = False

    @property
    def series_number(self) -> Optional[str]:
        if self.series and self.number:
            return f"{self.series[:2]} {self.series[2:]} {self.number}"
        return None

    @property
    def is_valid(self) -> bool:
        """Нет ошибок: все контрольные цифры сошлись, документ не просрочен."""
        return not any(i.level == "error" for i in self.issues)

    @property
    def confidence(self) -> float:
        """Грубая оценка уверенности 0..1 по контрольным суммам и исправлениям."""
        if not self.checks:
            return 0.3 if self.series and self.number else 0.0
        score = sum(self.checks.values()) / len(self.checks)
        score -= 0.05 * len(self.corrections)
        score -= 0.1 * sum(i.level == "error" for i in self.issues)
        return round(max(0.0, min(1.0, score)), 3)

    def add(self, level: Level, code: str, message: str) -> None:
        self.issues.append(Issue(level, code, message))

    def to_dict(self) -> dict:
        d = asdict(self)
        for k, v in d.items():
            if isinstance(v, date):
                d[k] = v.isoformat()
        d["series_number"] = self.series_number
        d["is_valid"] = self.is_valid
        d["confidence"] = self.confidence
        return d
