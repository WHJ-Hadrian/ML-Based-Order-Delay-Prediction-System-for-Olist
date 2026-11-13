"""Model explanations: permutation, SHAP, local contributions and counterfactuals."""

from __future__ import annotations

import importlib.util
from typing import Any, Callable

import numpy as np
import pandas as pd
from scipy import sparse
from sklearn.inspection import permutation_importance
from sklearn.pipeline import Pipeline

MAX_EXPLANATION_ROWS = 300
MAX_EXPLANATION_FEATURES = 20
MAX_LOCAL_FEATURES = 12
BACKGROUND_ROWS = 20

FEATURE_LABELS = {
    "distance_km": "配送距离",
    "freight_ratio": "运费占比",
    "estimated_delivery_days": "预计配送天数",
    "total_delivery_days": "实际配送天数",
    "purchase_month": "下单月份",
    "purchase_weekday": "下单星期",
    "purchase_hour": "下单小时",
    "purchase_day": "下单日",
    "purchase_weekofyear": "下单周",
    "customer_state": "客户州",
    "seller_state": "卖家州",
    "same_state": "同州发货",
    "product_category": "商品类目",
    "payment_value": "支付金额",
    "total_price": "商品总价",
    "total_freight": "总运费",
    "avg_item_price": "商品均价",
    "item_count": "商品件数",
    "seller_count": "卖家数量",
    "total_weight_g": "总重量",
    "payment_count": "支付笔数",
    "payment_installments_max": "最大分期数",
    "payment_type_count": "支付方式数",
    "product_category_count": "类目数量",
}


def explain_model(
    pipeline: Pipeline,
    features: pd.DataFrame,
    target: pd.Series,
    *,
    task: str,
    random_state: int,
    algorithm: str | None = None,
    max_rows: int = MAX_EXPLANATION_ROWS,
    top_n: int = MAX_EXPLANATION_FEATURES,
    n_repeats: int = 3,
) -> dict[str, Any]:
    """Compute bounded global and local explanations without failing training."""
    if len(features) == 0:
        return {
            "method": "permutation_importance",
            "available": False,
            "reason": "没有可用于解释的样本",
            "sampledRows": 0,
            "globalImportance": [],
            "shap": _shap_status(),
        }

    row_count = min(len(features), max(1, max_rows))
    if len(features) > row_count:
        positions = np.sort(
            np.random.default_rng(random_state).choice(len(features), size=row_count, replace=False)
        )
        sampled_features = features.iloc[positions].reset_index(drop=True)
        sampled_target = target.iloc[positions].reset_index(drop=True)
    else:
        sampled_features = features.reset_index(drop=True)
        sampled_target = target.reset_index(drop=True)

    scoring = "accuracy" if task == "classification" else "neg_root_mean_squared_error"
    permutation = None
    permutation_reason = None
    try:
        importance = permutation_importance(
            pipeline,
            sampled_features,
            sampled_target,
            scoring=scoring,
            n_repeats=max(1, n_repeats),
            random_state=random_state,
            n_jobs=1,
        )
        ranking = np.argsort(importance.importances_mean)[::-1]
        permutation = [
            {
                "rank": rank + 1,
                "feature": str(sampled_features.columns[int(index)]),
                "importanceMean": _finite(importance.importances_mean[int(index)]),
                "importanceStd": _finite(importance.importances_std[int(index)]),
            }
            for rank, index in enumerate(ranking[: max(1, top_n)])
        ]
    except Exception as error:
        permutation_reason = f"置换重要性计算失败：{type(error).__name__}"

    background = sampled_features.head(BACKGROUND_ROWS).copy()
    shap_result = _shap_global(pipeline, sampled_features, task, background)
    global_importance = shap_result.get("globalImportance") or permutation or []
    method = "shap" if shap_result.get("computed") else "permutation_importance"

    local = _local_explanation(
        pipeline,
        sampled_features.iloc[[0]].reset_index(drop=True),
        task=task,
        background=background,
        threshold=None,
        feature_types=_infer_feature_types(sampled_features),
    )

    return {
        "method": method,
        "available": bool(global_importance),
        "scoring": scoring,
        "algorithm": algorithm,
        "sampledRows": int(row_count),
        "nRepeats": int(max(1, n_repeats)),
        "globalImportance": global_importance,
        "permutationImportance": permutation or [],
        "permutationReason": permutation_reason,
        "localExplanation": local,
        "shap": shap_result.get("status", _shap_status()),
        "backgroundSample": _background_records(background),
        "featureTypes": _infer_feature_types(sampled_features),
        "featureLabels": {
            str(name): FEATURE_LABELS.get(str(name), str(name)) for name in sampled_features.columns
        },
        "limitations": [
            "解释结果基于抽样样本计算，样本量越大越稳定。",
            "SHAP 数值表示相对基线样本的平均影响，不等同于因果效应。",
            "反事实建议只是模型层面的最小改动路径，业务落地前仍需人工复核。",
        ],
    }


