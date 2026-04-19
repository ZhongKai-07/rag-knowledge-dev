import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { SpacesPage } from "@/pages/SpacesPage";
import { ChatPage } from "@/pages/ChatPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { DashboardPage } from "@/pages/admin/dashboard/DashboardPage";
import { KnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { IntentTreePage } from "@/pages/admin/intent-tree/IntentTreePage";
import { IntentListPage } from "@/pages/admin/intent-tree/IntentListPage";
import { IntentEditPage } from "@/pages/admin/intent-tree/IntentEditPage";
import { IngestionPage } from "@/pages/admin/ingestion/IngestionPage";
import { RagTracePage } from "@/pages/admin/traces/RagTracePage";
import { RagTraceDetailPage } from "@/pages/admin/traces/RagTraceDetailPage";
import { SystemSettingsPage } from "@/pages/admin/settings/SystemSettingsPage";
import { SampleQuestionPage } from "@/pages/admin/sample-questions/SampleQuestionPage";
import { QueryTermMappingPage } from "@/pages/admin/query-term-mapping/QueryTermMappingPage";
import { RagEvaluationPage } from "@/pages/admin/evaluations/RagEvaluationPage";
import { AccessCenterPage } from "@/pages/admin/access/AccessCenterPage";
import { useAuthStore } from "@/stores/authStore";
import { RequireAuth, RequireAnyAdmin, RequireSuperAdmin, RequireMenuAccess } from "@/router/guards";

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (isAuthenticated) {
    return <Navigate to="/spaces" replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return <Navigate to={isAuthenticated ? "/spaces" : "/login"} replace />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <HomeRedirect />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    path: "/spaces",
    element: (
      <RequireAuth>
        <SpacesPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAuth>
        <RequireAnyAdmin>
          <AdminLayout />
        </RequireAnyAdmin>
      </RequireAuth>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/admin/dashboard" replace />
      },
      {
        path: "dashboard",
        element: <RequireMenuAccess menuId="dashboard"><DashboardPage /></RequireMenuAccess>
      },
      {
        path: "knowledge",
        element: <RequireMenuAccess menuId="knowledge"><KnowledgeListPage /></RequireMenuAccess>
      },
      {
        path: "knowledge/:kbId",
        element: <RequireMenuAccess menuId="knowledge"><KnowledgeDocumentsPage /></RequireMenuAccess>
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <RequireMenuAccess menuId="knowledge"><KnowledgeChunksPage /></RequireMenuAccess>
      },
      {
        path: "intent-tree",
        element: <RequireSuperAdmin><IntentTreePage /></RequireSuperAdmin>
      },
      {
        path: "intent-list",
        element: <RequireSuperAdmin><IntentListPage /></RequireSuperAdmin>
      },
      {
        path: "intent-list/:id/edit",
        element: <RequireSuperAdmin><IntentEditPage /></RequireSuperAdmin>
      },
      {
        path: "ingestion",
        element: <RequireSuperAdmin><IngestionPage /></RequireSuperAdmin>
      },
      {
        path: "traces",
        element: <RequireSuperAdmin><RagTracePage /></RequireSuperAdmin>
      },
      {
        path: "traces/:traceId",
        element: <RequireSuperAdmin><RagTraceDetailPage /></RequireSuperAdmin>
      },
      {
        path: "settings",
        element: <RequireSuperAdmin><SystemSettingsPage /></RequireSuperAdmin>
      },
      {
        path: "sample-questions",
        element: <RequireSuperAdmin><SampleQuestionPage /></RequireSuperAdmin>
      },
      {
        path: "mappings",
        element: <RequireSuperAdmin><QueryTermMappingPage /></RequireSuperAdmin>
      },
      {
        path: "evaluations",
        element: <RequireSuperAdmin><RagEvaluationPage /></RequireSuperAdmin>
      },
      {
        path: "access",
        element: <RequireMenuAccess menuId="access"><AccessCenterPage /></RequireMenuAccess>
      },
      // P2.2: 旧路由 301 重定向至权限中心对应 Tab。观察期满，页面文件已删除。
      { path: "users", element: <Navigate to="/admin/access?tab=members" replace /> },
      { path: "roles", element: <Navigate to="/admin/access?tab=roles" replace /> },
      { path: "departments", element: <Navigate to="/admin/access?tab=departments" replace /> },
      { path: "sharing", element: <Navigate to="/admin/access?tab=sharing" replace /> }
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
