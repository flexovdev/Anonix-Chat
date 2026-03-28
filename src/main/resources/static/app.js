'use strict';

const joinPanel = document.getElementById('joinPanel');
const inputArea = document.getElementById('inputArea');
const messagesArea = document.getElementById('messagesArea');
const statusDiv = document.getElementById('status');
const roomIdDisplay = document.getElementById('roomIdDisplay');
const copyRoomBtn = document.getElementById('copyRoomBtn');
const shareRoomBtn = document.getElementById('shareRoomBtn');
const logoutBtn = document.getElementById('logoutBtn');
const roomIdInput = document.getElementById('roomIdInput');
const joinBtn = document.getElementById('joinBtn');
const createRoomBtn = document.getElementById('createRoomBtn');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const identityChip = document.getElementById('identityChip');
const aliasDisplay = document.getElementById('aliasDisplay');
const roomControls = document.getElementById('roomControls');
const allowOthersToggle = document.getElementById('allowOthersToggle');
const autoCleanupToggle = document.getElementById('autoCleanupToggle');
const saveSettingsBtn = document.getElementById('saveSettingsBtn');
const roomControlHint = document.getElementById('roomControlHint');

const ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{12,64}$/;
const SESSION_KEY_PREFIX = 'room_key_';
const SEND_COOLDOWN_MS = 1000;
const MAX_PLAINTEXT_LENGTH = 1500;
const RECONNECT_MAX_DELAY_MS = 15000;

let stompClient = null;
let roomId = null;
let sessionId = null;
let ownAlias = null;
let isRoomAdmin = false;
let encryptionKey = null;
let inviteKeyToken = null;
let lastSendTime = 0;
let manualDisconnect = false;
let reconnectAttempts = 0;
let reconnectTimerId = null;
const pendingMessages = new Map();
const pendingOrder = [];
const AudioContextClass = window.AudioContext || window.webkitAudioContext || null;
const SOUND_PATTERNS = Object.freeze({
    incoming_message: {
        wave: 'triangle',
        volume: 0.028,
        notes: [
            { at: 0.00, frequency: 784, duration: 0.06, gain: 1.0 },
            { at: 0.08, frequency: 1174, duration: 0.08, gain: 0.72 }
        ]
    },
    message_sent: {
        wave: 'sine',
        volume: 0.02,
        notes: [
            { at: 0.00, frequency: 659, duration: 0.05, gain: 0.8 },
            { at: 0.06, frequency: 880, duration: 0.05, gain: 0.55 }
        ]
    },
    participant_joined: {
        wave: 'triangle',
        volume: 0.024,
        notes: [
            { at: 0.00, frequency: 523, duration: 0.06, gain: 0.8 },
            { at: 0.08, frequency: 659, duration: 0.07, gain: 0.75 },
            { at: 0.18, frequency: 784, duration: 0.08, gain: 0.7 }
        ]
    },
    participant_left: {
        wave: 'sine',
        volume: 0.022,
        notes: [
            { at: 0.00, frequency: 659, duration: 0.07, gain: 0.78 },
            { at: 0.09, frequency: 523, duration: 0.08, gain: 0.66 },
            { at: 0.19, frequency: 392, duration: 0.10, gain: 0.58 }
        ]
    },
    invite_copied: {
        wave: 'triangle',
        volume: 0.02,
        notes: [
            { at: 0.00, frequency: 988, duration: 0.04, gain: 0.8 },
            { at: 0.06, frequency: 1318, duration: 0.05, gain: 0.62 }
        ]
    },
    invite_shared: {
        wave: 'triangle',
        volume: 0.022,
        notes: [
            { at: 0.00, frequency: 880, duration: 0.04, gain: 0.75 },
            { at: 0.06, frequency: 1174, duration: 0.05, gain: 0.66 },
            { at: 0.13, frequency: 1568, duration: 0.06, gain: 0.58 }
        ]
    },
    settings_saved: {
        wave: 'triangle',
        volume: 0.024,
        notes: [
            { at: 0.00, frequency: 587, duration: 0.05, gain: 0.78 },
            { at: 0.07, frequency: 880, duration: 0.08, gain: 0.7 }
        ]
    },
    connection_lost: {
        wave: 'sawtooth',
        volume: 0.014,
        notes: [
            { at: 0.00, frequency: 240, slideTo: 180, duration: 0.12, gain: 0.95 },
            { at: 0.14, frequency: 200, slideTo: 150, duration: 0.14, gain: 0.8 }
        ]
    },
    connection_restored: {
        wave: 'triangle',
        volume: 0.025,
        notes: [
            { at: 0.00, frequency: 523, duration: 0.05, gain: 0.78 },
            { at: 0.07, frequency: 659, duration: 0.06, gain: 0.72 },
            { at: 0.16, frequency: 880, duration: 0.08, gain: 0.66 }
        ]
    },
    error: {
        wave: 'sawtooth',
        volume: 0.016,
        notes: [
            { at: 0.00, frequency: 220, slideTo: 196, duration: 0.08, gain: 1.0 },
            { at: 0.10, frequency: 185, slideTo: 165, duration: 0.10, gain: 0.86 },
            { at: 0.22, frequency: 156, slideTo: 139, duration: 0.12, gain: 0.72 }
        ]
    }
});
const SOUND_COOLDOWNS_MS = Object.freeze({
    incoming_message: 140,
    message_sent: 100,
    participant_joined: 500,
    participant_left: 500,
    invite_copied: 300,
    invite_shared: 300,
    settings_saved: 250,
    connection_lost: 1200,
    connection_restored: 900,
    error: 300
});

