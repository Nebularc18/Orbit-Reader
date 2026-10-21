# Phone testing

Use the verified debug APK from `app/build/outputs/apk/debug/app-debug.apk` for testing. It is signed with the local Android debug key and retains application ID `app.bookorbit.community`. Install updates over an existing test build signed with the same key to preserve its local data. Signed production releases and the production update channel are separate work.

1. Copy the APK to the phone, open it, and allow installation from the app used to open the file if Android asks. Open Orbit Reader and sign into your BookOrbit server.
2. Tap an EPUB to read immediately. Long-press it in the library for file and download options. Tap the middle of a page to show the controls, and tap either edge or swipe to turn pages. Change its text size and theme, select a passage, save a highlight and note, close the reader, and reopen it. Check that the position and highlight remain.
3. On Android 15 or newer, open a PDF containing real text. Choose Select text, tap a word, and use Copy. Choose Select text again and drag across several words or lines, then use Highlight selected text and save the note. The selected quote should match the marked words. Scanned image pages need Highlight area instead. Older Android versions retain area highlighting.
4. Reopen the PDF, zoom in, and check the highlight alignment. Open Notes and jump to the saved passage. Try a reversed drag and tapping a blank margin. Confirm no incorrect highlight is saved.
5. Download an audiobook and an ebook, enable airplane mode, restart the app, and open both from their offline views. Check playback, page turns, saved positions, notes, and highlights.
6. With a synthetic audiobook playing, select End of chapter in Sleep timer. Check that it pauses at the selected chapter boundary, including a chapter spanning audio files and after changing speed. Check pause/resume, seeking backward, seeking past the boundary, replacing it with a timed timer, turning it off, and opening another book. Tap the whole-book right-hand time to switch remaining/total, then reopen the app to check the saved preference. Check both timeline drags, taps, and accessibility seeking. Also test lock-screen play/pause and skips, Bluetooth disconnect/reconnect, a timed sleep timer, and an incoming call. Note the book, approximate position, phone model, Android version, and exact behavior for any problem.
7. Reconnect and try explicit progress sharing and annotation publishing/importing using a test book. Local annotations and server copies are managed separately. Review server progress before publishing.

Never send passwords, access tokens, private books, or signing keys in a bug report.


## Recorded baseline

Samsung SM-S918B, Android 16, October 20, 2026. Installed the verified debug APK and used a synthetic test account and books. Passed PDF word selection, Copy, multi-line highlights, zoom alignment, saved notes and location jumps; EPUB contents, font size, theme, highlights and bookmarks; audiobook download and playback, background notification controls, screen-off playback and lock-screen pause/resume; and reopening downloaded audio, PDF and EPUB after an app restart with the fixture server stopped. Sleep-timer configuration and cancellation passed. Actual timer expiry, Bluetooth, incoming calls and prolonged battery restrictions still need testing. The fixture was restarted afterward so the phone can browse the test library again.

## Reading controls update

Samsung SM-S918B, Android 16, October 20, 2026. Installed the updated debug APK over the existing test build. Verified direct ebook opening and long-press file/removal options, hidden reader controls and center-tap reveal, EPUB edge page turns, PDF edge and swipe page turns, and PDF word selection with the new overlay controls. The EPUB selection hint appeared as a short snackbar and cleared automatically. A downloaded audiobook opened through Listen now, retained progress-conflict review, and sent consecutive synthetic-server progress updates about five seconds apart during playback. Playback was stopped afterward. The update passed 78 unit tests, Android lint, debug assembly, and formatting.

Android Back follow-up: installed the fix on the same Samsung. The bottom system Back button returned EPUB and PDF to the library while reader controls were visible. System Back also returned both readers while controls were hidden. In PDF text-selection mode, Back canceled selection and kept the page open; another Back returned to the library. Formatting, 78 unit tests, lint, debug assembly, and APK signature verification passed.

## Dashboard and ebook covers

The dashboard update passed 81 unit tests, formatting, lint, debug assembly, APK signature verification, Python fixture compilation, and direct HTTP checks of all three bounded dashboard shelves and five authenticated thumbnail endpoints. On the Android 15 Pixel emulator, ebook library and dashboard covers rendered; Continue listening opened playback; Continue reading reopened the saved EPUB location; Next in series opened its suggested comic; and the local reading section reopened a saved PDF with the fixture stopped. Playback was paused and the fixture restarted afterward. The APK was installed over the Samsung test build, preserving its current account. Further Samsung UI checks were blocked by another active device session, which was left undisturbed.

## Audiobook timer and timeline update

Added a chapter-boundary sleep timer, thin continuous timeline tracks with round handles, and a saved per-account remaining/total time toggle. All 85 unit tests, formatting, lint, debug assembly, and APK signature verification passed. New coverage includes targets within and across audio files, seeking beyond a target, missing chapter/duration metadata, remaining-time clamping, and preference persistence and account isolation. The device host was unreachable over SSH, so this update was not installed or visually verified on the Samsung or emulator. Actual chapter-timer expiry, replacement/cancellation, speed changes, and custom slider gestures still require device testing. The download APK was refreshed.

Chapter-list touch target follow-up: the chapter list and each row now fill the available width. Rows use equal 16 dp horizontal content padding, start-aligned labels, and a minimum height of 56 dp. This prevents the list and button widths from changing with visible title lengths while scrolling. Device verification remains pending while the device host is unreachable.
Formatting, all 85 unit tests, lint, debug assembly, and APK signature verification passed for the chapter-list change. The downloadable APK was refreshed.

Current-chapter follow-up: the chapter list now highlights the actual playback chapter with a tinted background, stronger text, a current-chapter icon, and selected accessibility semantics. Opening the list positions that chapter in view; playback changes update the highlight without forcing further scrolling. Formatting, all 85 unit tests, lint, debug assembly, and APK signature verification passed. The downloadable APK was refreshed. Installation and visual verification remain pending because the device host is unreachable.

On October 21, 2026, the device host became reachable. Installed the latest verified debug APK over the existing Samsung SM-S918B app and confirmed it opened to the Dashboard with the existing account retained. This installation includes the chapter timer, simplified timelines, saved time-display toggle, consistent chapter-row touch targets, and current-chapter highlight. Timer expiry and the new chapter-list interactions have not yet been exercised on the phone.
