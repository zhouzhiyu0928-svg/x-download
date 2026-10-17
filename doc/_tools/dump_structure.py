# -*- coding: utf-8 -*-
"""Dump docx body structure: paragraphs (with style) + tables (full cells)."""
import glob
import os
import sys

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph
from lxml import etree


def style_name(p):
    return '(none)' if p.style is None else p.style.name


def main(src, out):
    os.makedirs(out, exist_ok=True)
    for f in sorted(glob.glob(os.path.join(src, '*.docx'))):
        if os.path.basename(f).startswith('~$'):
            continue
        d = Document(f)
        lines = []
        ti = pi = 0
        for el in d.element.body:
            tag = etree.QName(el).localname
            if tag == 'p':
                p = Paragraph(el, d)
                lines.append('P%03d [%s] %s' % (pi, style_name(p), p.text))
                pi += 1
            elif tag == 'tbl':
                tb = Table(el, d)
                cols = len(tb.rows[0].cells) if tb.rows else 0
                lines.append('TABLE%d rows=%d cols=%d' % (ti, len(tb.rows), cols))
                for ri, row in enumerate(tb.rows):
                    lines.append('   R%02d | %s' % (ri, ' | '.join(c.text.replace('\n', '\\n') for c in row.cells)))
                ti += 1
        name = os.path.basename(f)[:-5] + '.dump.txt'
        with open(os.path.join(out, name), 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(lines))
        print('wrote %s  paragraphs=%d tables=%d' % (name, pi, ti))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
