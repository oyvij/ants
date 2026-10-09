#!/usr/bin/env python3
# PROTOTYPE, throwaway. Answers "Prototype a round of questions and its answer screen" (oyvij/ants#7).
# Three variants of the Ants TUI answer screen, all reading round.json (a griller Task's Result).
#   python3 prototype/answer_screen.py [A|B|C]
# Ctrl+T next variant · Ctrl+S send the round and show the answer JSON · Ctrl+Q quit
import curses, json, os, sys, textwrap

os.environ.setdefault("ESCDELAY", "25")
HERE = os.path.dirname(os.path.abspath(__file__))
ROUND = json.load(open(os.path.join(HERE, "round.json")))
QS = ROUND["output"]["questions"]
VARIANTS = {"A": "One at a time", "B": "One long form", "C": "List and detail"}


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
            return True
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
            return False
        s.sel = False
        return True

    def rows(s, w, focused):
        spans, pos = [], 0
        for para in s.text.split("\n"):
            spans += [(pos + i, pos + min(i + w, len(para))) for i in range(0, max(len(para), 1), w)]
            pos += len(para) + 1
        cr = max(r for r, (a, b) in enumerate(spans) if a <= s.cur <= b)
        attr = curses.A_REVERSE if s.sel and focused else curses.A_BOLD if focused else curses.A_NORMAL
        return [("▌ " + s.text[a:b], attr, 2 + s.cur - a if focused and r == cr else None) for r, (a, b) in enumerate(spans)]


class State:
    def __init__(s, variant):
        s.variant = variant
        s.areas = [Area(q["recommendation"]) for q in QS]
        s.done = [False] * len(QS)
        s.i = 0
        s.sent = False

    def mark(s, i):
        if s.areas[i].text != QS[i]["recommendation"]:
            return "✎"
        return "✓" if s.done[i] else "·"

    def answer(s):
        return {"answers": [
            {"id": q["id"], "answer": a.text, "source": "accepted" if a.text == q["recommendation"] else "written"}
            for q, a in zip(QS, s.areas)]}


def text_rows(text, w, attr=curses.A_NORMAL, indent=""):
    return [(indent + l, attr, None) for p in text.split("\n") for l in (textwrap.wrap(p, w - len(indent)) or [""])]


def blit(scr, y, x, rows, h, focus_row=0):
    """Draws rows from y, scrolled so focus_row is visible. Returns the cursor position or None."""
    top = max(0, focus_row - h + 3)
    cursor = None
    for r, (text, attr, col) in enumerate(rows[top : top + h]):
        try:
            scr.addnstr(y + r, x, text, scr.getmaxyx()[1] - x - 1, attr)
        except curses.error:
            pass
        if col is not None:
            cursor = (y + r, x + col)
    return cursor


def question_rows(st, i, w, focused):
    q, dim = QS[i], curses.A_DIM
    return (text_rows(q["ask"], w, curses.A_BOLD) + [("", 0, None)] + text_rows(q["why"], w, dim)
            + [("", 0, None), ("Recommended. Enter accepts, typing replaces:" if focused else "Answer:", dim, None)]
            + st.areas[i].rows(w - 2, focused))


def variant_a(st, scr, k, H, W):
    n, w = len(QS), min(W - 4, 90)
    if k == "ENTER":
        if st.i == n:
            st.sent = True
        else:
            st.done[st.i], st.i = True, st.i + 1
    elif k == "^B":
        st.i = max(0, st.i - 1)
    elif k and st.i < n:
        st.areas[st.i].key(k)
    if st.i == n:
        rows = [("Review the round", curses.A_BOLD, None), ("", 0, None)]
        for i, q in enumerate(QS):
            rows += [(f"{st.mark(i)} {q['ask']}", curses.A_NORMAL, None)] + text_rows(st.areas[i].text, w, curses.A_DIM, "    ")
        return rows, 0, "Enter send the round · Ctrl+B back"
    rows = [(f"Question {st.i + 1} of {n}", curses.A_DIM, None), ("", 0, None)] + question_rows(st, st.i, w, True)
    return rows, len(rows), "Enter accept · type to replace · ← or End to edit · Alt+Enter new line · Esc reset · Ctrl+B back"


