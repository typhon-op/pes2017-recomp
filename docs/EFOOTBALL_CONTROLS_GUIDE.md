# eFootball Mobile Classic Touch Controls Guide for PES 2017 Android Recomp

## 1. Introduction & Ergonomic Design
The touch control subsystem in the **PES 2017 Android Recompilation** (`com.pes2017.recomp`) has been overhauled to replicate the fluid mechanics, responsiveness, and clean layout of modern *eFootball Mobile Classic* virtual pad controls.

### Key Highlights:
- **Clean Right Side:** Standalone sprint/dash buttons that previously cluttered the right thumb area have been completely removed.
- **Integrated Auto-Sprint:** Sprinting is seamlessly unified into the left analogue joystick via outer-ring threshold detection.
- **Contextual Action Cluster:** A clean 4-button diamond layout adapts automatically between attacking and defending scenarios.
- **Advanced Micro-Flicks:** Directional swipe gestures on action buttons trigger lofted balls, finesse shots, chip shots, and stunning actions without requiring extra UI controls.
- **Infinite Customization Suite:** Built-in live editor (schema v9) for adjusting scaling, positions, opacities, deadzones, and auto-sprint thresholds with real-time persistence.

---

## 2. Left Joystick & Movement Physics

### Dynamic Floating Joystick
- **Floating Anchor:** Tapping anywhere on the left 45% of the screen dynamically anchors the virtual joystick center $(X_0, Y_0)$ under your thumb.
- **360° Analogue Freedom:** Continuous normalized coordinates $(DX, DY)$ are mapped directly to Xbox 360 inputs (`sThumbLX`, `sThumbLY` from `-32768` to `32767`).
- **Inner Deadzone Filtering:** Movements within the deadzone threshold (default `15%`, adjustable) prevent unwanted drift while preserving fine dribbling precision.

### Outer-Ring Auto-Sprint (`RB` Latching)
- **Vector Magnitude Check:** Deflection is calculated continuously as $\text{distance} = \sqrt{DX^2 + DY^2}$.
- **Automatic Dash:**
  - $\text{distance} < \text{Sprint Threshold}$: Normal precision dribble (`LS`). Xbox sprint bit `RB` (`0x0200`) is cleared (`0`).
  - $\text{distance} \ge \text{Sprint Threshold}$: Full stick tilt **plus automatic latching** of the Xbox Sprint bitmask `RB` (`0x0200 = 1`).
- **Adjustable Threshold:** The sprint boundary defaults to `85%` of the joystick radius and can be calibrated in the editor between `70%` and `95%` or disabled for manual sprint control.

### Left-Zone Double-Tap (Quick Stop / Face Goal)
- Rapidly double-tapping on the left joystick zone triggers an instant orientation reset (`LB` tap), allowing your player to stop immediately and face the opponent's goal.

---

## 3. Right-Side 4-Button Cluster & Dynamic Defensive State Machine

The right thumb operates a compact 4-button diamond matching the Xbox action geometry. When team possession changes, button labels and underlying game commands transition immediately without altering button positions or muscle memory.

| Position | Attacking Label | Attacking Action | Defensive Label | Defensive Action | Xbox Bitmask |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Lower** | `PASS` | Ground Pass | `PRESSURE` | Press Ball Carrier | `A` (`0x1000`) |
| **Left** | `SHOOT` | Normal Shot | `TACKLE` | Standing Tackle | `X` (`0x4000`) |
| **Right** | `CROSS` | Lob / Cross | `CLEAR` | Emergency Clearance | `B` (`0x2000`) |
| **Upper** | `THROUGH` | Through Ball | `GK RUSH` | Keeper Charge | `Y` (`0x8000`) |

### Tactical Tactical & Shoulder Controls:
- **`LB` (Top Right):** `SWITCH` (Player Change)
- **`RT` (Top Right):** `MATCH UP` (Jockey / Defend Space)
- **`LT` (Top Left):** Manual / Secondary Modifier

---

## 4. Advanced Button Micro-Flicks

When **Micro-Flicks** are enabled in the editor, swiping outward on action buttons triggers advanced techniques:

- **Pass (`A`):**
  - *Tap:* Standard ground pass (`A`).
  - *Flick Up / Vertical:* Lofted pass (`LB + A`).
  - *Flick Horizontal:* Stunning / driven pass (`RT + A`).
- **Shoot (`X`):**
  - *Tap:* Standard driven shot (`X`).
  - *Flick Up:* Chip shot (`LB + X`).
  - *Flick Down:* Controlled / Finesse shot (`RT + X`).
  - *Flick Horizontal:* Stunning shot (`RT + X`).
- **Through (`Y`):**
  - *Tap:* Ground through pass (`Y`).
  - *Flick:* Chipped through ball (`LB + Y`).

Haptic feedback confirms when a micro-flick threshold is achieved.

---

## 5. Infinite Customization Suite (Edit Controls Panel)

Access the customization editor at any time from:
- **In-Game Pause Menu:** Tap `MENU` (top right) $\rightarrow$ `Customize Controls`.
- **Main Settings:** `Settings` $\rightarrow$ `Controls` $\rightarrow$ `Arrange`.

### Configurable Parameters (Schema Version 9):
1. **Control Scale & Size:** Dynamic slider to resize joystick and action buttons independently.
2. **Opacity (Idle & Active):** Adjust resting button transparency from 5% to 100%.
3. **Sprint Limit Slider:** Fine-tune the auto-sprint activation boundary from 70% to 95% of stick radius.
4. **Auto Sprint Toggle:** Enable outer-ring auto-sprint or revert to manual input.
5. **Micro-Flicks Toggle:** Enable or disable swipe/flick gestures on action buttons.
6. **Snap to Grid:** Align controls evenly across screen axes.
7. **Reset Control / Reset All:** Instantly restore default geometry with safe screen clamping.

### Persistence:
Layouts and settings are automatically serialized to `controls.json` in the application data directory using atomic file replacement.

---

## 6. Physical Gamepad Compatibility
External Bluetooth and USB controllers (Xbox, DualShock, DualSense, standard HID gamepads) take absolute priority on **Player 1 / Slot 0** (`!device.isVirtual && device.isExternal`). On-screen touch controls safely hide while physical gamepads are active and restore automatically upon touching the screen.
