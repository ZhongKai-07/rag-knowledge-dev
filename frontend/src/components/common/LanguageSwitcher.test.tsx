import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import i18n from "@/i18n";
import { LanguageSwitcher } from "./LanguageSwitcher";

describe("LanguageSwitcher", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("zh-CN");
  });

  it("渲染当前语言 label（默认 zh-CN）", () => {
    render(<LanguageSwitcher />);
    expect(screen.getByText("简体中文")).toBeTruthy();
  });

  it("点击切换到 en 后，i18n 语言改变", async () => {
    render(<LanguageSwitcher />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByText("English"));
    await waitFor(() => {
      expect(i18n.resolvedLanguage).toBe("en");
    });
  });

  it("切换后 document.documentElement.lang 同步", async () => {
    render(<LanguageSwitcher />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByText("繁體中文（香港）"));
    await waitFor(() => {
      expect(document.documentElement.lang).toBe("zh-HK");
    });
  });
});
