# Vibemis Design Direction

## Identity & Purpose
- **Product**: Vibemis (`com.ineedmypills.vibemis`), Android game and desktop streaming client paired with Vibepollo, Apollo, and Sunshine hosts.
- **Audience**: Low-latency PC/console streamers using Android phones, tablets, foldables, handhelds, and Android TV.
- **Visual Language**: Material 3 Expressive with a Lunar-Solar high-contrast palette tuned for dimly lit gaming rooms and rapid D-Pad / thumb target scanning.
- **Dial**: `ENERGY 2 / RHYTHM 2 / MOTION 2`

## Palette (2 Core Colors + 1 Status Accent)
- **Primary (`LunarViolet` `#C5B4FF` dark / `#5742A3` light)**: Marks online, paired hosts and primary launch actions.
- **Secondary (`SolarCyan` `#7AD6FF` dark / `#006689` light)**: Marks newly discovered unpaired hosts and secondary profile controls.
- **Tertiary Accent (`AmberPulse` `#FFB77C` dark / `#8E4D00` light)**: Used strictly as the single live-session accent when a game is actively running on the host.
- **Neutral Surfaces (`#0E0E16` to `#343448` dark)**: Deep low-glare slate surfaces prevent OLED haloing during night streaming while keeping 7:1+ WCAG AAA text contrast with `#E6E1EE`.

## Typography
- **Typeface**: System Roboto / Sans-Serif (`FontFamily.SansSerif`) for zero-latency native glyph rendering and full multi-script coverage across 28 locales.
- **Hierarchy**: `headlineMedium` (26sp Bold, -0.25sp tracking) anchors top app bars; `titleLarge` (21sp Bold) names hosts; `labelLarge` (14sp SemiBold) labels poster titles and capability pills.

## Shape & Elevation Hierarchy
- **Cards (`large = 28.dp`)**: Expressive rounded cards distinguish tappable host/game targets from flat settings rows.
- **Status Badges (`small = 14.dp`)**: Non-interactive capability tags (`PyroWave`, `Virtual Display`) use compact radii so they read as metadata, never as buttons.
- **Interactive Chips (`AssistChip`)**: Reserved strictly for real actions (`Sleep Host`).
