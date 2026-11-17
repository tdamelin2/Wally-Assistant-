# Research: UI redesign patterns for a 10-area personal assistant (CMP 1.12.2, phone + laptop)
Date: 2026-11-10

## Answer
Use 4-5 top-level destinations with `NavigationSuiteScaffold`. It shows a bottom bar on the S25 and a rail on the XPS 16. Fold the other areas into hub screens, the way Todoist's "Browse" tab and Apple Health's "Browse" tab work. For CMP 1.12, the official "what's new" page lists `material3*:1.12.0-alpha03` and `adaptive*:1.3.0`. In Material 3 Expressive, the expanded rail replaces the navigation drawer. Don't repeat the tab name as a big screen title, and keep only one overflow entry.

## Findings
| # | Finding | Confidence | Source (date) |
|---|---------|------------|---------------|
| 1 | **Todoist:** phone bottom bar defaults are "Inbox, Today, Upcoming, and Browse". "Tap the Browse tab to navigate to any other view". Users can customize the tabs, and "any view added to a tab ... is automatically removed from the Browse menu" (no duplicates). Desktop uses a sidebar instead. | High | [Todoist help: nav bar](https://www.todoist.com/help/articles/customize-the-todoist-navigation-bar-L4qpkI0xj), [sidebar](https://www.todoist.com/help/todoist/features/customize-the-sidebar-in-todoist-S9JLTYqZV) (current) |
| 2 | **Things 3:** a few time-based lists (Today with "This Evening", Upcoming, Anytime, Someday), then Areas/Projects. Mac uses a sidebar. On iPhone, "the sidebar becomes the homepage". | High | [Cultured Code guide](https://culturedcode.com/things/guide/), [MacStories review](https://www.macstories.net/reviews/things-3-beauty-and-delight-in-a-task-manager/) (2017, design unchanged) |
| 3 | **Sunsama (desktop-first):** Home is daily task columns or a calendar view, with the calendar in the center and tasks on the right. The left panel holds navigation and rituals (daily planning). Mobile is limited. | Medium | [Sunsama help: Workspace navigation](https://help.sunsama.com/docs/usage-guides/workspace-navigation), [Kanban basics](https://help.sunsama.com/docs/getting-started/basics/kanban-task-view) |
| 4 | **Apple Health:** tabs are "Summary, Sharing, and Browse". Summary shows the user's Favorites. Browse lists every category. This is a good model for one Health hub with many metrics. | Medium (exact current-iOS tab set unconfirmed) | [Apple iPhone User Guide](https://support.apple.com/guide/iphone/view-your-health-data-iphe3d379c32/15.0) |
| 5 | **Bearable:** the home screen is a customizable dashboard where users can reorder and turn sections on or off. Check-in is split into tabs (Mood, Symptoms, Factors). | Medium | [Bearable docs](https://bearable.notion.site/Customising-Bearable-2027d0d874b0809daebdfe9ede57cff6) |
| 6 | `NavigationSuiteScaffold` default: "Navigation bar if the width or height is compact ... Navigation rail for everything else". You can override it, for example with `NavigationSuiteType.NavigationDrawer` when the width is at least `WIDTH_DP_EXPANDED_LOWER_BOUND`, via `currentWindowAdaptiveInfo()`. | High | [Android: Build adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation) |
| 7 | Size classes: compact <600dp, medium 600-840dp, expanded >840dp. Use a bar on compact and a rail on medium and up. | Medium | [Android codelab](https://developer.android.com/codelabs/adaptive-material-guidance), [SAP M3 nav bar](https://www.sap.com/design-system/fiori-design-android/v26-4/components/m3-standard-components/navigation-bar/usage) |
| 8 | Bottom bar: 3-5 destinations. SAP's M3 guidance says to avoid "more than five". | Medium (m3.material.io page did not render for me) | [SAP M3 nav bar](https://www.sap.com/design-system/fiori-design-android/v26-4/components/m3-standard-components/navigation-bar/usage) |
| 9 | M3 Expressive: the rail has collapsed and expanded versions. The expanded rail replaces the navigation drawer, and "The navigation drawer is being deprecated". | High (two sources) | [SAP M3 rail](https://www.sap.com/design-system/fiori-design-android/v26-4/components/m3-standard-components/navigation-rail/usage), [9to5Google](https://9to5google.com/2025/05/14/material-3-expressive-navigation/) (2025-05) |
| 10 | CMP 1.12 deps: `org.jetbrains.compose.material3:material3*:1.12.0-alpha03` (Jetpack Material3 1.5.0-alpha22), `org.jetbrains.compose.material3.adaptive:adaptive*:1.3.0`, `org.jetbrains.androidx.navigation:navigation-*:2.10.0-rc01`, `lifecycle-*:2.11.0`, `org.jetbrains.androidx.window:window-core:1.5.1`. | High | [Kotlin docs: What's new in CMP 1.12](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html) |
| 11 | `material3-adaptive-navigation-suite` is published under the `material3` group at the same version (1.12.0-alpha03). The adaptive artifacts include list-detail and supporting-pane scaffolds (`ListDetailPaneScaffold`, `SupportingPaneScaffold`). | Medium | [Maven Central](https://central.sonatype.com/artifact/org.jetbrains.compose.material3/material3), [CMP 1.12.0-beta01 notes](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0-beta01) |
| 12 | Writing: "write UI text in short, scannable segments". Remove redundant words from labels. A label shouldn't repeat what its container already says (for example, "navigation" inside a nav). | Medium | [Material writing](https://material.io/design/communication/writing.html), [Maersk labels](https://designsystem.maersk.com/content/labels/), [nav label article](https://news.lavx.hu/article/there-s-no-need-to-include-navigation-in-your-navigation-labels) |

## Consolidation pattern (from findings 1, 4, 5)
- **Hub plus favorites:** the home screen shows pinned cards, and a hub lists everything (Apple Health Summary/Browse, Todoist Browse).
- **Customizable tabs:** an area promoted to a tab disappears from the hub (Todoist).
- A possible grouping (my suggestion, not sourced): **Today** (calendar, tasks, briefing, exams), **Log** (check-ins, spending, journal, meals, dreams), **Insights** (Charts, Health, Screentime, Goals), **Chat**, and **More/Library** (Files, Study). On the laptop, use list-detail inside hubs.

## Conflicts
Android's default puts a rail on any non-compact window, while M3 Expressive recommends the expanded rail over the drawer. These fit together: on the laptop, use the expanded rail, not a drawer.

## Unconfirmed
- Whether CMP **1.12.2** bumps material3 past 1.12.0-alpha03. The 1.12.2 changelog would settle this.
- Exact wording on m3.material.io (the page did not render), including M3's own "3-5 destinations" text.
- Notion Calendar, Linear, Daylio, Google Fit, Arc/Dia, Raycast, and ChatGPT/Claude layouts were not researched (word budget).
- I found no specific source for "don't repeat the tab name as the screen title" (item 4). It is inferred from the general redundancy guidance.
