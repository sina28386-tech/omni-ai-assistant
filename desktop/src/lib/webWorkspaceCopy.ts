const en = {
  title: "Creative websites",
  intro: "Open your creative tools in your browser and work directly in your own account.",
  section: "Choose a website",
  open: "Open website",
  newTab: "opens in a new browser tab",
  noteTitle: "How this works",
  note: "These links open external websites. Sign in and manage your work and credits on each service. Omni does not connect your accounts or generate content here.",
  billingTitle: "Plans and API billing",
  billing: "Higgsfield and Runway charge for developer API usage separately from their website plans. Google Flow uses its own credits; Google developer API billing is separate. Check each provider for current terms and prices.",
  higgsfield: "Create images and video on Higgsfield.",
  runway: "Create and edit video and other media on Runway.",
  flow: "Create video with Google Flow.",
};

const fa: typeof en = {
  title: "ابزارهای خلاقیت در وب",
  intro: "ابزارهای خلاقیت را در مرورگر باز کنید و با حساب خودتان در هر سرویس کار کنید.",
  section: "انتخاب وب‌سایت",
  open: "باز کردن وب‌سایت",
  newTab: "در برگه جدید مرورگر باز می‌شود",
  noteTitle: "روش استفاده",
  note: "این لینک‌ها وب‌سایت‌های بیرونی را باز می‌کنند. ورود، مدیریت کارها و اعتبار در خود هر سرویس انجام می‌شود. اُمنی در این بخش حساب‌ها را متصل نمی‌کند و محتوایی تولید نمی‌کند.",
  billingTitle: "اشتراک و هزینه API",
  billing: "هزینه API توسعه‌دهندگان Higgsfield و Runway از اشتراک وب‌سایت آن‌ها جداست. Google Flow اعتبار مخصوص خود را دارد و صورتحساب API گوگل جداست. شرایط و قیمت روز را در هر سرویس بررسی کنید.",
  higgsfield: "ساخت تصویر و ویدیو در Higgsfield.",
  runway: "ساخت و ویرایش ویدیو و محتوای رسانه‌ای در Runway.",
  flow: "ساخت ویدیو با Google Flow.",
};

export function webWorkspaceText(language: "fa" | "en") {
  return language === "fa" ? fa : en;
}
