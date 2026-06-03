# UI Overhaul & V2 Architecture

> **STATUS (current): done and built well beyond this overhaul.** The advance
> loop is wired, and Staff plus many new screens (R&D, Ladder, Standings,
> History, interactive sponsor market, mid-season call-ups) have shipped. The
> "Pending / Next Steps" at the bottom are historical. See `frontend/README.md`
> for the current screens and `remaining.md` for the implementation log.

## Overview
This document outlines the changes made to the `frontend` directory to implement the new "F1-Broadcast" dark theme UI while safely preserving the original test environment. The UI was restructured from a flat panel system into a consolidated, component-driven layout.

## 1. Structural & Architectural Changes
*   **Environment Isolation:** 
    *   Moved all existing test panels to `frontend/src/testui/panels/`.
    *   Moved original `App.vue` logic to `frontend/src/testui/TestUI.vue`.
    *   Updated all internal API imports in `testui/panels` from `../api.js` to `../../api.js`.
*   **Master Switch (`App.vue`):** 
    *   Rewrote root `App.vue` to act purely as a state toggle between `TestUI.vue` and `NewUI.vue`.
    *   Added a floating `.dev-toggle` button to switch environments at runtime.
    *   Default application state set to `mode = 'new'`.
*   **New UI Shell (`NewUI.vue`):** 
    *   Created a new master layout combining the `NavBar`, `SeasonBar`, and dynamic panel routing.
*   **CSS Namespacing (`style.css`):**
    *   Wrapped all legacy CSS inside a `.test-ui-wrapper` scope to prevent UI bleeding and layout constraint conflicts.
    *   Defined global CSS variables for the dark theme inside `.new-ui-wrapper` (`--bg`, `--surface`, `--accent`, etc.).

## 2. New Global Components
*   `frontend/src/components/NavBar.vue`: Top-level navigation replacing the old tab system. Consolidated several redundant tabs.
*   `frontend/src/components/SeasonBar.vue`: Contextual header displaying the selected Constructor's crest, Principal name, Season, Round, Phase, and Cash reserves. Includes the "Advance" state action.

## 3. Panel Consolidations & Additions
*   **`SavesPanel.vue` (New)**: 
    *   Acts as the primary landing page / Main Menu.
    *   Handles `listSaves()`, `loadSave()`, and `deleteSave()`.
    *   Aligned creation payload to match backend DTO: `{ saveName: "...", managerName: "..." }`.
*   **`DashboardPanel.vue` (New)**: 
    *   Central hub consolidating next race data, team finances, constructor standings, and driver standings into a single bento-box grid.
*   **`RaceWeekendPanel.vue` (Consolidated)**: 
    *   Merged `PracticePanel.vue`, `StrategyPanel.vue`, and `ResultsPanel.vue` into a unified "Command Center".
    *   Added internal tab navigation (`Weekend Hub`, `Setup & Strategy`, `Session Results`).
*   **`MarketPanel.vue` (Consolidated)**: 
    *   Merged `MarketPanel.vue` (Drivers) and `SponsorshipsPanel.vue` into a single contract management screen with internal tabs.
    *   Added dynamic "Available Headroom" budget calculations.
*   **`SchedulePanel.vue` (Renamed/Updated)**: 
    *   Replaced `CalendarPanel.vue`. Added a clean grid layout for the season calendar.
*   **`TeamsPanel.vue` (Updated)**: 
    *   Reskinned to the new data-table format displaying crests, principals, and cash reserves.
*   **`DriversPanel.vue` (Updated)**: 
    *   Reskinned into a global scouting database.
    *   Added a client-side search input to filter by driver name or team.

## 4. Pending / Next Steps
*   **Advance Game Loop:** Wire the `emit('advance')` actions in `SeasonBar` and `RaceWeekendPanel` to trigger the backend state machine and refresh UI context.
*   **Remaining Panels:** Port `StaffPanel.vue` and `OffSeasonPanel.vue` to the `.new-ui-wrapper` aesthetic.
*   **Team Selection:** Re-integrate constructor selection into the creation flow once backend support for initial `teamId` selection is available, or build a "Take Control" action in the `TeamsPanel`.