def explain_sample(
    pipeline: Pipeline,
    sample: dict[str, Any],
    *,
    feature_types: dict[str, str],
    task: str,
    threshold: float | None = None,
    background: pd.DataFrame | None = None,
    include_counterfactuals: bool = True,
) -> dict[str, Any]:
    """Explain one raw feature row and suggest counterfactual flips."""
    frame = _prepare_sample(sample, feature_types)
    probabilities = _probabilities(pipeline, frame, task)
    classes = [str(value) for value in np.asarray(getattr(pipeline, "classes_", ())).tolist()]
    positive_index = 1 if len(classes) == 2 else max(0, len(classes) - 1)
    probability = (
        float(probabilities[0, positive_index]) if probabilities is not None else None
    )
    if task == "classification" and len(classes) == 2 and threshold is not None:
        predicted = classes[1] if probability is not None and probability >= threshold else classes[0]
    elif task == "classification" and probabilities is not None:
        predicted = classes[int(np.argmax(probabilities[0]))]
    else:
        predicted = float(pipeline.predict(frame)[0])

    contributions, base_value, method = _local_contributions(
        pipeline, frame, task, background, feature_types
    )
    label = _prediction_label(predicted, task, classes)
    narrative = _narrative(label, probability, threshold, contributions, task)
    counterfactuals = (
        _counterfactuals(
            pipeline, frame, feature_types, task, threshold, probability, predicted,
            classes, background,
        )
        if include_counterfactuals
        else []
    )
    return {
        "task": task,
        "prediction": _json_value(predicted),
        "probability": probability,
        "threshold": threshold,
        "confidence": (
            max(probability, 1.0 - probability) if probability is not None and len(classes) == 2 else None
        ),
        "positiveClass": classes[1] if len(classes) == 2 else None,
        "classes": classes,
        "baseValue": base_value,
        "contributions": contributions,
        "method": method,
        "naturalLanguage": narrative,
        "counterfactuals": counterfactuals,
        "sample": {key: _json_value(value) for key, value in sample.items()},
    }


def background_frame(
    records: list[dict[str, Any]] | None, feature_types: dict[str, str]
) -> pd.DataFrame | None:
    """Rebuild the stored SHAP background sample with training-time dtypes."""
    if not records:
        return None
    frame = pd.DataFrame(records)
    for name, kind in feature_types.items():
        if name not in frame.columns:
            continue
        if kind == "numeric":
            frame[name] = pd.to_numeric(frame[name], errors="coerce")
        else:
            frame[name] = frame[name].map(
                lambda value: np.nan if value is None or pd.isna(value) else str(value)
            )
    return frame


