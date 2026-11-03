---
name: router
description: Session bootstrap and navigation hub. Read at the start of every session before any task. Contains project state, routing table, and behavioural contract.
edges:
  - target: context/architecture.md
    condition: when working on system design, integrations, or understanding how components connect
  - target: context/stack.md
    condition: when working with specific technologies, libraries, or making tech decisions
  - target: context/conventions.md
    condition: when writing new code, reviewing code, or unsure about project patterns
  - target: context/decisions.md
    condition: when making architectural choices or understanding why something is built a certain way
  - target: context/setup.md
    condition: when setting up the dev environment or running the project for the first time
  - target: patterns/INDEX.md
    condition: when starting a task — check the pattern index for a matching pattern file
  - target: patterns/add-ship-block.md
    condition: when adding a new submarine hull block
  - target: patterns/debug-ship-water.md
    condition: when a ship is dry, flooding incorrectly, or missing water visuals
  - target: patterns/run-tests.md
    condition: when building or running the verification layers
  - target: patterns/vs2-physical-ship.md
    condition: when launching, docking, or debugging a VS2 physical ship
last_updated: 2026-11-03
---

# Session Bootstrap

If you haven't already read `AGENTS.md`, read it now — it contains the project identity, non-negotiables, and commands.

Then read this file fully before doing anything else in this session.

## Current Project State

**Working:**
- 1.20.1 Forge mod builds and loads; air-ocean deep dimension, outposts, and starter submarine generate.
- VS2 physical ships: launch, dock, thruster control, breach reconciliation, sonar proxy, and water-state sync.
- Per-compartment scalar water simulation, breach inflow splash/bubbles, ballast, pumps, reactor, and `/barotrauma ship flood` diagnostics.
- Per-block hull durability (plating 200 / glass 100), melee + explosion + block-ship collision damage, welding-tool repair, red→green world bars, crosshair percentage readout, and `ShipHullDamagePacket` sync.
- Sonar screen/hologram, depth HUD, underwater fog, movement drag, and oxygen/drowning survival.
- L1 unit tests (including `HullDamageTest`, `ShipPocketWaterTest`, `ShipWaterStateBuilderTest`) pass; 3 GameTests and L3 `.smoketest-vs` passed earlier.

**Not yet built:**
- Physical-ship persistence and world-load adoption (`VsPilot` is reset on server lifecycle).
- Multi-ship support (one `VsPilot` singleton manages one physical ship).
- Per-block water surface geometry (compartments are sent as greedy rectangular prisms; no per-cell fluid surface).
- Physical-ship collision → hull damage (VS2 2.4.11 collision event is not wired; only player melee, explosions, and block-ship collisions damage hulls).
- Tier/upgrade system for `hullHealthMultiplier` (official `increasewallhealth` is +20%/level, capped by T1/T2/T3 at 2/4/6 levels).
- Hull-durability persistence across world save or VS2 dismantle/relaunch.
- Migration of old saves containing real water or removed `hull_plug` blocks.

**Known issues:**
- The current MEX code graph indexes only the 3 files under `tools/`; Java sources are not in the graph. Use Grep/Read for Java.
- `ShipWaterRenderer` draws compartment water client-side by default (10 Hz packet + per-frame interpolation); the air ocean still has no real water blocks. Disable with `-Dbarotrauma.renderWater=false` when isolating data vs render issues.
- The existing `run/world` predates the air-ocean change; L3 `.smoketest-vs` currently stops with “找不到 9×9×9 空气空腔” until `run/world` is deleted and regenerated.
- `docs/IMPLEMENTATION.md` still describes the older water-column world; prefer the code and `.mex/context/architecture.md` when they conflict.
- Hull damage is runtime-only and is not included in any save format; don’t promise persistent repair progress yet.
- The hull damage bar is a world-space billboard (not a block texture); if it is missing, load `patterns/debug-hull-damage.md` before touching the water renderer.

## Routing Table

Load the relevant file based on the current task. Always load `context/architecture.md` first if not already in context this session.

| Task type | Load |
|-----------|------|
| Understanding how the system works | `context/architecture.md` |
| Working with a specific technology | `context/stack.md` |
| Writing or reviewing code | `context/conventions.md` |
| Making a design decision | `context/decisions.md` |
| Setting up or running the project | `context/setup.md` |
| Changing dev scripts, textures, or GameTest templates | `context/tools.md` |
| Any specific task | Check `patterns/INDEX.md` for a matching pattern |

## Behavioural Contract

For every task, follow this loop:

1. **CONTEXT** — Load the relevant context file(s) from the routing table above. Check `patterns/INDEX.md` for a matching pattern. If one exists, follow it.
2. **BUILD** — Do the work. If a pattern exists, follow its Steps. If you are about to deviate from an established pattern, say so before writing any code — state the deviation and why.
3. **VERIFY** — Load `context/conventions.md` and run the Verify Checklist item by item. State each item and whether the output passes. Do not summarise — enumerate explicitly.
4. **DEBUG** — If verification fails or something breaks, check `patterns/INDEX.md` for a debug pattern. Follow it. Fix the issue and re-run VERIFY.
5. **GROW** — After meaningful work, run this binary checklist:
   - **Ground:** What changed in reality? Name the changed behavior, system, command, dependency, or workflow.
   - **Record:** If project state changed, update the "Current Project State" section above. If documented facts changed, update the relevant `context/` file surgically. Record a new behavioral claim about specific code as its own entity (`mex wiki apply` with a `create-entry` operation), grounded to every symbol whose change could make it false and naming the identifiers and literals it depends on. Ground with node ids only; MEX records the fingerprints. Give the entity 3 to 6 `aliases` (short plain-word phrases a developer would type for the symptom or outcome it covers, written from the claim itself, not its title or identifiers).
   - **Connect:** Link section entities to their enclosing knowledge with `refines` using existing ids. Preview missing structural links with `mex wiki link-sections`, then apply explicitly. Review semantic relationships from evidence; do not connect records just because they share keywords or code groundings.
   - **Orient:** If this task can recur and no pattern exists, create one in `patterns/` using `patterns/README.md`, then add it to `patterns/INDEX.md`. If a pattern exists but you learned a gotcha, update it.
   - **Write:** Bump `last_updated` in every scaffold file you changed. Read `mex logging --json` before optional `mex log` notes: `significant` records material rationale, `checkpoints` batches useful notes at task/session boundaries, and `manual` avoids unsolicited notes. Honor explicit user log requests in every mode; mandatory workflow Activity and recovery audits remain required.
