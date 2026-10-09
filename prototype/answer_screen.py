#!/usr/bin/env python3
# PROTOTYPE, throwaway. Answers "Prototype a round of questions and its answer screen" (oyvij/ants#7).
# Round 2: variant A (one question at a time) won; B and C are in this branch's history.
# Adds a tab bar, colours, a context per question, and Tab/Shift+Tab to go back and edit.
# The real text area is Varde's question box (word jumps, selection, copy/paste, undo); this one is not.
#   python3 prototype/answer_screen.py
# Ctrl+S send the round and show the answer JSON · Ctrl+Q quit
import curses, json, os, textwrap

os.environ.setdefault("ESCDELAY", "25")
HERE = os.path.dirname(os.path.abspath(__file__))
ROUND = json.load(open(os.path.join(HERE, "round.json")))
QS = ROUND["output"]["questions"]


class Area:
    """A text area prefilled with the recommendation, which starts selected so typing replaces it."""

    def __init__(s, text):
        s.rec = text
        s.reset()

    def reset(s):
        s.text, s.cur, s.sel = s.rec, len(s.rec), True

    def put(s, c):
        if s.sel:
            s.text, s.cur = "", 0
        s.text = s.text[: s.cur] + c + s.text[s.cur :]
        s.cur += len(c)

    def key(s, k):
        if k == "ESC":
            s.reset()
            return
        if k == "ALT_ENTER":
            s.put("\n")
        elif len(k) == 1:
            s.put(k)
        elif k == "BS":
            if s.sel:
                s.text, s.cur = "", 0
            elif s.cur:
                s.text, s.cur = s.text[: s.cur - 1] + s.text[s.cur :], s.cur - 1
        elif k == "DEL":
            s.text = s.text[: s.cur] + s.text[s.cur + 1 :]
        elif k == "LEFT":
            s.cur = 0 if s.sel else max(0, s.cur - 1)
        elif k == "RIGHT":
            s.cur = min(len(s.text), s.cur + 1)
        elif k in ("HOME", "^A"):
            s.cur = 0
        elif k in ("END", "^E"):
            s.cur = len(s.text)
        elif k == "^U":
            s.text, s.cur = "", 0
        else:
            return
        s.sel = False

    def rows(s, w):
        spans, pos = [], 0
        for para in s.text.split("\n"):
            spans += [(pos + i, pos + min(i + w, len(para))) for i in range(0, max(len(para), 1), w)]
            pos += len(para) + 1
        cr = max(r for r, (a, b) in enumerate(spans) if a <= s.cur <= b)
        attr = C["sel"] if s.sel else C["answer"]
        return [("▌ " + s.text[a:b], attr, 2 + s.cur - a if r == cr else None) for r, (a, b) in enumerate(spans)]


def text_rows(text, w, attr, indent="  "):
    return [(indent + l, attr, None) for p in text.split("\n") for l in (textwrap.wrap(p, w - len(indent)) or [""])]


def put(scr, y, x, text, attr):
    try:
        scr.addnstr(y, x, text, scr.getmaxyx()[1] - x - 1, attr)
    except curses.error:
        pass


def blit(scr, y, x, rows, h, focus_row=0):
    """Draws rows from y, scrolled so focus_row is visible. Returns the cursor position or None."""
    top = max(0, focus_row - h + 3)
    cursor = None
    for r, (text, attr, col) in enumerate(rows[top : top + h]):
        put(scr, y + r, x, text, attr)
        if col is not None:
            cursor = (y + r, x + col)
    return cursor


def mark(areas, done, i):
    if areas[i].text != QS[i]["recommendation"]:
        return "✎"
    return "✓" if done[i] else "·"


def tab_bar(scr, y, areas, done, i):
    x = 1
    for t, label in enumerate([f" {mark(areas, done, j)} {q['title']} " for j, q in enumerate(QS)] + [" Send "]):
        put(scr, y, x, label, C["tab_on"] if t == i else C["tab"])
        x += len(label) + 1


def answer(areas):
    return {"answers": [
        {"id": q["id"], "answer": a.text, "source": "accepted" if a.text == q["recommendation"] else "written"}
        for q, a in zip(QS, areas)]}


