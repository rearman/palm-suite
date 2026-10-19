# Palm Suite Sync Server

The sync server is one Go binary. It uses only the Go standard library. It serves one user.
It stores only encrypted records. The server cannot read the data. The protocol is in [PROTOCOL.md](PROTOCOL.md).

## Install on a Linux VPS with nginx

Replace `sync.example.com` with your domain.

1. Download `palmsync-linux-amd64` (or `palmsync-linux-arm64`) from the GitHub Release with a `server-v*` tag.
   Install it and set the mode:

   ```
   sudo install -m 755 palmsync-linux-amd64 /usr/local/bin/palmsync
   ```

2. Make the system user, install the service unit and start the server:

   ```
   sudo useradd --system --home-dir /var/lib/palmsync --shell /usr/sbin/nologin palmsync
   sudo cp deploy/palmsync.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now palmsync
   ```

3. Make a token for each device:

   ```
   sudo -u palmsync palmsync token add phone
   ```

   The command runs as the `palmsync` user, which owns `/var/lib/palmsync`.
   The server reads the tokens on each request, so a new token works at once.
   Use the same form for `list` and `revoke`:

   ```
   sudo -u palmsync palmsync token list
   sudo -u palmsync palmsync token revoke phone
   ```

4. Set up nginx:

   ```
   sudo cp deploy/nginx-palmsync.conf /etc/nginx/sites-available/palmsync
   sudo nano /etc/nginx/sites-available/palmsync   # set server_name to sync.example.com
   sudo ln -s /etc/nginx/sites-available/palmsync /etc/nginx/sites-enabled/
   sudo certbot --nginx -d sync.example.com
   sudo nginx -t
   sudo systemctl reload nginx
   ```

5. Check the server. Use a token from step 3:

   ```
   curl -H "Authorization: Bearer TOKEN" https://sync.example.com/v1/meta
   ```

   The reply is `{"salt":"","check":""}` until the first device sets the sync passphrase values.

## Backup

Copy `/var/lib/palmsync` (`log.jsonl`, `meta.json`, `tokens.json`).
You can copy it while the service runs, because the log is append-only.
The last line of the copy can be torn. The server skips a torn last line.

## Passphrase

The sync passphrase is not stored on the server. Losing it makes the server data unreadable.
The devices still hold their own data.

## Build from source

```
cd sync-server && go test ./... && CGO_ENABLED=0 go build -o palmsync .
```
