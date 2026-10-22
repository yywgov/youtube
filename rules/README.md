# 远程规则（YiWei 设置 → 更新 → 远程规则）

App 每天最多检查一次 `rules.json`，只接受：
- 和 `rules.json.sha256` 一致的内容（改完 rules.json 一定要更新校验值：`sha256sum rules.json | cut -d' ' -f1 > rules.json.sha256`）；
- `schema` 为 1、`version` 比手机上已有的大或相等；
- `hide` 为最多 200 条 CSS 选择器，每条不超过 200 字符，不含 `{ } < > ; @ \`。

页面里还会逐条解析，太宽泛（如 `video`、`body`、`ytm-app`）或无效的选择器会被丢弃。任何一步失败都继续用上一份好的规则或内置规则。
