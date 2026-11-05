// The settings page. Its lists (.listed), such as the Vaults and the Agent folders: ＋ Add shows a new empty row,
// numbered after the last, and Remove empties its row and hides it, which Save reads as removed. Copy puts the login
// command on the clipboard. Edit opens a Source map's editor.
document.addEventListener('click', event => {
    const button = event.target.closest('[data-add], [data-remove], [data-copy], [data-edit]');
    if (!button) return;
    if ('edit' in button.dataset) {
        const dialog = document.getElementById(button.dataset.edit);
        dialog.showModal();
        dialog.querySelector('.cm-content')?.focus();
        return;
    }
    if ('copy' in button.dataset) {
        navigator.clipboard?.writeText(button.previousElementSibling.textContent).catch(() => {});
        button.textContent = 'Copied';
        return;
    }
    const empty = row => {
        row.querySelectorAll('input:not([type=radio]), select').forEach(field => field.value = '');
        row.querySelectorAll('[type=radio]').forEach(radio => radio.checked = false);
        return row;
    };
    if ('remove' in button.dataset) {
        empty(button.closest('.row')).hidden = true;
        return;
    }
    const rows = button.closest('.listed').querySelectorAll(':scope > .row'), last = rows[rows.length - 1];
    if (last.hidden) {
        last.hidden = false;
    } else {
        const row = empty(last.cloneNode(true));
        row.querySelectorAll('[name]').forEach(field => field.name = field.name.replace(/\[(\d+)]/, (_, i) => `[${+i + 1}]`));
        row.querySelectorAll('[type=radio]').forEach(radio => radio.value = +radio.value + 1);
        row.querySelectorAll('[type=password]').forEach(value => value.placeholder = 'e.g. npm_a1b2c3'); // a new Secret has none yet
        last.after(row);
    }
    button.previousElementSibling.querySelector('input')?.focus();
});

const settingsForm = document.getElementById('settings');
if (settingsForm) {
    const bar = settingsForm.querySelector('.savebar');
    const state = () => new URLSearchParams([...new FormData(settingsForm)].filter(([name, value]) => name !== '_csrf' && value !== '')).toString();
    const baselineKey = 'settings-baseline';
    const baseline = ('posted' in settingsForm.dataset && sessionStorage.getItem(baselineKey)) || state();
    sessionStorage.removeItem(baselineKey);
    let submitting = false;
    const unsaved = () => !submitting && state() !== baseline;
    const showBar = () => bar.hidden = !unsaved();
    settingsForm.addEventListener('input', showBar);
    settingsForm.addEventListener('change', showBar);
    document.addEventListener('click', event => {
        if (event.target.closest('[data-add], [data-remove]')) showBar();
    });
    settingsForm.addEventListener('submit', event => {
        if (event.submitter?.hasAttribute('formaction')) return;
        submitting = true;
        sessionStorage.setItem(baselineKey, baseline);
    });
    window.addEventListener('pageshow', () => submitting = false);
    window.addEventListener('beforeunload', event => {
        if (unsaved()) event.preventDefault();
    });
    showBar();
}

// The Alerts card: its toggle turns Alerts on for this device, asking the browser's permission only then, and
// registers its push subscription with Ants; off forgets it. Send a test Alert sends one here. None of it is the
// form's: its controls have no names, so Save never sees them.
const alertsCard = document.getElementById('alerts');
if (alertsCard) {
    const toggle = document.getElementById('alerts-on'), test = document.getElementById('alerts-test'), said = document.getElementById('alerts-said');
    const csrf = settingsForm.querySelector('input[name=_csrf]').value;
    const post = (path, body) => fetch(path, { method: 'POST', headers: { 'X-CSRF-TOKEN': csrf, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const key = Uint8Array.from(atob(alertsCard.dataset.key.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
    const subscription = async () => (await navigator.serviceWorker.getRegistration('/'))?.pushManager.getSubscription();
    const deviceName = () => {
        const agent = navigator.userAgent;
        const browser = /Edg\//.test(agent) ? 'Edge' : /Firefox\//.test(agent) ? 'Firefox' : /Chrome\//.test(agent) ? 'Chrome' : /Safari\//.test(agent) ? 'Safari' : 'A browser';
        const system = /Android/.test(agent) ? 'Android' : /iPhone|iPad/.test(agent) ? 'iOS' : /Mac OS/.test(agent) ? 'macOS' : /Windows/.test(agent) ? 'Windows' : /Linux/.test(agent) ? 'Linux' : '';
        return system ? `${browser} on ${system}` : browser;
    };
    const show = on => {
        toggle.checked = on;
        test.disabled = !on;
    };
    const turnOn = async () => {
        if (await Notification.requestPermission() !== 'granted') throw new Error("The browser didn't allow notifications for Ants.");
        const registration = await navigator.serviceWorker.register('/sw.js');
        await navigator.serviceWorker.ready;
        const sub = await registration.pushManager.getSubscription() ?? await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
        const answer = await post('/alerts/devices', { ...sub.toJSON(), name: deviceName() });
        if (!answer.ok) throw new Error(await answer.text() || "Ants didn't take this device.");
    };
    const turnOff = async () => {
        const sub = await subscription();
        if (!sub) return;
        await post('/alerts/devices/remove', { endpoint: sub.endpoint });
        await sub.unsubscribe();
    };
    if (window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window) {
        toggle.disabled = false;
        subscription().then(sub => show(!!sub && Notification.permission === 'granted'));
        toggle.addEventListener('change', async () => {
            const on = toggle.checked;
            toggle.disabled = true;
            said.textContent = '';
            try {
                await (on ? turnOn() : turnOff());
                show(on);
                said.textContent = on ? 'Alerts are on for this device.' : 'Alerts are off for this device.';
            } catch (e) {
                show(!on);
                said.textContent = e.message;
            }
            toggle.disabled = false;
        });
        test.addEventListener('click', async () => {
            const sub = await subscription();
            if (!sub) return show(false);
            said.textContent = await (await post('/alerts/test', { endpoint: sub.endpoint })).text();
        });
    } else {
        said.textContent = "This browser can't get Alerts here.";
    }
}