def _shap_global(
    pipeline: Pipeline,
    features: pd.DataFrame,
    task: str,
    background: pd.DataFrame,
) -> dict[str, Any]:
    status = _shap_status()
    if not status["installed"]:
        return {"computed": False, "status": status, "globalImportance": []}
    model = _unwrap_model(pipeline.named_steps.get("model"))
    if not _supports_shap_values(model):
        status["reason"] = "该算法没有快速 SHAP 解释器，已改用置换重要性"
        return {"computed": False, "status": status, "globalImportance": []}
    try:
        matrix, feature_names, mapping = _transform(pipeline, features)
        background_matrix, _, _ = _transform(pipeline, background)
        values, _ = _shap_values(pipeline, matrix, task, background_matrix)
        if values is None:
            status["reason"] = "当前模型结构不支持 SHAP 值计算，已改用置换重要性"
            return {"computed": False, "status": status, "globalImportance": []}
        mean_abs = np.mean(np.abs(values), axis=0)
        aggregated = _aggregate_vector(mean_abs, feature_names, mapping)
        ranking = sorted(aggregated.items(), key=lambda item: item[1], reverse=True)
        importance = [
            {"rank": index + 1, "feature": name, "importanceMean": round(float(value), 6)}
            for index, (name, value) in enumerate(ranking[:MAX_EXPLANATION_FEATURES])
        ]
        status.update({"computed": True, "method": _explainer_name(pipeline), "rows": int(len(features))})
        return {"computed": True, "status": status, "globalImportance": importance}
    except Exception as error:
        status["reason"] = f"SHAP 计算失败，已改用置换重要性：{type(error).__name__}"
        return {"computed": False, "status": status, "globalImportance": []}


def _local_explanation(
    pipeline: Pipeline,
    frame: pd.DataFrame,
    *,
    task: str,
    background: pd.DataFrame | None,
    threshold: float | None,
    feature_types: dict[str, str],
) -> dict[str, Any]:
    try:
        contributions, base_value, method = _local_contributions(
            pipeline, frame, task, background, feature_types
        )
        probabilities = _probabilities(pipeline, frame, task)
        classes = [str(value) for value in np.asarray(getattr(pipeline, "classes_", ())).tolist()]
        positive_index = 1 if len(classes) == 2 else max(0, len(classes) - 1)
        probability = (
            float(probabilities[0, positive_index]) if probabilities is not None else None
        )
        return {
            "available": True,
            "method": method,
            "probability": probability,
            "threshold": threshold,
            "baseValue": base_value,
            "contributions": contributions[:MAX_LOCAL_FEATURES],
        }
    except Exception as error:
        return {
            "available": False,
            "reason": f"单样本解释失败：{type(error).__name__}",
        }


def _local_contributions(
    pipeline: Pipeline,
    frame: pd.DataFrame,
    task: str,
    background: pd.DataFrame | None,
    feature_types: dict[str, str],
) -> tuple[list[dict[str, Any]], float | None, str]:
    model = _unwrap_model(pipeline.named_steps.get("model"))
    if background is not None and len(background) and _supports_shap_values(model):
        try:
            matrix, feature_names, mapping = _transform(pipeline, frame)
            background_matrix, _, _ = _transform(pipeline, background)
            values, base_value = _shap_values(pipeline, matrix, task, background_matrix)
            if values is not None:
                row = values[0]
                aggregated = _aggregate_vector(row, feature_names, mapping)
                original = frame.iloc[0].to_dict()
                contributions = _contribution_list(aggregated, original, feature_types)
                return contributions, _finite(base_value), _explainer_name(pipeline)
        except Exception:
            pass
    return _occlusion_contributions(pipeline, frame, task, background, feature_types)


def _occlusion_contributions(
    pipeline: Pipeline,
    frame: pd.DataFrame,
    task: str,
    background: pd.DataFrame | None,
    feature_types: dict[str, str],
) -> tuple[list[dict[str, Any]], float | None, str]:
    probabilities = _probabilities(pipeline, frame, task)
    if probabilities is None:
        return [], None, "unsupported"
    classes = [str(value) for value in np.asarray(getattr(pipeline, "classes_", ())).tolist()]
    positive_index = 1 if len(classes) == 2 else int(np.argmax(probabilities[0]))
    original_probability = float(probabilities[0, positive_index])
    baseline = _baseline_frame(background, frame, feature_types)
    base_probability = _predict_positive(pipeline, baseline, task, positive_index)
    contributions: list[dict[str, Any]] = []
    for feature in frame.columns:
        modified = frame.copy()
        modified.loc[0, feature] = baseline.loc[0, feature]
        replaced_probability = _predict_positive(pipeline, modified, task, positive_index)
        contribution = original_probability - replaced_probability
        contributions.append({
            "feature": feature,
            "label": _label(feature),
            "value": _json_value(frame.iloc[0][feature]),
            "contribution": round(float(contribution), 6),
            "direction": "increases" if contribution >= 0 else "decreases",
            "type": feature_types.get(feature, "unknown"),
        })
    contributions.sort(key=lambda item: abs(item["contribution"]), reverse=True)
    return contributions[:MAX_LOCAL_FEATURES], _finite(base_probability), "occlusion"


