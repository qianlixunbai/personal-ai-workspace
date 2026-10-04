# ADR-010 — Frontend Build and Desktop Distribution

Status: Accepted

Date: 2026-10-04 (Asia/Shanghai)

## Implemented build decision

One package at `desktop/frontend` owns React19.3.0, React DOM19.3.0, strict TypeScript7.0.2
and Vite8.3.2. Versions were checked against live npm metadata on2026-10-04; Node24.16.0 meets
the declared Node20.19+/22.12+ compatibility. Dependencies are pinned in `package.json` and the
committed `package-lock.json`. Developer commands use npm, with no alternate package manager,
monorepo framework, SSR or backend-for-frontend.

Vitest5.0.3/jsdom30.1.2/Testing Library16.3.3 cover the shell, bridge correlations/invalid responses,
native actions, status, navigation, hostile text rendering and theme storage. They are dev-only.
Production depends only on React/React DOM. There is no remote font/CDN, analytics, animation,
Markdown, icon framework or charting package.

```powershell
cd desktop/frontend
npm ci
npm test
npm run build
cd ../..
dotnet build desktop/PersonalAiWorkspace.Desktop.slnx -c Release
dotnet publish desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj -c Release
```

Desktop's pre-build target runs `npm ci`, strict typecheck and Vite production build by default,
then verifies the versioned SHA-256 asset manifest and copies output into `MainWorkspace` for
both build and publish (including referenced acceptance projects). Generated `dist`, dependencies
and coverage are ignored. A prebuilt developer pipeline can pass `-p:FrontendSkipBuild=true`;
the manifest verification still runs, requiring compatible production assets and Node on the
build machine. Missing, damaged or development-CSP assets fail the build clearly. Host-side
manifest and CSP checks independently fail to a native fallback on damaged distribution.

Only verified output is mapped into WebView2. Node/npm/Vite are developer prerequisites; an
ordinary user's build/published application has no frontend-server or Node dependency.

## Development loading

An explicit `dotnet ... -c Debug -p:MainWorkspaceDev=true` compiles a separate fixed
`http://127.0.0.1:5173` policy. Start it with `npm run dev`; Vite binds loopback with strict port.
The allowlisted credential-isolated bridge still applies. This development server only permits
the additional self/HMR connections and inline React refresh/injected styles in its CSP.
Production output preserves strict CSP. Debug without the property uses bundled assets.
Release rejects that property and compiles out the development origin; it never probes or falls
back to Vite, nor accepts an arbitrary frontend URL.

## Desktop/runtime distribution

Desktop remains `net10.0-windows`, using official Microsoft.Web.WebView2 SDK1.0.4258.31,
pinned in the Desktop project. Core has no WebView dependency. Installed Evergreen WebView2
is required for the React surface; the host explicitly detects initialization failure and
offers native legacy UI. This milestone does not install/download WebView2 automatically.

Java and .NET remain the existing separately provisioned runtimes. Choosing self-contained
.NET, Java bundling, an installer, runtime provisioning, updates or signing remains future
M5E/distribution work. No supervisor or process-owner inference is introduced.

## References

- [Vite prerequisites](https://vite.dev/guide/)
- [Official WebView2 SDK package](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4258.31)
- [Microsoft user-data-folder guidance](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/user-data-folder)