let audioContext = null;
const soundLastPlayedAt = new Map();

function ensureAudioContext() {
    if (!AudioContextClass) {
        return null;
    }

    if (!audioContext) {
        audioContext = new AudioContextClass();
    }

    return audioContext;
}

async function primeAudioContext() {
    const context = ensureAudioContext();
    if (!context || context.state !== 'suspended') {
        return;
    }

    try {
        await context.resume();
    } catch (error) {
        console.debug('Audio context resume skipped', error);
    }
}

function playEventSound(eventName) {
    const pattern = SOUND_PATTERNS[eventName];
    const context = ensureAudioContext();

    if (!pattern || !context || context.state !== 'running') {
        return;
    }

    const now = Date.now();
    const cooldown = SOUND_COOLDOWNS_MS[eventName] || 0;
    const lastPlayedAt = soundLastPlayedAt.get(eventName) || 0;
    if (now - lastPlayedAt < cooldown) {
        return;
    }
    soundLastPlayedAt.set(eventName, now);

    const masterGain = context.createGain();
    masterGain.gain.setValueAtTime(1, context.currentTime);
    masterGain.connect(context.destination);

    pattern.notes.forEach(note => {
        const oscillator = context.createOscillator();
        const gainNode = context.createGain();
        const startTime = context.currentTime + note.at;
        const attackTime = Math.min(0.02, note.duration / 2);
        const peakGain = Math.max(0.0001, pattern.volume * (note.gain || 1));

        oscillator.type = pattern.wave;
        oscillator.frequency.setValueAtTime(note.frequency, startTime);
        if (note.slideTo) {
            oscillator.frequency.linearRampToValueAtTime(note.slideTo, startTime + note.duration);
        }

        gainNode.gain.setValueAtTime(0.0001, startTime);
        gainNode.gain.exponentialRampToValueAtTime(peakGain, startTime + attackTime);
        gainNode.gain.exponentialRampToValueAtTime(0.0001, startTime + note.duration);

        oscillator.connect(gainNode);
        gainNode.connect(masterGain);
        oscillator.start(startTime);
        oscillator.stop(startTime + note.duration + 0.04);
    });

    window.setTimeout(() => {
        masterGain.disconnect();
    }, 1000);
}

function setStatus(text, state = 'idle') {
    statusDiv.innerText = text;
    statusDiv.dataset.state = state;
}

function setComposerDisabled(disabled) {
    messageInput.disabled = disabled;
    sendBtn.disabled = disabled;
}

function updateActionButtons() {
    const activeRoom = Boolean(roomId && sessionId);
    copyRoomBtn.hidden = !activeRoom;
    logoutBtn.hidden = !activeRoom;
    shareRoomBtn.hidden = !activeRoom || typeof navigator.share !== 'function';
}

function resetMessages() {
    messagesArea.innerHTML = '<div class="system-message">Создайте комнату или откройте полную ссылку-приглашение, чтобы начать чат.</div>';
}

function setRoomDisplay(currentRoomId) {
    if (!currentRoomId) {
        roomIdDisplay.innerText = 'Комната не выбрана';
        roomIdDisplay.title = '';
        return;
    }

    const shortened = currentRoomId.length > 26
        ? `${currentRoomId.slice(0, 12)}...${currentRoomId.slice(-8)}`
        : currentRoomId;

    roomIdDisplay.innerText = shortened;
    roomIdDisplay.title = currentRoomId;
}

