# Risk Register — working copy

Owner: Tony Doan. Copy of `Risk Register`

| Rank | Risk | Impact       | Probability | Score | Mitigation | Related requirements | Last reviewed |
|---|---|--------------|---|---|---|---|---|
| 1 | Low user willingness  | 5            | 3 | 15 | Questionnaire feedback, points incentives, clear safety/privacy messaging, station-based matching | FR-02, FR-09, NFR-01, NFR-03 | 2026-08-10 |
| 2 | Technical complexity  | 4            | 3 | 12 | Start with fixed-route/time-window matching, improve after core flow works. | FR-04, FR-10 | 2026-08-10 |
| 3 | Data privacy | 5            | 2 | 10 | Monash SSO, restricted access, data minimisation, security review | FR-01, FR-08, NFR-01 | 2026-08-10 |
| 4 | Integration risk  | 4            | 3 | 12 | Calendar/reminder fallback; describe Allocate+ sync as dependent on access approval | FR-06 | 2026-08-10 |
| 5 | Scope creep | 4            | 3 | 12 | Keep core ride request/driver management/matching as Must Have; treat extras as Should/Could Have | FR-02, FR-03, FR-04, FR-09, FR-10 | 2026-08-10 |

## Notes for the next retrospective (2026-10-06)

Scores above are unchanged - these are observations to discuss before updating the workbook.

- **Risk 3 (Data privacy):** Monash SSO is still blocked, so the app now stores its own
  passwords (PBKDF2-hashed) and session tokens. That raises the stakes of having no HTTPS and no
  login rate limiting - both should land before any use beyond the team. See
  `docs/SECURITY_CHECKLIST.md` review notes.
- **Risk 2 (Technical complexity):** recent additions (auto-expiry, overlapping-trip rules,
  Undo) are rule-based and covered by backend tests, keeping complexity contained.
- **Possible new risk - demo/testing depends on one laptop:** the backend runs on a developer's
  machine reached over Tailscale. If that machine is off or asleep, nobody can test or demo.
  Mitigation: agree on hosting (also needed for HTTPS).
- **Possible new risk - parallel UI work:** screens are being restyled while features are still
  changing; changes to the same screen can conflict. Mitigation: pull before starting, and agree
  who owns which screen.

## Process

Review at every retrospective. If a risk's
likelihood or impact has materially changed, note it here and raise it in the weekly planning
sync so Score/Rank can be updated in the official workbook too.
