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
  - target: patterns/debug-hull-damage.md
    condition: when hull durability, damage bars, welding, or breach-on-zero-HP behavior looks wrong
  - target: patterns/run-tests.md
    condition: when building or running the verification layers
  - target: patterns/vs2-physical-ship.md
    condition: when launching, docking, or debugging a VS2 physical ship
last_updated: 2026-11-04
---

# Session Bootstrap

If you haven't already read `AGENTS.md`, read it now — it contains the project identity, non-negotiables, and commands.

Then read this file fully before doing anything else in this session.

## 合作者下次开工提醒：先读模块还原路线

开工前请阅读 [潜渊症八大模块协作路线](../docs/BAROTRAUMA_MODULE_ROADMAP.md)。它结合官方 Wiki 与仓库现状，包含白话职责、完整子步骤、首轮交付、接口边界及夜间 `/goal` 模板。

先与人类确认认领的大模块和共同基线，再开始实现；路线不是已分配任务，也不是已完成功能清单。不同模块使用独立工作目录，先约定公共接口，避免各自重造或同时覆盖船、水、供电、伤病等核心系统。方案内精确原作数值仍须逐项核实。

这是随 Git 分享的开工阅读入口，不是自动通知；合作者获取包含本提醒的提交后才能看到。

## Current Project State

**Working:**
- 1.20.1 Forge mod builds and loads; air-ocean deep dimension, outposts, and starter submarine generate.
- VS2 physical ships: launch, dock, thruster control, breach reconciliation, sonar proxy, and water-state sync.
- **Minimum gameplay loop (2026-11-04, local work merged on top of `8751a00`, uncommitted):** mission terminal (main outpost `8,-63,0` + B relay station at `320,-300`), 5 missions (salvage×3 / recon / delivery), credits + shop (repair/oxygen/welder/hull upgrade), deterministic POI wrecks, sonar-screen「下潜/靠泊」buttons, first-login guidance + objective HUD, pressure/collision threats.
- **Persistence:** `data/ShipSavedData` (assembled snapshot + hull damage + shipId + proxy), block-ship hull damage in block-entity NBT, VS2 world-load adoption (`VsPilot.adoptFromSave` + shipyard chunk force-load). `.smoketest-persist` PASS (`船=true 耐久记录=2`), `.smoketest-mission` PASS.
- Per-compartment scalar water simulation, breach inflow splash/bubbles, ballast, pumps, reactor, and `/barotrauma ship flood` diagnostics.
- Per-block hull durability (plating 200 / glass 100), melee + explosion + block-ship collision damage, welding-tool repair, camera-facing billboard bars (readable at any view angle), vanilla-style cracks (all 6 faces, outside-visible), damage-scaled leak splashes, and `ShipHullDamagePacket` sync.
- Sonar screen/hologram, depth HUD, underwater fog, movement drag, and oxygen/drowning survival.
- **Bulkhead door (`barotrauma:bulkhead_door`, 1×2) and deck hatch (`barotrauma:hatch`, 3×1)**, GeckoLib
  rendered, toggled by right-clicking the fixed frame (the open state's pick shape is frame-only, so
  the doorway does not block attacks). The door was redrawn 2026-11-04: 15 axis-aligned cuboids,
  6×8 real window opening with cutout glass, panel slides 16px into the side wall (`open` 0.6 s).
  Pick shapes are cached per `FACING` via `DoorShapes.slab`; a fixed north-facing shape previously
  left east/west doors clickable only at a 1px centre overlap. Toggle sounds are the original
  Barotrauma `Door1..4.ogg`.
- L1 unit tests (46, including `HullDamageNbtTest`) pass; 3 GameTests and L3 `.smoketest-vs` passed earlier.

**Not yet built:**
- Multi-ship support (one `VsPilot` singleton manages one physical ship).
- Per-block water surface geometry (compartments are sent as greedy rectangular prisms; no per-cell fluid surface).
- Physical-ship collision → hull damage via a VS2 collision callback (currently an AABB sampling fallback).
- Tier/upgrade system beyond the shop's flat +20%/level `hullHealthMultiplier` (no T1/T2/T3 caps yet).
- Compartment water volume / ship oxygen persistence across reload (hull damage and upgrade level persist; water does not).
- Migration of old saves containing real water or removed `hull_plug` blocks.

**Known issues:**
- **OPEN (not yet confirmed in game): east/west-facing bulkhead doors close by right-clicking the frame.** Shapes now rotate with `FACING`; the old symptom was "only the door centre reacts".
- **OPEN: the drain pump still empties a whole connected compartment** ("只要在一个房间都会抽" per user). The `[pump]` log (every 20 ticks) in `ShipSimulation` is intentionally kept for this.
- **Do not read "cannot sink inside the ship" as a swim bug.** 2026-11-04: the player stood on the cabin floor while the breached ship sank (world y falling, assembly coordinates constant). On VS2 physical ships `getBlockState` at the ship's world position is always air and `getBlockCollisions` does not see the hull; map back with `pos - VsState.offset*` before concluding anything.
- **OPEN: compartment water surface still shows visible seams/boundary lines** after multiple fix rounds (2026-11-04 user test). Current renderer uses per-corner surface averaging + waves + no step faces; still not clean. Next step: run `-Dbarotrauma.waterDebug=true` in a real flooded scene (assembled physical ship + breaches) and dump the `[water-diag]` log before changing geometry again. See `patterns/debug-ship-water.md`.
- Without any `ShipWaterStatePacket` (fresh world, ship not assembled: `ship=-1 boxes=0`), the water renderer does nothing and the underwater filter tints ship interiors — do not misread that as a water-rendering bug. Check `[water] 画水面` box counts first.
- The current MEX code graph indexes only the 3 files under `tools/`; Java sources are not in the graph. Use Grep/Read for Java.
- `ShipWaterRenderer` draws compartment water client-side by default (10 Hz packet + per-frame interpolation); the air ocean still has no real water blocks. Disable with `-Dbarotrauma.renderWater=false` when isolating data vs render issues.
- The existing `run/world` predates the air-ocean change; L3 `.smoketest-vs` currently stops with “找不到 9×9×9 空气空腔” until `run/world` is deleted and regenerated.
- `docs/IMPLEMENTATION.md` still describes the older water-column world; prefer the code and `.mex/context/architecture.md` when they conflict.
- **Local MVP work is uncommitted** on top of upstream `8751a00` (46 files); commit/push need explicit human authorization. The running `run-client` is still the pre-merge build.
- Full manual playthrough of the 7-step loop (terminal → dive → salvage → dock → turn in → upgrade → quit/reload) has not been done on the merged build; automated coverage is `.smoketest-mission` + `.smoketest-persist`, and a 73-minute idle-render soak showed 0 OOM.
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
| Debugging hull damage, durability bars, or welding | `patterns/debug-hull-damage.md` |
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
