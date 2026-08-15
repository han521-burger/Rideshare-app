# Risk Register — working copy

Owner: Tony Doan. Copy of `Risk Register`

| Rank | Risk | Impact       | Probability | Score | Mitigation | Related requirements | Last reviewed |
|---|---|--------------|---|---|---|---|---|
| 1 | Low user willingness  | 5            | 3 | 15 | Questionnaire feedback, points incentives, clear safety/privacy messaging, station-based matching | FR-02, FR-09, NFR-01, NFR-03 | 2026-08-10 |
| 2 | Technical complexity  | 4            | 3 | 12 | Start with fixed-route/time-window matching, improve after core flow works. | FR-04, FR-10 | 2026-08-10 |
| 3 | Data privacy | 5            | 2 | 10 | Monash SSO, restricted access, data minimisation, security review | FR-01, FR-08, NFR-01 | 2026-08-10 |
| 4 | Integration risk  | 4            | 3 | 12 | Calendar/reminder fallback; describe Allocate+ sync as dependent on access approval | FR-06 | 2026-08-10 |
| 5 | Scope creep | 4            | 3 | 12 | Keep core ride request/driver management/matching as Must Have; treat extras as Should/Could Have | FR-02, FR-03, FR-04, FR-09, FR-10 | 2026-08-10 |

## Process

Review at every retrospective. If a risk's
likelihood or impact has materially changed, note it here and raise it in the weekly planning
sync so Score/Rank can be updated in the official workbook too.
