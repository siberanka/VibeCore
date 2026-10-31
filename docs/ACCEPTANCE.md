# Acceptance plan

Unit tests cover the implemented core foundation. No live server or client result is claimed yet. Each item below must be checked on recorded server and client versions before the related feature is described as supported.

- Verify the chosen build with JDK 25 and inspect metadata and dependency packaging.
- Start the exact Paper build on Java 25; confirm enable, disable and cleanup.
- Exercise commands and completion with operator, ordinary player and console identities.
- Confirm invalid configuration/import/deploy retains the previous valid state.
- Check every inventory click/drag path, delayed action cancellation and transaction failure.
- Check cursor hit testing, screen scaling, renderer capabilities and UI cleanup.
- Check hologram visibility, slides, placements, chunk lifecycle and media limits.
- Check stable glyph allocation, pack collisions, client rejection and Java/Bedrock differences.
- Check editor authorization, expired links, concurrent saves, unsafe files and unsafe URLs.
- Measure declared capacity on recorded hardware and versions; publish no unmeasured guarantees.
- Inspect the candidate Git tree, history and release archive before publication.
