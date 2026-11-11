"""Reproducible dataset splitting for random, temporal, and grouped holdouts."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Literal

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split

SplitStrategy = Literal["random", "temporal", "group"]


@dataclass(frozen=True, slots=True)
class SplitSpec:
    strategy: SplitStrategy = "random"
    test_size: float = 0.2
    validation_size: float = 0.0
    random_state: int = 42
    time_column: str | None = None
    group_column: str | None = None

    def __post_init__(self) -> None:
        if self.strategy not in ("random", "temporal", "group"):
            raise ValueError("切分策略必须是 random、temporal 或 group")
        if not 0.1 <= self.test_size <= 0.4:
            raise ValueError("测试集比例需在 0.1 到 0.4 之间")
        if not 0.0 <= self.validation_size < 0.4:
            raise ValueError("验证集比例需在 0 到 0.4 之间")
        if self.test_size + self.validation_size > 0.6:
            raise ValueError("测试集与验证集比例之和不能超过 0.6")
        if self.strategy == "temporal" and not self.time_column:
            raise ValueError("时间切分必须指定时间字段")
        if self.strategy == "group" and not self.group_column:
            raise ValueError("分组切分必须指定分组字段")


@dataclass(frozen=True, slots=True)
class SplitResult:
    train_indices: np.ndarray
    validation_indices: np.ndarray
    test_indices: np.ndarray
    summary: dict[str, Any]


def split_positions(
    frame: pd.DataFrame,
    spec: SplitSpec,
    *,
    target: pd.Series,
    task: str,
) -> SplitResult:
    """Return positional row indices for train/validation/test partitions."""
    if len(frame) != len(target):
        raise ValueError("切分数据与目标字段行数不一致")
    if spec.strategy == "temporal":
        return _temporal_split(frame, spec, task=task)
    if spec.strategy == "group":
        return _group_split(frame, spec, target=target, task=task)
    return _random_split(frame, spec, target=target, task=task)


def _random_split(
    frame: pd.DataFrame,
    spec: SplitSpec,
    *,
    target: pd.Series,
    task: str,
) -> SplitResult:
    indices = np.arange(len(frame), dtype=np.int64)
    test_count = math.ceil(len(indices) * spec.test_size)
    if test_count < 1 or test_count >= len(indices):
        raise ValueError("当前测试集比例无法产生有效的训练集和测试集")

    stratify: pd.Series | None = None
    if task == "classification":
        class_counts = target.value_counts()
        if class_counts.min() < 2:
            raise ValueError("每个目标类别至少需要 2 条记录，才能划分训练集和测试集")
        if test_count < len(class_counts) or len(indices) - test_count < len(class_counts):
            raise ValueError("当前测试集比例不足以覆盖所有类别，请增加数据或调整测试集比例")
        stratify = target

    try:
        remaining, test_indices = train_test_split(
            indices,
            test_size=test_count,
            random_state=spec.random_state,
            stratify=stratify,
        )
    except ValueError as error:
        raise ValueError(f"无法划分训练集和测试集：{error}") from error

    validation_indices = np.array([], dtype=np.int64)
    validation_count = math.ceil(len(indices) * spec.validation_size)
    if validation_count:
        if validation_count >= len(remaining):
            raise ValueError("验证集比例过大，训练集不足以继续切分")
        validation_stratify = None
        if task == "classification":
            remaining_target = target.iloc[remaining]
            if remaining_target.value_counts().min() < 2:
                raise ValueError("验证集切分后每个目标类别至少需要 2 条训练记录")
            validation_stratify = remaining_target
        try:
            remaining, validation_indices = train_test_split(
                remaining,
                test_size=validation_count,
                random_state=spec.random_state + 1,
                stratify=validation_stratify,
            )
        except ValueError as error:
            raise ValueError(f"无法划分验证集：{error}") from error

    train_indices = np.sort(remaining)
    summary = _base_summary("random", len(indices), train_indices, validation_indices, test_indices, spec)
    summary["stratified"] = task == "classification"
    return SplitResult(train_indices, np.sort(validation_indices), np.sort(test_indices), summary)


def _temporal_split(frame: pd.DataFrame, spec: SplitSpec, *, task: str) -> SplitResult:
    if spec.time_column not in frame.columns:
        raise ValueError(f"时间字段不存在：{spec.time_column}")
    raw_time = frame[spec.time_column]
    parsed = pd.to_datetime(raw_time, errors="coerce", utc=True)
    if parsed.isna().any():
        numeric = pd.to_numeric(raw_time, errors="coerce")
        if numeric.isna().any() or not np.isfinite(numeric.to_numpy(dtype=float)).all():
            raise ValueError("时间切分字段必须全部是有效时间或有限数值")
        sortable = numeric.to_numpy(dtype=float)
    else:
        sortable = parsed.astype("int64").to_numpy()

    order = np.argsort(sortable, kind="stable")
    indices = np.arange(len(frame), dtype=np.int64)[order]
    test_count = math.ceil(len(indices) * spec.test_size)
    validation_count = math.ceil(len(indices) * spec.validation_size)
    train_count = len(indices) - test_count - validation_count
    if train_count < 2:
        raise ValueError("时间切分后训练集不足，请降低测试集或验证集比例")

    train_indices = indices[:train_count]
    validation_indices = indices[train_count:train_count + validation_count]
    test_indices = indices[train_count + validation_count:]
    summary = _base_summary("temporal", len(indices), train_indices, validation_indices, test_indices, spec)
    summary["timeColumn"] = spec.time_column
    summary["timeRange"] = {
        "start": _json_time(raw_time.iloc[int(order[0])]),
        "end": _json_time(raw_time.iloc[int(order[-1])]),
    }
    summary["chronological"] = True
    summary["warnings"] = (
        ["时间切分不保证各分区包含全部目标类别"] if task == "classification" else []
    )
    return SplitResult(train_indices, validation_indices, test_indices, summary)


def _group_split(
    frame: pd.DataFrame,
    spec: SplitSpec,
    *,
    target: pd.Series,
    task: str,
) -> SplitResult:
    if spec.group_column not in frame.columns:
        raise ValueError(f"分组字段不存在：{spec.group_column}")
    groups = frame[spec.group_column].astype("string").str.strip()
    if groups.isna().any() or (groups == "").any():
        raise ValueError("分组字段不能包含空值")
    unique_groups = np.array(sorted(groups.unique().tolist()), dtype=object)
    if len(unique_groups) < 3:
        raise ValueError("分组切分至少需要 3 个不同分组")

    shuffled = unique_groups.copy()
    np.random.default_rng(spec.random_state).shuffle(shuffled)
    test_groups = math.ceil(len(shuffled) * spec.test_size)
    validation_groups = math.ceil(len(shuffled) * spec.validation_size)
    train_groups = len(shuffled) - test_groups - validation_groups
    if train_groups < 1:
        raise ValueError("分组切分后训练分组不足，请降低测试集或验证集比例")

    test_names = set(shuffled[:test_groups].tolist())
    validation_names = set(shuffled[test_groups:test_groups + validation_groups].tolist())
    train_names = set(shuffled[test_groups + validation_groups:].tolist())
    train_indices = np.flatnonzero(groups.isin(train_names).to_numpy()).astype(np.int64)
    validation_indices = np.flatnonzero(groups.isin(validation_names).to_numpy()).astype(np.int64)
    test_indices = np.flatnonzero(groups.isin(test_names).to_numpy()).astype(np.int64)

    summary = _base_summary("group", len(frame), train_indices, validation_indices, test_indices, spec)
    summary["groupColumn"] = spec.group_column
    summary["groupCount"] = int(len(unique_groups))
    summary["splitGroupCounts"] = {
        "train": len(train_names),
        "validation": len(validation_names),
        "test": len(test_names),
    }
    summary["groupIsolation"] = True
    warnings: list[str] = []
    if task == "classification":
        for name, positions in (
            ("训练集", train_indices),
            ("验证集", validation_indices),
            ("测试集", test_indices),
        ):
            if len(positions) and target.iloc[positions].nunique() < 2:
                warnings.append(f"{name}只包含一个目标类别")
    summary["warnings"] = warnings
    return SplitResult(train_indices, validation_indices, test_indices, summary)


def _base_summary(
    strategy: str,
    total: int,
    train: np.ndarray,
    validation: np.ndarray,
    test: np.ndarray,
    spec: SplitSpec,
) -> dict[str, Any]:
    return {
        "strategy": strategy,
        "totalRows": int(total),
        "trainRows": int(len(train)),
        "validationRows": int(len(validation)),
        "testRows": int(len(test)),
        "trainRatio": round(len(train) / total, 6) if total else 0.0,
        "validationRatio": round(len(validation) / total, 6) if total else 0.0,
        "testRatio": round(len(test) / total, 6) if total else 0.0,
        "testSize": spec.test_size,
        "validationSize": spec.validation_size,
        "randomState": spec.random_state,
        "warnings": [],
    }


def _json_time(value: Any) -> Any:
    if isinstance(value, (pd.Timestamp, np.datetime64)):
        return pd.Timestamp(value).isoformat()
    if isinstance(value, np.generic):
        return value.item()
    return value
