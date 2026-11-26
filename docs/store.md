# Store (OUYA community server)

PocketMicro's **STORE** button browses a [stouyapi](https://github.com/cweiske/stouyapi)
community OUYA server, by default `http://ouya.cweiske.de` (changeable under **SERVER**). The
server's Discover pages are shown as rows of games; category tiles (genres, players, A-Z,
"Best rated", developer pages) open their own pages; bundles list their apps.

A game page shows the server's details (developer, genres, players, rating, version, size,
screenshots, description). **DOWNLOAD & INSTALL** fetches the APK from the server's download link,
following http/https redirects (game files can be hosted elsewhere, e.g. the Internet Archive),
and checks the server's file size and MD5 checksum. The file is kept in PocketMicro's private
`files/store/` folder and handed to the normal import: assessment, conversion with all repairs,
and install (Shizuku when the game needs its original Android version). A downloaded game can be
converted again from its page without downloading.

Endpoints used: `GET /api/v1/discover/{page}` (front page `discover`),
`GET /api/v1/details?app={package}`, `GET /api/v1/apps/{versionUuid}/download`.

The public server serves plain HTTP and redirects HTTPS to HTTP, so PocketMicro allows cleartext
traffic. The Store is a community service; games and data come from the server, not from
PocketMicro. PocketMicro does not handle in-game purchases.
