from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from app.ml.training.splitting import SplitSpec, split_positions


def _frame(rows: int = 60) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "feature": np.arange(rows),
            "label": ["yes" if index % 2 else "no" for index in range(rows)],
            "event_time": pd.date_range("2024-01-01", periods=rows, freq="D"),
            "customer": [f"c{index % 12}" for index in range(rows)],
        }
    )


def test_random_split_is_reproducible_and_disjoint_with_validation() -> None:
    frame = _frame()
    spec = SplitSpec(validation_size=0.2, random_state=7)

    first = split_positions(frame, spec, target=frame["label"], task="classification")
    second = split_positions(frame, spec, target=frame["label"], task="classification")

    assert np.array_equal(first.train_indices, second.train_indices)
    assert np.array_equal(first.test_indices, second.test_indices)
    assert len(first.train_indices) + len(first.validation_indices) + len(first.test_indices) == 60
    assert not set(first.train_indices) & set(first.test_indices)
    assert not set(first.train_indices) & set(first.validation_indices)
    assert not set(first.validation_indices) & set(first.test_indices)
    assert first.summary["strategy"] == "random"
    assert first.summary["validationRows"] == 12


def test_temporal_split_keeps_chronology() -> None:
    frame = _frame()

    split = split_positions(
        frame,
        SplitSpec(strategy="temporal", time_column="event_time", test_size=0.25),
        target=frame["label"],
        task="classification",
    )

    assert frame.loc[split.train_indices, "event_time"].max() < frame.loc[split.test_indices, "event_time"].min()
    assert split.summary["chronological"] is True
    assert split.summary["timeColumn"] == "event_time"


def test_group_split_never_leaks_a_customer_across_partitions() -> None:
    frame = _frame()

    split = split_positions(
        frame,
        SplitSpec(strategy="group", group_column="customer", validation_size=0.15),
        target=frame["label"],
        task="classification",
    )

    train_groups = set(frame.loc[split.train_indices, "customer"])
    validation_groups = set(frame.loc[split.validation_indices, "customer"])
    test_groups = set(frame.loc[split.test_indices, "customer"])
    assert train_groups.isdisjoint(validation_groups)
    assert train_groups.isdisjoint(test_groups)
    assert validation_groups.isdisjoint(test_groups)
    assert split.summary["groupIsolation"] is True


def test_temporal_and_group_configuration_requires_columns() -> None:
    with pytest.raises(ValueError, match="时间字段"):
        SplitSpec(strategy="temporal")
    with pytest.raises(ValueError, match="分组字段"):
        SplitSpec(strategy="group")
