import i18n from "i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { initReactI18next } from "react-i18next";

import {
  DEFAULT_LOCALE,
  FALLBACK_LOCALE,
  NAMESPACES,
  STORAGE_KEY,
  SUPPORTED_LOCALES,
  type Locale,
} from "./config";

async function loadBundle(locale: Locale) {
  for (const ns of NAMESPACES) {
    try {
      const mod = await import(`../locales/${locale}/${ns}.json`);
      i18n.addResourceBundle(locale, ns, mod.default ?? mod, true, true);
    } catch {
      // silent — fallback to zh-CN
    }
  }
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: FALLBACK_LOCALE,
    supportedLngs: SUPPORTED_LOCALES as unknown as string[],
    ns: NAMESPACES as unknown as string[],
    defaultNS: "common",
    detection: {
      order: ["localStorage", "navigator"],
      lookupLocalStorage: STORAGE_KEY,
      caches: ["localStorage"],
    },
    interpolation: { escapeValue: false },
    returnEmptyString: false,
  });

i18n.on("initialized", async () => {
  const current = (i18n.resolvedLanguage ?? DEFAULT_LOCALE) as Locale;
  document.documentElement.lang = current;
  await loadBundle(current);
});

i18n.on("languageChanged", async (lng) => {
  document.documentElement.lang = lng;
  await loadBundle(lng as Locale);
});

export default i18n;
