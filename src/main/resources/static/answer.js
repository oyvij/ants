// Without this script the page is a plain form of every question. Ants stamps the sources.
const form = document.getElementById('round');
const questions = [...form.querySelectorAll('section.question')];
const review = document.getElementById('review');
const tabs = document.getElementById('tabs');
const [back, reset, next] = ['back', 'reset', 'next'].map(id => document.getElementById(id));
const footer = form.querySelector('footer');
const n = questions.length;
const accepted = new Set();
let i = 0;

const answer = j => questions[j].querySelector('textarea');
const edited = j => answer(j).value !== questions[j].dataset.recommendation;
const answered = j => (accepted.has(j) || edited(j)) && answer(j).value.trim() !== '';
const marks = j => [answered(j) && '✓', edited(j) && '✎'].filter(Boolean).join(' ');
const steps = {ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1};

function button(j, ...content) {
    const b = document.createElement('button');
    b.type = 'button';
    b.append(...content);
    b.onclick = () => go(j);
    return b;
}

function span(className, text) {
    return Object.assign(document.createElement('span'), {className, textContent: text});
}

function row(q, j) {
    return button(j, span('n', j + 1), ' ', span('t', q.dataset.title), ' ', span('m', marks(j)));
}

function go(j) {
    i = j;
    render();
}

function reveal(tab) {
    const shown = tabs.getBoundingClientRect(), box = tab.getBoundingClientRect();
    tabs.scrollLeft += Math.min(0, box.left - shown.left) + Math.max(0, box.right - shown.right);
}

function render() {
    const focused = tabs.contains(document.activeElement);
    tabs.replaceChildren(...questions.map(row), button(n, span('t', 'Review and send')));
    [...tabs.children].forEach((tab, j) => {
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', j === i);
        tab.tabIndex = j === i ? 0 : -1;
    });
    if (focused) tabs.children[i].focus();
    reveal(tabs.children[i]);
    questions.forEach((q, j) => q.hidden = j !== i);
    review.hidden = i !== n;
    if (i === n) {
        // Each full question, with the answer under it.
        document.getElementById('review-list').replaceChildren(...questions.map((q, j) => {
            const item = button(j, `${marks(j)} ${q.querySelector('.ask .tx').textContent.trim()}`);
            item.append(Object.assign(document.createElement('div'), {className: 'a', textContent: answer(j).value}));
            return item;
        }));
    }
    back.style.visibility = i === 0 ? 'hidden' : '';
    reset.style.visibility = i === n ? 'hidden' : '';
    next.textContent = i === n ? 'Send the Round' : 'Next';
}

tabs.onkeydown = event => {
    const step = steps[event.key];
    if (!step) return;
    event.preventDefault();
    go((i + step + n + 1) % (n + 1));
};
back.onclick = () => go(i - 1);
reset.onclick = () => {
    answer(i).value = questions[i].dataset.recommendation;
    answer(i).dispatchEvent(new Event('change')); // the markdown editor over it takes the value
    render();
};
next.onclick = event => {
    if (i < n) {
        event.preventDefault();
        accepted.add(i);
        go(i + 1);
        return;
    }
    // A hidden empty answer would stop the form without a word, so show it first.
    const empty = questions.findIndex((q, j) => !answer(j).value.trim());
    if (empty >= 0) {
        event.preventDefault();
        go(empty);
        answer(empty).reportValidity();
    }
};
form.addEventListener('input', render);
new ResizeObserver(() => tabs.setAttribute('aria-orientation', getComputedStyle(tabs).flexDirection === 'row' ? 'horizontal' : 'vertical')).observe(tabs);
new ResizeObserver(() => form.style.setProperty('--footer-height', footer.offsetHeight + 'px')).observe(footer, { box: 'border-box' });
tabs.hidden = back.hidden = reset.hidden = false;
render();
