package dev.qqbot.message;
import dev.qqbot.persistence.Db;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@Service
public class GroupStore {
 private final Db db;
 public GroupStore(Db db){this.db=db;}
 public Group get(UUID id){var row=db.one("select * from group_config where id=?",id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group not found");return Group.from(row);}
 public Group find(String channel,String account,String external){var row=db.one("select * from group_config where channel=? and account_id=? and external_id=?",channel,account,external);return row==null?null:Group.from(row);}
 public boolean valid(UUID id,long epoch){var g=get(id);return g.enabled()&&g.epoch()==epoch;}
}