function setAliasDisplay(alias) {
    ownAlias = alias || null;
    if (!ownAlias) {
        identityChip.hidden = true;
        aliasDisplay.innerText = 'Скрыт';
        return;
    }

    aliasDisplay.innerText = ownAlias;
    identityChip.hidden = false;
}

function setRoomControlsState(settings = null) {
    if (!settings) {
        roomControls.hidden = true;
        isRoomAdmin = false;
        allowOthersToggle.checked = true;
        autoCleanupToggle.checked = false;
        allowOthersToggle.disabled = true;
        autoCleanupToggle.disabled = true;
        saveSettingsBtn.hidden = true;
        roomControlHint.innerText = 'После входа здесь появятся параметры комнаты.';
        return;
    }

    roomControls.hidden = false;
    isRoomAdmin = Boolean(settings.isAdmin);
    allowOthersToggle.checked = Boolean(settings.allowOthers);
    autoCleanupToggle.checked = Boolean(settings.autoCleanup);
    allowOthersToggle.disabled = !isRoomAdmin;
    autoCleanupToggle.disabled = !isRoomAdmin;
    saveSettingsBtn.hidden = !isRoomAdmin;
    roomControlHint.innerText = isRoomAdmin
        ? 'Вы управляете комнатой. Здесь можно закрыть вход и включить автоочистку.'
        : 'Вы подключены как участник. Просмотр настроек доступен, изменение только у владельца комнаты.';
}

function addSystemMessage(text, options = {}) {
    const { event = null, playSound = false } = options;
    const sysDiv = document.createElement('div');
    sysDiv.className = 'system-message';
    sysDiv.innerText = text;
    messagesArea.appendChild(sysDiv);
    sysDiv.scrollIntoView({ behavior: 'smooth', block: 'end' });
    if (playSound && event) {
        playEventSound(event);
    }
}

function escapeHtml(str) {
    if (!str) {
        return '';
    }

    return str.replace(/[&<>]/g, function(match) {
        if (match === '&') return '&amp;';
        if (match === '<') return '&lt;';
        if (match === '>') return '&gt;';
        return match;
    });
}

