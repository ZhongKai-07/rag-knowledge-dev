import { api } from "./api";

export interface SpacesStats {
  kbCount: number;
  totalDocumentCount: number;
}

export async function getSpacesStats(): Promise<SpacesStats> {
  return api.get<SpacesStats, SpacesStats>("/spaces/stats");
}
