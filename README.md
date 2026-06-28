# DLS Bot — Dream League Soccer offline AI bot

Faqat **offline (botga qarshi)** rejimda DLS o'ynaydigan AI bot. Ekranni OpenCV bilan
tahlil qiladi, strategik qarorni Groq (Llama 3) backend orqali oladi va
AccessibilityService orqali "inson kabi" barmoq harakatlari yuboradi.

> ⚠️ Faqat offline rejim uchun. Online/live (real odamlarga qarshi) ishlatish
> insofsizlik va ToS buzilishi — qo'llab-quvvatlanmaydi.

## Tuzilma

```
dls-bot/
├── backend/        Node.js (Express) + Groq API
│   ├── server.js
│   ├── package.json
│   ├── .env.example   (.env ni shundan yarating — kalit shu yerda)
│   └── templates/     (ball.png, control_indicator.png, goal_banner.png, concede_banner.png, play_button.png)
└── android/        Android Studio (Java) ilova
    ├── settings.gradle
    └── app/...
```

## Backend ishga tushirish

```bash
cd backend
cp .env.example .env      # .env ichiga Groq kalitini yozing
npm install
npm start
```

`backend/templates/` ichiga shablon rasmlarini (640x360 masshtabda kesilgan) qo'ying.

## Android

1. OpenCV 4.12.0 Android SDK'ni `android/opencv` papkasiga import qiling
   (File > New > Import Module).
2. `app/src/main/java/.../net/ApiClient.java` va `backend/.env` dagi IP manzilni
   bir xil qiling (telefon va PC bitta Wi-Fi'da).
3. Ilovani o'rnating, ruxsatlarni bering (Overlay > Accessibility > Ekran olish).
4. O'yinni oching, suzuvchi panelda **CALIB** bilan tugmalarni kalibrlang, **START** bosing.

## Funksiyalar

- OpenCV multi-scale + ROI matchTemplate (to'p/tugma aniqlash)
- Inson simulyatsiyasi (random delay, koordinata jitter, mikro-svayp)
- Gol hisobiga qarab rejim (AGGRESSIVE / TIKI-TAKA)
- Bizning va raqib golini aniqlash
- O'yin holati (menyu/o'yin) aniqlash, PLAY avtomatik bosish
- Kalibrlash ekrani, vizual debug overlay
- Taktika profillari (Balansli/Hujumchi/Himoyachi)
- Backend statistikasi (zarba/pas/gol)

## Xavfsizlik

- `.env` (Groq kaliti) `.gitignore` orqali himoyalangan — hech qachon commit qilinmaydi.
