# Demo script: TraceVeil and a suspicious-looking APK

**Purpose:** show TraceVeil reacting to an APK that carries the signals of fake e-Challan apps. Those signals are SMS and contacts permissions, display over other apps, an accessibility service, a C2-style lookup and a hard-coded IP address.

**The app:** RiskDemo, labelled **TEST Challan (TraceVeil demo)** with a red **TEST** icon. It is a harmless controlled fixture. It declares those signals and does nothing with them. See [riskdemo/README.md](../riskdemo/README.md) for exactly what it does and does not do.

**Say this first:** "This is our own test app. It looks like a fake challan app from the outside, but it reads nothing and sends nothing. We use it so we never have to bring real malware to a demo."

## Before the demo

- **Build the APK.** Run `:riskdemo:assembleDebug` with the locked build runner. The output is `riskdemo/build/outputs/apk/debug/riskdemo-debug.apk`. Record its SHA-256 for the demo notes.
- **Install TraceVeil.** Install the build that contains **APK Guard** and **Quarantine**. Both are on separate branches at the time of writing and are not yet merged or phone-tested together.
- **Remove any old copy.** If RiskDemo (`com.traceveil.riskdemo`) is already on the phone, uninstall it so the "before install" step is real.
- **Rehearse once on the demo phone.** Play Protect and Android's restricted settings can change the flow (see step 3).

## Script (about 4 minutes)

1. **Send the APK to the phone with Office Kit.**
   - Use Office Kit's file transfer to send `riskdemo-debug.apk` from the laptop.
   - Point out that this is how the lures arrive: an APK file in a message or a shared folder, not from Google Play.
   - Office Kit file-transfer acceptance is still an open item in `docs/NEXT_WORK.md`. Confirm it works on the demo phone during rehearsal.

2. **Open it with TraceVeil before installing (APK Guard).**
   - From the received file, choose **Open with** or **Share** and pick TraceVeil.
   - APK Guard reads the file without installing it. Its rules, at the time of writing, should show:
     - SMS reading (review carefully).
     - An accessibility service (review carefully).
     - The combination "can see OTPs and act on or cover your screen".
     - Display over other apps.
     - Contacts.
     - "The name looks like a government service". This one is INFERRED from the word "challan".
     - The Android debug signing certificate.
   - Stress the wording: these are capabilities the APK *asks for*. They do not prove misuse, and TraceVeil does not call the app malicious.

3. **Install it.**
   - Allow the install for the source app only if the demo needs it.
   - **Google Play Protect may block or warn about this install.** Its guidance treats sideloaded apps that request SMS or accessibility access as high risk. If it blocks the install, that is the phone's own protection working, so show it. You can then continue the Quarantine part with an install by the team member who holds the phone/ADB lease, or skip to step 5.
   - Never switch off Play Protect for the demo.

4. **Run TraceVeil Quarantine on RiskDemo.**
   - Start a Quarantine run for `com.traceveil.riskdemo`, then open RiskDemo. The red TEST banner stays on screen the whole time.
   - On launch, RiskDemo makes:
     - three DNS lookups: `c2.traceveil-demo.invalid`, `pay-challan-update.invalid` and `otp-relay.example`;
     - one TCP connection attempt to `203.0.113.10:443`.

     Quarantine should list the lookups and the blocked connection to a hard-coded address.
   - Tap **Request SMS permission**, **Open accessibility settings** and **Open display-over-apps settings**. Show that Quarantine recorded the permission dialog and the two Settings screens.
   - Answer the SMS dialog either way and leave the accessibility service off. Nothing in RiskDemo turns anything on.
   - Back in RiskDemo, its **Network attempts made on launch** card shows its own view: "No address" and "Not connected", with 0 bytes sent.

5. **Explain the limits (say these out loud).**
   - RiskDemo is a fixture. Catching it shows that the rules react to these signals, not that TraceVeil detects real malware. Real malware can hide its permissions behind a second-stage download, load code at run time, or use encrypted or cached DNS.
   - A DNS lookup is an attempt to contact a host, not proof that data was sent. Every RiskDemo target is a reserved name or a documentation address that cannot reach a real server.
   - APK Guard reads only what the APK declares. Quarantine sees only the run you watched.
   - Neither feature says an app is safe or unsafe. Unknown stays unknown.
   - Uninstall RiskDemo after the demo.

## If something goes wrong

- **Restricted setting.** Android may refuse to let you turn on RiskDemo's accessibility service (or allow SMS) because it was installed from a file. Show it as Android's own protection. You do not need to enable anything for the demo.
- **No lookups in Quarantine on a second launch.** Android may have cached the negative answers. Force-stop RiskDemo, start a fresh run, then use **Repeat the network attempts**.
- **APK Guard or Quarantine not in the installed build.** Show RiskDemo's banner, its declared permissions in Android's App info page, and its on-screen attempt list. Say that the TraceVeil features are on separate branches pending merge and phone acceptance.
