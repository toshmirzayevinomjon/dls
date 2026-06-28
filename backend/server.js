require('dotenv').config();
const express = require('express');
const fs = require('fs');
const path = require('path');
const { Readable } = require('stream');

const app = express();
const PORT = process.env.PORT || 3000;
const GROQ_API_KEY = process.env.GROQ_API_KEY;
const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions';
const GROQ_MODEL = 'llama-3.1-8b-instant';
const GOAL_THRESHOLD = 5; // 5 golga yetganda Tiki-taka rejimiga o'tadi

if (!GROQ_API_KEY) {
    console.error('XATO: GROQ_API_KEY topilmadi (.env faylini tekshiring).');
    process.exit(1);
}

app.use(express.json());
app.use('/templates', express.static(path.join(__dirname, 'templates')));
// Yuklab olish sayti (landing page) va APK fayli shu papkadan beriladi
app.use(express.static(path.join(__dirname, 'public')));

// Railway deploy qilinganda RAILWAY_PUBLIC_DOMAIN avtomatik beriladi.
// Lokalda BASE_URL ni .env orqali bering yoki localhost ishlatiladi.
const RAILWAY_DOMAIN = process.env.RAILWAY_PUBLIC_DOMAIN;
const BASE_URL = process.env.BASE_URL
    || (RAILWAY_DOMAIN ? `https://${RAILWAY_DOMAIN}` : `http://localhost:${PORT}`);
const STATS_FILE = path.join(__dirname, 'stats.json');

// ====================== SOZLAMALAR ======================
const SETTINGS = {
    version: 1,
    processWidth: 640,
    processHeight: 360,
    matchThreshold: 0.62,
    loopIntervalMs: 130,
    attackRight: true,
    joystickRadius: 0.10,
    gestureDurationMs: 90,
    // #10 ROI: to'pni faqat shu hududda qidirish (normallashtirilgan). null bo'lsa butun ekran.
    roi: { x: 0.10, y: 0.12, w: 0.80, h: 0.76 },
    // #3 multi-scale: to'pni shu o'lchamlarda qidiramiz
    scales: [0.8, 1.0, 1.2],
    // Avtonom navigatsiya
    packageNames: ["com.firsttouchgames.dls7", "com.firsttouchgames.dls8", "com.firsttouchgames.dls"],
    navThreshold: 0.78,
    relaunchAfterMs: 12000,
    maxBackTries: 4,
    heatThrottleC: 42.0,
    buttons: [
        { name: "A_shoot", x: 0.90, y: 0.78 },
        { name: "B_pass",  x: 0.82, y: 0.88 },
        { name: "C_thru",  x: 0.74, y: 0.70 },
        { name: "joystick",x: 0.14, y: 0.80 }
    ]
};

// ====================== SHABLONLAR ======================
const TEMPLATES = [
    { name: "ball",              url: `${BASE_URL}/templates/ball.png` },
    { name: "control_indicator", url: `${BASE_URL}/templates/control_indicator.png` }, // #2 boshqaruvdagi o'yinchi belgisi
    { name: "goal_banner",       url: `${BASE_URL}/templates/goal_banner.png` },        // bizning gol
    { name: "concede_banner",    url: `${BASE_URL}/templates/concede_banner.png` },     // #6 raqib goli
    { name: "play_button",       url: `${BASE_URL}/templates/play_button.png` },        // #7 menyu: PLAY
    { name: "continue_button",   url: `${BASE_URL}/templates/continue_button.png` },    // match tugadi: Continue
    { name: "ok_button",         url: `${BASE_URL}/templates/ok_button.png` }           // popup: OK/Yopish
];

// ====================== #9 TAKTIKA PROFILLARI ======================
const PROFILES = {
    balanced:  { label: "Balansli",  desc: "Muvozanatli hujum va himoya." },
    attacker:  { label: "Hujumchi",  desc: "Tavakkalchi, doim oldinga, ko'p zarba." },
    defender:  { label: "Himoyachi", desc: "Ehtiyotkor, ko'p pas, kam tavakkal." }
};

// ====================== #8 STATISTIKA ======================
function loadStats() {
    try { return JSON.parse(fs.readFileSync(STATS_FILE, 'utf8')); }
    catch (e) { return {}; }
}
function saveStats(s) {
    try { fs.writeFileSync(STATS_FILE, JSON.stringify(s, null, 2)); }
    catch (e) { console.error('Stats yozish xato:', e.message); }
}
function getStat(matchId) {
    const all = loadStats();
    if (!all[matchId]) {
        all[matchId] = { shots: 0, passes: 0, decisions: 0, myGoals: 0, oppGoals: 0, startedAt: Date.now() };
        saveStats(all);
    }
    return { all, stat: all[matchId] };
}