def _counterfactuals(
    pipeline: Pipeline,
    frame: pd.DataFrame,
    feature_types: dict[str, str],
    task: str,
    threshold: float | None,
    probability: float | None,
    predicted: Any,
    classes: list[str],
    background: pd.DataFrame | None,
) -> list[dict[str, Any]]:
    if task != "classification" or len(classes) != 2 or probability is None:
        return []
    cutoff = threshold if threshold is not None else 0.5
    currently_positive = probability >= cutoff
    candidates: list[dict[str, Any]] = []
    numeric_features = [
        name for name, kind in feature_types.items() if kind == "numeric" and name in frame.columns
    ]
    categorical_features = [
        name for name, kind in feature_types.items() if kind == "categorical" and name in frame.columns
    ]
    for feature in numeric_features:
        current = frame.iloc[0][feature]
        try:
            current_value = float(current)
        except (TypeError, ValueError):
            continue
        if background is not None and feature in background.columns:
            values = pd.to_numeric(background[feature], errors="coerce").dropna()
            low = float(values.quantile(0.05)) if len(values) else current_value
            high = float(values.quantile(0.95)) if len(values) else current_value
        else:
            low, high = current_value * 0.5, current_value * 1.5
        if not np.isfinite(low) or not np.isfinite(high) or high <= low:
            continue
        grid = np.linspace(low, high, 25)
        best = None
        for candidate in grid:
            modified = frame.copy()
            modified[feature] = pd.to_numeric(modified[feature], errors="coerce").astype(float)
            modified.loc[0, feature] = float(candidate)
            new_probability = _predict_positive(pipeline, modified, task, 1)
            flipped = (new_probability >= cutoff) != currently_positive
            if flipped:
                distance = abs(candidate - current_value)
                if best is None or distance < best[0]:
                    best = (distance, float(candidate), float(new_probability))
        if best is not None:
            candidates.append({
                "feature": feature,
                "label": _label(feature),
                "from": round(current_value, 6),
                "to": round(best[1], 6),
                "delta": round(best[1] - current_value, 6),
                "newProbability": round(best[2], 6),
                "message": (
                    f"将{_label(feature)}调整到 {best[1]:.2f} 时，"
                    f"预测概率变为 {best[2]:.1%}，结论会翻转。"
                ),
            })
            if len(candidates) >= 2:
                return candidates
    for feature in categorical_features:
        if background is None or feature not in background.columns:
            continue
        current = str(frame.iloc[0][feature])
        for candidate in background[feature].dropna().astype(str).unique()[:12]:
            if candidate == current:
                continue
            modified = frame.copy()
            modified.loc[0, feature] = candidate
            new_probability = _predict_positive(pipeline, modified, task, 1)
            if (new_probability >= cutoff) != currently_positive:
                candidates.append({
                    "feature": feature,
                    "label": _label(feature),
                    "from": current,
                    "to": candidate,
                    "delta": None,
                    "newProbability": round(float(new_probability), 6),
                    "message": (
                        f"将{_label(feature)}改为 {candidate} 时，"
                        f"预测概率变为 {new_probability:.1%}，结论会翻转。"
                    ),
                })
                break
        if len(candidates) >= 2:
            break
    return candidates


def _transform(pipeline: Pipeline, frame: pd.DataFrame) -> tuple[np.ndarray, list[str], dict[int, str]]:
    preprocessor = pipeline.named_steps.get("preprocess")
    if preprocessor is None:
        matrix = frame.to_numpy()
        names = [str(name) for name in frame.columns]
    else:
        matrix = preprocessor.transform(frame)
        if sparse.issparse(matrix):
            matrix = matrix.toarray()
        try:
            names = [str(name) for name in preprocessor.get_feature_names_out()]
        except Exception:
            names = [f"feature_{index}" for index in range(np.asarray(matrix).shape[1])]
    mapping = _feature_mapping(names, list(frame.columns))
    return np.asarray(matrix, dtype=float), names, mapping


