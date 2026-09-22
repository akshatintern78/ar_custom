# ar_cust — Custom Solar AR (Flutter + Native Android)

Native-inside-Flutter Solar AR. **No Google ARCore / AR services.**

Reference-only (do not edit from this workstream): `Solar_360_app`, `Imt_Billbook_Backend`, `Imt_Billbook_frontend`, `ar_custom`.

## Flow

1. Flutter **Design rooftop** screen — pick kW, alignment, post heights.
2. Tap **View AR**.
3. MethodChannel `com.example.ar_cust/solar_ar` → `openSolarAr`.
4. Native `CustomSolarArActivity`: rear **CameraX** + transparent **SceneView/Filament** GLB overlay.
5. IMU world tracking places / locks the `.glb` on a gravity-horizontal surface. **Move** / **Rotate** / **Reset**.

## Model

```
android/app/src/main/assets/models/solar_3kw_6x1_rooftop.glb
```

Source copy also at `assets/models/solar_3kw_6x1_rooftop.glb` (loaded from Android assets by native code).

## Run

```powershell
cd D:\360\ar_cust
flutter pub get
flutter run
```
