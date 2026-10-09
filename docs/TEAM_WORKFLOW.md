# TraceVeil team branch workflow

Repository: https://github.com/agrimsri/IQOO-Kernel-Panic.git. Agrim reviews the baseline branch and merges it into `main`. After that merge, each teammate creates their own development branch from the same integrated `main`.

## Baseline and authorship

The repository status below is the handoff preparation snapshot, before Agrim and Yashvi perform their imports. Later contributor commits must be checked on GitHub rather than inferred from this snapshot.

The existing application is an import. Baseline commit subjects must say `import(...)` or otherwise identify the import. Product rebranding and setup documentation are new changes, but the underlying application predates the finale. See `PROVENANCE.md`.

Each teammate performs and reviews their own actual import or new development work, commits with their own verified identity, and pushes through their own authenticated GitHub account. Do not rotate identities on one laptop to create apparent participation. Do not alter dates or rewrite a published history.

Six baseline import commits now exist on `setup/traceveil-baseline`, authored as `Nitya Prakash Pandey <nityaprakashpandey389@gmail.com>` using the existing configured identity. All six have been pushed to `origin/setup/traceveil-baseline`. GitHub attribution requires that this email be verified on the intended account.

The remote branches `agrim/rules-reports` and `yashvi/ui-ai` were created at the same baseline tip, `445db1d597d2ab374367d5bad06b12841416ba12`, and verified through `git ls-remote`. No teammate-authored commits have been created. The remote had no branches before this publication; no `main` branch was created or changed by these operations. Agrim must establish the intended `main` integration base before the later merge workflow can be used.

| Commit | Scope |
| --- | --- |
| `27c9d4a` | Root build setup, licence, provenance and source manifest |
| `420b8b2` | Core event model and DNS codec |
| `564d4fa` | Consent phase assignment |
| `49d91cf` | Android DNS tunnel |
| `f33ad2d` | Accessibility consent watcher and app control |
| `445db1d` | Audit orchestration and application data |

These are imports created in one local batching operation, not evidence of six separate periods of event development. The remaining source files are still untracked in the full local working tree. A checkout of the current committed branch alone is incomplete and is not buildable until the other modules are committed.

The requested count is unresolved: exactly fifteen with unequal counts permits 6/5/4; at least five per person with unequal counts requires more than fifteen. The provisional batching below uses fifteen total and 6/5/4. Its batch numbers identify scopes, not the eventual chronological order; Nitya's scopes were committed first as requested.

## Provisional fifteen import batches

These describe baseline organization, not fifteen newly implemented features. Source import commits may have unresolved dependencies until later batches arrive. Build and test the complete branch before review or merge.

| Batch | Account | Scope | Honest subject |
| --- | --- | --- | --- |
| 1 | nitya-prakash-pandey-2005 | Root build configuration, wrappers, licence, ignore rules, provenance | `import(build): establish documented application baseline` |
| 2 | nitya-prakash-pandey-2005 | Core event model, DNS codec, packet fixtures and codec tests | `import(core): add baseline event model and DNS codec` |
| 3 | agrimsri | Tracker catalogue, consent lexicon, resources and related tests | `import(core): add baseline tracker and consent catalogues` |
| 4 | agrimsri | Screen auditor, rules, screen/rules tests and fixtures | `import(core): add baseline assessment engine` |
| 5 | nitya-prakash-pandey-2005 | Phase assignment and its tests | `import(core): add baseline consent phase assignment` |
| 6 | agrimsri | Legal corpus, fixes, Markdown/JSON reports and related tests | `import(core): add baseline citations fixes and reports` |
| 7 | nitya-prakash-pandey-2005 | Android build configuration, manifest, capture service and relay | `import(capture): add baseline Android DNS tunnel` |
| 8 | nitya-prakash-pandey-2005 | Accessibility consent capture and Shizuku/device control | `import(control): add baseline consent watcher and app control` |
| 9 | yashviup | LiteRT-LM model discovery, review, explanations and embeddings | `import(ai): add baseline on-device model integration` |
| 10 | nitya-prakash-pandey-2005 | Orchestration, app graph, repository, settings and sample fixtures | `import(audit): add baseline multi-plan orchestration` |
| 11 | agrimsri | Android PDF export, sharing and clipboard | `import(report): add baseline on-device export tools` |
| 12 | yashviup | Activity, view model, Compose screens, components and theme | `import(ui): add baseline audit application screens` |
| 13 | yashviup | LeakyShop module and its two flavours | `import(demo): add baseline leaky and fixed test apps` |
| 14 | agrimsri | Android labels, icons, font and service/file-provider resources | `import(resources): add TraceVeil-labelled application resources` |
| 15 | yashviup | README, screenshots, workflow and verification notes | `docs(team): document baseline setup and review workflow` |

