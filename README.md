# RaceBox GPS Pro — Android source

Prototype GPS performance logger for Android. It is not affiliated with or equivalent to the commercial RaceBox product.

## Features
- 0–60 km/h, 0–100 km/h, 201 m and 402 m runs
- GPS speed, estimated accuracy, distance, elapsed time and peak speed
- Speed-vs-time graph
- CSV logging for each run and a local session history
- Uses Android's GNSS/location updates as quickly as the device/provider delivers them
- Can use an external GNSS receiver only if that receiver/app exposes its location fixes to Android's location system. Generic Bluetooth NMEA support is not built in.

## Build an APK
1. Install Android Studio on a computer.
2. Extract this ZIP.
3. Android Studio > Open > choose the extracted `RaceBoxGPS_Pro` folder.
4. Let Gradle sync. If asked, install Android SDK Platform 35 and Android Gradle Plugin requirements.
5. Build > Build Bundle(s) / APK(s) > Build APK(s).
6. Click `locate` in the notification. Copy the APK to your Android phone and install it. Android may ask you to allow installation from that file manager.
7. Open the app, allow precise location, turn on Location/GPS, and go outdoors with a clear view of the sky.

## Use safely
- Do not operate the phone while riding. Mount it securely and have another person operate it, or test only in a closed, legal area.
- Wait until GPS accuracy is around 5–10 m and readings settle before arming.
- Select the run type, press ARM, then start from a complete stop. The timer starts when GPS speed first exceeds approximately 2 km/h, so low-speed GPS lag can affect results.
- Press STOP to end manually. Each finished/stopped run is saved in the app's private files as CSV; the app also shows a short history.
- CSV files are in the app's internal files directory (`Android/data/com.oped.racebox/files/` may not be browsable on newer Android versions). Use Android Studio's Device Explorer or add a future export/share feature to move them out.

## Accuracy notes
Phone GPS results are estimates. Standard phone GNSS may update at 1 Hz, and smoothing/interpolation cannot recover missing measurements. For more repeatable drag measurements, a high-rate GNSS receiver (5–10 Hz or faster) is preferable. External receivers are supported only when Android receives their location updates; Bluetooth pairing alone does not guarantee integration. Validate against a known timing system before relying on results.
