> Historical design document: this describes an earlier prototype and may not reflect the current implementation. See the repository README for current status.

# قرارداد عامل‌ها — AGENT_CONTRACTS

وضعیت: **قرارداد طراحی؛ runtime پیاده‌سازی نشده است.**

این سند شکل داده و مرزهای امنیتی «گروه‌عامل‌ها» را تعریف می‌کند تا رابط کاربری و سرویس‌های آینده بر یک قرارداد ثابت ساخته شوند. در نسخهٔ فعلی Omni Desktop هیچ عامل، ابزار اجرایی، حلقهٔ استدلال یا دسترسی فایل وجود ندارد. صفحهٔ Agents فقط وضعیت «در دسترس نیست» را نشان می‌دهد و هیچ اجرایی را شبیه‌سازی نمی‌کند.

## معماری پایه

`Behavior → State → UI`

- **Behavior** تصمیم می‌گیرد چه چیزی مجاز است و چه چیزی نه.
- **State** تنها وضعیت قابل‌مشاهده و قابل‌بازپخش را نگه می‌دارد.
- **UI** وضعیت را نمایش می‌دهد و هرگز خودش ابزار اجرا نمی‌کند.

## AgentManifest

```json
{
  "id": "uuid",
  "name": "string",
  "version": "semver",
  "description": "string",
  "instructions": "string",
  "model": { "providerId": "uuid", "model": "string" },
  "tools": ["tool.name"],
  "limits": {
    "maxSteps": 12,
    "maxRuntimeMs": 120000,
    "maxOutputBytes": 262144
  },
  "createdAt": 0,
  "updatedAt": 0
}
```

قواعد:

- `instructions` و توضیحات ابزار، متن غیرقابل‌اعتماد نیستند؛ سیاست‌های runtime همیشه بر آن‌ها اولویت دارند.
- `model` فقط به یک سرویس‌دهندهٔ ثبت‌شده اشاره می‌کند؛ کلید سرویس‌دهنده هرگز وارد manifest نمی‌شود.
- manifest ناشناخته یا دارای فیلد اضافی باید رد شود، نه اینکه با حدس تفسیر شود.

## ToolContract

```json
{
  "name": "files.read",
  "description": "Read a UTF-8 file inside an approved workspace.",
  "inputSchema": { "type": "object", "additionalProperties": false },
  "sideEffect": "read",
  "requiresConfirmation": false,
  "timeoutMs": 10000,
  "availability": "unavailable"
}
```

`sideEffect` یکی از `read | write | execute | network` است. هر `write`، `execute` و `network` پیش از اجرا نیازمند تأیید صریح کاربر است. `availability` در V1 برای همهٔ ابزارها `unavailable` است.

## مجوزها و محدودیت‌ها

- پیش‌فرض: **deny**.
- دسترسی فایل فقط به ریشهٔ workspace تأییدشده و با canonical path؛ `..`، symlink و path absolute خارج از ریشه رد می‌شوند.
- شبکه به‌صورت پیش‌فرض بسته است؛ هر دامنه باید در allowlist صریح باشد.
- اجرای فرمان فقط در sandbox با timeout، محدودیت خروجی و بدون inherit کردن کلیدهای محیطی.
- هیچ tool نباید کلید سرویس‌دهنده، `secrets.json` یا محتوای حافظهٔ بدون انتخاب کاربر را بخواند.
- نتیجهٔ هر ابزار باید قابل‌حسابرسی باشد: نام، ورودی پاک‌سازی‌شده، زمان، خروجی، خطا و تأیید کاربر.

## چرخهٔ اجرا

`queued → planning → waiting_approval → executing → completed | failed | cancelled`

- توقف کاربر باید در هر مرحله ممکن باشد و وضعیت `cancelled` را ماندگار کند.
- تلاش مجدد یک run جدید می‌سازد؛ run قبلی حذف یا بازنویسی نمی‌شود.
- خطاهای ابزار باید به‌صورت داده‌ی ساختاریافته برگردند، نه متن آزاد.

## رویدادهای پیشنهادی (آینده)

```text
event: run.started     data: { runId, agentId }
event: run.step        data: { runId, step, summary }
event: run.approval    data: { runId, tool, inputPreview }
event: run.delta       data: { runId, text }
event: run.finished    data: { runId, status, usage }
```

این رویدادها هنوز وجود ندارند. هرگونه پیاده‌سازی باید نسخه‌دار، قابل‌قطع و قابل‌بازپخش باشد.

## وضعیت فعلی پیاده‌سازی

- [x] قرارداد داده و مرزهای امنیتی مستند شده‌اند.
- [x] UI وضعیت صادقانهٔ عدم دسترسی را نشان می‌دهد.
- [ ] runtime، ابزار، تأییدیه، sandbox و audit log وجود ندارند.
- [ ] هیچ ادعای اجرای واقعی در محصول مجاز نیست تا زمانی که موارد بالا پیاده‌سازی و آزمون شوند.
