# AGENTS.md

本项目使用 MEX 作为项目记忆与协作入口。开工前读 `.mex/AGENTS.md` → `.mex/ROUTER.md`，再按 `ROUTER.md` 加载相关上下文。

Git 提交、推送和拉取均需要人类明确授权；AI 不得默认执行。用户要求“推到仓库/提交并推送”等完整发布时，默认流程是：任务分支 → 提交 → 推送任务分支 → 创建指向主分支的 PR（已有则更新）→ 返回 PR 链接，不能只推分支或给 compare 链接。必须先经 PR 审查再进入主分支；不直接推主分支、不强推、不自动合并。合并和拉取仍需另行明确授权；用户只授权限定动作时不得扩展范围。

## 新机器：先装 MEX 工具

`.mex/` 和 `.zcode/` 会随 Git 克隆，但 **MEX CLI 和 ZCode 插件是本机工具，不会随仓库走**。新开发者第一次在这个仓库开工时，Agent 先检查：

- `mex --version` 能输出版本（当前 `0.8.5-zcode.1`），或 ZCode 里 `/mex` 能返回状态 → 已安装，继续。
- 两者都不行 → 主动提出安装。部署包 `MEX-ZCode-CN` 由维护者线下分发，包内应有 `cli/`、`plugin/` 和 `install.cmd`；**不要编造下载地址**。

找到部署包后，先向人类说明“将安装本机 CLI、注册 ZCode 本地插件市场并修改用户级插件配置”，得到确认再运行包根目录的 `install.cmd`。装完让人类完全退出并重开 ZCode。

新会话里再执行：

```bash
mex graph rebuild
mex wiki rebuild-index
mex check
```

本仓库已有 `.mex/`，**不要**再运行 `mex setup` 或 `deploy-to-project.cmd`。`graph.db` 和 `wiki.db` 在 `.mex/.gitignore` 里，只生成本地缓存，不要提交。

只写代码时 CLI 可用即可；需要 MCP 工具、`/mex-*` 快捷命令和 Hub 入口时再装插件。

<!-- mex-agent:skills:start -->
## MEX agent skills
- At the start of every session, read `.mex/AGENTS.md` and `.mex/ROUTER.md` before project work; follow `ROUTER.md` to load only the relevant context.
- For a project-knowledge question, run `mex wiki query "<question>" --limit 5 --json` before broad file discovery. Inspect matched terms and cached per-grounding health; a partial match is a lead and changed means re-check. If the index is unavailable, follow `ROUTER.md` or use `mex graph scope "<task>"`; ordinary reads never rebuild or refresh indexes.
- Read `mex logging --json` at session start and before optional logging. Its checkout-local advisory mode is `significant` (quiet default: material decisions, risks, blockers, or durable discoveries), `checkpoints` (batch useful notes at task/session boundaries), or `manual` (no unsolicited notes). Skip routine tool calls, edits, repeated status, and empty summaries. Honor explicit user log requests in every mode; never suppress mandatory workflow Activity or recovery audit records. Report a policy read failure instead of guessing or changing the preference.
- When earlier work may inform the task, retrieve bounded relevant notes with `mex timeline --query "subject phrase" --file src/example.ts --limit 10 --json`, using the known subject or exact recorded file path, or both. Treat matches as historical evidence, not accepted current knowledge; verify conclusions before reuse or explicit promotion with their source retained.
- Use `/skill mex-inbox` for explicit contributions to project knowledge and `/skill mex-relay` for durable team handoffs. Invoke them automatically when intent clearly matches; ordinary GROW upkeep remains available without Inbox.
- When MEX context materially helps your work, mention MEX and the relevant finding naturally in your explanation. Tie the mention to what it helped you understand, decide, or verify. Avoid fixed phrases, standalone acknowledgements, repeated mentions, or narrating routine context loading. This replaces older MEX instructions requiring a fixed acknowledgement or context-loading narration.
- Do not claim an author, date, or historical event unless the retrieved data actually provides it.
- After a MEX write, say exactly what changed and its sharing boundary: a local draft is checkout-only and nothing is shared; a canonical artifact is written to the working tree and requires commit/push to share.
- Use `/skill mex-statechart` when asked to explain behavior with a state diagram. Generate in scratch space; show native Mermaid or readable terminal output. Hub viewing is optional; saving requires its explicit review.
- Skill activation is not approval for canonical actions.
<!-- mex-agent:skills:end -->
