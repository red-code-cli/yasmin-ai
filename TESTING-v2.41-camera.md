# v2.41 Camera Vision (live) - manual test plan (needs a real phone; NOTHING below was run in the authoring environment)

Open the Conversation log (menu > Conversation) and watch the `Camera:` lines.

| # | Do | Expect |
|---|----|--------|
| 1 | Start Yasmin, say "Yasmin, camera open karke dekho" (app in front) | she says one short line, Camera Vision opens, status shows Camera active (back), Live vision ON, frames counter grows by 1 per second |
| 2 | Ask "mere saamne kya hai?" then "woh kaun si cheez hai?" without any new command | answers from the live picture, no "I am looking" talk, no new tool call |
| 3 | Move the phone, ask again | the answer follows the new view |
| 4 | Ask "mere left side mein kya hai?" with the back camera | object on the LEFT of the picture; with the front camera it must be the RIGHT of the picture |
| 5 | Tap FRONT CAMERA / BACK CAMERA 10 times quickly, ask a question after each | no crash, status goes starting -> active (front/back), the answer matches the shown lens |
| 6 | Say "camera band karo" / tap END CAMERA | frames counter stops, red CAMERA ON line disappears, page returns home, the camera LED goes off, Yasmin says she cannot see any more |
| 7 | Say the camera command while WhatsApp is in front | if Android blocks the launch: notification "Tap to open Camera Vision"; after the tap the stream starts; END CAMERA goes back to WhatsApp |
| 8 | Lock the phone, ask for the camera by voice | she says the phone is locked |
| 9 | Press Home while streaming | status "paused (app in the background)", no frames; coming back resumes |
| 10 | Switch off Wi-Fi / mobile data for 20 s while streaming | status shows connecting; frames resume after reconnect; no crash, no memory growth |
| 11 | Deny the camera permission | message and ALLOW CAMERA button, tool answers with the reason |
| 12 | Say "Yasmin sleep" while streaming | no frames are sent while asleep |
| 13 | Gallery PICK IMAGE and WHAT DO YOU SEE? | written answer from the chosen model (gemini-3.8-flash or fallback gemini-3.5-flash); Error text if the key / model is wrong |
| 14 | Leave the page with the back button while streaming | stream stops, camera released |

Open questions to watch: whether the Live model stays silent after the "[SYSTEM NOTICE]" texts (camera on / off / switched / paused), and whether a session with video keeps its length limit (context-window compression and session resumption were already part of the existing setup).