function formatTime(timestamp) {
    return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function createMessageElement({ text, isOwn, alias, timestamp, pending = false, failed = false, clientMessageId = '' }) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${isOwn ? 'own' : 'other'}`;
    if (pending) {
        messageDiv.classList.add('pending');
    }
    if (failed) {
        messageDiv.classList.add('failed');
    }
    if (clientMessageId) {
        messageDiv.dataset.clientMessageId = clientMessageId;
    }

    const sender = isOwn ? `Вы · ${alias || ownAlias || 'Скрыт'}` : (alias || 'Гость');
    const timeLabel = failed
        ? 'Ошибка отправки'
        : (pending ? 'Отправка...' : formatTime(timestamp || new Date().toISOString()));

    messageDiv.innerHTML = `
        <div class="meta">
            <span>${escapeHtml(sender)}</span>
            <span class="meta-time">${escapeHtml(timeLabel)}</span>
        </div>
        <div class="text">${escapeHtml(text)}</div>
    `;

    return messageDiv;
}

function addPendingMessage(clientMessageId, text) {
    const element = createMessageElement({
        text,
        isOwn: true,
        alias: ownAlias,
        timestamp: new Date().toISOString(),
        pending: true,
        clientMessageId
    });

    messagesArea.appendChild(element);
    element.scrollIntoView({ behavior: 'smooth', block: 'end' });
    pendingMessages.set(clientMessageId, element);
    pendingOrder.push(clientMessageId);

    window.setTimeout(() => {
        if (pendingMessages.has(clientMessageId)) {
            markPendingMessageFailed(clientMessageId, 'Сообщение не подтверждено сервером.');
        }
    }, 12000);
}

function markPendingMessageDelivered(clientMessageId, alias, timestamp, text) {
    const element = pendingMessages.get(clientMessageId);
    if (!element) {
        messagesArea.appendChild(createMessageElement({
            text,
            isOwn: true,
            alias,
            timestamp
        }));
        playEventSound('message_sent');
        return;
    }

    element.classList.remove('pending', 'failed');
    const senderLabel = element.querySelector('.meta span');
    const timeLabel = element.querySelector('.meta-time');
    const textNode = element.querySelector('.text');

    if (senderLabel) {
        senderLabel.innerText = `Вы · ${alias || ownAlias || 'Скрыт'}`;
    }
    if (timeLabel) {
        timeLabel.innerText = formatTime(timestamp);
    }
    if (textNode) {
        textNode.innerText = text;
    }

    pendingMessages.delete(clientMessageId);
    removePendingFromQueue(clientMessageId);
    playEventSound('message_sent');
}

function markPendingMessageFailed(clientMessageId, reason) {
    const element = pendingMessages.get(clientMessageId);
    if (!element) {
        return;
    }

    element.classList.remove('pending');
    element.classList.add('failed');
    const timeLabel = element.querySelector('.meta-time');
    if (timeLabel) {
        timeLabel.innerText = 'Не отправлено';
    }
    if (reason) {
        element.title = reason;
    }

    pendingMessages.delete(clientMessageId);
    removePendingFromQueue(clientMessageId);
    playEventSound('error');
}

function markOldestPendingMessageFailed(reason) {
    const oldestId = pendingOrder[0];
    if (oldestId) {
        markPendingMessageFailed(oldestId, reason);
    }
}

function removePendingFromQueue(clientMessageId) {
    const index = pendingOrder.indexOf(clientMessageId);
    if (index >= 0) {
        pendingOrder.splice(index, 1);
    }
}

function addIncomingMessage(text, alias, timestamp) {
    const element = createMessageElement({
        text,
        isOwn: false,
        alias,
        timestamp
    });
    messagesArea.appendChild(element);
    element.scrollIntoView({ behavior: 'smooth', block: 'end' });
    playEventSound('incoming_message');
}

function getCsrfToken() {
    const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
}

async function apiFetch(url, options = {}) {
    const requestOptions = {
        credentials: 'same-origin',
        ...options
    };

    const headers = new Headers(requestOptions.headers || {});
    const method = (requestOptions.method || 'GET').toUpperCase();
    const csrfToken = getCsrfToken();

    if (csrfToken && !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
        headers.set('X-XSRF-TOKEN', csrfToken);
    }

    requestOptions.headers = headers;
    return fetch(url, requestOptions);
}

function isValidRoomId(value) {
    return ROOM_ID_PATTERN.test(value);
}

function normalizeRoomId(value) {
    return value.trim();
}

function base64UrlEncode(bytes) {
    let binary = '';
    bytes.forEach(byte => {
        binary += String.fromCharCode(byte);
    });

    return btoa(binary)
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/g, '');
}

function base64UrlDecode(token) {
    const normalized = token.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    return Uint8Array.from(binary, character => character.charCodeAt(0));
}

function getInviteFromHash() {
    const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
    const params = new URLSearchParams(hash);
    return {
        roomId: params.get('room') || '',
        keyToken: params.get('key') || ''
    };
}

function updateInviteHash(currentRoomId, keyToken) {
    const hash = new URLSearchParams({
        room: currentRoomId,
        key: keyToken
    }).toString();
    history.replaceState(null, '', `${window.location.pathname}#${hash}`);
}

function clearInviteHash() {
    history.replaceState(null, '', window.location.pathname);
}

function buildInviteLink(currentRoomId, keyToken) {
    return `${window.location.origin}${window.location.pathname}#room=${encodeURIComponent(currentRoomId)}&key=${encodeURIComponent(keyToken)}`;
}

async function generateKey() {
    return crypto.subtle.generateKey(
        { name: 'AES-GCM', length: 256 },
        true,
        ['encrypt', 'decrypt']
    );
}

async function exportKeyToken(key) {
    const rawKey = new Uint8Array(await crypto.subtle.exportKey('raw', key));
    return base64UrlEncode(rawKey);
}

async function importKeyToken(keyToken) {
    const rawKey = base64UrlDecode(keyToken);
    return crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, true, ['encrypt', 'decrypt']);
}

function storeKeyToken(currentRoomId, keyToken) {
    sessionStorage.setItem(`${SESSION_KEY_PREFIX}${currentRoomId}`, keyToken);
}

function loadStoredKeyToken(currentRoomId) {
    return sessionStorage.getItem(`${SESSION_KEY_PREFIX}${currentRoomId}`);
}

function clearStoredKeyToken(currentRoomId) {
    sessionStorage.removeItem(`${SESSION_KEY_PREFIX}${currentRoomId}`);
}

