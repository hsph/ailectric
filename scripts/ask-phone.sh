#!/bin/sh
# Vibrates three times short and asks on the phone whether a test may take over the screen.
# Prints ready, 10, 30 or 60, or timeout if nobody answered. Needs the debug build installed.
# Usage: scripts/ask-phone.sh <adb-serial> [seconds to wait, default 60]
serial=$1
wait=${2:-60}
adb="/opt/homebrew/bin/adb -s $serial"
receiver=app.ailectric/.debug.TakeoverPromptReceiver

$adb shell run-as app.ailectric rm -f files/takeover_answer
$adb shell pm grant app.ailectric android.permission.POST_NOTIFICATIONS
$adb shell am broadcast -n "$receiver" >/dev/null
for _ in 1 2 3; do
    $adb shell cmd vibrator_manager synced oneshot 200
    sleep 0.2
done

end=$(($(date +%s) + wait))
while [ "$(date +%s)" -lt "$end" ]; do
    answer=$($adb shell run-as app.ailectric cat files/takeover_answer 2>/dev/null)
    case $answer in
        ready | 10 | 30 | 60)
            echo "$answer"
            exit 0
            ;;
    esac
    sleep 1
done
echo timeout
