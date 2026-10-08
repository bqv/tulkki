-- schema 78, mechanically resolved by executing the tree's live DDL owners in
-- data/src/test/java/uk/xa0/tulkki/data/SchemaSnapshotTest.java - a schema-75
-- fixture walked by MIGRATION_75_76, MIGRATION_76_77 and MIGRATION_77_78 - rather
-- than by parsing DDL strings, so it cannot drift from what the migrations produce.
--
-- Regenerate only by naming the task:
--   TULKKI_WRITE_SCHEMA_SNAPSHOT=1 tools/build :data:testDebugUnitTest --tests "*SchemaSnapshotTest*"
--
-- schema-75.sql is NOT superseded by this file: it is the pre-adoption pin record
-- for `messages` and the conversation table that SchemaNameTest reads, and its
-- own
-- generator read DDL that S5-5 deleted, so it is history rather than a copy.
--
-- The persisted table the naming rule bans the spelling of is written
-- {conversation_table} here, and the mask is applied to both sides of the
-- comparison, so a drift is still caught: the file records the device's own schema
-- without carrying a name that is not ours.

-- index message_conversation_index
CREATE INDEX message_conversation_index ON messages(conversationUuid);

-- index message_deleted_index
CREATE INDEX message_deleted_index ON messages(deleted);

-- index message_expire_at_index
CREATE INDEX message_expire_at_index ON messages(expire_at);

-- index message_file_deleted_index
CREATE INDEX message_file_deleted_index ON messages(file_deleted);

-- index message_file_path_index
CREATE INDEX message_file_path_index ON messages(relativeFilePath);

-- index message_time_index
CREATE INDEX message_time_index ON messages(timeSent);

-- index message_time_received_index
CREATE INDEX message_time_received_index ON messages(timeReceived);

-- index message_type_index
CREATE INDEX message_type_index ON messages(type);

-- index pinned_messages_account_index
CREATE INDEX pinned_messages_account_index ON pinned_messages (account_uuid);

-- index pinned_messages_index
CREATE INDEX pinned_messages_index ON pinned_messages (conversation_uuid);

-- index sync_conversation_account_index
CREATE INDEX sync_conversation_account_index ON sync_conversation (account_uuid);

-- index translation_queue_due
CREATE INDEX translation_queue_due ON translation_queue (state, next_attempt_at);

-- index webxdc_index
CREATE INDEX webxdc_index ON webxdc_updates (conversationUuid, thread);

-- index webxdc_message_id_index
CREATE UNIQUE INDEX webxdc_message_id_index ON webxdc_updates (conversationUuid, message_id);

-- table accounts
CREATE TABLE "accounts" (uuid TEXT NOT NULL PRIMARY KEY,username TEXT,server TEXT,password TEXT,display_name TEXT,status TEXT,status_message TEXT,rosterversion TEXT,options INTEGER,avatar TEXT,keys TEXT,hostname TEXT,resource TEXT,pinned_mechanism TEXT,pinned_channel_binding TEXT,fast_mechanism TEXT,fast_token TEXT,ordering INTEGER DEFAULT 0,port INTEGER DEFAULT 5222);