async function resolveRoomKey(currentRoomId) {
    const invite = getInviteFromHash();
    if (invite.roomId === currentRoomId && invite.keyToken) {
        try {
            const inviteKey = await importKeyToken(invite.keyToken);
            storeKeyToken(currentRoomId, invite.keyToken);
            updateInviteHash(currentRoomId, invite.keyToken);
            return { key: inviteKey, keyToken: invite.keyToken, source: 'invite' };
        } catch (error) {
            console.error('Invalid invite key', error);
            addSystemMessage('Ключ приглашения поврежден. Будет создан новый локальный ключ.');
        }
    }

    const storedKeyToken = loadStoredKeyToken(currentRoomId);
    if (storedKeyToken) {
        try {
            const storedKey = await importKeyToken(storedKeyToken);
            updateInviteHash(currentRoomId, storedKeyToken);
            return { key: storedKey, keyToken: storedKeyToken, source: 'session' };
        } catch (error) {
            console.error('Stored key import failed', error);
            clearStoredKeyToken(currentRoomId);
        }
    }

    const generatedKey = await generateKey();
    const generatedKeyToken = await exportKeyToken(generatedKey);
    storeKeyToken(currentRoomId, generatedKeyToken);
    updateInviteHash(currentRoomId, generatedKeyToken);
    return { key: generatedKey, keyToken: generatedKeyToken, source: 'generated' };
}

async function encryptMessage(key, plaintext) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encoded = new TextEncoder().encode(plaintext);
    const encrypted = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: iv },
        key,
        encoded
    );

    const combined = new Uint8Array(iv.length + encrypted.byteLength);
    combined.set(iv, 0);
    combined.set(new Uint8Array(encrypted), iv.length);
    return base64UrlEncode(combined);
}

async function decryptMessage(key, ciphertextToken) {
    try {
        const combined = base64UrlDecode(ciphertextToken);
        const iv = combined.slice(0, 12);
        const encrypted = combined.slice(12);
        const decrypted = await crypto.subtle.decrypt(
            { name: 'AES-GCM', iv: iv },
            key,
            encrypted
        );

        return new TextDecoder().decode(decrypted);
    } catch (error) {
        console.error('Decryption failed', error);
        return '[не удалось расшифровать этим ключом]';
    }
}

function clearReconnectTimer() {
    if (reconnectTimerId) {
        window.clearTimeout(reconnectTimerId);
        reconnectTimerId = null;
    }
}

async function requestNewRoomId() {
    const response = await apiFetch('/api/chat/room', { method: 'POST' });
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || 'Не удалось создать новую комнату.');
    }

    const data = await response.json();
    return data.roomId;
}

async function validateCurrentSession() {
    if (!roomId || !sessionId) {
        return false;
    }

    try {
        const response = await apiFetch(`/api/chat/session/${encodeURIComponent(sessionId)}/validate?roomId=${encodeURIComponent(roomId)}`);
        if (!response.ok) {
            return false;
        }
        const data = await response.json();
        return Boolean(data.valid);
    } catch (error) {
        console.error('Session validation failed', error);
        return false;
    }
}

async function loadRoomSettings() {
    if (!roomId) {
        setRoomControlsState(null);
        return;
    }

    try {
        const response = await apiFetch(`/api/chat/room/${encodeURIComponent(roomId)}/settings?sessionId=${encodeURIComponent(sessionId || '')}`);
        if (!response.ok) {
            setRoomControlsState(null);
            return;
        }
        const settings = await response.json();
        setRoomControlsState(settings);
    } catch (error) {
        console.error('Failed to load room settings', error);
        setRoomControlsState(null);
    }
}

async function saveRoomSettings() {
    if (!roomId || !sessionId || !isRoomAdmin) {
        return;
    }

    saveSettingsBtn.disabled = true;
    try {
        const response = await apiFetch(`/api/chat/room/${encodeURIComponent(roomId)}/settings`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                adminSessionId: sessionId,
                allowOthers: allowOthersToggle.checked,
                autoCleanup: autoCleanupToggle.checked
            })
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Не удалось сохранить настройки.');
        }

        const settings = await response.json();
        setRoomControlsState(settings);
        playEventSound('settings_saved');
        addSystemMessage('Настройки комнаты сохранены.');
    } catch (error) {
        console.error('Failed to save room settings', error);
        addSystemMessage(error.message || 'Не удалось сохранить настройки.');
        playEventSound('error');
    } finally {
        saveSettingsBtn.disabled = false;
    }
}

