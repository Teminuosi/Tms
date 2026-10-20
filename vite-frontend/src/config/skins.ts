// P2 多主题(皮肤):6 套渐变皮肤,复用 s-ui 已拍板的那套。默认第一套 aurora。
// 每套 = 一个 HeroUI 主题(在 tailwind.config.js 里定义)+ 一层固定渐变背景(themes.css)。
export interface Skin {
  id: string;
  name: string;
  base: "light" | "dark";
  swatch: string; // 选择器里的小色块 = 该皮肤渐变的缩影
}

export const SKINS: Skin[] = [
  // 跟博客「三月空间」(3yuedaohang.com)同一套:深空黑底 + 紫蓝渐变。颜色在 themes.css
  { id: "sanyue", name: "三月空间", base: "dark", swatch: "linear-gradient(135deg,#8a6bff,#5b8cff)" },
  { id: "aurora", name: "极光", base: "dark", swatch: "linear-gradient(135deg,#302b63,#a855f7)" },
  { id: "mesh", name: "流光", base: "light", swatch: "linear-gradient(135deg,#c2e9fb,#fbc2eb)" },
  { id: "deepsea", name: "深海", base: "dark", swatch: "linear-gradient(135deg,#0e3a5f,#38bdf8)" },
  { id: "sunrise", name: "晨曦", base: "light", swatch: "linear-gradient(135deg,#ffaa96,#b4a0ff)" },
  { id: "cyber", name: "赛博", base: "dark", swatch: "linear-gradient(135deg,#ec4899,#22d3ee)" },
  { id: "mint", name: "薄荷", base: "light", swatch: "linear-gradient(135deg,#a7f3d0,#bae6fd)" },
  { id: "midnight", name: "午夜", base: "dark", swatch: "linear-gradient(135deg,#0b0e14,#1f2937)" },
  { id: "clean", name: "简白", base: "light", swatch: "linear-gradient(135deg,#f8fafc,#e5e7eb)" },
  { id: "forest", name: "森林", base: "dark", swatch: "linear-gradient(135deg,#065f46,#10b981)" },
  { id: "lava", name: "熔岩", base: "dark", swatch: "linear-gradient(135deg,#b91c1c,#f97316)" },
  { id: "sakura", name: "樱花", base: "light", swatch: "linear-gradient(135deg,#fbcfe8,#e9d5ff)" },
  { id: "grape", name: "葡萄", base: "dark", swatch: "linear-gradient(135deg,#6d28d9,#db2777)" },
  { id: "sand", name: "暖沙", base: "light", swatch: "linear-gradient(135deg,#fde8c8,#f5d0a9)" },
  { id: "steel", name: "钢蓝", base: "dark", swatch: "linear-gradient(135deg,#1e293b,#475569)" },
];

export const DEFAULT_SKIN = "sanyue";

export function savedSkin(): Skin {
  if (typeof localStorage === "undefined") return SKINS[0];
  let id = localStorage.getItem("skin");
  // 默认皮肤从 aurora 换成 sanyue 时,老用户存的 "aurora" 多半只是当年的默认值、不是自己选的
  // (applySkin 每次都会写回 localStorage)。只换这一次;之后再手动选回极光就一直是极光。
  if (localStorage.getItem("skinDefaultV2") !== "1") {
    if (id === null || id === "aurora") id = DEFAULT_SKIN;
    localStorage.setItem("skinDefaultV2", "1");
  }
  return SKINS.find((s) => s.id === id) ?? SKINS[0];
}

// 把皮肤落到 <html>:主题类(驱动 HeroUI 主题)+ dark 类(驱动 tailwind 的 dark: 工具类)。
export function applySkin(id: string): void {
  const skin = SKINS.find((s) => s.id === id) ?? SKINS[0];
  const html = document.documentElement;
  SKINS.forEach((s) => html.classList.remove(s.id));
  html.classList.add(skin.id);
  if (skin.base === "dark") html.classList.add("dark");
  else html.classList.remove("dark");
  html.style.colorScheme = skin.base;
  localStorage.setItem("skin", skin.id);
}
