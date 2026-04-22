import * as React from "react";
import { useTranslation } from "react-i18next";
import { Globe } from "lucide-react";
import { cn } from "@/lib/utils";
import { LOCALE_LABELS, SUPPORTED_LOCALES, type Locale } from "@/i18n/config";

export function LanguageSwitcher() {
  const { i18n } = useTranslation("common");
  const [open, setOpen] = React.useState(false);
  const current = (i18n.resolvedLanguage ?? "zh-CN") as Locale;

  const change = async (l: Locale) => {
    await i18n.changeLanguage(l);
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-[8px] border border-vio-line bg-white",
          "px-2.5 py-1.5 font-body text-[11px] text-vio-ink/70",
          "transition-colors hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent",
        )}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Language"
      >
        <Globe className="h-3 w-3" />
        <span>{LOCALE_LABELS[current]}</span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute bottom-full left-0 mb-1 w-40 rounded-[10px] border border-vio-line bg-white p-1 shadow-halo"
        >
          {SUPPORTED_LOCALES.map((l) => (
            <button
              key={l}
              type="button"
              onClick={() => change(l)}
              role="menuitem"
              className={cn(
                "w-full rounded-[6px] px-2 py-1.5 text-left font-body text-[12px]",
                "transition-colors",
                l === current
                  ? "bg-[var(--vio-accent-mist)] text-vio-accent"
                  : "text-vio-ink/80 hover:bg-[var(--vio-accent-mist)]/60",
              )}
            >
              {LOCALE_LABELS[l]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