async function notifyRoomActivity(action) {
    if (!roomId || !sessionId) {
        return;
    }

    try {
        await apiFetch(`/api/chat/room/${encodeURIComponent(roomId)}/activity`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId, action })
        });
    } catch (error) {
        console.error('Room activity notification failed', error);
    }
}

async function leaveRoom(options = {}) {
    const {
        confirmLeave = true,
        notifyServer = true,
        clearStoredKey = true,
        clearHash = true
    } = options;

    if (confirmLeave && sessionId) {
        const confirmed = window.confirm('Выйти из комнаты и удалить ключ приглашения из этой вкладки?');
        if (!confirmed) {
            return false;
        }
    }

    manualDisconnect = true;
    clearReconnectTimer();

    const currentRoomId = roomId;
    const currentSessionId = sessionId;

    if (notifyServer && currentSessionId) {
        try {
            await apiFetch(`/api/chat/session/${encodeURIComponent(currentSessionId)}`, {
                method: 'DELETE'
            });
        } catch (error) {
            console.error('Session removal failed', error);
        }
    }

    if (stompClient && stompClient.connected) {
        try {
            stompClient.disconnect();
        } catch (error) {
            console.error('WebSocket disconnect failed', error);
        }
    }

    stompClient = null;

    if (clearStoredKey && currentRoomId) {
        clearStoredKeyToken(currentRoomId);
    }

    roomId = null;
    sessionId = null;
    ownAlias = null;
    isRoomAdmin = false;
    encryptionKey = null;
    inviteKeyToken = null;
    lastSendTime = 0;
    pendingMessages.clear();
    pendingOrder.length = 0;

    joinPanel.hidden = false;
    inputArea.hidden = true;
    roomIdInput.value = '';
    setComposerDisabled(true);
    setAliasDisplay('');
    setRoomDisplay('');
    setRoomControlsState(null);
    updateActionButtons();
    if (clearHash) {
        clearInviteHash();
    }
    resetMessages();
    setStatus('Отключено', 'idle');
    return true;
}

async function recoverExpiredSession() {
    const preservedRoomId = roomId;
    await leaveRoom({
        confirmLeave: false,
        notifyServer: false,
        clearStoredKey: false,
        clearHash: false
    });
    roomIdInput.value = preservedRoomId || '';
    setStatus('Нужно войти заново', 'error');
    addSystemMessage('Сессия истекла или была потеряна. Нажмите «Войти» снова.');
    playEventSound('error');
}

function scheduleReconnect(reason, soundEvent = null) {
    if (manualDisconnect || !roomId || !sessionId || reconnectTimerId) {
        return;
    }

    reconnectAttempts += 1;
    const delay = Math.min(RECONNECT_MAX_DELAY_MS, 1000 * (2 ** Math.min(reconnectAttempts - 1, 4)));
    setStatus(`Переподключение через ${Math.ceil(delay / 1000)} сек`, 'pending');
    setComposerDisabled(true);
    addSystemMessage(reason);
    if (soundEvent) {
        playEventSound(soundEvent);
    }

    reconnectTimerId = window.setTimeout(async () => {
        reconnectTimerId = null;
        const valid = await validateCurrentSession();
        if (!valid) {
            await recoverExpiredSession();
            return;
        }
        connectSocket(true);
    }, delay);
}

function handleSocketDrop(reason) {
    if (manualDisconnect) {
        return;
    }

    stompClient = null;
    setComposerDisabled(true);
    scheduleReconnect(reason, 'connection_lost');
}

function subscribeToRoom() {
    stompClient.subscribe(`/topic/room/${roomId}`, async function(message) {
        const payload = JSON.parse(message.body);

        if (payload.type === 'system') {
            addSystemMessage(payload.content);
            if (payload.event) {
                playEventSound(payload.event);
            }
            return;
        }

        if (payload.type === 'error') {
            markOldestPendingMessageFailed(payload.content || 'Сообщение не доставлено.');
            addSystemMessage(payload.content || 'Не удалось выполнить действие в комнате.');
            playEventSound(payload.event || 'error');
            if ((payload.content || '').includes('Invalid session')) {
                await recoverExpiredSession();
            }
            return;
        }

        if (payload.type !== 'message') {
            return;
        }

        const decryptedText = await decryptMessage(encryptionKey, payload.content);
        if (payload.clientMessageId && pendingMessages.has(payload.clientMessageId)) {
            markPendingMessageDelivered(payload.clientMessageId, payload.alias, payload.timestamp, decryptedText);
            return;
        }

        addIncomingMessage(decryptedText, payload.alias, payload.timestamp);
    });
}