def variant_b(st, scr, k, H, W):
    n, w = len(QS), min(W - 4, 90)
    st.i = min(st.i, n - 1)
    if k == "ENTER":
        st.done[st.i] = True
        if st.i == n - 1:
            st.sent = True
        st.i = min(st.i + 1, n - 1)
    elif k in ("TAB", "DOWN"):
        st.i = min(st.i + 1, n - 1)
    elif k in ("BTAB", "UP"):
        st.i = max(st.i - 1, 0)
    elif k:
        st.areas[st.i].key(k)
    rows, focus = [], 0
    for i, q in enumerate(QS):
        rows += [(f"{st.mark(i)} {i + 1}. {q['title']}", curses.A_REVERSE if i == st.i else curses.A_DIM, None)]
        rows += question_rows(st, i, w, i == st.i) + [("", 0, None)]
        if i == st.i:
            focus = len(rows)
    return rows, focus, "Enter accept and go on (on the last, sends) · Tab/↑↓ move · Alt+Enter new line · Esc reset"


def variant_c(st, scr, k, H, W):
    n, lw = len(QS), 28
    st.i = min(st.i, n - 1)
    if k == "ENTER":
        st.done[st.i] = True
        st.i = next((j for j in list(range(st.i + 1, n)) + list(range(n)) if not st.done[j]), st.i)
    elif k in ("TAB", "DOWN"):
        st.i = (st.i + 1) % n
    elif k in ("BTAB", "UP"):
        st.i = (st.i - 1) % n
    elif k:
        st.areas[st.i].key(k)
    left = [(f" {st.mark(i)} {i + 1}. {q['title']}"[:lw].ljust(lw), curses.A_REVERSE if i == st.i else 0, None)
            for i, q in enumerate(QS)]
    left += [("", 0, None), (f" {sum(st.done)}/{n} answered", curses.A_DIM, None), (" Ctrl+S send", curses.A_DIM, None)]
    blit(scr, 3, 1, left, H - 5)
    for y in range(3, H - 2):
        try:
            scr.addstr(y, lw + 2, "│", curses.A_DIM)
        except curses.error:
            pass
    rows = question_rows(st, st.i, min(W - lw - 6, 80), True)
    return rows, len(rows), "Enter accept and jump to the next open one · Tab/↑↓ pick · Alt+Enter new line · Esc reset"


DRAW = {"A": variant_a, "B": variant_b, "C": variant_c}


def read_key(scr):
    k = scr.get_wch()
    if isinstance(k, int):
        return {curses.KEY_ENTER: "ENTER", curses.KEY_BACKSPACE: "BS", curses.KEY_LEFT: "LEFT",
                curses.KEY_RIGHT: "RIGHT", curses.KEY_UP: "UP", curses.KEY_DOWN: "DOWN", curses.KEY_HOME: "HOME",
                curses.KEY_END: "END", curses.KEY_BTAB: "BTAB", curses.KEY_DC: "DEL"}.get(k, "")
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


def main(scr):
    curses.raw()
    curses.use_default_colors()
    st = State(sys.argv[1].upper() if len(sys.argv) > 1 and sys.argv[1].upper() in VARIANTS else "A")
    k = ""
    while True:
        if k == "^Q":
            return
        if k == "^T":
            keys = list(VARIANTS)
            st.variant, k = keys[(keys.index(st.variant) + 1) % len(keys)], ""
        if k == "^S":
            st.sent, k = True, ""
        scr.erase()
        H, W = scr.getmaxyx()
        # The header comes from Ants (Run, Task, Notification tag), not from the round's JSON.
        blit(scr, 0, 1, [(f"Ants · Run invoices-csv-export · Grill the spec (round 2) · [questions]", curses.A_BOLD, None),
                         (ROUND["summary"], curses.A_DIM, None)], 2)
        if st.sent:
            rows = text_rows(json.dumps(st.answer(), indent=2, ensure_ascii=False), W - 4)
            rows = [("Sent. The User Task completes with:", curses.A_BOLD, None), ("", 0, None)] + rows
            blit(scr, 3, 2, rows, H - 5)
            hint, cursor = "Any key: back to the round · Ctrl+Q quit", None
            if k:
                st.sent = False
        else:
            rows, focus, hint = DRAW[st.variant](st, scr, k, H, W)
            if st.sent:  # the key just sent it; redraw as sent
                k = ""
                continue
            x = 2 if st.variant != "C" else 32
            cursor = blit(scr, 3, x, rows, H - 5, focus)
        blit(scr, H - 2, 1, [(hint, curses.A_DIM, None)], 1)
        bar = f" ◀ variant {st.variant}: {VARIANTS[st.variant]} ▶  Ctrl+T next · Ctrl+S send · Ctrl+Q quit "
        blit(scr, H - 1, max(0, (W - len(bar)) // 2), [(bar, curses.A_REVERSE, None)], 1)
        curses.curs_set(1 if cursor else 0)
        if cursor:
            scr.move(*cursor)
        k = read_key(scr)


curses.wrapper(main)
