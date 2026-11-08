package dev.qqbot.message;
import java.util.*;
public record Group(UUID id,String channel,String accountId,String externalId,String name,boolean enabled,boolean replyEnabled,String adminIds,long epoch,long revision){
 public static Group from(Map<String,Object> r){return new Group((UUID)r.get("id"),(String)r.get("channel"),(String)r.get("account_id"),(String)r.get("external_id"),(String)r.get("name"),(boolean)r.get("enabled"),(boolean)r.get("reply_enabled"),(String)r.get("admin_ids"),((Number)r.get("epoch")).longValue(),((Number)r.get("revision")).longValue());}
 public boolean isAdmin(String sender){return Arrays.stream(adminIds.split("[,\\s]+")).anyMatch(sender::equals);}
}
