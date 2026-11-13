> Historical design document: this describes an earlier prototype and may not reflect the current implementation. See the repository README for current status.

# فضای کدنویسی — CODING_WORKSPACE

وضعیت: **در دسترس نیست.**

Omni Desktop در V1 هیچ ادیتور کد، ترمینال، اجرای فرمان، Git یا ابزار تغییر فایل ندارد. صفحهٔ Code فقط قرارداد و مرزهای نسخهٔ آینده را نشان می‌دهد. هیچ فایل یا فرمانی در پس‌زمینه اجرا نمی‌شود.

## قابلیت موردنظر

یک workspace محلی که کاربر صریحاً انتخاب می‌کند و Omni می‌تواند در آن:

1. درخت فایل را بخواند.
2. تغییر را پیشنهاد دهد و diff را نشان دهد.
3. پس از تأیید، patch را اعمال کند.
4. فرمان آزمون را در sandbox اجرا کند.
5. نتیجهٔ آزمون و خروجی را ثبت کند.
6. تغییر را به‌صورت اتمیک برگرداند.

## قرارداد داده

```ts
interface WorkspaceSession {
  id: string;
  root: string;              // canonical, user-approved
  createdAt: number;
  readOnly: boolean;
  allowedCommands: string[]; // exact argv prefixes, not shell strings
}

interface FileChange {
  path: string;              // relative to root
  beforeHash: string | null;
  afterHash: string | null;
  patch: string;
  status: "proposed" | "approved" | "applied" | "rejected";
}

interface CommandRun {
  id: string;
  argv: string[];
  cwd: string;
  timeoutMs: number;
  status: "queued" | "running" | "passed" | "failed" | "cancelled";
  exitCode?: number;
  outputPreview: string;
}
```

## مرزهای امنیتی

- ریشهٔ workspace باید با انتخاب صریح کاربر تعیین شود؛ مسیر پیش‌فرض وجود ندارد.
- هر خواندن/نوشتن با path canonical و بررسی prefix ریشه انجام می‌شود.
- `shell: true` ممنوع است؛ فرمان‌ها فقط به‌صورت `argv` اجرا می‌شوند.
- محیط فرمان باید پاک‌سازی شود؛ کلیدهای provider و متغیرهای حساس حذف می‌شوند.
- خروجی فرمان محدود به اندازه و زمان مشخص است.
- هر تغییر فایل پیش از اعمال، diff و هش قبل/بعد را نشان می‌دهد.
- rollback فقط برای تغییرات همین session مجاز است.

## تجربهٔ کاربری

- **Simple**: «انتخاب پوشه» و سپس یک توضیح روشن که این قابلیت هنوز فعال نیست.
- **Studio**: قرارداد، وضعیت runtime و فهرست قابلیت‌های غایب.
- هیچ دکمه‌ای نباید اجرای واقعی را القا کند؛ برچسب «در دسترس نیست» باید در همان دید باشد.

## وضعیت فعلی

- [x] قرارداد و مرزهای امنیتی نوشته شده‌اند.
- [x] صفحهٔ Code وضعیت unavailable را نشان می‌دهد.
- [ ] file tree، diff، patch، terminal، sandbox و Git وجود ندارند.
- [ ] هیچ آزمون end-to-end برای کدنویسی وجود ندارد، چون قابلیتی وجود ندارد.
