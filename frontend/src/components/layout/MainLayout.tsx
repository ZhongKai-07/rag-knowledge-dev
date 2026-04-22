import * as React from "react";
import { Menu } from "lucide-react";

import { Sidebar } from "@/components/layout/Sidebar";

interface MainLayoutProps {
  children: React.ReactNode;
}

export function MainLayout({ children }: MainLayoutProps) {
  const [sidebarOpen, setSidebarOpen] = React.useState(false);

  return (
    <div className="flex min-h-screen" style={{ backgroundColor: "var(--vio-surface)" }}>
      <Sidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <main className="relative flex-1 min-h-0 overflow-hidden" style={{ backgroundColor: "var(--vio-surface)" }}>
        {/* Mobile-only sidebar toggle — matches sidebar-open icon; hidden on lg+ since sidebar is always visible */}
        <button
          type="button"
          aria-label="打开侧边栏"
          onClick={() => setSidebarOpen(true)}
          className="absolute left-4 top-4 z-10 flex h-8 w-8 items-center justify-center rounded-lg border border-vio-line bg-white text-vio-ink/60 transition-colors hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent lg:hidden"
        >
          <Menu className="h-4 w-4" />
        </button>
        {children}
      </main>
    </div>
  );
}
