# -*- coding: utf-8 -*-
"""Integrity check for an edited .docx set: open every file in Word and export PDF.

Why: raw XML edits (python-docx) can produce a file that python-docx still reads but Word
refuses or renders wrong. Word opening + exporting cleanly is the proof the revision did not
corrupt the documents; the PDFs also give the user something to skim.

Requires Word (Windows) and pywin32. On this host pywin32 lives only in the hermes-agent venv:

    V=/c/Users/<user>/AppData/Local/hermes/hermes-agent/venv
    PYTHONPATH="C:\\Users\\<user>\\AppData\\Local\\hermes\\hermes-agent\\venv\\Lib\\site-packages;\
C:\\Users\\<user>\\AppData\\Local\\hermes\\hermes-agent\\venv\\Lib\\site-packages\\win32;\
C:\\Users\\<user>\\AppData\\Local\\hermes\\hermes-agent\\venv\\Lib\\site-packages\\win32\\lib" \
      PYTHONIOENCODING=utf-8 "$V/Scripts/python.exe" word_render_check.py "D:\\path\\to\\docs"

Usage:
    python word_render_check.py [src_dir] [out_dir]     # out_dir defaults to <src_dir>/_render_out
Exit code is non-zero if any document failed to open/export.
"""
import glob
import os
import sys

import win32com.client as wc


def main(argv):
    src = os.path.abspath(argv[1]) if len(argv) > 1 else os.getcwd()
    out = os.path.abspath(argv[2]) if len(argv) > 2 else os.path.join(src, '_render_out')
    os.makedirs(out, exist_ok=True)

    files = sorted(glob.glob(os.path.join(src, '*.docx')))
    # never treat backup/tool folders' copies as part of the set
    files = [f for f in files if not os.path.basename(f).startswith('~$')]
    if not files:
        print(f'no .docx in {src}')
        return 2

    word = wc.Dispatch('Word.Application')
    word.Visible = False
    word.DisplayAlerts = 0
    ok = 0
    try:
        for f in files:
            name = os.path.basename(f)
            doc = word.Documents.Open(f, ReadOnly=True, AddToRecentFiles=False)
            try:
                pages = doc.ComputeStatistics(2)   # wdStatisticPages
                words = doc.ComputeStatistics(0)   # wdStatisticWords
                pdf = os.path.join(out, name[:-5] + '.pdf')
                doc.ExportAsFixedFormat(pdf, 17)   # wdExportFormatPDF
                size = os.path.getsize(pdf) if os.path.exists(pdf) else 0
                print(f'OK   {name}  pages={pages} words={words}  pdf={size // 1024}KB')
                ok += 1
            finally:
                doc.Close(False)
    finally:
        word.Quit()

    print(f'\nrendered {ok}/{len(files)}')
    return 0 if ok == len(files) else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
