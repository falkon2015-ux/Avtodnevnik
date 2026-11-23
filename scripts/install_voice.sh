#!/usr/bin/env bash
set -euo pipefail
# Source recording is provided by the owner of this personal Android project.
# Upload voice/elevenlabs_master.mp3 to GitHub to enable these offline clips.
src="voice/elevenlabs_master.mp3"
out="app/src/main/res/raw"
if [[ ! -f "$src" ]]; then
  echo "No ElevenLabs source recording in GitHub; Android TTS fallback remains active."
  exit 0
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "::error::ffmpeg is required to split ElevenLabs recording."
  exit 1
fi
mkdir -p "$out"

# Timecodes from the supplied 29.6-second recording. Cuts lie in silent gaps.
# Single voice file is edited into eleven self-contained Android audio assets.
split() {
  local name="$1" start="$2" end="$3"
  ffmpeg -nostdin -loglevel error -y -ss "$start" -to "$end" -i "$src" \
    -vn -ac 1 -ar 44100 -c:a libmp3lame -b:a 80k \
    -af 'afade=t=in:st=0:d=0.015,areverse,afade=t=in:st=0:d=0.02,areverse' \
    "$out/$name.mp3"
  test -s "$out/$name.mp3" || { echo "::error::Empty audio: $name"; exit 1; }
}
split day_start 0.00 3.28
split standby_start 3.28 6.78
split day_resume 6.78 8.80
split day_end 8.80 12.11
split map_open 12.11 13.97
split journal_open 13.97 15.70
split address_saved 15.70 17.06
split journal_saved 17.06 18.88
split gps_lost 18.88 23.36
split voice_enabled 23.36 25.45
split voice_test 25.45 29.60
echo "Installed $(find "$out" -maxdepth 1 -name '*.mp3' | wc -l) spoken command clips."
