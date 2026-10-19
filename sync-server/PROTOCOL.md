# Palm Suite Sync Protocol

This document is the contract between the sync server and its clients.
The Android client follows it. A future desktop app follows it too.

## 1. Access

The server has one user. Each device has its own bearer token.

Send the token in the header:

```
Authorization: Bearer <token>
```

The server returns 401 when the token is missing or wrong.

## 2. Setup values

### GET /v1/meta

Returns the setup values:

```json
{"salt": "string", "check": "string"}
```

Both fields are empty strings until the first device sets them.

### PUT /v1/meta

Sets the setup values. Send:

```json
{"salt": "string", "check": "string"}
```

The server sets the values one time only.

| Status | Meaning                          |
| ------ | -------------------------------- |
| 204    | The values are set.              |
| 400    | A field is empty.                |
| 409    | The values are already set.      |

## 3. Sync

### POST /v1/sync

Request:

```json
{
  "since": 0,
  "push": [
    {"id": "string", "baseRev": 0, "deleted": false, "blob": "string"}
  ]
}
```

Response:

```json
{
  "rev": 0,
  "accepted": [{"id": "string", "rev": 0}],
  "conflicts": ["string"],
  "changes": [{"id": "string", "rev": 0, "deleted": false, "blob": "string"}]
}
```

### Server rules

- An item in `push` is accepted when `baseRev` equals the current rev of that id on the server.
  For a new id, `baseRev` is 0.
- An accepted item gets the next global revision. The server stores it.
- An item that does not match is a conflict. The server does not store it. Its id goes in `conflicts`.
- `changes` lists every record with a rev greater than `since`. The list includes the records from this push.
- A deleted item keeps a tombstone on the server. The server never removes a tombstone.

### Limits

| Item                          | Limit                                  |
| ----------------------------- | -------------------------------------- |
| `id`                          | At most 64 bytes.                      |
| `blob`                        | At most 64 KiB of base64 text.         |
| Request body                  | At most 16 MiB.                        |
| Duplicate ids in one push     | The server returns 400.                |
| `blob` of a deleted item      | Empty.                                 |
| `blob` of a live item         | Required. Not empty.                   |

## 4. Client rules

The server does not enforce these rules. The client must follow them.

- Conflict: keep both versions, as Palm HotSync did.
  - The server version replaces the local record.
  - The local version becomes a new record with a new id. The client pushes it on the next sync.
- Local delete and server edit: the server version comes back. The client does not delete it.
- Local edit and server delete: the client pushes the local version again as a new record with a new id.
- The client stores the highest rev it has seen. It sends that rev as `since` on the next sync.
- The client syncs until a response has no conflicts.

## 5. Encryption

The device encrypts every record. The server stores only ciphertext.

- Key: `PBKDF2-HMAC-SHA256(passphrase, salt, 600000 rounds, 32 bytes)`.
- Salt: 16 random bytes, encoded as base64. The first device creates it and stores it with `PUT /v1/meta`.
- Blob: `base64(nonce || ciphertext_and_tag)`.
  - The nonce is 12 random bytes.
  - The cipher is AES-256-GCM.
  - The associated data is the record id, encoded as UTF-8.
- Check: the blob of the UTF-8 text `palm-suite`, with the id `check`.
  A device that cannot decrypt the check value has a wrong passphrase.

## 6. Record plaintext

The plaintext of a blob is UTF-8 JSON. The field `kind` gives the record type.
Each kind has these keys:

- Event: `{"kind":"event","title","note","date","untimed","start","end","alarm","alarmAdvance","alarmUnit","repeat","every","repeatEnd","weekDays","exceptions":[sorted longs],"private"}`
- To Do: `{"kind":"todo","title","note","priority","due","done","category":uid or "","private"}`
- Memo: `{"kind":"memo","text","category":uid or "","private","pos"}`
- Category: `{"kind":"category","name","app"}`
- ShortCuts: `{"kind":"shortcuts","list":[{"name","text"}]}`

Rules:

- A record refers to its category by the category uid in `category`.
  An empty string means no category.
- The value of `app` is 0 for To Do or 1 for Memo Pad.
- The ShortCuts record has the id `shortcuts`. The device that syncs the record last wins.
- A client compares two records by their content, without the `uid`. If the content is the same, the two versions are the same.