function connectSocket(reconnecting = false) {
    if (!roomId || !sessionId) {
        return;
    }

    clearReconnectTimer();
    setComposerDisabled(true);

    const socket = new SockJS('/ws');
    const client = Stomp.over(socket);
    client.debug = null;

    let closeHandled = false;
    const handleClose = (reason) => {
        if (closeHandled) {
            return;
        }
        closeHandled = true;
        handleSocketDrop(reason);
    };

    socket.onclose = () => handleClose('Соединение потеряно. Пытаемся восстановить...');
    socket.onerror = () => handleClose('Ошибка сети. Пытаемся переподключиться...');

    client.connect({}, async function() {
        if (closeHandled || manualDisconnect) {
            return;
        }

        stompClient = client;
        reconnectAttempts = 0;
        setComposerDisabled(false);
        setStatus(`В сети: ${ownAlias}`, 'online');
        subscribeToRoom();
        await loadRoomSettings();
        if (reconnecting) {
            addSystemMessage('Соединение восстановлено.');
        } else {
            addSystemMessage(`Вы вошли как ${ownAlias}. У каждого участника свой анонимный псевдоним.`);
        }
        if (reconnecting) {
            playEventSound('connection_restored');
        }
    }, function(error) {
        console.error('STOMP error', error);
        handleClose('Не удалось подключиться. Повторяем попытку...');
    });
}

