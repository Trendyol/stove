export type StoveRoute = "dashboard" | "admin";

export function routeForPath(pathname: string): StoveRoute {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  return normalized === "/admin" ? "admin" : "dashboard";
}

export function pathForRoute(route: StoveRoute): string {
  return route === "admin" ? "/admin" : "/";
}
