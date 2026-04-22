/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        card: "hsl(var(--card))",
        "card-foreground": "hsl(var(--card-foreground))",
        popover: "hsl(var(--popover))",
        "popover-foreground": "hsl(var(--popover-foreground))",
        primary: "hsl(var(--primary))",
        "primary-foreground": "hsl(var(--primary-foreground))",
        secondary: "hsl(var(--secondary))",
        "secondary-foreground": "hsl(var(--secondary-foreground))",
        muted: "hsl(var(--muted))",
        "muted-foreground": "hsl(var(--muted-foreground))",
        accent: "hsl(var(--accent))",
        "accent-foreground": "hsl(var(--accent-foreground))",
        destructive: "hsl(var(--destructive))",
        "destructive-foreground": "hsl(var(--destructive-foreground))",
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        "chat-user": "hsl(var(--chat-user))",
        "chat-assistant": "hsl(var(--chat-assistant))",
        // ── Design Tokens ──────────────────────────────
        brand: {
          DEFAULT:  "var(--brand)",
          muted:    "var(--brand-muted)",
          subtle:   "var(--brand-subtle)",
          faint:    "var(--brand-faint)",
          lighter:  "var(--brand-lighter)",
          ring:     "var(--brand-ring)",
          fg:       "var(--brand-fg)",
        },
        surface: {
          DEFAULT: "var(--surface)",
          2: "var(--surface-2)",
          3: "var(--surface-3)",
          4: "var(--surface-4)",
        },
        ink: {
          DEFAULT: "var(--ink)",
          2: "var(--ink-2)",
          3: "var(--ink-3)",
          4: "var(--ink-4)",
          5: "var(--ink-5)",
        },
        line: {
          DEFAULT: "var(--line)",
          2: "var(--line-2)",
          3: "var(--line-3)",
        },
        danger: {
          DEFAULT: "var(--danger)",
          subtle:  "var(--danger-subtle)",
          border:  "var(--danger-border)",
        },
        // ── Violet Aurora Tokens (P0) ──────────────────
        vio: {
          surface:   "var(--vio-surface)",
          "surface-2": "var(--vio-surface-2)",
          ink:       "var(--vio-ink)",
          line:      "var(--vio-line)",
          accent:    "var(--vio-accent)",
          "accent-2": "var(--vio-accent-2)",
          "accent-subtle": "var(--vio-accent-subtle)",
          "accent-mist": "var(--vio-accent-mist)",
          success:   "var(--vio-success)",
          warning:   "var(--vio-warning)",
          danger:    "var(--vio-danger)",
        },
      },
      fontFamily: {
        display: ["Fraunces", "Noto Serif CJK SC", "Noto Serif CJK HK", "Songti SC", "Georgia", "serif"],
        body: ["'Inter Tight'", "Noto Sans SC", "Noto Sans HK", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      boxShadow: {
        soft: "0 24px 60px -30px rgba(10, 10, 15, 0.65)",
        glow: "0 0 0 1px rgba(59, 130, 246, 0.2), 0 16px 40px rgba(59, 130, 246, 0.25)",
        neon: "0 0 30px rgba(59, 130, 246, 0.35)",
        paper: "0 1px 0 var(--vio-line)",
        halo:  "0 12px 40px -12px rgba(139,127,239,0.25)",
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: 0, transform: "translateY(10px)" },
          "100%": { opacity: 1, transform: "translateY(0)" }
        },
        "pulse-soft": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0.5 }
        },
        "blink": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0 }
        },
        "spin-slow": {
          "0%": { transform: "rotate(0deg)" },
          "100%": { transform: "rotate(360deg)" }
        },
        "glow": {
          "0%, 100%": { opacity: 0.5 },
          "50%": { opacity: 1 }
        },
        "float": {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-6px)" }
        },
        "aurora-sweep": {
          "0%":   { transform: "translateX(-30%)", opacity: 0.0 },
          "40%":  { opacity: 0.6 },
          "100%": { transform: "translateX(130%)", opacity: 0.0 },
        },
      },
      animation: {
        "fade-up": "fade-up 0.35s ease-out",
        "pulse-soft": "pulse-soft 1.4s ease-in-out infinite",
        "blink": "blink 1s step-end infinite",
        "spin-slow": "spin-slow 4s linear infinite",
        "glow": "glow 2.6s ease-in-out infinite",
        "float": "float 6s ease-in-out infinite",
        "aurora-sweep": "aurora-sweep 1400ms ease-out 200ms 1",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "grid-pattern":
          "linear-gradient(rgba(255,255,255,0.06) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.06) 1px, transparent 1px)"
      }
    }
  },
  plugins: [require("@tailwindcss/typography")]
};