// ====================== HISOB (in-memory) ======================
const scoreboard = {};
function getScore(matchId) {
    if (!scoreboard[matchId]) scoreboard[matchId] = { myGoals: 0, oppGoals: 0, updatedAt: Date.now() };
    return scoreboard[matchId];
}
function resolveMode(myGoals) {
    return myGoals >= GOAL_THRESHOLD ? 'TIKI_TAKA' : 'AGGRESSIVE';
}

// ====================== GROQ PROMPT ======================
function buildPrompt(s, mode, score, profile) {
    const ball = s.ball || { x: 0.5, y: 0.5 };
    const opp = s.oppGoal || { x: 0.95, y: 0.5 };
    const own = s.ownGoal || { x: 0.05, y: 0.5 };
    const enemy = s.nearestOpponent || null;
    const hasBall = s.hasBall === true;
    const attackRight = s.attackRight !== false;

    const modeRules = mode === 'AGGRESSIVE'
        ? `REJIM: AGRESSIV HUJUM (hisob ${score.myGoals}-${score.oppGoals}). Maqsad: tez va ko'p gol urish. To'p bizda va darvoza oldida -> DOIM "ZARBA". Uzoqda -> "SURISH"/"PAS". "HIMOYA" faqat o'z darvozamizga juda yaqin bo'lsa.`
        : `REJIM: TIKI-TAKA (hisob ${score.myGoals}-${score.oppGoals}). Maqsad: golni saqlash, to'pni yo'qotmaslik. To'p bizda -> deyarli DOIM xavfsiz "PAS". "ZARBA" faqat 100% imkoniyatda. To'p bizda emas -> "HIMOYA".`;

    const profRules = profile === 'attacker'
        ? 'PROFIL: Hujumchi -> tavakkalni afzal ko\'r, ko\'proq ZARBA.'
        : profile === 'defender'
        ? 'PROFIL: Himoyachi -> ehtiyotkor bo\'l, ko\'proq PAS, kam tavakkal.'
        : 'PROFIL: Balansli -> vaziyatga qarab muvozanatli qaror.';

    return `Sen Dream League Soccer offline o'yini uchun strategik miyasan.
Koordinatalar 0.0..1.0 (x: chap=0 o'ng=1, y: yuqori=0 past=1).

${modeRules}
${profRules}

VAZIYAT:
- To'p: x=${ball.x}, y=${ball.y}
- To'p bizdami: ${hasBall ? 'HA' : "YO'Q"}
- Raqib darvozasi: x=${opp.x}, y=${opp.y}
- O'z darvozamiz: x=${own.x}, y=${own.y}
- Eng yaqin raqib: ${enemy ? `x=${enemy.x}, y=${enemy.y}` : "noma'lum"}
- Hujum yo'nalishi: ${attackRight ? "o'ngga" : "chapga"}

FAQAT shu JSON, boshqa matn yo'q:
{"action":"PAS|ZARBA|HIMOYA|SURISH","direction":"left|right|up|down|none","confidence":0.0,"reason":"qisqa"}`;
}

function safeParse(raw, mode) {
    try {
        const m = raw.match(/\{[\s\S]*\}/);
        const o = JSON.parse(m ? m[0] : raw);
        const allowed = ['PAS', 'ZARBA', 'HIMOYA', 'SURISH'];
        if (!allowed.includes(o.action)) o.action = 'SURISH';
        if (!['left', 'right', 'up', 'down', 'none'].includes(o.direction)) o.direction = 'none';
        if (typeof o.confidence !== 'number') o.confidence = 0.5;
        if (typeof o.reason !== 'string') o.reason = '';
        if (mode === 'AGGRESSIVE' && o.action === 'HIMOYA' && o.confidence < 0.8) o.action = 'SURISH';
        if (mode === 'TIKI_TAKA' && o.action === 'ZARBA' && o.confidence < 0.85) o.action = 'PAS';
        return o;
    } catch (e) {
        return { action: mode === 'AGGRESSIVE' ? 'SURISH' : 'PAS', direction: 'none', confidence: 0.3, reason: 'parse_fallback' };
    }
}

// ====================== ENDPOINTLAR ======================
app.get('/api/bot/settings', (req, res) => res.json(SETTINGS));
app.get('/api/bot/templates', (req, res) => res.json({ templates: TEMPLATES }));
app.get('/api/bot/profiles', (req, res) => res.json({ profiles: PROFILES, active: 'balanced' }));

app.post('/api/bot/calibrate', (req, res) => {
    if (Array.isArray(req.body.buttons)) {
        SETTINGS.buttons = req.body.buttons;
        SETTINGS.version += 1;
        console.log('[CALIBRATE]', JSON.stringify(SETTINGS.buttons));
        return res.json({ ok: true, version: SETTINGS.version, buttons: SETTINGS.buttons });
    }
    res.status(400).json({ ok: false, error: 'buttons massiv bo\'lishi kerak' });
});

