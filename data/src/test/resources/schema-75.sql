-- schema 75, for `messages` and `conversations`, mechanically resolved from
-- data/src/main/java/uk/xa0/tulkki/data/DatabaseBackend.java at 4c56cd1185902fb5fb7e48c7f46e056d6b6a60f1
-- by .toolchain/s5-1/mk_schema.py. Do not hand-edit: regenerate.
--
-- `NUMBER` is deliberate and load-bearing. Room normalises a declared type with no
-- INT/CHAR/CLOB/TEXT/BLOB/REAL/FLOA/DOUB in it to ColumnInfo.UNDEFINED, and the entity side
-- of Room's validation can only ever emit TEXT/INTEGER/REAL/BLOB - which is why this step
-- declares no entity. See HistoryDatabase.kt.

-- messages: 32 columns in the CREATE plus the 6 guarded ALTERs below = 38 columns on the
-- device. The CREATE is the fresh-install path; an upgraded file gets the same columns,
-- because `ensureMessageFileDeletedColumn` runs on both.
create table if not exists messages( uuid TEXT PRIMARY KEY, conversationUuid TEXT, timeSent NUMBER, counterpart TEXT, trueCounterpart TEXT,body TEXT, encryption NUMBER, status NUMBER,type NUMBER, relativeFilePath TEXT, serverMsgId TEXT, axolotl_fingerprint TEXT, carbon INTEGER, edited TEXT, read NUMBER DEFAULT 1, oob INTEGER, errorMsg TEXT,readByMarkers TEXT,markable NUMBER DEFAULT 0,file_deleted NUMBER DEFAULT 0,deleted NUMBER DEFAULT 0,bodyLanguage TEXT,retractId TEXT,occupantId TEXT,occupant_id TEXT,reactions TEXT,remoteMsgId TEXT,ephemeral_timer INTEGER DEFAULT 0,expire_at NUMBER DEFAULT 0,translated_body TEXT,translation_lang TEXT,translation_state INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(conversationUuid) REFERENCES conversations(uuid) ON DELETE CASCADE);
ALTER TABLE messages ADD COLUMN subject TEXT;
ALTER TABLE messages ADD COLUMN oobUri TEXT;
ALTER TABLE messages ADD COLUMN fileParams TEXT;
ALTER TABLE messages ADD COLUMN payloads TEXT;
ALTER TABLE messages ADD COLUMN timeReceived NUMBER;
ALTER TABLE messages ADD COLUMN notificationDismissed NUMBER DEFAULT 0;

-- conversations: the CREATE, then Tulkki's two language columns, which arrive as guarded
-- ALTERs (DatabaseBackend.addTranslationColumns) on an upgraded file and are therefore part
-- of schema 75 even though the CREATE does not name them.
create table if not exists conversations (uuid TEXT PRIMARY KEY, name TEXT, contactUuid TEXT, accountUuid TEXT, contactJid TEXT, created NUMBER, status NUMBER, mode NUMBER, attributes TEXT, FOREIGN KEY(accountUuid) REFERENCES accounts(uuid) ON DELETE CASCADE);
ALTER TABLE conversations ADD COLUMN detected_language TEXT;
ALTER TABLE conversations ADD COLUMN language_override TEXT;

CREATE INDEX if not exists message_conversation_index ON messages(conversationUuid);
CREATE INDEX if not exists message_deleted_index ON messages(deleted);
CREATE INDEX if not exists message_expire_at_index ON messages(expire_at);
create index if not exists message_file_deleted_index ON messages(file_deleted);
CREATE INDEX if not exists message_file_path_index ON messages(relativeFilePath);
CREATE INDEX if not exists message_time_index ON messages(timeSent);
CREATE INDEX IF NOT EXISTS message_time_received_index ON messages (timeReceived);
CREATE INDEX if not exists message_type_index ON messages(type);

-- cids: the local-file map. Every declared type here is TEXT, which is why this is the one
-- table S5-1 can declare an entity for: Room compares each column's normalised affinity,
-- and TEXT is the only affinity this schema's declared types and Room's entity side agree
-- on for all of a table's columns at once. `url` arrives as a guarded ALTER.
CREATE TABLE IF NOT EXISTS cids (cid TEXT NOT NULL PRIMARY KEY,path TEXT NOT NULL);
ALTER TABLE cids ADD COLUMN url TEXT;
