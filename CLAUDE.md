# CLAUDE.md

## Design Decisions

### No JPA Relationship Annotations

All entities use explicit foreign key ID fields (`Long qrCodeId`, `String agentUserid`)
rather than JPA `@ManyToOne`/`@OneToMany` annotations. This is intentional:

- **Pros:** Avoids lazy-loading pitfalls, keeps entities lightweight, aligns FK values
  with WeChat Work native identifiers for cross-system correlation.
- **Cons:** No automatic cascade queries; requires manual JPQL JOINs.
- **Decision date:** 2025 (at project inception), reaffirmed 2026-06-20.