async function joinRoom(predefinedRoomId = null) {
    let requestedRoomId = predefinedRoomId ?? normalizeRoomId(roomIdInput.value);

    if (!requestedRoomId) {
        try {
            requestedRoomId = await requestNewRoomId();
        } catch (error) {
            addSystemMessage(error.message || 'Не удалось создать новую комнату.');
            playEventSound('error');
            return;
        }
    }

    if (!isValidRoomId(requestedRoomId)) {
        addSystemMessage('ID комнаты должен быть длиной 12-64 символа и содержать только буквы, цифры, "-" или "_".');
        playEventSound('error');
        return;
    }

    if (sessionId) {
        await leaveRoom({ confirmLeave: false, notifyServer: true });
    }

    manualDisconnect = false;

    roomId = requestedRoomId;
    roomIdInput.value = roomId;
    setRoomDisplay(roomId);
    setAliasDisplay('');
    setRoomControlsState(null);
    updateActionButtons();
    messagesArea.innerHTML = '';
    addSystemMessage(`Подготавливаем комнату "${roomId}"...`);
    setStatus('Подключение...', 'pending');

    const roomKey = await resolveRoomKey(roomId);
    encryptionKey = roomKey.key;
    inviteKeyToken = roomKey.keyToken;

    if (roomKey.source === 'invite') {
        addSystemMessage('Ключ приглашения загружен из URL-фрагмента. Сервер его не видит.');
    } else if (roomKey.source === 'session') {
        addSystemMessage('Ключ восстановлен из этой вкладки браузера.');
    } else {
        addSystemMessage('Новый ключ приглашения создан локально. Для других участников отправляйте полную ссылку.');
    }

    try {
        const response = await apiFetch(`/api/chat/room/${encodeURIComponent(roomId)}/session`, {
            method: 'POST'
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Не удалось создать сессию.');
        }

        const data = await response.json();
        sessionId = data.sessionId;
        setAliasDisplay(data.alias || 'Скрытый псевдоним');
        setRoomControlsState({
            allowOthers: true,
            autoCleanup: false,
            isAdmin: data.isAdmin === true || data.isAdmin === 'true'
        });
        updateActionButtons();

        joinPanel.hidden = true;
        inputArea.hidden = false;
        setComposerDisabled(true);
        manualDisconnect = false;
        connectSocket(false);
        messageInput.focus();
    } catch (error) {
        console.error(error);
        sessionId = null;
        setAliasDisplay('');
        setRoomControlsState(null);
        updateActionButtons();
        setStatus('Ошибка входа', 'error');
        addSystemMessage(error.message || 'Не удалось создать сессию.');
        playEventSound('error');
    }
}

async function createRoomAndJoin() {
    try {
        setStatus('Создаем комнату...', 'pending');
        const newRoomId = await requestNewRoomId();
        roomIdInput.value = newRoomId;
        await joinRoom(newRoomId);
    } catch (error) {
        console.error(error);
        setStatus('Ошибка создания', 'error');
        addSystemMessage(error.message || 'Не удалось создать комнату.');
        playEventSound('error');
    }
}

async function sendMessage() {
    if (!stompClient || !stompClient.connected) {
        addSystemMessage('Нет подключения. Сначала войдите в комнату.');
        playEventSound('error');
        return;
    }

    const text = messageInput.value.trim();
    if (!text) {
        return;
    }

    if (text.length > MAX_PLAINTEXT_LENGTH) {
        addSystemMessage(`Сообщение слишком длинное. Максимум: ${MAX_PLAINTEXT_LENGTH} символов.`);
        playEventSound('error');
        return;
    }

    const now = Date.now();
    if (now - lastSendTime < SEND_COOLDOWN_MS) {
        addSystemMessage('Подождите немного перед отправкой следующего сообщения.');
        playEventSound('error');
        return;
    }
    lastSendTime = now;

    const clientMessageId = base64UrlEncode(crypto.getRandomValues(new Uint8Array(18)));
    const encrypted = await encryptMessage(encryptionKey, text);

    addPendingMessage(clientMessageId, text);

    stompClient.send(`/app/chat/${roomId}`, {}, JSON.stringify({
        senderId: sessionId,
        content: encrypted,
        timestamp: new Date().toISOString(),
        clientMessageId
    }));

    messageInput.value = '';
    messageInput.focus();
}

async function copyRoomLink() {
    if (!roomId || !inviteKeyToken) {
        return;
    }

    try {
        await navigator.clipboard.writeText(buildInviteLink(roomId, inviteKeyToken));
        addSystemMessage('Ссылка-приглашение скопирована. Отправляйте ее целиком, чтобы получатель получил и ключ.');
        await notifyRoomActivity('copied_invite_link');
    } catch (error) {
        console.error('Clipboard write failed', error);
        addSystemMessage('Не удалось записать ссылку в буфер обмена. Скопируйте адрес страницы вручную.');
        playEventSound('error');
    }
}

async function shareRoomLink() {
    if (!roomId || !inviteKeyToken || typeof navigator.share !== 'function') {
        return;
    }

    try {
        await navigator.share({
            title: 'Anonix',
            text: 'Подключайтесь к приватной комнате в Anonix',
            url: buildInviteLink(roomId, inviteKeyToken)
        });
        addSystemMessage('Ссылка-приглашение отправлена через системное меню.');
        await notifyRoomActivity('shared_invite_link');
    } catch (error) {
        if (error.name !== 'AbortError') {
            console.error('Share failed', error);
            addSystemMessage('Не удалось открыть меню поделиться. Попробуйте скопировать ссылку.');
            playEventSound('error');
        }
    }
}

function autoJoinFromInvite() {
    const invite = getInviteFromHash();
    if (!invite.roomId) {
        return;
    }

    roomIdInput.value = invite.roomId;
    joinRoom(invite.roomId);
}

joinBtn.addEventListener('click', () => joinRoom());
createRoomBtn.addEventListener('click', createRoomAndJoin);
sendBtn.addEventListener('click', sendMessage);
saveSettingsBtn.addEventListener('click', saveRoomSettings);
roomIdInput.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        joinRoom();
    }
});
messageInput.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        sendMessage();
    }
});
copyRoomBtn.addEventListener('click', copyRoomLink);
shareRoomBtn.addEventListener('click', shareRoomLink);
logoutBtn.addEventListener('click', () => {
    leaveRoom({ confirmLeave: true, notifyServer: true });
});
window.addEventListener('pointerdown', () => {
    void primeAudioContext();
}, { passive: true });
window.addEventListener('keydown', () => {
    void primeAudioContext();
});

window.addEventListener('beforeunload', () => {
    if (roomId) {
        clearStoredKeyToken(roomId);
    }
});

window.addEventListener('offline', () => {
    if (roomId && sessionId) {
        setStatus('Нет сети', 'error');
    }
});

window.addEventListener('online', () => {
    if (!manualDisconnect && roomId && sessionId && !stompClient) {
        scheduleReconnect('Интернет снова доступен. Пытаемся переподключиться...');
    }
});

setStatus('Отключено', 'idle');
setComposerDisabled(true);
setRoomDisplay('');
setAliasDisplay('');
setRoomControlsState(null);
updateActionButtons();
autoJoinFromInvite();