app.post('/api/bot/score', (req, res) => {
    const matchId = (req.body && req.body.matchId) || 'default';
    const sc = getScore(matchId);
    const { all, stat } = getStat(matchId);

    if (req.body.event === 'MY_GOAL') { sc.myGoals += 1; stat.myGoals += 1; }
    else if (req.body.event === 'OPP_GOAL') { sc.oppGoals += 1; stat.oppGoals += 1; }
    else if (req.body.event === 'RESET') { sc.myGoals = 0; sc.oppGoals = 0; }
    if (typeof req.body.myGoals === 'number') sc.myGoals = req.body.myGoals;
    if (typeof req.body.oppGoals === 'number') sc.oppGoals = req.body.oppGoals;

    sc.updatedAt = Date.now();
    saveStats(all);
    const mode = resolveMode(sc.myGoals);
    console.log(`[SCORE] ${matchId}: ${sc.myGoals}-${sc.oppGoals} -> ${mode}`);
    res.json({ myGoals: sc.myGoals, oppGoals: sc.oppGoals, mode, threshold: GOAL_THRESHOLD });
});

// #8 statistikani oshirish (shot/pass/decision)
app.post('/api/bot/stats', (req, res) => {
    const matchId = (req.body && req.body.matchId) || 'default';
    const { all, stat } = getStat(matchId);
    if (req.body.action === 'ZARBA') stat.shots += 1;
    if (req.body.action === 'PAS') stat.passes += 1;
    stat.decisions += 1;
    saveStats(all);
    res.json({ ok: true });
});

app.get('/api/bot/stats/:matchId', (req, res) => {
    const all = loadStats();
    res.json(all[req.params.matchId] || { error: 'topilmadi' });
});

// #50 Ilovadan crash/xato loglarini qabul qilish
app.post('/api/bot/log', (req, res) => {
    const { matchId = 'default', level = 'INFO', message = '' } = req.body || {};
    console.log(`[BOT-LOG][${level}][${matchId}] ${message}`);
    res.json({ ok: true });
});

app.post('/api/bot/decision', async (req, res) => {
    const state = req.body || {};
    const matchId = state.matchId || 'default';
    const profile = state.profile || 'balanced';
    const sc = getScore(matchId);
    if (typeof state.myGoals === 'number') sc.myGoals = state.myGoals;
    if (typeof state.oppGoals === 'number') sc.oppGoals = state.oppGoals;
    const mode = resolveMode(sc.myGoals);

    try {
        const r = await fetch(GROQ_URL, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${GROQ_API_KEY}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model: GROQ_MODEL,
                temperature: 0.2,
                max_tokens: 120,
                response_format: { type: 'json_object' },
                messages: [
                    { role: 'system', content: 'Sen futbol uchun tezkor qaror AI san. Faqat JSON qaytar.' },
                    { role: 'user', content: buildPrompt(state, mode, sc, profile) }
                ]
            })
        });
        if (!r.ok) {
            console.error('Groq xato:', r.status, await r.text());
            return res.json({ action: mode === 'AGGRESSIVE' ? 'SURISH' : 'PAS', direction: 'none', confidence: 0.2, reason: 'groq_unavailable', mode });
        }
        const data = await r.json();
        const decision = safeParse(data.choices?.[0]?.message?.content || '', mode);
        decision.mode = mode;
        decision.myGoals = sc.myGoals;
        return res.json(decision);
    } catch (e) {
        console.error('Server xatosi:', e.message);
        return res.json({ action: mode === 'AGGRESSIVE' ? 'SURISH' : 'PAS', direction: 'none', confidence: 0.1, reason: 'server_error', mode });
    }
});

// APK'ni GitHub Release'dan olib, saytdan TO'G'RIDAN-TO'G'RI yuklab beradi.
// Foydalanuvchi faqat saytni ko'radi, GitHub'ga o'tmaydi.
const APK_RELEASE_URL = 'https://github.com/toshmirzayevinomjon/dls/releases/latest/download/dls-bot.apk';

app.get('/download/apk', async (req, res) => {
    try {
        const r = await fetch(APK_RELEASE_URL, { redirect: 'follow' });
        if (!r.ok || !r.body) {
            return res.status(502).send('APK hozircha tayyor emas. Birozdan so\'ng urinib ko\'ring.');
        }
        res.setHeader('Content-Type', 'application/vnd.android.package-archive');
        res.setHeader('Content-Disposition', 'attachment; filename="dls-bot.apk"');
        const len = r.headers.get('content-length');
        if (len) res.setHeader('Content-Length', len);
        Readable.fromWeb(r.body).pipe(res);
    } catch (e) {
        console.error('APK stream xato:', e.message);
        res.status(502).send('APK yuklab bo\'lmadi: ' + e.message);
    }
});

app.get('/health', (req, res) => res.json({ ok: true, model: GROQ_MODEL, threshold: GOAL_THRESHOLD }));

app.listen(PORT, '0.0.0.0', () => {
    console.log(`DLS server :${PORT} (Groq: ${GROQ_MODEL}, BASE_URL: ${BASE_URL})`);
});