-- table blocked_jids
CREATE TABLE blocked_jids (account_uuid TEXT NOT NULL, jid TEXT NOT NULL, PRIMARY KEY (account_uuid, jid), FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table blocked_media
CREATE TABLE blocked_media (cid TEXT NOT NULL PRIMARY KEY);

-- table cids
CREATE TABLE cids (cid TEXT NOT NULL PRIMARY KEY,path TEXT NOT NULL, url TEXT);

-- table contacts
CREATE TABLE "contacts" (_id INTEGER NOT NULL,accountUuid TEXT,servername TEXT,systemname TEXT,presence_name TEXT,jid TEXT,pgpkey TEXT,photouri TEXT,options INTEGER,systemaccount INTEGER,avatar TEXT,last_presence TEXT,callsDisabled INTEGER DEFAULT 0,last_time INTEGER,rtpCapability TEXT,groups TEXT, PRIMARY KEY(_id), UNIQUE(accountUuid, jid) ON CONFLICT REPLACE, FOREIGN KEY(accountUuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table {conversation_table}
CREATE TABLE "{conversation_table}" (uuid TEXT NOT NULL PRIMARY KEY,name TEXT,contactUuid TEXT,accountUuid TEXT,contactJid TEXT,created INTEGER,status INTEGER,mode INTEGER,attributes TEXT,detected_language TEXT,language_override TEXT, FOREIGN KEY(accountUuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table discovery_results
CREATE TABLE "discovery_results" (_id INTEGER NOT NULL,hash TEXT,ver TEXT,result TEXT, PRIMARY KEY(_id), UNIQUE(hash, ver) ON CONFLICT REPLACE);

-- table identities
CREATE TABLE "identities" (_id INTEGER NOT NULL,account TEXT,name TEXT,ownkey INTEGER,fingerprint TEXT,certificate BLOB,trust TEXT,active INTEGER,last_activation INTEGER,key TEXT, PRIMARY KEY(_id), UNIQUE(account, name, fingerprint) ON CONFLICT IGNORE, FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table messages
CREATE TABLE "messages" (uuid TEXT NOT NULL PRIMARY KEY,conversationUuid TEXT,timeSent INTEGER,counterpart TEXT,trueCounterpart TEXT,body TEXT,encryption INTEGER,status INTEGER,type INTEGER,relativeFilePath TEXT,serverMsgId TEXT,axolotl_fingerprint TEXT,carbon INTEGER,edited TEXT,read INTEGER DEFAULT 1,oob INTEGER,errorMsg TEXT,readByMarkers TEXT,markable INTEGER DEFAULT 0,file_deleted INTEGER DEFAULT 0,deleted INTEGER DEFAULT 0,bodyLanguage TEXT,retractId TEXT,occupantId TEXT,occupant_id TEXT,reactions TEXT,remoteMsgId TEXT,ephemeral_timer INTEGER DEFAULT 0,expire_at INTEGER DEFAULT 0,translated_body TEXT,translation_lang TEXT,translation_state INTEGER NOT NULL DEFAULT 0,subject TEXT,oobUri TEXT,fileParams TEXT,payloads TEXT,timeReceived INTEGER,notificationDismissed INTEGER DEFAULT 0,delivery INTEGER NOT NULL DEFAULT 2, FOREIGN KEY(conversationUuid) REFERENCES {conversation_table}(uuid) ON DELETE CASCADE);

-- table messages_index
CREATE VIRTUAL TABLE messages_index USING fts4 (uuid,translated_body,notindexed="uuid",content="messages",tokenize='unicode61');

-- table messages_index_docsize
CREATE TABLE 'messages_index_docsize'(docid INTEGER PRIMARY KEY, size BLOB);

-- table messages_index_segdir
CREATE TABLE 'messages_index_segdir'(level INTEGER,idx INTEGER,start_block INTEGER,leaves_end_block INTEGER,end_block INTEGER,root BLOB,PRIMARY KEY(level, idx));

-- table messages_index_segments
CREATE TABLE 'messages_index_segments'(blockid INTEGER PRIMARY KEY, block BLOB);

-- table messages_index_stat
CREATE TABLE 'messages_index_stat'(id INTEGER PRIMARY KEY, value BLOB);

-- table muted_participants
CREATE TABLE "muted_participants" (account_uuid TEXT,muc_jid TEXT,occupant_id TEXT, PRIMARY KEY(muc_jid, occupant_id), FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table pinned_messages
CREATE TABLE pinned_messages (message_uuid TEXT PRIMARY KEY, conversation_uuid TEXT, account_uuid TEXT, body TEXT, timestamp NUMBER, cid TEXT, FOREIGN KEY(conversation_uuid) REFERENCES {conversation_table}(uuid) ON DELETE CASCADE, FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table posts
CREATE TABLE posts (uuid TEXT PRIMARY KEY,account_uuid TEXT,author_jid TEXT,title TEXT,content TEXT,attachment_url TEXT,attachment_type TEXT,link_url TEXT,published NUMBER,comments_node TEXT,FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table prekeys
CREATE TABLE "prekeys" (_id INTEGER NOT NULL,account TEXT,id INTEGER,key TEXT, PRIMARY KEY(_id), UNIQUE(account, id) ON CONFLICT REPLACE, FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table presence_templates
CREATE TABLE "presence_templates" (_id INTEGER NOT NULL,uuid TEXT,last_used INTEGER,message TEXT,status TEXT, PRIMARY KEY(_id), UNIQUE(message, status) ON CONFLICT REPLACE);

-- table resolver_results
CREATE TABLE resolver_results(domain TEXT,hostname TEXT,ip BLOB,priority NUMBER,directTls NUMBER,authenticated NUMBER,port NUMBER,UNIQUE(domain) ON CONFLICT REPLACE);

-- table sessions
CREATE TABLE "sessions" (_id INTEGER NOT NULL,account TEXT,name TEXT,device_id INTEGER,key TEXT, PRIMARY KEY(_id), UNIQUE(account, name, device_id) ON CONFLICT REPLACE, FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table signed_prekeys
CREATE TABLE "signed_prekeys" (_id INTEGER NOT NULL,account TEXT,id INTEGER,key TEXT, PRIMARY KEY(_id), UNIQUE(account, id) ON CONFLICT REPLACE, FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table stories
CREATE TABLE stories (uuid TEXT PRIMARY KEY,contact TEXT,url TEXT,type TEXT,title TEXT,published NUMBER);

-- table sync_conversation
CREATE TABLE sync_conversation (conversation_uuid TEXT NOT NULL PRIMARY KEY, account_uuid TEXT NOT NULL, anchor_stanza_id TEXT, anchor_time INTEGER NOT NULL DEFAULT 0, archive_first_id TEXT, swept_through INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, FOREIGN KEY(conversation_uuid) REFERENCES {conversation_table}(uuid) ON DELETE CASCADE);

-- table sync_cursor
CREATE TABLE sync_cursor (account_uuid TEXT NOT NULL PRIMARY KEY, anchor_stanza_id TEXT, anchor_time INTEGER NOT NULL DEFAULT 0, anchor_source INTEGER NOT NULL DEFAULT 0, gap_end INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table sync_gap
CREATE TABLE sync_gap (account_uuid TEXT NOT NULL, conversation_uuid TEXT NOT NULL DEFAULT '', gap_start INTEGER NOT NULL, gap_end INTEGER NOT NULL, region INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 0, reason TEXT, opened_at INTEGER NOT NULL, closed_at INTEGER, PRIMARY KEY (account_uuid, conversation_uuid, gap_start, region), FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE);

-- table translation_cache
CREATE TABLE translation_cache (cache_key TEXT PRIMARY KEY,detected_language TEXT,translated_body TEXT NOT NULL,total_tokens INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);

-- table translation_queue
CREATE TABLE "translation_queue" (message_uuid TEXT NOT NULL PRIMARY KEY,conversation_uuid TEXT,body TEXT NOT NULL,target_language TEXT,cache_key TEXT NOT NULL,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at INTEGER NOT NULL DEFAULT 0,last_error TEXT,created_at INTEGER NOT NULL,failed_at INTEGER, FOREIGN KEY(message_uuid) REFERENCES messages(uuid) ON DELETE CASCADE);

-- table translation_usage
CREATE TABLE translation_usage (day TEXT PRIMARY KEY,peak_cache_hit INTEGER NOT NULL DEFAULT 0,peak_cache_miss INTEGER NOT NULL DEFAULT 0,peak_output INTEGER NOT NULL DEFAULT 0,off_peak_cache_hit INTEGER NOT NULL DEFAULT 0,off_peak_cache_miss INTEGER NOT NULL DEFAULT 0,off_peak_output INTEGER NOT NULL DEFAULT 0);

-- table translation_usage_origin
CREATE TABLE translation_usage_origin (day TEXT NOT NULL,origin TEXT NOT NULL DEFAULT '',peak_cache_hit INTEGER NOT NULL DEFAULT 0,peak_cache_miss INTEGER NOT NULL DEFAULT 0,peak_output INTEGER NOT NULL DEFAULT 0,off_peak_cache_hit INTEGER NOT NULL DEFAULT 0,off_peak_cache_miss INTEGER NOT NULL DEFAULT 0,off_peak_output INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(day, origin));

-- table webxdc_updates
CREATE TABLE "webxdc_updates" (serial INTEGER NOT NULL,conversationUuid TEXT NOT NULL,sender TEXT NOT NULL,thread TEXT NOT NULL,threadParent TEXT,info TEXT,document TEXT,summary TEXT,payload TEXT,message_id TEXT, PRIMARY KEY(serial), FOREIGN KEY(conversationUuid) REFERENCES {conversation_table}(uuid) ON DELETE CASCADE);

-- trigger after_message_delete
CREATE TRIGGER after_message_delete BEFORE DELETE ON messages BEGIN DELETE FROM messages_index WHERE rowid=OLD.rowid; END;

-- trigger after_message_insert
CREATE TRIGGER after_message_insert AFTER INSERT ON messages BEGIN INSERT INTO messages_index(rowid,uuid,translated_body) VALUES(NEW.rowid,NEW.uuid,NEW.translated_body); END;

-- trigger after_message_update
CREATE TRIGGER after_message_update UPDATE OF uuid,translated_body ON messages BEGIN UPDATE messages_index SET translated_body=NEW.translated_body,uuid=NEW.uuid WHERE rowid=OLD.rowid; END;