def read_key(scr):
    k = scr.get_wch()
    if isinstance(k, int):
        return {curses.KEY_ENTER: "ENTER", curses.KEY_BACKSPACE: "BS", curses.KEY_LEFT: "LEFT",
                curses.KEY_RIGHT: "RIGHT", curses.KEY_HOME: "HOME", curses.KEY_END: "END",
                curses.KEY_BTAB: "BTAB", curses.KEY_DC: "DEL"}.get(k, "")
    if k == "\x1b":
        scr.nodelay(True)
        seq = ""
        try:
            while True:
                seq += str(scr.get_wch())
        except curses.error:
            pass
        scr.nodelay(False)
        return "ALT_ENTER" if seq in ("\r", "\n") else "ESC" if not seq else ""
    if k in ("\r", "\n"):
        return "ENTER"
    if k in ("\x7f", "\x08"):
        return "BS"
    if k == "\t":
        return "TAB"
    if ord(k) < 32:
        return "^" + chr(ord(k) + 64)
    return k


C = {}


def main(scr):
    curses.raw()
    curses.use_default_colors()
    for n, fg in enumerate([curses.COLOR_CYAN, curses.COLOR_MAGENTA, curses.COLOR_YELLOW, curses.COLOR_GREEN], 1):
        curses.init_pair(n, fg, -1)
    curses.init_pair(5, curses.COLOR_BLACK, curses.COLOR_CYAN)
    curses.init_pair(6, curses.COLOR_BLACK, curses.COLOR_YELLOW)
    C.update(tab=curses.color_pair(1), tab_on=curses.color_pair(5) | curses.A_BOLD, label=curses.color_pair(1) | curses.A_BOLD,
             context=curses.color_pair(2), ask=curses.A_BOLD, why=curses.A_DIM, rec=curses.color_pair(3) | curses.A_BOLD,
             sel=curses.color_pair(6), answer=curses.color_pair(4) | curses.A_BOLD, dim=curses.A_DIM)
    n = len(QS)
    areas, done, i, sent, k = [Area(q["recommendation"]) for q in QS], [False] * n, 0, False, ""
    while k != "^Q":
        if sent:
            sent = not k
        elif k == "^S" or (k == "ENTER" and i == n):
            sent = True
        elif k == "ENTER":
            done[i], i = True, i + 1
        elif k == "TAB":
            i = (i + 1) % (n + 1)
        elif k == "BTAB":
            i = (i - 1) % (n + 1)
        elif k and i < n:
            areas[i].key(k)
        scr.erase()
        H, W = scr.getmaxyx()
        w = min(W - 4, 90)
        # The header comes from Ants (Run, Task, Notification tag), not from the round's JSON.
        put(scr, 0, 1, "Ants · Run invoices-csv-export · Grill the spec (round 2) · [questions]", C["label"])
        put(scr, 1, 1, ROUND["summary"], C["dim"])
        tab_bar(scr, 3, areas, done, n if sent else i)
        cursor = None
        if sent:
            rows = [("Sent. The User Task completes with:", C["label"], None), ("", 0, None)]
            rows += text_rows(json.dumps(answer(areas), indent=2, ensure_ascii=False), W - 4, 0, "")
            hint = "Any key: back to the round · Ctrl+Q quit"
            blit(scr, 5, 2, rows, H - 7)
        elif i == n:
            rows = [("Review the round", C["label"], None), ("", 0, None)]
            for j, q in enumerate(QS):
                rows += [(f"{mark(areas, done, j)} {q['ask']}", C["ask"], None)] + text_rows(areas[j].text, w, C["answer"], "    ")
            hint = "Enter send the round · Shift+Tab back to a question to change it"
            blit(scr, 5, 2, rows, H - 7)
        else:
            q, gap = QS[i], ("", 0, None)
            rows = ([("QUESTION", C["label"], None)] + text_rows(q["ask"], w, C["ask"]) + [gap]
                    + [("WHAT IT SETTLES", C["label"], None)] + text_rows(q["context"], w, C["context"]) + [gap]
                    + [("RECOMMENDED", C["label"], None)] + text_rows(q["why"], w, C["why"]) + [gap]
                    + [("YOUR ANSWER", C["label"], None)] + areas[i].rows(w - 2))
            hint = "Enter accept · type to replace · ← or End to edit · Alt+Enter new line · Esc reset · Tab/Shift+Tab move"
            cursor = blit(scr, 5, 2, rows, H - 7, len(rows))
        put(scr, H - 2, 1, hint, C["dim"])
        put(scr, H - 1, 1, "Ctrl+S send · Ctrl+Q quit", C["dim"])
        curses.curs_set(1 if cursor else 0)
        if cursor:
            scr.move(*cursor)
        k = read_key(scr)


curses.wrapper(main)
