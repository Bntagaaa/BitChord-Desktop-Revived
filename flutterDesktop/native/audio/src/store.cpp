#include "bitchord_audio.h"
#include <sqlite3.h>
#include <cstring>
#include <memory>
#include <stdexcept>
extern "C" {
#include <libavutil/sha.h>
#include <libavutil/mem.h>
}
namespace {
struct Database {
 sqlite3* db=nullptr;
 explicit Database(const char* path) {
  if(!path || sqlite3_open_v2(path,&db,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,nullptr)!=SQLITE_OK) {
   if(db) sqlite3_close(db); db=nullptr; throw std::runtime_error("open");
  }
  try {
   sqlite3_busy_timeout(db,2000);
   execute("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;");
   sqlite3_stmt* check=nullptr;
   if(sqlite3_prepare_v2(db,"PRAGMA user_version",-1,&check,nullptr)!=SQLITE_OK) throw std::runtime_error("schema");
   const int step=sqlite3_step(check); const int version=step==SQLITE_ROW?sqlite3_column_int(check,0):-1; sqlite3_finalize(check);
   if(version<0 || version>1) throw std::runtime_error("unsupported schema");
   execute("CREATE TABLE IF NOT EXISTS local_state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload BLOB NOT NULL); PRAGMA user_version=1;");
  } catch(...) { sqlite3_close(db); db=nullptr; throw; }
 }
 ~Database() { if(db) sqlite3_close(db); }
 void execute(const char* sql) { if(sqlite3_exec(db,sql,nullptr,nullptr,nullptr)!=SQLITE_OK) throw std::runtime_error("SQL"); }
};
struct Statement {
 sqlite3_stmt* s=nullptr;
 Statement(sqlite3* db,const char* sql) { if(sqlite3_prepare_v2(db,sql,-1,&s,nullptr)!=SQLITE_OK) throw std::runtime_error("prepare"); }
 ~Statement() { sqlite3_finalize(s); }
};
}
extern "C" {
int32_t bc_store_read(const char* path,uint8_t** bytes,uint64_t* length,uint64_t* revision) { try {
 if(!bytes || !length || !revision) return BC_INVALID; *bytes=nullptr; *length=*revision=0;
 Database db(path); Statement query(db.db,"SELECT revision,payload FROM local_state WHERE id=1");
 const int result=sqlite3_step(query.s); if(result==SQLITE_DONE) return BC_OK; if(result!=SQLITE_ROW) return BC_FAILED;
 const auto n=sqlite3_column_bytes(query.s,1); if(n<=0 || n>16*1024*1024) return BC_INVALID;
 auto* memory=static_cast<uint8_t*>(bc_alloc(uint64_t(n))); if(!memory) return BC_FAILED;
 memcpy(memory,sqlite3_column_blob(query.s,1),size_t(n)); *bytes=memory; *length=uint64_t(n); *revision=uint64_t(sqlite3_column_int64(query.s,0)); return BC_OK;
} catch(...) { return BC_FAILED; } }
int32_t bc_store_write(const char* path,const uint8_t* bytes,uint64_t length,uint64_t expected) { try {
 if(!bytes || !length || length>16*1024*1024 || expected>=uint64_t(INT64_MAX)) return BC_INVALID;
 Database db(path); db.execute("BEGIN IMMEDIATE");
 try {
  Statement query(db.db,"SELECT revision FROM local_state WHERE id=1");
  const int result=sqlite3_step(query.s);
  if(result!=SQLITE_DONE && result!=SQLITE_ROW) throw std::runtime_error("read");
  const auto stored=result==SQLITE_ROW?uint64_t(sqlite3_column_int64(query.s,0)):0;
  if(stored!=expected) { db.execute("ROLLBACK"); return BC_STALE; }
  Statement update(db.db,"INSERT INTO local_state(id,revision,payload) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload");
  if(sqlite3_bind_int64(update.s,1,sqlite3_int64(expected+1))!=SQLITE_OK || sqlite3_bind_blob(update.s,2,bytes,int(length),SQLITE_TRANSIENT)!=SQLITE_OK || sqlite3_step(update.s)!=SQLITE_DONE) throw std::runtime_error("write");
  db.execute("COMMIT"); return BC_OK;
 } catch(...) { sqlite3_exec(db.db,"ROLLBACK",nullptr,nullptr,nullptr); throw; }
} catch(...) { return BC_FAILED; } }
int32_t bc_sha256(const uint8_t* bytes,uint64_t length,uint8_t* digest) {
 if((!bytes && length) || !digest || length>16*1024*1024) return BC_INVALID;
 auto* sha=av_sha_alloc(); if(!sha) return BC_FAILED;
 av_sha_init(sha,256); av_sha_update(sha,bytes,size_t(length)); av_sha_final(sha,digest); av_free(sha); return BC_OK;
}
}
