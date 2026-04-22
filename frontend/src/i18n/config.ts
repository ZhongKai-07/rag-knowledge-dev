export const SUPPORTED_LOCALES = ["zh-CN", "zh-HK", "en"] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "zh-CN";
export const FALLBACK_LOCALE: Locale = "zh-CN";

export const NAMESPACES = ["common", "chat", "admin", "access", "errors"] as const;
export type Namespace = (typeof NAMESPACES)[number];

export const STORAGE_KEY = "htkb.locale";

export const LOCALE_LABELS: Record<Locale, string> = {
  "zh-CN": "简体中文",
  "zh-HK": "繁體中文（香港）",
  en: "English",
};
