// Answer a Round: one question at a time under a tab bar marking each untouched (·), accepted (✓) or edited (✎),
// then Review and send. Without this script the page is a plain form of every question. Ants stamps the sources.
const form = document.getElementById('round');
const questions = [...form.querySelectorAll('section.question')];
const review = document.getElementById('review');
const tabs = document.getElementById('tabs');
const [back, reset, next] = ['back', 'reset', 'next'].map(id => document.getElementById(id));
const n = questions.length;
const accepted = new Set();
let i = 0;

const answer = j => questions[j].querySelector('textarea');
const mark = j => answer(j).value !== questions[j].dataset.recommendation ? '✎' : accepted.has(j) ? '✓' : '·';

function button(label, j) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.onclick = () => go(j);
    return b;
}

function go(j) {
    i = j;
    render();
}

function render() {
    tabs.replaceChildren(...questions.map((q, j) => button(`${mark(j)} ${q.dataset.title}`, j)), button('Review and send', n));
    [...tabs.children].forEach((tab, j) => {
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', j === i);
    });
    questions.forEach((q, j) => q.hidden = j !== i);
    review.hidden = i !== n;
    if (i === n) {
        document.getElementById('review-list').replaceChildren(...questions.map((q, j) => {
            const item = button(`${mark(j)} ${q.dataset.title}: ${answer(j).value}`, j);
            item.style.display = 'block';
            return item;
        }));
    }
    back.style.visibility = i === 0 ? 'hidden' : '';
    reset.style.visibility = i === n ? 'hidden' : '';
    next.textContent = i === n ? 'Send the Round' : 'Next';
}

back.onclick = () => go(i - 1);
reset.onclick = () => {
    answer(i).value = questions[i].dataset.recommendation;
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
tabs.hidden = back.hidden = reset.hidden = false;
render();
