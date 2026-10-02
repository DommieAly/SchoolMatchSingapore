# Design diagrams (Lab 2)

These are the editable design sources. They were copied on **2026-09-30** from the Lab repo
`euphoricboi/SC2006_SchoolMatch-SG`, folder `Codes/Lab2/`, commit `bacedba` ("chore: organize the final deliverables").

**From now on, edit the diagrams here, not in the Lab repo.** When a PR changes a design class, operation,
attribute or dialog-map transition, update the diagram in the same PR and add a row to
[`../design-changes.md`](../design-changes.md).

Open `.drawio` files with [draw.io](https://www.drawio.com) (desktop app, the web app, or the IntelliJ "Diagrams.net" plugin).
After editing, export the matching `.png` again with the same file name.

| File | What it shows |
|---|---|
| `SC2006-Lab2-1-UseCaseDiagram.drawio` / `.png` | Use case diagram (20 use cases, one `User` actor) |
| `SC2006-Lab2-3-EntityClassDiagram.png` | Entity classes (exported from the `Entity Model` page of the class-diagram source) |
| `SC2006-Lab2-ClassDiagram-source.drawio` | Source for the class diagrams. Two pages: `Class Diagram (ECB)` (all layers) and `Entity Model` |
| `SC2006-Lab2-ClassDiagram-AllLayers.png` | Boundary + control + entity in one picture (from the `Class Diagram (ECB)` page) |
| `SC2006-Lab2-4-BoundaryControlClasses.drawio` / `.png` | 19 «boundary» and 13 «control» classes with their operations |
| `SC2006-Lab2-5-InitialDialogMap.drawio` / `.png` | Dialog map. Use the page **Initial Dialog Map (revised)** (24 states, 64 transitions). `Page-1` is the old version, kept for history |
| `control-deps.csv` | Which control class may call which other control class (design arrows + DC-14). `ArchitectureTest` reads this file and fails the build on any other control-to-control dependency |

How the diagrams map to code: see the naming rule in the [project README](../../README.md#naming-rule-design-class--java-class),
the routes in [`../routes.md`](../routes.md), and the requirement ids in [`../requirements.csv`](../requirements.csv).

Adding an arrow between two controls: add the row to `control-deps.csv`, draw the arrow in
`SC2006-Lab2-4-BoundaryControlClasses.drawio`, and record it in `design-changes.md`, all in one PR.