Final staging must assign every source file once and verify that none are omitted. Real additional edits belong in additional commits; do not invent work to satisfy a count.

## Agrim merges the baseline

The remaining imports are sequential: Agrim creates five commits on `agrim/rules-reports` and pushes them. Before making any of her four commits, Yashvi fetches and runs `git merge --ff-only origin/agrim/rules-reports` on `yashvi/ui-ai`. She then reviews, commits and pushes her four assigned batches. With no other changes, her final branch contains all fifteen contribution commits in a linear history. Stop if the fast-forward fails; do not rebase or force-push a shared branch.

The handoff files and exact commands are in `hackathon-preparation/teammate-handoff` outside the application repository. Agrim's package contains 25 files in five batches; Yashvi's contains 30 in four batches. Git author settings are `Agrim Srivastava <agrimsri26@gmail.com>` and `Yashvi Upadhyay <24f2007780@ds.study.iitm.ac.in>` respectively. The team confirms collaborator access has been accepted; each person still needs to verify their email and authenticate privately.

Once all imports are complete, run core tests and assemble all three debug APKs; verify no generated outputs, secrets, or model weights are staged. Review the final branch against the provenance record. Agrim integrates only after the eligibility conflict and actual contributor identities are resolved. If `main` still does not exist, he can establish it at the verified final tip; if it exists, review its ancestry and use a safe fast-forward or the repository's reviewed merge process. Do not overwrite an existing `main`.

Preserve individual commits if the required count depends on them. Do not squash fifteen commits into one. Review merge strategy before merging; a merge commit can increase the total history count, so distinguish fifteen contribution commits from total repository commits.

## Separate development branches after the merge

| Teammate | Suggested branch | Initial work |
| --- | --- | --- |
| nitya-prakash-pandey-2005 | `nitya/capture-control` | Consent provenance, reset failure, capture recovery |
| agrimsri | `agrim/rules-reports` | Assessment coverage, citations, qualified exports |
| yashviup | `yashvi/ui-ai` | Per-audit explanation state, camera/model UX, demo consent choices |

Agrim and Yashvi can first fetch and select the published branches in their own clones:

```powershell
git fetch origin
# Agrim:
git switch --track origin/agrim/rules-reports
# Yashvi (in her own clone instead):
git switch --track origin/yashvi/ui-ai
```

Only run the command for the relevant teammate. These branches currently contain the same partial baseline; remaining untracked source files must be transferred before completing the assigned imports. After the baseline is integrated into `main`, each teammate starts or updates their development branch from that integrated `main`. For a new branch, run:

```powershell
git switch main
git pull --ff-only origin main
git switch -c YOUR_BRANCH
```

Set `user.name` and `user.email` locally to the actual contributor's verified GitHub identity. A username is not sufficient to infer the exact author email; obtain it from that person's settings. Keep passwords and tokens out of chat and the repository.

Before the remaining import work, obtain each teammate's actual Git author name and verified email (or the exact private commit email displayed in GitHub Settings > Emails). Confirm repository collaborator access has been accepted. Each teammate authenticates privately on their own machine, reviews the assigned import files, and creates their own commits. Author metadata is separate from GitHub authentication; changing names or emails here does not sign into another account. Transfer the remaining baseline files to their clones explicitly, since untracked files are not included in a push or clone. Do not share credentials or rotate identities here to manufacture participation.

Office Kit is installed and paired according to the team. Follow the current venue Red/Green Light instructions. ADB remains a separate installation/debugging connection; use an explicit device serial for multi-phone operations.
