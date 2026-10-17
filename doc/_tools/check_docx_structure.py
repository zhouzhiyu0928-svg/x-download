# -*- coding: utf-8 -*-
"""只读结构校验：标题普查（含杂项标题）· 表格方正 · 关键串存在/不存在断言。

用法：
    python check_docx_structure.py review.docx                      # 只做结构
    python check_docx_structure.py review.docx checks.json          # 结构 + 内容断言
    python check_docx_structure.py review.docx checks.json --h1 13 --h2 17

checks.json:
    {"存在": ["8. 接口（R2）", "invoice:detail:waive"],
     "不存在": ["版本：2.2", "wb.invoice.detail.sales-list.prompt-version"]}

设计要点（都是踩过的）：
  * 「不存在」断言默认跳过**修订记录**段落——它合法引用旧措辞（"2.3 · 2026-10-10 · 由 X 改为 Y"）。
  * 杂项标题 = 标题文本不匹配 `^\\d+(\\.\\d+)?[\\.\\s、]` 且不等于文档标题行的段落。整批插入正文时
    若克隆了标题锚点，就会出现这种"内容对、样式错"的段落（文本断言抓不到）。
  * 本脚本**只读**：不要把它和修改脚本合并，否则重跑一次就会把内容插两遍。
"""
import json
import re
import sys

from docx import Document

HEAD_NUM = re.compile(r'^\d+(\.\d+)?[\.\s、]')
REV_ROW = re.compile(r'^\d+\.\d+\s*[·|]\s*\d{4}-\d{2}-\d{2}')   # 修订记录行
TITLE_HINT = re.compile(r'(工作台|方案|说明书|设计|规范|报告|计划)')


def full_text(doc):
    parts = [p.text for p in doc.paragraphs]
    for t in doc.tables:
        for r in t.rows:
            parts.append(' | '.join(c.text for c in r.cells))
    return '\n'.join(parts)


def main():
    args = [a for a in sys.argv[1:]]
    if not args:
        print(__doc__)
        return 2
    path = args[0]
    checks = {}
    expect_h1 = expect_h2 = None
    if len(args) > 1 and args[1].endswith('.json'):
        with open(args[1], encoding='utf-8') as fh:
            checks = json.load(fh)
    for i, a in enumerate(args):
        if a == '--h1':
            expect_h1 = int(args[i + 1])
        if a == '--h2':
            expect_h2 = int(args[i + 1])

    doc = Document(path)
    fails, warns = [], []

    # ---------- 标题普查 ----------
    heads = [(p.style.name, p.text.strip()) for p in doc.paragraphs
             if p.style is not None and p.style.name.startswith('Heading')]
    h1 = [t for s, t in heads if s in ('Heading 1', 'Heading1')]
    h2 = [t for s, t in heads if s in ('Heading 2', 'Heading2')]
    print(f'标题普查：H1={len(h1)} H2={len(h2)} 合计={len(heads)}')
    for level, texts in (('H1', h1), ('H2', h2)):
        for t in texts:
            if not HEAD_NUM.match(t) and not TITLE_HINT.search(t):
                warns.append(f'疑似杂项 {level} 标题（正文行被设成了标题样式？）：{t[:60]}')
    if expect_h1 is not None and len(h1) != expect_h1:
        fails.append(f'H1 数不符：期望 {expect_h1}，实际 {len(h1)}')
    if expect_h2 is not None and len(h2) != expect_h2:
        fails.append(f'H2 数不符：期望 {expect_h2}，实际 {len(h2)}')

    # ---------- 表格方正 ----------
    for i, t in enumerate(doc.tables, 1):
        widths = {len(r.cells) for r in t.rows}
        if len(widths) > 1:
            fails.append(f'第 {i} 张表列数不一致（克隆行的典型损坏）：{sorted(widths)}')

    # ---------- 内容断言 ----------
    text = full_text(doc)
    for s in checks.get('存在', []):
        if s not in text:
            fails.append(f'应存在但缺失：{s}')
    for s in checks.get('不存在', []):
        hit = [p.text.strip() for p in doc.paragraphs
               if s in p.text and not REV_ROW.match(p.text.strip())]
        if hit or (s in text and not any(s in p.text for p in doc.paragraphs)):
            fails.append(f'应移除但仍存在：{s} → {hit[:1]}')

    for w in warns:
        print('  WARN', w)
    for f in fails:
        print('  FAIL', f)
    ok = len(checks.get('存在', [])) + len(checks.get('不存在', [])) - len(fails)
    print(f'结果：{"全部通过" if not fails else "有失败"}（断言 {ok} 项通过，{len(fails)} 项失败，{len(warns)} 项告警）')
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
