#!/bin/bash
set -euo pipefail
umask 077
DB=/var/lib/assistant/assistant.db
DIR=/var/backups/assistant
NAME=assistant-$(TZ=Asia/Jerusalem date +%F).db
rm -f "$DIR/$NAME.tmp"
sqlite3 "$DB" "VACUUM INTO '$DIR/$NAME.tmp'"
mv "$DIR/$NAME.tmp" "$DIR/$NAME"
# keep the newest 14 by name (names sort by date), locally and on Drive
ls -1 "$DIR"/assistant-*.db | sort | head -n -14 | xargs -r rm --
# upload every local backup that is not on Drive yet (retries a night whose upload failed)
rclone copy "$DIR" gcrypt: --include "assistant-*.db"
rclone lsf gcrypt: | sort | head -n -14 | while read -r f; do rclone deletefile "gcrypt:$f"; done
