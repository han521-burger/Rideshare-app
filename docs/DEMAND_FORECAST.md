# Demand forecast

The Demand zones card (Request a ride / Offer a ride, Route step) opens on **Predicted** demand: the number of
ride requests a model expects at each approved hub in the current time bucket. **Past requests** shows the
history it learned from ([DEMAND.md](DEMAND.md)).

**The training history is synthetic** (the app has no real usage yet). The card says so:
*Forecast · Boosted trees (Poisson) · trained on synthetic history*.

## Files

| File | What it is |
|---|---|
| `app/src/test/.../domain/DemandHistoryExport.kt` | Writes the demand history to `analysis/data/*.csv` (only when `DEMAND_EXPORT_DIR` is set) |
| `analysis/make_notebook.py` | Source of the notebook's cells |
| `analysis/demand_forecast.ipynb` | The analysis, with its outputs and charts |
| `analysis/figures/*.png` | The charts, as image files for a report |
| `app/src/main/resources/demand_forecast.json` | The exported forecast the app reads |
| `core/demand/ForecastDemand.kt` | Loads that file and feeds it into the existing demand pipeline |

## Method

1. **Panel.** 30 hubs × 75 days × 24 hours = 54,000 hub-hours, zeros included (95.7% have no request).
2. **Split by time.** Train on the first 60 days, test on the last 15 days.
3. **Baselines.** Last week repeated; mean by hub × weekday × hour; mean by hub × weekday/weekend × hour.
4. **Models.** Poisson regression (log link; hub + hub type × weekday/weekend × hour + day of week) and
   gradient-boosted trees with a Poisson loss.
5. **Scores.** Poisson deviance (the proper score for counts; MAE rewards predicting zero on data this sparse),
   plus, at the level the card shows (hub × bucket, averaged per day), top-3 hubs correct and rank correlation.
6. **Export.** The model with the lowest deviance is refit on all 75 days. For each hub and each of the 8 buckets it
   predicts the expected requests in that block on one day.

## Results (15 test days)

| | Poisson deviance | Top-3 hubs correct | Rank correlation |
|---|---|---|---|
| Best baseline (mean by hub × weekday/weekend × hour) | 0.295 | 62.5% | 0.52 |
| Poisson regression | 0.223 | 54.2% | 0.58 |
| **Boosted trees (Poisson)**, used in the app | **0.220** | 54.2% | **0.58** |

The model has 25% lower deviance than the best baseline and ranks hubs best. On the exact top 3 a pooled average does as well
(24 picks in all, so one pick moves the score about 4 points). The Poisson model largely recovers the generator's
day-of-week effects relative to Friday: Mon/Tue/Wed ×1.40/1.40/1.38 against the true ×1.31/1.38/1.38. Thursday is off
(×1.60 against ×1.31), which is noise from only about 9 Thursdays in training.

## Re-running

```
# 1. export the history (from the project root)
DEMAND_EXPORT_DIR=../analysis/data ./gradlew :app:testDebugUnitTest --tests "*.DemandHistoryExport"
# 2. rebuild and run the notebook (writes the figures and app/src/main/resources/demand_forecast.json)
cd analysis
python make_notebook.py
python -m jupyter nbconvert --to notebook --execute --inplace demand_forecast.ipynb
```

Needs pandas, numpy, scikit-learn, matplotlib, nbformat and jupyter (Anaconda has them).
`DemandForecastTest` checks the exported file: every hub in every bucket, the numbers reach the card unchanged,
and it beats its baseline.

## Limitations

- Synthetic history: the model mostly recovers the rules that generated it. With real requests the pipeline is the same
  (see *Replacing synthetic with real data* in DEMAND.md), but accuracy must be measured again.
- No weather, exam weeks, holidays or events.
- Point forecasts only; the spread is not shown.
- Exported once, not retrained live.
