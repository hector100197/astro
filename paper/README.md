# paper/ — JOSS submission

Submission to the [Journal of Open Source Software](https://joss.theoj.org/).

## Build

```bash
make           # produces paper.pdf via Pandoc
```

Requires Pandoc + a LaTeX installation (`xelatex` engine).

## Submission

When ready (Sem 11):

1. `git tag v1.0` and create a GitHub release.
2. Submit at https://joss.theoj.org/papers/new with the release tag.
3. Reviewers will check: documentation, tests, statement of need, software paper formatting.
4. On acceptance: receive a DOI, citable forever.
