export function getErrorMessage(error: unknown, fallback: string) {
  if (typeof error === "string" && error.trim()) {
    return error;
  }
  if (error && typeof error === "object") {
    const maybeMessage = (error as { message?: unknown }).message;
    if (typeof maybeMessage === "string" && maybeMessage.trim()) {
      return maybeMessage;
    }
  }
  return fallback;
}

// Backend returns HTTP 200 + Result { code, data, message }. The axios interceptor
// throws on non-zero code with err.code populated. Network / parse / 5xx errors
// come through axios without a string `code` of that form.
export function isRbacRejection(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const code = (error as { code?: unknown }).code;
  return typeof code === "string" && code !== "" && code !== "0";
}