def _supports_shap_values(model: Any) -> bool:
    if model is None:
        return False
    return _is_tree_model(model) or _is_linear_model(model)


def _shap_values(
    pipeline: Pipeline,
    matrix: np.ndarray,
    task: str,
    background_matrix: np.ndarray,
) -> tuple[np.ndarray | None, float | None]:
    shap = _import_shap()
    if shap is None:
        return None, None
    model = _unwrap_model(pipeline.named_steps.get("model"))
    if model is None:
        return None, None
    explainer = None
    if _is_tree_model(model):
        explainer = shap.TreeExplainer(model)
    elif _is_linear_model(model) and len(background_matrix):
        explainer = shap.LinearExplainer(model, background_matrix)
    if explainer is None:
        return None, None
    values = explainer.shap_values(matrix)
    base = getattr(explainer, "expected_value", None)
    normalized = _positive_values(values, task)
    if normalized is None:
        return None, None
    return normalized, _positive_base(base)


def _prediction_function(model: Any, task: str) -> Callable[[np.ndarray], np.ndarray]:
    if task == "classification":
        def predict(matrix: np.ndarray) -> np.ndarray:
            probabilities = model.predict_proba(matrix)
            if probabilities.ndim == 2 and probabilities.shape[1] == 2:
                return probabilities[:, 1]
            return probabilities
        return predict
    return model.predict


def _positive_values(values: Any, task: str) -> np.ndarray | None:
    if isinstance(values, list):
        if not values:
            return None
        selected = values[1] if len(values) == 2 else values[-1]
        return np.asarray(selected, dtype=float)
    array = np.asarray(values, dtype=float)
    if array.ndim == 3:
        return array[:, :, -1]
    return array


def _positive_base(base: Any) -> float | None:
    if base is None:
        return None
    array = np.asarray(base, dtype=float).reshape(-1)
    if len(array) == 0:
        return None
    return _finite(float(array[-1]))


def _feature_mapping(names: list[str], original: list[str]) -> dict[int, str]:
    mapping: dict[int, str] = {}
    for index, name in enumerate(names):
        if name.startswith("numeric__"):
            mapping[index] = name[len("numeric__"):]
        elif name.startswith("categorical__"):
            rest = name[len("categorical__"):]
            candidates = [
                column for column in original
                if rest == column or rest.startswith(f"{column}_")
            ]
            mapping[index] = max(candidates, key=len) if candidates else rest
        else:
            mapping[index] = name
    return mapping


def _aggregate_vector(values: np.ndarray, names: list[str], mapping: dict[int, str]) -> dict[str, float]:
    aggregated: dict[str, float] = {}
    for index, value in enumerate(np.asarray(values, dtype=float)):
        feature = mapping.get(index, names[index] if index < len(names) else f"feature_{index}")
        aggregated[feature] = aggregated.get(feature, 0.0) + float(value)
    return aggregated


def _contribution_list(
    aggregated: dict[str, float],
    original: dict[str, Any],
    feature_types: dict[str, str],
) -> list[dict[str, Any]]:
    contributions = [
        {
            "feature": feature,
            "label": _label(feature),
            "value": _json_value(original.get(feature)),
            "contribution": round(float(value), 6),
            "direction": "increases" if value >= 0 else "decreases",
            "type": feature_types.get(feature, "unknown"),
        }
        for feature, value in aggregated.items()
    ]
    contributions.sort(key=lambda item: abs(item["contribution"]), reverse=True)
    return contributions


def _narrative(
    label: str,
    probability: float | None,
    threshold: float | None,
    contributions: list[dict[str, Any]],
    task: str,
) -> str:
    if task != "classification" or probability is None:
        return f"模型预测结果为 {label}。"
    cutoff = threshold if threshold is not None else 0.5
    parts = [f"模型预测为{label}，正类概率 {probability:.1%}，判定阈值为 {cutoff:.2f}。"]
    positive = [item for item in contributions if item["contribution"] > 0][:3]
    negative = [item for item in contributions if item["contribution"] < 0][:2]
    if positive:
        parts.append("推高预测的主要因素：" + "、".join(
            f"{item['label']}（{item['contribution']:+.3f}）" for item in positive
        ) + "。")
    if negative:
        parts.append("拉低预测的主要因素：" + "、".join(
            f"{item['label']}（{item['contribution']:+.3f}）" for item in negative
        ) + "。")
    parts.append("以上解释用于辅助判断，不构成因果结论。")
    return " ".join(parts)


