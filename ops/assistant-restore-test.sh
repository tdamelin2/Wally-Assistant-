#!/bin/bash
# Usage: assistant-restore-test.sh <backup-file>
# Exits 0 only when the backup's integrity_check is exactly "ok" and all thirty-four table counts
# match the live database. Close both apps first so nothing syncs during the test.
umask 077
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "usage: $0 <backup-file>" >&2
    exit 2
fi
BACKUP=$1
LIVE=${ASSISTANT_LIVE_DB:-/var/lib/assistant/assistant.db}
TABLES="checkin spending journal task health_day workout cal_event mail_item briefing meal kv doc_folder doc dream dream_pattern goal ai_job course study_file card quiz exam usage_session app_rule listen mail_draft mail_action hw_check approval research chat chat_message memory_note outbox_notification"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
COPY=$TMP/restore-test.db
cp -- "$BACKUP" "$COPY"

INTEGRITY=$(sqlite3 "$COPY" "PRAGMA integrity_check")
echo "integrity_check: $INTEGRITY"
if [ "$INTEGRITY" != "ok" ]; then
    echo "FAIL: integrity_check is not ok" >&2
    exit 1
fi

status=0
printf '%-12s %10s %10s\n' table backup live
for t in $TABLES; do
    b=$(sqlite3 "$COPY" "SELECT count(*) FROM $t")
    l=$(sqlite3 -readonly "$LIVE" "SELECT count(*) FROM $t")
    printf '%-12s %10s %10s\n' "$t" "$b" "$l"
    if [ "$b" != "$l" ]; then
        echo "FAIL: count mismatch in $t" >&2
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo "PASS"
fi
exit "$status"
