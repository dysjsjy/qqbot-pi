# Motion Primitives source and local adaptations

Upstream: https://github.com/ibelick/motion-primitives

Pinned commit: `120f64f6ca60348e251f929e9c81f11ccbe45eda` (GitHub codeload archive comment).

License: MIT, Copyright (c) 2024 ibelick. Upstream uses the spelling `LICENCE.md`; exact text is preserved in LICENSE.md and public/third-party-notices.txt.

- https://github.com/ibelick/motion-primitives/blob/120f64f6ca60348e251f929e9c81f11ccbe45eda/components/core/animated-background.tsx; original SHA-256 `fa592713c408eaf58b21976c20737d84239c42a0b7e47cf9e7843121b1c0852c`

Local changes: ordinary CSS instead of Tailwind/cn; controlled selection that preserves events; stable React keys and React 19 types; LazyMotion/m components; user reduced-motion support; no retained exiting panels. Group and viewport reveals keep content readable and actionable. The Java backend and API contracts are unchanged.

Runtime: motion 14.14.0, MIT (distributed package license retained by npm). Both apps use the same version, with synchronous domMin features. Selection measures only the current control rectangle and animates the decorative background; the drag/gesture/shared-layout engines are omitted to meet the resource budget. A useSyncExternalStore media-query subscription follows OS preference changes live and drives MotionConfig plus the component static paths.