def _prepare_sample(sample: dict[str, Any], feature_types: dict[str, str]) -> pd.DataFrame:
    frame = pd.DataFrame([{name: sample.get(name) for name in feature_types}])
    for name, kind in feature_types.items():
        if kind == "numeric":
            frame[name] = pd.to_numeric(frame[name], errors="coerce")
        else:
            frame[name] = frame[name].map(
                lambda value: np.nan if value is None or pd.isna(value) else str(value)
            )
    return frame


def _baseline_frame(
    background: pd.DataFrame | None,
    frame: pd.DataFrame,
    feature_types: dict[str, str],
) -> pd.DataFrame:
    baseline = frame.copy()
    if background is None or not len(background):
        return baseline
    for name, kind in feature_types.items():
        if name not in baseline.columns:
            continue
        if kind == "numeric":
            values = pd.to_numeric(background[name], errors="coerce").dropna()
            baseline.loc[0, name] = float(values.median()) if len(values) else 0.0
        else:
            values = background[name].dropna().astype(str)
            baseline.loc[0, name] = values.mode().iloc[0] if len(values) else "(missing)"
    return baseline


def _probabilities(pipeline: Pipeline, frame: pd.DataFrame, task: str) -> np.ndarray | None:
    if task != "classification" or not hasattr(pipeline, "predict_proba"):
        return None
    try:
        return pipeline.predict_proba(frame)
    except (AttributeError, ValueError):
        return None


def _predict_positive(
    pipeline: Pipeline, frame: pd.DataFrame, task: str, positive_index: int
) -> float:
    probabilities = _probabilities(pipeline, frame, task)
    if probabilities is None:
        return float(pipeline.predict(frame)[0])
    return float(probabilities[0, min(positive_index, probabilities.shape[1] - 1)])


def _infer_feature_types(frame: pd.DataFrame) -> dict[str, str]:
    return {
        str(name): "numeric" if pd.api.types.is_numeric_dtype(frame[name].dtype) else "categorical"
        for name in frame.columns
    }


def _background_records(background: pd.DataFrame) -> list[dict[str, Any]]:
    return [
        {str(key): _json_value(value) for key, value in row.items()}
        for row in background.to_dict(orient="records")
    ]


def _prediction_label(predicted: Any, task: str, classes: list[str]) -> str:
    if task != "classification":
        return str(_json_value(predicted))
    value = str(predicted)
    if value in {"1", "True", "true"}:
        return "延误/正类"
    if value in {"0", "False", "false"}:
        return "未延误/负类"
    return value


def _label(feature: str) -> str:
    return FEATURE_LABELS.get(feature, feature)


def _explainer_name(pipeline: Pipeline) -> str:
    model = _unwrap_model(pipeline.named_steps.get("model"))
    name = type(model).__name__ if model is not None else "unknown"
    return f"shap:{name}"


def _unwrap_model(model: Any) -> Any:
    """Unwrap only our label-encoding wrapper, never a fitted ensemble member."""
    if model is None:
        return None
    if type(model).__name__ == "LabelEncodedClassifier":
        return getattr(model, "estimator_", model)
    return model


def _is_tree_model(model: Any) -> bool:
    name = type(model).__name__.lower()
    return any(token in name for token in ("forest", "tree", "xgb", "lgbm", "lightgbm", "gradientboost"))


def _is_linear_model(model: Any) -> bool:
    return hasattr(model, "coef_")


def _import_shap() -> Any | None:
    try:
        import shap  # type: ignore

        return shap
    except Exception:
        return None


def _shap_status() -> dict[str, Any]:
    installed = importlib.util.find_spec("shap") is not None
    return {
        "installed": installed,
        "computed": False,
        "method": None,
        "reason": None if installed else "未安装 shap，无法计算 SHAP 解释",
    }


def _json_value(value: Any) -> Any:
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, float) and not np.isfinite(value):
        return None
    return value


def _finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if np.isfinite(number) else None
