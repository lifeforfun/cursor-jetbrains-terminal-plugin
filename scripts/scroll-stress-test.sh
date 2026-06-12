#!/usr/bin/env bash
# 模拟 cursor-agent 持续输出，用于测试终端自动滚动是否贴底。
# 在 GoLand「Cursor CLI Terminal」里执行: bash scripts/scroll-stress-test.sh

set -euo pipefail

TASKS=(
  "扫描项目结构"
  "分析 Kotlin 源码"
  "模拟工具调用输出"
  "生成长段总结"
)

for i in "${!TASKS[@]}"; do
  n=$((i + 1))
  title="${TASKS[$i]}"
  echo ""
  echo "════════════════════════════════════════"
  echo "  任务 ${n}/4: ${title}"
  echo "════════════════════════════════════════"
  sleep 0.3

  for line in $(seq 1 40); do
    printf "\r  [%s] 处理中... 行 %2d/40  " "${title}" "${line}"
    sleep 0.05
  done
  echo ""

  # 模拟 TUI 重绘（易触发滚动跳顶）
  printf '\033[H\033[2J'
  echo "  ✓ 任务 ${n} 完成: ${title}"
  for j in $(seq 1 15); do
    echo "    输出块 ${j}: $(printf 'Lorem ipsum %.0s' {1..8})"
    sleep 0.03
  done
done

echo ""
echo "════════════════════════════════════════"
echo "  全部 4 个任务完成 — 视图应停在此行"
echo "════════════════════════════════════════"
