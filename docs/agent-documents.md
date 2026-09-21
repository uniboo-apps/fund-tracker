# 文書の役割と参照先

通常作業の行動許可（自動 commit／safe-push を含む）の正本は [共通方針](C:/work/Claude/docs/agent-rules/workspace.md) だけ。repo 固有の例外・追加検証は `agent-rules.md`。以下は参考・仕様・限定計画で、行動許可を与えない。仕様・安全条件は変更対象の該当箇所を必ず読み、実装と照合する。計画の命令はそのタスクを明示的に再開するときだけ適用し、通常開発へ持ち込まない。未完了かどうかはファイルの日付だけで判断しない。

| 文書 | 分類／確認条件 |
|---|---|
| [codex-fix-instructions.md](codex-fix-instructions.md) | 限定計画（該当機能の安全条件を確認。実行順は明示再開時のみ） |
