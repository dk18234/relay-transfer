package dev.dineshrama.relay;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferStore {
    final JdbcTemplate db;
    public TransferStore(JdbcTemplate db){this.db=db;}
    private static Instant time(ResultSet r,String column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
    public Transfer get(UUID id,String lock){
        var rows=db.query("SELECT * FROM transfer WHERE id=? "+lock,(r,n)->new Transfer(r.getObject("id",UUID.class),r.getString("name"),r.getLong("size"),r.getLong("chunk_size"),r.getInt("part_count"),r.getString("expected_sha256"),r.getString("status"),r.getString("storage"),time(r,"created_at"),time(r,"updated_at"),time(r,"expires_at"),r.getInt("verification_attempts"),r.getString("actual_sha256"),r.getString("last_error"),r.getBoolean("cleaned"),List.of(),List.of()),id);
        if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Transfer not found");
        var t=rows.getFirst();
        var parts=db.query("SELECT * FROM part WHERE transfer_id=? ORDER BY number",(r,n)->new Transfer.Part(r.getInt("number"),r.getLong("size"),r.getString("sha256"),r.getString("storage_key"),time(r,"accepted_at")),id);
        var events=db.query("SELECT * FROM (SELECT * FROM transfer_event WHERE transfer_id=? ORDER BY sequence DESC LIMIT 200) recent ORDER BY sequence",(r,n)->new Transfer.Event(r.getLong("sequence"),r.getString("type"),r.getString("message"),(Integer)r.getObject("part_number"),time(r,"at")),id);
        return new Transfer(t.id(),t.name(),t.size(),t.chunkSize(),t.partCount(),t.expectedSha256(),t.status(),t.storage(),t.createdAt(),t.updatedAt(),t.expiresAt(),t.verificationAttempts(),t.actualSha256(),t.lastError(),t.cleaned(),parts,events);
    }
    public List<Map<String,Object>> list(){
        return db.query("SELECT t.*,coalesce(sum(p.size) FILTER(WHERE p.accepted_at IS NOT NULL),0) accepted_bytes,count(p.accepted_at) accepted_parts FROM transfer t LEFT JOIN part p ON p.transfer_id=t.id GROUP BY t.id ORDER BY t.created_at DESC LIMIT 50",(r,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getObject("id"));m.put("name",r.getString("name"));m.put("size",r.getLong("size"));m.put("status",r.getString("status"));m.put("storage",r.getString("storage"));m.put("createdAt",time(r,"created_at"));m.put("partCount",r.getInt("part_count"));m.put("acceptedBytes",r.getLong("accepted_bytes"));m.put("acceptedParts",r.getInt("accepted_parts"));return m;
        });
    }
    public void event(UUID id,String type,String message,Integer part){db.update("INSERT INTO transfer_event(transfer_id,type,message,part_number) VALUES(?,?,?,?)",id,type,message,part);}
    public UUID byRequest(String key){var ids=db.query("SELECT id FROM transfer WHERE request_key=?",(r,n)->r.getObject(1,UUID.class),key);return ids.isEmpty()?null:ids.getFirst();}
}
